package app.soiboi.engine.servers

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.sources.Http
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

class RemotePlaylist(val id: String, val name: String)

/**
 * A remote library the app can browse and play from next to local files: Navidrome, OpenSubsonic, Jellyfin,
 * Emby, Plex, WebDAV or Google Drive. Remote songs become ordinary [Song] rows with a server-side id, so
 * playlists, smart playlists, search and playback treat them exactly like local ones.
 */
interface MusicServer {
    val origin: SongOrigin
    val label: String

    /** Checks connectivity and credentials. */
    suspend fun ping(): Boolean

    /** Every song on the server, paged internally. [onProgress] gets the count so far. */
    suspend fun allSongs(onProgress: (Int) -> Unit = {}): List<Song>

    /** A URL the player can open for [song], with any token baked in. */
    fun streamUrl(song: Song): String

    /** Headers the player must send with [streamUrl] (WebDAV and Drive need auth that cannot ride on the URL). */
    fun streamHeaders(song: Song): Map<String, String> = emptyMap()

    fun coverUrl(song: Song, size: Int = 600): String? = null
    suspend fun lyrics(song: Song): String? = null

    suspend fun playlists(): List<RemotePlaylist> = emptyList()
    suspend fun playlistSongs(id: String): List<Song> = emptyList()
    suspend fun createPlaylist(name: String): String? = null
    suspend fun updatePlaylist(id: String, songIds: List<String>): Boolean = false
    suspend fun deletePlaylist(id: String): Boolean = false

    suspend fun starred(): List<Song> = emptyList()
    suspend fun setStarred(songId: String, starred: Boolean): Boolean = false
    suspend fun scrobble(songId: String): Boolean = false

    /** Saves the song's file to [target], for offline caching. */
    suspend fun download(song: Song, target: File): Boolean = downloadTo(streamUrl(song), streamHeaders(song), target)
}

suspend fun downloadTo(url: String, headers: Map<String, String>, target: File): Boolean = try {
    target.parentFile?.mkdirs()
    val tmp = File(target.path + ".part")
    val response: HttpResponse = Http.client.get(url) { headers.forEach { (k, v) -> header(k, v) } }
    if (!response.status.isSuccess()) false else { tmp.writeBytes(response.bodyAsBytes()); tmp.renameTo(target) }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    EngineLog.output("download $url: $e"); false
}

/** A request with the connector's timeouts and error logging; the shared shape every server uses. */
internal suspend fun HttpRequestBuilder.json() { header("Accept", "application/json") }

internal suspend fun requestJson(
    method: HttpMethod, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, label: String = "",
): JsonElement? = try {
    withTimeout(20_000) {
        val response = Http.client.request(url) {
            this.method = method
            headers.forEach { (k, v) -> header(k, v) }
            header("Accept", "application/json")
            if (body != null) { header("Content-Type", "application/json"); setBody(body) }
        }
        if (response.status.isSuccess()) response.bodyAsText().takeIf { it.isNotBlank() }?.let { Http.json.parseToJsonElement(it) } ?: kotlinx.serialization.json.JsonNull
        else { EngineLog.output("[$label] $url -> HTTP ${response.status.value}"); null }
    }
} catch (e: Exception) {
    EngineLog.output("[$label] $url: $e"); null
}
