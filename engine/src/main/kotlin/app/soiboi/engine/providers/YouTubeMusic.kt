package app.soiboi.engine.providers

import app.soiboi.engine.addons.*
import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.sources.*
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*

val YouTubeMusicManifest = AddonManifest(
    id = "ytmusic", name = "YouTube Music", version = "1", description = "Search and play YouTube Music songs, albums, artists and playlists, and start a mix radio from any song. No account needed.",
    icon = null, resources = setOf("search", "stream"), types = setOf("track", "album", "artist", "playlist"), contentType = "music",
    catalogs = listOf(AddonCatalog("home", "track", "Picked for you"), AddonCatalog("new_albums", "album", "New albums")), settings = emptyList(),
)

private const val SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
private const val ALBUMS = "EgWKAQIYAWoMEA4QChADEAQQCRAF"
private const val ARTISTS = "EgWKAQIgAWoMEA4QChADEAQQCRAF"
private const val PLAYLISTS = "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"

/** Every value stored under [key] anywhere below this node, outermost first. InnerTube nests the same renderers at different depths per page. */
internal fun JsonElement?.findAll(key: String, out: MutableList<JsonElement> = ArrayList()): List<JsonElement> {
    when (this) {
        is JsonObject -> for ((k, v) in this) { if (k == key) out += v; v.findAll(key, out) }
        is JsonArray -> for (v in this) v.findAll(key, out)
        else -> {}
    }
    return out
}

internal fun JsonElement?.first(key: String): JsonElement? = findAll(key).firstOrNull()

private val typeWords = setOf("Song", "Video", "Album", "Single", "EP", "Artist", "Playlist", "Podcast", "Episode", "Profile")

/** Text runs split at the " • " separators InnerTube puts between fields: "Artist • Album • 3:45". */
internal fun fieldsOf(runs: JsonElement?): List<String> {
    val fields = ArrayList<String>(); val current = StringBuilder()
    for (r in runs.arr().orEmpty()) {
        val t = r["text"].str() ?: continue
        if (t.trim() == "•") { fields += current.toString().trim(); current.clear() } else current.append(t)
    }
    fields += current.toString().trim()
    return fields.filter { it.isNotEmpty() }
}

internal fun parseDuration(s: String): Long? {
    val parts = s.split(':').map { it.toLongOrNull() ?: return null }
    if (parts.size !in 2..3) return null
    return parts.fold(0L) { a, b -> a * 60 + b } * 1000
}

internal fun bestThumb(item: JsonElement?): String? {
    val thumbs = item.findAll("thumbnails").firstOrNull().arr().orEmpty()
    val url = thumbs.lastOrNull()["url"].str() ?: return null
    // Thumbnails end in "=w120-h120-..."; ask for a larger square.
    return url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544").replace(Regex("=s\\d+"), "=s544")
}

private sealed interface Item {
    data class Track(val t: AddonTrack) : Item
    data class Album(val a: AddonAlbum) : Item
    data class Artist(val a: AddonArtist) : Item
    data class Playlist(val p: AddonPlaylist) : Item
}

