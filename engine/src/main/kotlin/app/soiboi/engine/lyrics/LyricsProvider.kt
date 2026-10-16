package app.soiboi.engine.lyrics

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Song
import app.soiboi.engine.sources.Http
import app.soiboi.engine.sources.arr
import app.soiboi.engine.sources.get
import app.soiboi.engine.sources.int
import app.soiboi.engine.sources.obj
import app.soiboi.engine.sources.str
import app.soiboi.engine.sources.urlWith
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.charset.Charset

/** LRCLIB asks clients to identify themselves rather than send a browser UA. */
private const val USER_AGENT = "Soiboi (https://github.com/batgaurish/soiboi)"
private const val LRCLIB = "https://lrclib.net"

class LrclibResult(val synced: String?, val plain: String?) {
    val isEmpty: Boolean get() = synced.isNullOrBlank() && plain.isNullOrBlank()

    /** Synced lyrics are strongly preferred: an unsynced blob is a different, much less useful feature wearing the same name. */
    val best: String? get() = synced?.takeIf { it.isNotBlank() } ?: plain?.takeIf { it.isNotBlank() }
}

/**
 * LRCLIB lyrics, the last step in the chain: embedded tags, then a local `.lrc` sidecar, then here.
 * A hit is written back beside the audio so a track costs one round trip and keeps working offline.
 * Every failure is non-fatal: missing lyrics must never break playback.
 */
class Lrclib(private val fallbackDir: java.io.File? = null) {
    /** Looks up [title] by [artist]; null when nothing matches, the network is down, or the request times out. */
    suspend fun fetch(title: String?, artist: String?, album: String? = null, durationMs: Long? = null): LrclibResult? {
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return null
        val seconds = durationMs?.let { it / 1000 }
        // The exact-match endpoint keys on duration, which avoids picking up a live or remixed cut with the same name.
        val exact = get("/api/get", buildMap {
            put("track_name", title.trim()); put("artist_name", artist.trim())
            if (!album.isNullOrBlank()) put("album_name", album.trim())
            if (seconds != null && seconds > 0) put("duration", "$seconds")
        })?.obj()?.let(::resultOf)
        if (exact != null) return exact
        // Fall back to search, which tolerates tag drift between our copy and theirs.
        val body = get("/api/search", mapOf("track_name" to title.trim(), "artist_name" to artist.trim()))?.arr()?.mapNotNull { it.obj() }
        if (body.isNullOrEmpty()) return null
        // Prefer a candidate within a couple of seconds of ours: a large mismatch usually means a different edit of the same song.
        val chosen = (if (seconds != null && seconds > 0) body.firstOrNull { c -> c["duration"].int()?.let { kotlin.math.abs(it - seconds) <= 2 } == true } else null) ?: body.first()
        return resultOf(chosen)
    }

    private fun resultOf(o: JsonObject) = LrclibResult(o["syncedLyrics"].str(), o["plainLyrics"].str()).takeUnless { it.isEmpty }

    private suspend fun get(path: String, query: Map<String, String>) = try {
        withTimeout(6000) {
            val response = Http.client.get(urlWith("$LRCLIB$path", query)) { header("User-Agent", USER_AGENT) }
            if (response.status == HttpStatusCode.OK) Http.json.parseToJsonElement(response.bodyAsText()) else null
        }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        EngineLog.output("lrclib: timed out"); null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        EngineLog.output("lrclib: $e"); null
    }

    /**
     * Writes [lyrics] to a `.lrc` beside [audioPath] so the next play is offline. When that folder is read-only (a mounted share,
     * files owned by another user) they go to the app's own cache instead, so a song is never fetched twice.
     */
    fun cacheSidecar(audioPath: String, lyrics: String) {
        try {
            val dot = audioPath.lastIndexOf('.'); if (dot <= 0) return
            val file = File(audioPath.substring(0, dot) + ".lrc")
            if (!file.exists()) file.writeText(lyrics)
        } catch (_: Exception) {
            try { fallbackFile(audioPath)?.let { it.parentFile?.mkdirs(); it.writeText(lyrics) } } catch (e: Exception) { EngineLog.output("lrclib cache: $e") }
        }
    }

    /** Where lyrics for [audioPath] live when they could not be written beside it. */
    fun fallbackFile(audioPath: String): File? = fallbackDir?.let { File(it, java.security.MessageDigest.getInstance("MD5").digest(audioPath.toByteArray()).joinToString("") { b -> "%02x".format(b) } + ".lrc") }
}

/** Finds the best lyrics for a song: a sidecar `.lrc` or the tags, upgraded to synced lyrics from LRCLIB when only untimed ones exist. */
class LyricsProvider(
    private val lrclib: Lrclib,
    private val lrclibEnabled: () -> Boolean,
    /** Lyrics for a connector song (Navidrome and friends). */
    private val remoteLyrics: suspend (Song) -> String? = { null },
    /** Tried in order when LRCLIB has no synced lyrics. */
    private val extraSources: () -> List<LyricsSource> = { emptyList() },
    private val noLyrics: String = "No lyrics",
    private val parseFailed: String = "Couldn't read these lyrics",
) {
    private val cache = HashMap<String, ParsedLyrics>()

    suspend fun parsedFor(song: Song): ParsedLyrics {
        cache[song.id]?.let { return it }
        var lines: List<String> = emptyList()
        val path = song.path
        if (path == null) {
            lines = remoteLyrics(song)?.split('\n').orEmpty()
        } else {
            val sidecar = File(path.substringBeforeLast('.') + ".lrc")
            // A sidecar .lrc wins over tags: Apple downloads embed line-timed lyrics and the pipeline writes the word-timed
            // version beside the file; reading the sidecar only when the tags were empty meant it was never used.
            val cached = lrclib.fallbackFile(path)?.takeIf { it.exists() }
            lines = when {
                sidecar.exists() -> readLines(sidecar)
                cached != null -> readLines(cached)
                else -> song.lyrics.orEmpty().split('\n').takeIf { !song.lyrics.isNullOrEmpty() }.orEmpty()
            }
        }

        // Ask LRCLIB when we have nothing, or when what we have is untimed: gamdl embeds Apple's plain lyrics, so a downloaded
        // track always arrives unsynced, and treating "has lyrics" as "done" meant it could never gain synced lyrics.
        if (lrclibEnabled() && !hasTimestamps(lines)) {
            val fetched = lrclib.fetch(song.title, song.artist, song.album, song.durationMs)
            val synced = fetched?.synced?.takeIf { it.isNotBlank() }
            if (synced != null) {
                lines = synced.split('\n')
                path?.let { lrclib.cacheSidecar(it, synced) }
            } else {
                val other = extraSources().firstNotNullOfOrNull { it.fetch(song)?.takeIf { l -> l.isNotBlank() } }
                if (other != null) { lines = other.split('\n'); path?.let { lrclib.cacheSidecar(it, other) } }
                else if (isBlank(lines)) {
                    // Nothing local at all, so even unsynced words are an improvement.
                    fetched?.best?.let { best -> lines = best.split('\n'); path?.let { lrclib.cacheSidecar(it, best) } }
                }
            }
        }
        return parseLrc(lines, noLyrics, parseFailed, song.durationMs).also { cache[song.id] = it }
    }

    private fun readLines(file: File): List<String> = try {
        file.readLines()
    } catch (_: Exception) {
        try { file.readLines(Charset.forName("GBK")) } catch (e: Exception) { EngineLog.output(e.toString()); emptyList() }
    }

    fun forget(songId: String) { cache.remove(songId) }
}
