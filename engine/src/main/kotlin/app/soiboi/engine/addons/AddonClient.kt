package app.soiboi.engine.addons

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.sources.BROWSER_USER_AGENT
import app.soiboi.engine.sources.Http
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Soiboi's addons speak the same small HTTP protocol Eclipse's do, so an existing Eclipse addon installs
 * here by its URL: a `/manifest.json` that says what the server can do, then `/search`, `/stream/{id}`,
 * `/album/{id}`, `/artist/{id}`, `/playlist/{id}`, `/catalog/{id}` and the ISRC resolvers.
 */

data class AddonSettingDef(val key: String, val label: String, val type: String, val default: String, val options: List<Pair<String, String>>)
data class AddonCatalog(val id: String, val type: String, val name: String)

data class AddonManifest(
    val id: String, val name: String, val version: String, val description: String,
    val icon: String?, val resources: Set<String>, val types: Set<String>, val contentType: String,
    val catalogs: List<AddonCatalog>, val settings: List<AddonSettingDef>,
) {
    val canSearch get() = "search" in resources
    val canStream get() = "stream" in resources
    val hasCatalogs get() = catalogs.isNotEmpty()
}

data class AddonTrack(
    val id: String, val title: String, val artist: String, val album: String?, val durationMs: Long?,
    val artworkUrl: String?, val isrc: String?, val format: String?, val streamUrl: String?,
)
data class AddonAlbum(val id: String, val title: String, val artist: String?, val artworkUrl: String?, val year: Int?)
data class AddonArtist(val id: String, val name: String, val artworkUrl: String?)
data class AddonPlaylist(val id: String, val title: String, val artworkUrl: String?, val description: String?)