/** One list or tile renderer as whatever it is: a song, album, artist or playlist, told apart by where it navigates. */
private fun item(r: JsonElement, fallbackArtist: String? = null, fallbackAlbum: String? = null): Item? {
    val isRow = r["flexColumns"] != null
    val title = (if (isRow) r["flexColumns"].arr()?.getOrNull(0)["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"].arr()?.firstOrNull()["text"].str()
        else r["title"]["runs"].arr()?.firstOrNull()["text"].str())?.trim() ?: return null
    val subtitleRuns = if (isRow) r["flexColumns"].arr()?.getOrNull(1)["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"] else r["subtitle"]["runs"]
    val fields = fieldsOf(subtitleRuns)
    val videoId = r.first("videoId").str()
    val browseId = r["navigationEndpoint"]["browseEndpoint"]["browseId"].str() ?: r.first("browseId").str()
    val thumb = bestThumb(r["thumbnail"] ?: r["thumbnailRenderer"] ?: r)
    val tokens = fields.filter { it !in typeWords }
    val duration = tokens.lastOrNull()?.let(::parseDuration) ?: r["fixedColumns"].first("text")?.let { fieldsOf(it["runs"]).firstOrNull()?.let(::parseDuration) }
    return when {
        videoId != null -> {
            val textTokens = tokens.filter { parseDuration(it) == null && !it.endsWith(" plays") && !it.endsWith(" views") }
            Item.Track(AddonTrack(videoId, title, textTokens.getOrNull(0) ?: fallbackArtist ?: "Unknown Artist", textTokens.getOrNull(1) ?: fallbackAlbum, duration, thumb, null, null, null))
        }
        browseId?.startsWith("MPRE") == true -> Item.Album(AddonAlbum(browseId, title, tokens.firstOrNull { it.toIntOrNull() == null } ?: fallbackArtist, thumb, tokens.firstNotNullOfOrNull { it.toIntOrNull() }))
        browseId?.startsWith("UC") == true -> Item.Artist(AddonArtist(browseId, title, thumb))
        browseId?.startsWith("VL") == true -> Item.Playlist(AddonPlaylist(browseId.removePrefix("VL"), title, thumb, tokens.firstOrNull()))
        else -> null
    }
}

internal class Parsed(val tracks: List<AddonTrack>, val albums: List<AddonAlbum>, val artists: List<AddonArtist>, val playlists: List<AddonPlaylist>)

/** Every song, album, artist and playlist renderer found in [root], in page order. */
internal fun parseItems(root: JsonElement?, fallbackArtist: String? = null, fallbackAlbum: String? = null): Parsed {
    val raw = (root.findAll("musicResponsiveListItemRenderer") + root.findAll("musicTwoRowItemRenderer"))
    val items = raw.mapNotNull { item(it, fallbackArtist, fallbackAlbum) }
    return Parsed(
        items.filterIsInstance<Item.Track>().map { it.t }.distinctBy { it.id }, items.filterIsInstance<Item.Album>().map { it.a }.distinctBy { it.id },
        items.filterIsInstance<Item.Artist>().map { it.a }.distinctBy { it.id }, items.filterIsInstance<Item.Playlist>().map { it.p }.distinctBy { it.id },
    )
}

