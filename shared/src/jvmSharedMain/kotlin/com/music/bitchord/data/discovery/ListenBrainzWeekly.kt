package com.music.bitchord.data.discovery

import com.music.bitchord.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One server-authored ListenBrainz profile recommendation playlist. */
data class ListenBrainzWeeklyPlaylist(
    val id: String,
    val title: String,
    val description: String,
    val tracks: List<ListenBrainzWeeklyTrack>,
)
data class ListenBrainzWeeklyTrack(
    /** Stable recording MBID when supplied by the JSPF MusicBrainz extension. */
    val recordingMbid: String?,
    val title: String,
    val artist: String,
    val release: String?,
)

sealed interface ListenBrainzWeeklyResult {
    data object Loading : ListenBrainzWeeklyResult
    data class Success(val username: String, val playlists: List<ListenBrainzWeeklyPlaylist>) : ListenBrainzWeeklyResult
    data class Empty(val username: String) : ListenBrainzWeeklyResult
    data class Failure(val message: String, val httpStatus: Int? = null) : ListenBrainzWeeklyResult
}

/**
 * Loads the profile's official recommendation playlist metadata and their JSPF recordings.
 * Every request is independently bounded; malformed top-level payloads and HTTP failures are
 * surfaced rather than disguised as an empty recommendation set.
 */
