package app.soiboi.engine.spotify

import app.soiboi.engine.addons.AddonException
import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.core.SecretStore
import app.soiboi.engine.sources.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Where Spotify sends the browser back to. The person adds this exact address to their Spotify app's redirect list. */
const val SPOTIFY_REDIRECT = "http://127.0.0.1:8898/callback"

private val SCOPES = listOf(
    "user-read-private", "user-library-read", "user-library-modify", "playlist-read-private", "playlist-read-collaborative",
    "user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing",
).joinToString(" ")

private fun b64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** A PKCE sign-in: the verifier stays here, only its hash goes in the address. No client secret is needed or kept. */
class SpotifyLogin(val clientId: String) {
    val verifier: String = b64url(ByteArray(48).also { SecureRandom().nextBytes(it) })
    val state: String = b64url(ByteArray(12).also { SecureRandom().nextBytes(it) })
    val url: String get() = urlWith("https://accounts.spotify.com/authorize", mapOf(
        "client_id" to clientId, "response_type" to "code", "redirect_uri" to SPOTIFY_REDIRECT, "scope" to SCOPES,
        "code_challenge_method" to "S256", "code_challenge" to b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())), "state" to state,
    ))
}

data class SpotifyDevice(val id: String, val name: String, val type: String, val active: Boolean, val volume: Int?)
data class SpotifyPlayback(val playing: Boolean, val positionMs: Long, val durationMs: Long, val uri: String?, val deviceId: String?)

/** Spotify's Web API with the person's own sign-in. Tokens refresh themselves and are kept in the encrypted store. */
class SpotifyApi(private val secrets: SecretStore, private val http: HttpClient = Http.client) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    val clientId: String? get() = secrets.get("spotify.client")?.takeIf { it.isNotBlank() }
    val connected: Boolean get() = secrets.get("spotify.tokens") != null

    fun saveClientId(id: String) = secrets.put("spotify.client", id.trim().ifEmpty { null })

    private fun tokens(): JsonObject? = secrets.get("spotify.tokens")?.let { runCatching { json.parseToJsonElement(it) as JsonObject }.getOrNull() }

    /** Trades the code from the redirect for tokens. Returns an error sentence, or null when signed in. */
    suspend fun finishLogin(login: SpotifyLogin, code: String): String? {
        val answer = tokenRequest(mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to SPOTIFY_REDIRECT, "client_id" to login.clientId, "code_verifier" to login.verifier))
        return if (answer == null) "Spotify did not accept the sign-in. Check the redirect address in your Spotify app." else { store(answer, null); null }
    }

    private suspend fun tokenRequest(form: Map<String, String>): JsonObject? = try {
        val response = http.post("https://accounts.spotify.com/api/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(form.entries.joinToString("&") { "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" })
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) json.parseToJsonElement(text) as? JsonObject else { EngineLog.output("spotify token: ${response.status.value} ${text.take(200)}"); null }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { EngineLog.output("spotify token: $e"); null }

    private fun store(answer: JsonObject, previousRefresh: String?) {
        val expires = System.currentTimeMillis() + (answer["expires_in"].long() ?: 3600) * 1000 - 60_000
        secrets.put("spotify.tokens", buildJsonObject {
            put("access", answer["access_token"].str().orEmpty()); put("refresh", answer["refresh_token"].str() ?: previousRefresh.orEmpty()); put("expires", expires)
        }.toString())
    }

    fun signOut() { secrets.put("spotify.tokens", null) }

    private suspend fun accessToken(): String = lock.withLock {
        val t = tokens() ?: throw AddonException("Connect Spotify in Settings first.")
        if ((t["expires"].long() ?: 0) > System.currentTimeMillis()) return t["access"].str().orEmpty()
        val refresh = t["refresh"].str().orEmpty(); val id = clientId ?: throw AddonException("Connect Spotify in Settings first.")
        val answer = tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to id))
            ?: throw AddonException("Spotify signed you out. Connect it again in Settings.")
        store(answer, refresh)
        answer["access_token"].str().orEmpty()
    }

    /** One call. Returns the parsed body, JsonNull for an empty success, or throws an [AddonException] with a sentence to show. */
    suspend fun call(method: HttpMethod, path: String, query: Map<String, String?> = emptyMap(), body: JsonObject? = null): JsonElement {
        val response = try {
            withTimeout(20_000) {
                http.request(urlWith(if (path.startsWith("http")) path else "https://api.spotify.com/v1$path", query)) {
                    this.method = method; header("Authorization", "Bearer ${accessToken()}")
                    if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AddonException("Spotify did not answer in time.")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: AddonException) { throw e
        } catch (e: Exception) { EngineLog.output("spotify $path: $e"); throw AddonException("Could not reach Spotify.") }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) return if (text.isBlank()) JsonNull else runCatching { json.parseToJsonElement(text) }.getOrDefault(JsonNull)
        EngineLog.output("spotify ${response.status.value} $path: ${text.take(300)}")
        val message = (runCatching { json.parseToJsonElement(text) }.getOrNull())["error"]["message"].str()
        throw AddonException(when (response.status.value) {
            401 -> "Spotify signed you out. Connect it again in Settings."
            403 -> message ?: "Spotify refused. Playback control needs Premium, and your Spotify app must list you as a user."
            404 -> if (path.contains("player")) "No Spotify device is open. Start Spotify on a phone or computer, then try again." else "Spotify does not have that."
            429 -> "Spotify says slow down. Try again in a moment."
            else -> message ?: "Spotify answered ${response.status.value}."
        })
    }

    suspend fun devices(): List<SpotifyDevice> = call(HttpMethod.Get, "/me/player/devices")["devices"].arr().orEmpty().mapNotNull { d ->
        SpotifyDevice(d["id"].str() ?: return@mapNotNull null, d["name"].str() ?: "Device", d["type"].str().orEmpty(), d["is_active"].bool() == true, d["volume_percent"].int())
    }

    suspend fun playback(): SpotifyPlayback? {
        val r = call(HttpMethod.Get, "/me/player")
        if (r !is JsonObject) return null
        val item = r["item"]
        return SpotifyPlayback(r["is_playing"].bool() == true, r["progress_ms"].long() ?: 0, item["duration_ms"].long() ?: 0, item["uri"].str(), r["device"]["id"].str())
    }

    suspend fun play(uri: String, deviceId: String?, positionMs: Long) {
        call(HttpMethod.Put, "/me/player/play", mapOf("device_id" to deviceId), buildJsonObject { put("uris", buildJsonArray { add(uri) }); put("position_ms", positionMs) })
    }
    suspend fun resume(deviceId: String?) { call(HttpMethod.Put, "/me/player/play", mapOf("device_id" to deviceId)) }
    suspend fun pause() { call(HttpMethod.Put, "/me/player/pause") }
    suspend fun seek(ms: Long) { call(HttpMethod.Put, "/me/player/seek", mapOf("position_ms" to ms.toString())) }
    suspend fun volume(percent: Int) { call(HttpMethod.Put, "/me/player/volume", mapOf("volume_percent" to percent.coerceIn(0, 100).toString())) }
    suspend fun transfer(deviceId: String) { call(HttpMethod.Put, "/me/player", body = buildJsonObject { put("device_ids", buildJsonArray { add(deviceId) }); put("play", true) }) }
}