data class AddonSearchResult(val tracks: List<AddonTrack>, val albums: List<AddonAlbum>, val artists: List<AddonArtist>, val playlists: List<AddonPlaylist>) {
    val isEmpty get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

data class AddonStream(val url: String, val format: String?, val quality: String?, val headers: Map<String, String>)

/** A detail page: an album or playlist has [tracks]; an artist has top tracks and [albums]. */
data class AddonDetail(val title: String?, val artworkUrl: String?, val tracks: List<AddonTrack>, val albums: List<AddonAlbum>)

/** One row an addon declared in its manifest, with its first page of items. */
data class AddonCatalogPage(val tracks: List<AddonTrack>, val albums: List<AddonAlbum>, val playlists: List<AddonPlaylist>, val artists: List<AddonArtist>)

class AddonException(message: String) : Exception(message)

/** What the app asks of anything that offers songs to search and play: an installed addon, or a provider built into Soiboi. */
interface CatalogClient {
    suspend fun search(q: String): AddonSearchResult
    suspend fun stream(id: String): AddonStream
    suspend fun album(id: String): AddonDetail
    suspend fun artist(id: String): AddonDetail
    suspend fun playlist(id: String): AddonDetail
    suspend fun catalog(id: String, skip: Int = 0): AddonCatalogPage
    suspend fun resolveIsrc(isrc: String): String? = null
}

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray)?.toList().orEmpty()
private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifEmpty { null }
private fun JsonObject.first(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { k -> this[k]?.takeIf { it !is JsonNull } }
private fun JsonObject.string(vararg keys: String): String? = first(*keys).let { v ->
    // Some addons send a list of artists where others send a name.
    if (v is JsonArray) v.mapNotNull { e -> e.str() ?: e.obj()?.string("name", "title") }.joinToString(", ").ifEmpty { null } else v.str() ?: v.obj()?.string("name", "title")
}

/** The addon protocol does not pin duration's unit: values under 10,000 are seconds, larger ones milliseconds. */
private fun durationMs(v: JsonElement?): Long? {
    val d = (v as? JsonPrimitive)?.doubleOrNull ?: return null
    if (d <= 0) return null
    return if (d < 10_000) (d * 1000).toLong() else d.toLong()
}

internal fun parseManifest(root: JsonObject): AddonManifest? {
    val id = root.string("id") ?: return null
    val name = root.string("name") ?: return null
    return AddonManifest(
        id = id, name = name, version = root.string("version") ?: "0",
        description = root.string("description").orEmpty(), icon = root.string("icon"),
        resources = root["resources"].arr().mapNotNull { it.str() ?: it.obj()?.string("name") }.toSet(),
        types = root["types"].arr().mapNotNull { it.str() }.toSet(),
        contentType = root.string("contentType") ?: "music",
        catalogs = root["catalogs"].arr().mapNotNull { c -> c.obj()?.let { o -> o.string("id")?.let { cid -> AddonCatalog(cid, o.string("type") ?: "track", o.string("name", "title") ?: cid) } } },
        settings = root["settings"].arr().mapNotNull { s ->
            val o = s.obj() ?: return@mapNotNull null
            val key = o.string("key", "id") ?: return@mapNotNull null
            AddonSettingDef(
                key, o.string("label", "name", "title") ?: key, o.string("type") ?: "text", o.string("default").orEmpty(),
                o["options"].arr().mapNotNull { opt -> opt.str()?.let { it to it } ?: opt.obj()?.let { oo -> oo.string("value", "id")?.let { v -> v to (oo.string("label", "name", "title") ?: v) } } },
            )
        },
    )
}

internal fun parseTrack(o: JsonObject): AddonTrack? {
    val id = o.string("id") ?: return null
    val title = o.string("title", "name") ?: return null
    return AddonTrack(
        id, title, o.string("artist", "artists", "artistName") ?: "Unknown Artist", o.string("album", "albumTitle"),
        durationMs(o.first("duration", "durationMs", "durationMS")), o.string("artworkURL", "artworkUrl", "cover", "image", "thumbnail"),
        o.string("isrc"), o.string("format"), o.string("streamURL", "streamUrl"),
    )
}

internal fun parseAlbum(o: JsonObject): AddonAlbum? {
    val id = o.string("id") ?: return null
    return AddonAlbum(id, o.string("title", "name") ?: return null, o.string("artist", "artists", "artistName"), o.string("artworkURL", "artworkUrl", "cover", "image", "thumbnail"), (o["year"] as? JsonPrimitive)?.intOrNull)
}

internal fun parseArtist(o: JsonObject): AddonArtist? =
    AddonArtist(o.string("id") ?: return null, o.string("name", "title") ?: return null, o.string("artworkURL", "artworkUrl", "cover", "image", "thumbnail"))

internal fun parsePlaylist(o: JsonObject): AddonPlaylist? =
    AddonPlaylist(o.string("id") ?: return null, o.string("title", "name") ?: return null, o.string("artworkURL", "artworkUrl", "cover", "image", "thumbnail"), o.string("description"))

private fun <T> JsonElement?.items(parse: (JsonObject) -> T?): List<T> = arr().mapNotNull { it.obj()?.let(parse) }

/** A client for one addon. [settings] are appended to every request as query parameters, as the protocol asks. */
class AddonClient(baseUrl: String, private val settings: Map<String, String> = emptyMap(), private val http: HttpClient = Http.client) : CatalogClient {
    val base: String = normalise(baseUrl)

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement? {
        val url = URLBuilder().takeFrom(base + path).apply {
            (settings + query).forEach { (k, v) -> if (v.isNotEmpty()) parameters.append(k, v) }
        }.buildString()
        val body = try {
            withTimeout(15_000) {
                val response = http.get(url) { header("User-Agent", BROWSER_USER_AGENT); header("Accept", "application/json") }
                if (response.status == HttpStatusCode.NotFound) return@withTimeout null
                if (response.status != HttpStatusCode.OK) throw AddonException("The addon answered ${response.status.value}.")
                response.bodyAsText()
            }
        } catch (e: TimeoutCancellationException) {
            throw AddonException("The addon did not answer in time.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: AddonException) {
            throw e
        } catch (e: Exception) {
            EngineLog.output("addon $base$path: ${e.stackTraceToString().take(700)}")
            throw AddonException("Could not reach the addon (${e.message ?: e::class.simpleName}).")
        } ?: return null
        return try { Http.json.parseToJsonElement(body) } catch (e: Exception) { throw AddonException("The addon sent something that is not JSON.") }
    }

    /** The manifest, and the JSON it came as so an install can be kept and read back offline. */
    suspend fun manifestWithRaw(): Pair<AddonManifest, String> {
        val root = get("/manifest.json").obj() ?: throw AddonException("No manifest at that address. An addon URL ends where /manifest.json begins.")
        return (parseManifest(root) ?: throw AddonException("That manifest has no id or name.")) to root.toString()
    }

    suspend fun manifest(): AddonManifest = manifestWithRaw().first

    override suspend fun search(q: String): AddonSearchResult {
        val root = get("/search", mapOf("q" to q)).obj()
        return AddonSearchResult(
            root?.get("tracks").items(::parseTrack), root?.get("albums").items(::parseAlbum),
            root?.get("artists").items(::parseArtist), root?.get("playlists").items(::parsePlaylist),
        )
    }

    override suspend fun stream(id: String): AddonStream {
        val root = get("/stream/${encode(id)}").obj() ?: throw AddonException("The addon has no stream for that track.")
        val url = root.string("url", "streamURL", "streamUrl") ?: throw AddonException("The addon answered without a stream URL.")
        val headers = root["headers"].obj()?.mapNotNull { (k, v) -> v.str()?.let { k to it } }?.toMap().orEmpty()
        return AddonStream(url, root.string("format", "codec"), root.string("quality"), headers)
    }

    private suspend fun detail(path: String): AddonDetail {
        val root = get(path).obj() ?: return AddonDetail(null, null, emptyList(), emptyList())
        val tracks = (root["tracks"].items(::parseTrack)).ifEmpty { root["topTracks"].items(::parseTrack) }
        return AddonDetail(root.string("title", "name"), root.string("artworkURL", "artworkUrl", "cover", "image"), tracks, root["albums"].items(::parseAlbum))
    }

    override suspend fun album(id: String) = detail("/album/${encode(id)}")
    override suspend fun artist(id: String) = detail("/artist/${encode(id)}")
    override suspend fun playlist(id: String) = detail("/playlist/${encode(id)}")

    override suspend fun catalog(id: String, skip: Int): AddonCatalogPage {
        val items = get("/catalog/${encode(id)}", mapOf("skip" to skip.toString())).obj()?.get("items").arr().mapNotNull { it.obj() }
        // A row carries one kind of item, but nothing says which, so each is read as what it looks like.
        val tracks = ArrayList<AddonTrack>(); val albums = ArrayList<AddonAlbum>(); val playlists = ArrayList<AddonPlaylist>(); val artists = ArrayList<AddonArtist>()
        for (o in items) when {
            o["tracks"] != null -> parsePlaylist(o)?.let(playlists::add)
            o.string("artist", "artists") != null && o.string("album") != null -> parseTrack(o)?.let(tracks::add)
            o.string("artist", "artists") != null -> parseAlbum(o)?.let(albums::add)
            o.string("name") != null && o.string("title") == null -> parseArtist(o)?.let(artists::add)
            else -> parseTrack(o)?.let(tracks::add)
        }
        return AddonCatalogPage(tracks, albums, playlists, artists)
    }

    /** The addon's track id for an exact recording, or null. Never a guess. */
    override suspend fun resolveIsrc(isrc: String): String? = get("/resolve-isrc", mapOf("isrc" to isrc)).obj()?.string("trackId")

    private fun encode(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    companion object {
        fun normalise(url: String): String = url.trim().removeSuffix("/").removeSuffix("/manifest.json").removeSuffix("/")
    }
}