class ListenBrainzWeeklyClient(
    private val token: () -> String?,
    private val client: OkHttpClient = Http.client.newBuilder()
        .callTimeout(5, TimeUnit.SECONDS).connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): ListenBrainzWeeklyResult {
        val credential = token()?.trim().orEmpty()
        if (credential.isBlank()) return ListenBrainzWeeklyResult.Failure("Add a ListenBrainz user token in Settings to load your weekly playlists.")
        return try {
            withTimeout(LOAD_TIMEOUT_MS) {
            val account = get("/1/validate-token", credential)
            val user = account["user_name"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { account["valid"]?.jsonPrimitive?.booleanOrNull == true && it.isNotBlank() }
                ?: return@withTimeout ListenBrainzWeeklyResult.Failure("ListenBrainz token is invalid. Update it in Settings.", 401)
            val encoded = java.net.URLEncoder.encode(user, "UTF-8").replace("+", "%20")
            val metadata = get("/1/user/$encoded/playlists/createdfor", credential = null, count = MAX_PLAYLISTS)
            val entries = metadata["playlists"]?.jsonArray
                ?: return@withTimeout ListenBrainzWeeklyResult.Failure("ListenBrainz recommendations response has no playlists array")
            val refs = entries.mapNotNull { elem ->
                val wrapper = elem.jsonObject
                val p = wrapper["playlist"]?.jsonObject ?: wrapper
                val playlistUrl = p["identifier"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("https://musicbrainz.org/playlist/") || it.startsWith("https://listenbrainz.org/playlist/") }
                val mb = playlistUrl?.substringAfterLast('/')?.takeIf { UUID_PATTERN.matches(it) }
                mb?.takeIf(String::isNotBlank)?.let { Ref(it, p["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), p["annotation"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
            }.distinctBy(Ref::id).take(MAX_PLAYLISTS)
            if (entries.isNotEmpty() && refs.isEmpty()) throw IllegalStateException("ListenBrainz recommendations did not contain playlist MBIDs")
            val playlists = refs.map { ref -> loadPlaylist(ref) }
            if (playlists.isEmpty()) ListenBrainzWeeklyResult.Empty(user)
            else ListenBrainzWeeklyResult.Success(user, playlists)
            }
        } catch (e: ApiFailure) {
            ListenBrainzWeeklyResult.Failure(e.message ?: "ListenBrainz request failed", e.status)
        } catch (e: TimeoutCancellationException) {
            ListenBrainzWeeklyResult.Failure("ListenBrainz weekly discovery timed out")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ListenBrainzWeeklyResult.Failure(e.message ?: "Could not load ListenBrainz recommendations")
        }
    }

    private suspend fun loadPlaylist(ref: Ref): ListenBrainzWeeklyPlaylist {
        val root = get("/1/playlist/${java.net.URLEncoder.encode(ref.id, "UTF-8")}", credential = null, metadata = false)
        val p = root["playlist"]?.jsonObject ?: root
        val tracks = p["track"]?.jsonArray.orEmpty().mapNotNull { element ->
            runCatching {
                val t = element.jsonObject
                val recordingId = t["identifier"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("https://musicbrainz.org/recording/") }
                    ?.substringAfterLast('/')
                    ?.takeIf { UUID_PATTERN.matches(it) }
                val title = t["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val creator = t["creator"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isBlank() || creator.isBlank()) null else ListenBrainzWeeklyTrack(recordingId, title, creator, t["album"]?.jsonPrimitive?.contentOrNull)
            }.getOrNull()
        }.take(MAX_TRACKS)
        return ListenBrainzWeeklyPlaylist(ref.id, p["title"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: ref.title.ifBlank { "Weekly discovery" }, p["annotation"]?.jsonPrimitive?.contentOrNull ?: ref.description, tracks)
    }

    private val requestMutex = Mutex()
    private var lastRequestAt = 0L
    private var rateLimitNotBefore = 0L

    private suspend fun get(
        path: String,
        credential: String?,
        metadata: Boolean = true,
        count: Int? = null,
    ): JsonObject = requestMutex.withLock {
        val now = System.currentTimeMillis()
        val waitForSpacing = REQUEST_INTERVAL_MS - (now - lastRequestAt)
        val waitForServerWindow = rateLimitNotBefore - now
        val waitMillis = maxOf(waitForSpacing, waitForServerWindow)
        if (waitMillis > 0) delay(waitMillis)
        lastRequestAt = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            val base = "https://api.listenbrainz.org".toHttpUrl().newBuilder().addPathSegments(path.removePrefix("/"))
            if (!metadata) base.addQueryParameter("fetch_metadata", "false")
            count?.let { base.addQueryParameter("count", it.toString()) }
            val requestBuilder = Request.Builder().url(base.build())
                .header("User-Agent", "BitChord/1.0 (https://github.com/kushagrasinghx/BitChord)").get()
            credential?.let { requestBuilder.header("Authorization", "Token $it") }
            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                val remaining = response.header("X-RateLimit-Remaining")?.toIntOrNull()
                val resetSeconds = response.header("X-RateLimit-Reset-In")?.toLongOrNull()
                if (remaining == 0 && resetSeconds != null && resetSeconds > 0) {
                    rateLimitNotBefore = System.currentTimeMillis() + resetSeconds * 1000L
                }
                if (!response.isSuccessful) throw ApiFailure(response.code, "ListenBrainz returned HTTP ${response.code}")
                val source = response.body?.source() ?: throw IllegalStateException("ListenBrainz response had no body")
                val bytes = okio.Buffer()
                var total = 0L
                while (true) {
                    val read = source.read(bytes, minOf(8192L, MAX_BODY_BYTES + 1 - total))
                    if (read == -1L) break
                    total += read
                    if (total > MAX_BODY_BYTES) throw IllegalStateException("ListenBrainz response exceeded the 2 MiB limit")
                }
                try { json.parseToJsonElement(bytes.readUtf8()).jsonObject }
                catch (e: Exception) { throw IllegalStateException("ListenBrainz returned invalid JSON", e) }
            }
        }
    }

    private data class Ref(val id: String, val title: String, val description: String)
    private class ApiFailure(val status: Int, message: String) : Exception(message)
    private companion object {
        const val MAX_PLAYLISTS = 4
        const val MAX_TRACKS = 100
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        const val REQUEST_INTERVAL_MS = 1000L
        const val LOAD_TIMEOUT_MS = 35_000L
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
