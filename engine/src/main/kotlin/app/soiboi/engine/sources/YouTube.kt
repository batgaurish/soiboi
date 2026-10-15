package app.soiboi.engine.sources

import app.soiboi.engine.download.PipelineRunner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Trailing decoration that is never part of a song's name; matched repeatedly because uploads stack it. */
private val noise = Regex(
    "[(\\[]\\s*(?:" +
        "official\\s*(?:music\\s*)?(?:video|audio|visualizer|lyric[s]?\\s*video)?|" +
        "lyric[s]?(?:\\s*video)?|audio|visuali[sz]er|" +
        "hd|hq|4k|full\\s*hd|remaster(?:ed)?(?:\\s*\\d{4})?|" +
        "explicit|clean|radio\\s*edit|music\\s*video|" +
        "with\\s*lyrics|letra|legendado" +
        ")\\s*[)\\]]",
    RegexOption.IGNORE_CASE,
)

/** A featured-artist suffix outside brackets. Apple lists the track under the primary artist alone. */
private val featuring = Regex("\\s+(?:ft\\.?|feat\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)

/** "Various Artists - Topic", the auto-generated channels YouTube Music uses for real catalog uploads. */
private val topicSuffix = Regex("\\s*-\\s*Topic\\s*$", RegexOption.IGNORE_CASE)

/** Hyphen, en dash and em dash all appear; the spaces around them stop "Sun-El" being torn in half. */
private val separator = Regex("\\s+[-\u2013\u2014]\\s+")

private fun clean(value: String) = value
    .replace(noise, " ")
    .replace(Regex("\\s{2,}"), " ")
    // Left behind when the trailing bracket group was the whole tail.
    .replace(Regex("[\\s\\-\u2013\u2014|]+$"), "")
    .replace(Regex("^[\\s\\-\u2013\u2014|]+"), "")
    .trim()

/**
 * Turns a YouTube entry into an artist and a title. YouTube entries are videos, not tracks: the
 * artist and title arrive fused into one human-written string. In order of trust:
 *  1. A "- Topic" channel is YouTube Music's own catalog upload: uploader is the artist.
 *  2. "Artist - Title" in the video name; the uploader is often a label, so the name wins.
 *  3. Neither: the title is the track and the uploader the best guess at an artist.
 */
fun parseYouTubeTrack(rawTitle: String, rawUploader: String, isrc: String? = null): ExternalTrack {
    val uploader = rawUploader.trim()
    val topic = topicSuffix.containsMatchIn(uploader)
    val artistFromUploader = clean(uploader.replace(topicSuffix, ""))

    if (topic) return ExternalTrack(clean(rawTitle).replace(featuring, "").trim(), artistFromUploader, isrc)

    val cleaned = clean(rawTitle)
    separator.find(cleaned)?.let { m ->
        val artist = cleaned.substring(0, m.range.first).trim()
        val title = cleaned.substring(m.range.last + 1).trim()
        // Only when both halves survive: "- Anthem" would otherwise produce an empty field.
        if (artist.isNotEmpty() && title.isNotEmpty()) {
            return ExternalTrack(title.replace(featuring, "").trim(), artist.replace(featuring, "").trim(), isrc)
        }
    }
    return ExternalTrack(cleaned.replace(featuring, "").trim(), artistFromUploader, isrc)
}

/** The `list=` id in a YouTube or YouTube Music URL; a bare id works too. */
fun youTubePlaylistId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    if (Regex("^(?:PL|OLAK5uy_|RD|UU|LL|FL)[A-Za-z0-9_-]{10,}$").matches(trimmed)) return trimmed
    val uri = try { java.net.URI(if ("://" in trimmed) trimmed else "https://$trimmed") } catch (_: Exception) { return null }
    val host = uri.host ?: return null
    if (!hostMatches(host, "youtube.com") && !hostMatches(host, "youtu.be")) return null
    val id = (uri.rawQuery ?: "").split('&').map { it.split('=', limit = 2) }.firstOrNull { it[0] == "list" }?.getOrNull(1)
    return id?.takeIf { it.isNotEmpty() }
}

/** YouTube Music playlists, read through the yt-dlp already in the pipeline. Nothing to offer unprompted. */
class YouTubePlaylistSource(private val runner: PipelineRunner) : ExternalPlaylistSource() {
    override val id = "youtube"
    override val displayName = "YouTube Music"
    override suspend fun playlists() = emptyList<ExternalPlaylist>()
    override val acceptsLinks = true
    override fun playlistIdFromUrl(url: String) = youTubePlaylistId(url)

    @Volatile private var error: String? = null
    override val lastError: String? get() = error

    override suspend fun titleFor(playlistId: String) = fetch(playlistId, limit = 1)?.get("title").str()

    override suspend fun tracks(playlistId: String): List<ExternalTrack> =
        fetch(playlistId)?.get("entries").arr().orEmpty().mapNotNull { it.obj() }.map {
            parseYouTubeTrack(it["title"].str() ?: "", it["uploader"].str() ?: "", it["isrc"].str())
        }.filter { it.title.isNotEmpty() }

    private suspend fun fetch(playlistId: String, limit: Int? = null): kotlinx.serialization.json.JsonObject? {
        error = null
        try {
            var result: kotlinx.serialization.json.JsonObject? = null
            runner.run("playlist", buildJsonObject {
                put("url", "https://music.youtube.com/playlist?list=$playlistId")
                if (limit != null) put("limit", limit)
            }).collect { e ->
                if (result == null) {
                    if (e.isDone) result = e.raw else if (e.isError) { error = e.message; result = kotlinx.serialization.json.JsonObject(emptyMap()) }
                }
            }
            return result?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            error = e.toString()
        }
        return null
    }
}
