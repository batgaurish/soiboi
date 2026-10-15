package app.soiboi.engine.sources

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class ParsedPage(val title: String, val tracks: List<ExternalTrack>)

/** Title and tracks from a page's schema.org `MusicPlaylist` or `MusicAlbum`, or null when the page has none. */
fun parseJsonLdPlaylist(html: String): ParsedPage? {
    val blocks = Regex("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(html)
    for (block in blocks) {
        val decoded = try { Http.json.parseToJsonElement(block.groupValues[1]) } catch (_: Exception) { continue }
        val nodes: List<JsonElement> = if (decoded is JsonArray) decoded else listOf(decoded)
        for (node in nodes.mapNotNull { it.obj() }) {
            val type = node["@type"].str()
            if (type != "MusicPlaylist" && type != "MusicAlbum") continue
            var entries = node["track"]
            if (entries is JsonObject) entries = entries["itemListElement"]
            val list = entries.arr() ?: continue
            return ParsedPage(node["name"].str()?.trim() ?: "Playlist", list.mapNotNull { it.obj()?.let { e -> jsonLdTrack(e, node) } })
        }
    }
    return null
}

private fun jsonLdTrack(entry: JsonObject, playlist: JsonObject): ExternalTrack? {
    // An ItemList wraps each recording in a ListItem.
    val recording = entry["item"].obj() ?: entry
    val title = recording["name"].str()?.let(::tidy)
    val artist = artistName(recording["byArtist"]) ?: artistName(playlist["byArtist"])
    if (title.isNullOrEmpty() || artist == null) return null
    return ExternalTrack(title, artist, recording["isrcCode"].str()?.trim()?.ifEmpty { null })
}

/**
 * schema.org allows a name, an object, or a list of either. Gaana puts every credited name in one
 * comma-joined string, so the first two names stand for the track.
 */
private fun artistName(byArtist: JsonElement?): String? {
    val items: List<JsonElement?> = if (byArtist is JsonArray) byArtist else listOf(byArtist)
    val names = items.flatMap { a ->
        val name = if (a is JsonObject) a["name"].str() else a.str()
        name?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
    return if (names.isEmpty()) null else names.take(2).joinToString(", ")
}

private fun tidy(text: String) = text.replace(Regex("\\s+"), " ").trim()

/** A source whose playlists are public pages carrying schema.org data (Qobuz, Gaana). */
class JsonLdPlaylistSource(
    override val id: String,
    override val displayName: String,
    private val domain: String,
    /** The path segment that marks a playlist page. */
    private val pathMarker: String,
) : ExternalPlaylistSource() {
    override suspend fun playlists() = emptyList<ExternalPlaylist>()
    override val acceptsLinks = true

    @Volatile private var error: String? = null
    override val lastError: String? get() = error

    override fun playlistIdFromUrl(url: String): String? {
        val uri = try { java.net.URI(url.trim()) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        if (!hostMatches(host, domain)) return null
        if (pathMarker !in uri.path.split('/')) return null
        // The page itself, without tracking parameters.
        return "https://$host${uri.path}"
    }

    private suspend fun read(url: String): ParsedPage? {
        val parsed = fetchText(url)?.let(::parseJsonLdPlaylist)
        error = if (parsed == null) "Could not read a tracklist from that $displayName page." else null
        return parsed
    }

    override suspend fun titleFor(playlistId: String) = read(playlistId)?.title
    override suspend fun tracks(playlistId: String) = read(playlistId)?.tracks.orEmpty()
}

fun qobuzPlaylistSource() = JsonLdPlaylistSource("qobuz", "Qobuz", "qobuz.com", "playlists")
fun gaanaPlaylistSource() = JsonLdPlaylistSource("gaana", "Gaana", "gaana.com", "playlist")

/** Title and tracks from a Bandcamp album page's `data-tralbum` attribute, or null. */
fun parseBandcampAlbum(html: String): ParsedPage? {
    val match = Regex("data-tralbum=\"([^\"]*)\"").find(html) ?: return null
    val data = try {
        Http.json.parseToJsonElement(
            match.groupValues[1].replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"),
        ).obj()
    } catch (_: Exception) { null } ?: return null
    val albumArtist = data["artist"].str()?.trim()
    val tracks = data["trackinfo"].arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { track ->
        val title = track["title"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val artist = (track["artist"].str()?.trim() ?: albumArtist)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ExternalTrack(title, artist)
    }
    return ParsedPage(data["current"]["title"].str()?.trim() ?: "Bandcamp album", tracks)
}

/** Bandcamp has no playlists, only albums, with each tracklist embedded as JSON. */
class BandcampAlbumSource : ExternalPlaylistSource() {
    override val id = "bandcamp"
    override val displayName = "Bandcamp"
    override suspend fun playlists() = emptyList<ExternalPlaylist>()
    override val acceptsLinks = true

    @Volatile private var error: String? = null
    override val lastError: String? get() = error

    override fun playlistIdFromUrl(url: String): String? {
        val uri = try { java.net.URI(url.trim()) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        if (!hostMatches(host, "bandcamp.com")) return null
        val segments = uri.path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "album") return null
        return "https://$host/album/${segments[1]}"
    }

    private suspend fun read(url: String): ParsedPage? {
        val parsed = fetchText(url)?.let(::parseBandcampAlbum)
        error = if (parsed == null) "Could not read that Bandcamp album." else null
        return parsed
    }

    override suspend fun titleFor(playlistId: String) = read(playlistId)?.title
    override suspend fun tracks(playlistId: String) = read(playlistId)?.tracks.orEmpty()
}
