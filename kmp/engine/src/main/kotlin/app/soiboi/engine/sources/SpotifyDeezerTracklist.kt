package app.soiboi.engine.sources

import app.soiboi.engine.core.EngineLog
import kotlinx.serialization.json.JsonObject

private val spotifyUri = Regex("^spotify:playlist:([A-Za-z0-9]+)$")
private val alnum = Regex("^[A-Za-z0-9]+$")

private fun pathSegments(url: String): Pair<String, List<String>>? = try {
    val uri = java.net.URI(if ("://" in url) url else "https://$url")
    uri.host?.let { it to uri.path.split('/').filter { s -> s.isNotEmpty() } }
} catch (_: Exception) { null }

/** The playlist id in a Spotify URL, web or embed or `spotify:playlist:` URI. A `?si=` share token is ignored. */
fun spotifyPlaylistId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    spotifyUri.find(trimmed)?.let { return it.groupValues[1] }
    val (host, segments) = pathSegments(trimmed) ?: return null
    if (!hostMatches(host, "spotify.com")) return null
    val i = segments.indexOf("playlist")
    if (i == -1 || i + 1 >= segments.size) return null
    return segments[i + 1].takeIf { alnum.matches(it) }
}

class SpotifyEmbed(val title: String?, val tracks: List<ExternalTrack>)

/**
 * Pulls the playlist out of an embed page's `__NEXT_DATA__` blob. Spotify's own API needs a client
 * secret, which this project ruled out; the public embed page ships its data as JSON instead, though
 * without ISRC, so these resolve by artist+title. Isolated here so a shape change is one function to fix.
 */
fun parseSpotifyEmbed(html: String): SpotifyEmbed? {
    val match = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        .find(html) ?: return null
    return try {
        val data = Http.json.parseToJsonElement(match.groupValues[1])
        val entity = data["props"]["pageProps"]["state"]["data"]["entity"].obj() ?: return null
        SpotifyEmbed(entity["name"].str(), entity["trackList"].arr().orEmpty().mapNotNull { parseSpotifyTrack(it.obj() ?: return@mapNotNull null) })
    } catch (_: Exception) {
        null // A shape change should cost this import, not the app.
    }
}

/** One `trackList` entry: `title` and `subtitle`, where subtitle is the artist line (comma-joined for collaborations). */
fun parseSpotifyTrack(json: JsonObject): ExternalTrack? {
    val title = normalise(json["title"].str())
    val artist = normalise(json["subtitle"].str())
    if (title.isNullOrEmpty() || artist.isNullOrEmpty()) return null
    return ExternalTrack(title, artist)
}

/** Trims and collapses whitespace, including the non-breaking spaces the embed separates artists with. */
private fun normalise(value: String?): String? =
    value?.replace(' ', ' ')?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }

class SpotifyPlaylistSource : ExternalPlaylistSource() {
    override val id = "spotify"
    override val displayName = "Spotify"
    override suspend fun playlists() = emptyList<ExternalPlaylist>()
    override val acceptsLinks = true
    override fun playlistIdFromUrl(url: String) = spotifyPlaylistId(url)

    private suspend fun fetch(playlistId: String): SpotifyEmbed? {
        val page = fetchText("https://open.spotify.com/embed/playlist/$playlistId", mapOf("User-Agent" to "Mozilla/5.0"))
        return page?.let(::parseSpotifyEmbed)
    }

    override suspend fun titleFor(playlistId: String) = fetch(playlistId)?.title
    override suspend fun tracks(playlistId: String) = fetch(playlistId)?.tracks.orEmpty()
}

private const val DEEZER_API = "https://api.deezer.com"

/** The playlist id in a Deezer URL, including localised paths like `/en/playlist/123`. */
fun deezerPlaylistId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val (host, segments) = pathSegments(trimmed) ?: return null
    if (!hostMatches(host, "deezer.com")) return null
    val i = segments.indexOf("playlist")
    if (i == -1 || i + 1 >= segments.size) return null
    return segments[i + 1].takeIf { Regex("^\\d+$").matches(it) }
}

