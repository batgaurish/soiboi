package app.soiboi.engine.servers

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.sources.Http
import app.soiboi.engine.sources.arr
import app.soiboi.engine.sources.get
import app.soiboi.engine.sources.int
import app.soiboi.engine.sources.long
import app.soiboi.engine.sources.obj
import app.soiboi.engine.sources.str
import app.soiboi.engine.sources.urlWith
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.time.Instant

private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

/** What a signed-in Drive account is holding. Stored by the app in secure storage, never logged. */
class DriveTokens(val accessToken: String, val refreshToken: String?, val expiresAtEpochMs: Long)

/**
 * Google's OAuth 2 device flow: the user opens a short URL on any device and types a code, so no redirect
 * handler or embedded browser is needed on either platform. It needs a Google Cloud OAuth client of type
 * "TVs and Limited Input devices"; the client id and secret are the user's own, entered once.
 */
class GoogleDriveAuth(private val clientId: String, private val clientSecret: String) {
    class DeviceCode(val deviceCode: String, val userCode: String, val verificationUrl: String, val intervalSeconds: Int, val expiresInSeconds: Int)

    suspend fun start(): DeviceCode? {
        val res = postForm("https://oauth2.googleapis.com/device/code", mapOf("client_id" to clientId, "scope" to DRIVE_SCOPE)) ?: return null
        return DeviceCode(res["device_code"].str() ?: return null, res["user_code"].str() ?: return null,
            res["verification_url"].str() ?: "https://www.google.com/device", res["interval"].int() ?: 5, res["expires_in"].int() ?: 1800)
    }

    /** Polls until the user approves. Returns tokens, or null on denial or timeout. */
    suspend fun await(code: DeviceCode): DriveTokens? {
        val end = System.currentTimeMillis() + code.expiresInSeconds * 1000L
        var interval = code.intervalSeconds
        while (System.currentTimeMillis() < end) {
            delay(interval * 1000L)
            val res = postForm("https://oauth2.googleapis.com/token", mapOf(
                "client_id" to clientId, "client_secret" to clientSecret, "device_code" to code.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")) ?: continue
            when (res["error"].str()) {
                null -> return tokensFrom(res, null)
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                else -> return null
            }
        }
        return null
    }

    suspend fun refresh(tokens: DriveTokens): DriveTokens? {
        val refresh = tokens.refreshToken ?: return null
        val res = postForm("https://oauth2.googleapis.com/token", mapOf("client_id" to clientId, "client_secret" to clientSecret, "refresh_token" to refresh, "grant_type" to "refresh_token")) ?: return null
        return if (res["access_token"] == null) null else tokensFrom(res, refresh)
    }

    private fun tokensFrom(res: JsonObject, keepRefresh: String?) = DriveTokens(
        res["access_token"].str() ?: "", res["refresh_token"].str() ?: keepRefresh, System.currentTimeMillis() + (res["expires_in"].int() ?: 3600) * 1000L - 60_000,
    )

    private suspend fun postForm(url: String, fields: Map<String, String>): JsonObject? = try {
        val r = Http.client.submitForm(url, Parameters.build { fields.forEach { (k, v) -> append(k, v) } })
        Http.json.parseToJsonElement(r.bodyAsText()).obj()
    } catch (e: Exception) { EngineLog.output("google oauth: $e"); null }
}

/**
 * A Google Drive folder as a music library: every audio file under [folderId] (or the whole Drive when null)
 * becomes a song. Audio tags are not in the Drive metadata, so the folder layout and file name supply them like
 * WebDAV. Streams use `alt=media` with a bearer token, which the player sends as a header.
 */
class GoogleDriveServer(
    private var tokens: DriveTokens, private val auth: GoogleDriveAuth, private val folderId: String? = null,
    private val onTokens: (DriveTokens) -> Unit = {},
) : MusicServer {
    override val origin = SongOrigin.GoogleDrive
    override val label = "Google Drive"

    private suspend fun bearer(): String {
        if (System.currentTimeMillis() >= tokens.expiresAtEpochMs) auth.refresh(tokens)?.let { tokens = it; onTokens(it) }
        return "Bearer ${tokens.accessToken}"
    }

    /** The access token as of the last refresh; the player reads it per request through [streamHeaders]. */
    override fun streamHeaders(song: Song) = mapOf("Authorization" to "Bearer ${tokens.accessToken}")
    override fun streamUrl(song: Song) = "$DRIVE_API/files/${song.id}?alt=media"

    override suspend fun ping() = requestJson(HttpMethod.Get, "$DRIVE_API/about?fields=user", mapOf("Authorization" to bearer()), label = label) != null

    override suspend fun allSongs(onProgress: (Int) -> Unit): List<Song> {
        val out = ArrayList<Song>()
        val parents = HashMap<String, String?>()
        val q = buildString {
            append("trashed=false and (mimeType contains 'audio/' or mimeType='application/ogg')")
            if (folderId != null) append(" and '$folderId' in parents")
        }
        var pageToken: String? = null
        do {
            val res = requestJson(HttpMethod.Get, urlWith("$DRIVE_API/files", mapOf(
                "q" to q, "pageSize" to "1000", "pageToken" to pageToken, "supportsAllDrives" to "true", "includeItemsFromAllDrives" to "true",
                "fields" to "nextPageToken,files(id,name,mimeType,size,modifiedTime,parents,thumbnailLink,videoMediaMetadata,appProperties)",
            )), mapOf("Authorization" to bearer()), label = label).obj() ?: break
            for (f in res["files"].arr().orEmpty().mapNotNull { it.obj() }) {
                val name = f["name"].str() ?: continue
                val base = name.substringBeforeLast('.')
                val numbered = Regex("^(\\d{1,3})[\\s._-]+(.+)$").find(base)
                // "Artist - Title" is the common ripped-file name when there is no folder structure to lean on.
                val dash = Regex("^(.+?)\\s+-\\s+(.+)$").find(numbered?.groupValues?.get(2) ?: base)
                out += Song(
                    id = f["id"].str() ?: continue, origin = origin, format = f["mimeType"].str()?.substringAfter("audio/")?.substringAfter("x-")?.ifEmpty { null } ?: name.substringAfterLast('.', ""),
                    title = dash?.groupValues?.get(2) ?: numbered?.groupValues?.get(2) ?: base, artist = dash?.groupValues?.get(1),
                    track = numbered?.groupValues?.get(1)?.toIntOrNull(), modified = f["modifiedTime"].str()?.let { runCatching { Instant.parse(it) }.getOrNull() },
                )
            }
            onProgress(out.size)
            pageToken = res["nextPageToken"].str()
        } while (pageToken != null)
        return out
    }
}