/** YouTube Music through its own web endpoints; streams are resolved by [resolve], which on both platforms is yt-dlp in the pipeline. */
class YouTubeMusicClient(private val resolve: suspend (videoId: String) -> AddonStream) : CatalogClient {
    private suspend fun call(endpoint: String, body: JsonObjectBuilder.() -> Unit): JsonElement? {
        val payload = buildJsonObject {
            put("context", buildJsonObject { put("client", buildJsonObject { put("clientName", "WEB_REMIX"); put("clientVersion", "1.20250310.01.00"); put("hl", "en"); put("gl", "US") }) })
            body()
        }
        return try {
            val response = Http.client.post("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false") {
                contentType(ContentType.Application.Json); header("Origin", "https://music.youtube.com"); header("User-Agent", BROWSER_USER_AGENT)
                setBody(payload.toString())
            }
            if (response.status.value != 200) throw AddonException("YouTube Music answered ${response.status.value}.")
            Http.json.parseToJsonElement(response.bodyAsText())
        } catch (e: AddonException) { throw e
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { EngineLog.output("ytmusic $endpoint: $e"); throw AddonException("Could not reach YouTube Music.") }
    }

    private suspend fun searchWith(q: String, params: String) = parseItems(call("search") { put("query", q); put("params", params) })

    override suspend fun search(q: String): AddonSearchResult = coroutineScope {
        val songs = async { searchWith(q, SONGS).tracks }
        val albums = async { runCatching { searchWith(q, ALBUMS).albums }.getOrDefault(emptyList()) }
        val artists = async { runCatching { searchWith(q, ARTISTS).artists }.getOrDefault(emptyList()) }
        val playlists = async { runCatching { searchWith(q, PLAYLISTS).playlists }.getOrDefault(emptyList()) }
        AddonSearchResult(songs.await(), albums.await(), artists.await(), playlists.await())
    }

    override suspend fun stream(id: String): AddonStream = resolve(id)

    private suspend fun browse(id: String) = call("browse") { put("browseId", id) }

    private fun headerOf(root: JsonElement?): Pair<String?, String?> {
        val h = root.first("musicResponsiveHeaderRenderer") ?: root.first("musicImmersiveHeaderRenderer") ?: root.first("musicDetailHeaderRenderer") ?: root["header"]
        val title = h["title"]["runs"].arr()?.firstOrNull()["text"].str()
        return title to bestThumb(h["thumbnail"] ?: h)
    }

    override suspend fun album(id: String): AddonDetail {
        val root = browse(id)
        val (title, art) = headerOf(root)
        val artist = root.first("straplineTextOne")["runs"].arr()?.firstOrNull()["text"].str()
        val rows = root["contents"].findAll("musicResponsiveListItemRenderer")
        val tracks = rows.mapNotNull { (item(it, artist, title) as? Item.Track)?.t?.let { t -> t.copy(artworkUrl = t.artworkUrl ?: art) } }
        return AddonDetail(title, art, tracks, emptyList())
    }

    override suspend fun playlist(id: String): AddonDetail {
        // "RDAMVM<videoId>" is a mix radio: the `next` endpoint builds its queue.
        if (id.startsWith("RDAMVM") || id.startsWith("RDAMPL")) {
            val v = id.removePrefix("RDAMVM").removePrefix("RDAMPL")
            val root = call("next") { put("videoId", v); put("playlistId", id); put("isAudioOnly", true) }
            val rows = root.findAll("playlistPanelVideoRenderer")
            val tracks = rows.mapNotNull { r ->
                val vid = r["videoId"].str() ?: return@mapNotNull null
                val title = r["title"]["runs"].arr()?.firstOrNull()["text"].str() ?: return@mapNotNull null
                val f = fieldsOf(r["longBylineText"]["runs"])
                AddonTrack(vid, title, f.getOrNull(0) ?: "Unknown Artist", f.getOrNull(1), r["lengthText"]["runs"].arr()?.firstOrNull()["text"].str()?.let(::parseDuration), bestThumb(r["thumbnail"]), null, null, null)
            }
            return AddonDetail("Radio", tracks.firstOrNull()?.artworkUrl, tracks, emptyList())
        }
        val root = browse(if (id.startsWith("VL")) id else "VL$id")
        val (title, art) = headerOf(root)
        val tracks = root["contents"].findAll("musicResponsiveListItemRenderer").mapNotNull { (item(it) as? Item.Track)?.t }
        return AddonDetail(title, art, tracks, emptyList())
    }

    override suspend fun artist(id: String): AddonDetail {
        val root = browse(id)
        val (title, art) = headerOf(root)
        val parsed = parseItems(root["contents"], fallbackArtist = title)
        return AddonDetail(title, art, parsed.tracks.take(25), parsed.albums)
    }

    override suspend fun catalog(id: String, skip: Int): AddonCatalogPage {
        if (skip > 0) return AddonCatalogPage(emptyList(), emptyList(), emptyList(), emptyList())
        val root = browse(if (id == "new_albums") "FEmusic_new_releases_albums" else "FEmusic_home")
        val p = parseItems(root["contents"])
        return AddonCatalogPage(p.tracks.take(30), p.albums.take(30), p.playlists.take(20), emptyList())
    }
}

fun youTubeMusicProvider(resolve: suspend (String) -> AddonStream) = BuiltinProvider(YouTubeMusicManifest, { YouTubeMusicClient(resolve) })