/** One Deezer track. Deezer carries an exact ISRC per track, the most accurate bridge to Apple's catalog. */
fun parseDeezerTrack(json: JsonObject): ExternalTrack? {
    val title = json["title"].str()?.trim()
    val artist = json["artist"]["name"].str()?.trim()
    if (title.isNullOrEmpty() || artist.isNullOrEmpty()) return null
    return ExternalTrack(title, artist, json["isrc"].str()?.trim()?.ifEmpty { null })
}

class DeezerPlaylistSource : ExternalPlaylistSource() {
    override val id = "deezer"
    override val displayName = "Deezer"
    override suspend fun playlists() = emptyList<ExternalPlaylist>()
    override val acceptsLinks = true
    override fun playlistIdFromUrl(url: String) = deezerPlaylistId(url)

    private suspend fun fetch(playlistId: String): JsonObject? {
        val body = fetchJson("$DEEZER_API/playlist/$playlistId").obj() ?: return null
        // Deezer answers 200 with an error object, so a missing or private playlist looks like success until this.
        return if (body["error"] != null) null else body
    }

    override suspend fun titleFor(playlistId: String) = fetch(playlistId)?.get("title").str()
    override suspend fun tracks(playlistId: String): List<ExternalTrack> =
        fetch(playlistId)?.get("tracks")["data"].arr().orEmpty().mapNotNull { it.obj()?.let(::parseDeezerTrack) }
}

private val separators = listOf(" - ", " – ", " — ", "\t")

/** Tracks parsed from pasted text (`Artist - Title` lines, or a CSV with Artist/Title/ISRC columns). */
fun parseTracklist(text: String): List<ExternalTrack> {
    val lines = text.split(Regex("\r?\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return emptyList()
    val header = lines.first().lowercase().split(',').map { it.trim() }
    val artistCol = header.indexOf("artist")
    val titleCol = header.indexOfFirst { it == "title" || it == "track" || it == "name" }
    if (artistCol != -1 && titleCol != -1) {
        val isrcCol = header.indexOf("isrc")
        return lines.drop(1).mapNotNull { csvTrack(splitCsv(it), artistCol, titleCol, isrcCol) }
    }
    return lines.mapNotNull { line ->
        val sep = separators.firstOrNull { it in line } ?: return@mapNotNull null
        val i = line.indexOf(sep)
        // A leading track number ("1. Artist - Title") is noise, not artist.
        val artist = line.substring(0, i).replaceFirst(Regex("^\\d+[.)]\\s*"), "").trim()
        val title = line.substring(i + sep.length).trim()
        if (artist.isEmpty() || title.isEmpty()) null else ExternalTrack(title, artist)
    }
}

private fun csvTrack(cells: List<String>, artist: Int, title: Int, isrc: Int): ExternalTrack? {
    fun cell(i: Int) = if (i in cells.indices) cells[i].trim() else ""
    if (cell(artist).isEmpty() || cell(title).isEmpty()) return null
    return ExternalTrack(cell(title), cell(artist), cell(isrc).ifEmpty { null })
}

/** Splits one CSV line, honouring double-quoted cells. */
private fun splitCsv(line: String): List<String> {
    val cells = ArrayList<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' -> if (quoted && i + 1 < line.length && line[i + 1] == '"') { cell.append('"'); i++ } else quoted = !quoted
            c == ',' && !quoted -> { cells += cell.toString(); cell.clear() }
            else -> cell.append(c)
        }
        i++
    }
    cells += cell.toString()
    return cells
}

/** A pasted tracklist: the way in for any service Soiboi cannot read. The pasted text is the playlist id. */
class TracklistSource : ExternalPlaylistSource() {
    override val id = "tracklist"
    override val displayName = "a pasted tracklist"
    override suspend fun playlists() = emptyList<ExternalPlaylist>()
    override val acceptsLinks = true

    /** Claims pasted text of two or more readable lines, never a single link. */
    override fun playlistIdFromUrl(url: String): String? =
        if ('\n' !in url.trim() || parseTracklist(url).size < 2) null else url

    override suspend fun titleFor(playlistId: String) = "Pasted tracklist (${parseTracklist(playlistId).size} tracks)"
    override suspend fun tracks(playlistId: String) = parseTracklist(playlistId)
}
