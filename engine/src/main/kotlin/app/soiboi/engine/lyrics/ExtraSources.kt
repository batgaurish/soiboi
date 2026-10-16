package app.soiboi.engine.lyrics

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Song
import app.soiboi.engine.sources.*
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

/** A place to look for synced lyrics after LRCLIB. Returns LRC text, or null; never throws. */
fun interface LyricsSource { suspend fun fetch(song: Song): String? }

private fun lrcStamp(ms: Long): String {
    val total = ms.coerceAtLeast(0); val m = total / 60000; val s = (total / 1000) % 60; val cs = (total % 1000) / 10
    return "[%02d:%02d.%02d]".format(m, s, cs)
}

/**
 * Musixmatch, through the same web-app API its desktop client uses. Needs the person's own user token (Settings), because
 * the anonymous one Musixmatch hands out is often a placeholder that answers every search with the wrong song.
 */
class MusixmatchSource(private val token: () -> String) : LyricsSource {
    override suspend fun fetch(song: Song): String? {
        val t = token().trim(); val title = song.title; val artist = song.artist
        if (t.isEmpty() || title.isNullOrBlank() || artist.isNullOrBlank()) return null
        return try {
            withTimeout(8000) {
                val url = urlWith("https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get", buildMap {
                    put("format", "json"); put("namespace", "lyrics_richsynched"); put("subtitle_format", "lrc"); put("app_id", "web-desktop-app-v1.0"); put("usertoken", t)
                    put("q_track", title); put("q_artist", artist); song.album?.let { put("q_album", it) }; song.durationMs?.let { put("q_duration", (it / 1000).toString()) }
                })
                val body = Http.client.get(url) { header("Cookie", "AWSELB=0; AWSELBCORS=0"); header("User-Agent", BROWSER_USER_AGENT) }.bodyAsText()
                val calls = Http.json.parseToJsonElement(body)["message"]["body"]["macro_calls"]
                val matched = calls["matcher.track.get"]["message"]["body"]["track"]
                // A wrong match is worse than none: insist on the title we asked for.
                val matchedTitle = matched["track_name"].str()?.lowercase().orEmpty()
                if (matchedTitle.isNotEmpty() && !matchedTitle.contains(title.lowercase().take(12)) && !title.lowercase().contains(matchedTitle.take(12))) return@withTimeout null
                calls["track.subtitles.get"]["message"]["body"]["subtitle_list"].arr()?.firstOrNull()["subtitle"]["subtitle_body"].str()?.takeIf { it.isNotBlank() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { EngineLog.output("musixmatch: $e"); null }
    }
}

/** Paxsenix's Apple Music lyrics service, looked up by the song's Apple Music id from Apple's public search. Best effort: the service is often busy. */
class PaxsenixSource : LyricsSource {
    override suspend fun fetch(song: Song): String? {
        val title = song.title; val artist = song.artist
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return null
        return try {
            withTimeout(7000) {
                val search = fetchJson(urlWith("https://itunes.apple.com/search", mapOf("term" to "$artist $title", "entity" to "song", "limit" to "5")))
                val seconds = song.durationMs?.div(1000)
                val hit = search["results"].arr().orEmpty().firstOrNull { r ->
                    r["trackName"].str()?.lowercase() == title.lowercase() && (seconds == null || r["trackTimeMillis"].long()?.let { kotlin.math.abs(it / 1000 - seconds) <= 3 } != false)
                } ?: return@withTimeout null
                val id = hit["trackId"].long() ?: return@withTimeout null
                val lyrics = fetchJson(urlWith("https://lyrics.paxsenix.org/apple-music/lyrics", mapOf("id" to id.toString()))) ?: return@withTimeout null
                if (lyrics["error"].bool() == true) return@withTimeout null
                toLrc(lyrics)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { EngineLog.output("paxsenix: $e"); null }
    }

    /** Reads either a bare list of lines or `{content: [...]}`; a line carries its start in ms and its words as text or as timed syllables. */
    internal fun toLrc(root: JsonElement): String? {
        val lines = (root.arr() ?: root["content"].arr() ?: root["lyrics"].arr()).orEmpty()
        val out = lines.mapNotNull { l ->
            val start = l["timestamp"].long() ?: l["time"].long() ?: l["begin"].long() ?: return@mapNotNull null
            val text = l["text"].str() ?: l["text"].arr()?.mapNotNull { it["text"].str() }?.joinToString("")?.trim() ?: return@mapNotNull null
            if (text.isBlank()) null else "${lrcStamp(start)}$text"
        }
        return out.takeIf { it.size >= 2 }?.joinToString("\n")
    }
}
