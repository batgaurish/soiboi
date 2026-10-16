package app.soiboi.engine.providers

import app.soiboi.engine.addons.*
import app.soiboi.engine.sources.*
import kotlinx.serialization.json.JsonElement

private val HOSTS = listOf("de1.api.radio-browser.info", "nl1.api.radio-browser.info", "at1.api.radio-browser.info")

val RadioManifest = AddonManifest(
    id = "radio", name = "Internet radio", version = "1", description = "Live stations from the community radio-browser directory. Search by name, country or genre.",
    icon = null, resources = setOf("search", "stream"), types = setOf("track"), contentType = "music",
    catalogs = listOf(
        AddonCatalog("topvote", "track", "Most loved stations"), AddonCatalog("tag:lofi", "track", "Lo-fi"), AddonCatalog("tag:jazz", "track", "Jazz"),
        AddonCatalog("tag:bollywood", "track", "Bollywood"), AddonCatalog("tag:classical", "track", "Classical"), AddonCatalog("tag:electronic", "track", "Electronic"),
        AddonCatalog("tag:news", "track", "News"),
    ), settings = emptyList(),
)

/** A station as a "track" with no duration: it plays until stopped. Tags and country stand in for the artist. */
internal fun radioStation(e: JsonElement): AddonTrack? {
    val o = e.obj() ?: return null
    val uuid = o["stationuuid"].str() ?: return null
    val name = o["name"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val url = (o["url_resolved"].str()?.takeIf { it.isNotBlank() } ?: o["url"].str())?.takeIf { it.startsWith("http") } ?: return null
    if ((o["lastcheckok"].int() ?: 1) == 0) return null // The directory's own probe could not open it.
    val codec = o["codec"].str()?.lowercase()?.takeIf { it.isNotBlank() && it != "unknown" }
    val where = listOfNotNull((o["countrycode"].str()?.takeIf { it.isNotBlank() } ?: o["country"].str()?.takeIf { it.isNotBlank() }), o["tags"].str()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2)?.joinToString(", ")?.takeIf { it.isNotBlank() }).joinToString(" · ")
    return AddonTrack(uuid, name, where.ifEmpty { "Live radio" }, "Live radio", null, o["favicon"].str()?.takeIf { it.startsWith("http") }, null, codec, url)
}

class RadioClient : CatalogClient {
    private suspend fun api(path: String): JsonElement? {
        for (host in HOSTS) fetchJson("https://$host$path", mapOf("Accept" to "application/json"))?.let { return it }
        throw AddonException("Could not reach the radio directory.")
    }

    private fun stations(e: JsonElement?) = e.arr().orEmpty().mapNotNull(::radioStation).distinctBy { it.title.lowercase() }

    override suspend fun search(q: String): AddonSearchResult {
        val byName = stations(api("/json/stations/search?name=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=40&hidebroken=true&order=votes&reverse=true"))
        val byTag = if (byName.size < 10) stations(api("/json/stations/search?tag=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=20&hidebroken=true&order=votes&reverse=true")) else emptyList()
        return AddonSearchResult((byName + byTag).distinctBy { it.id }, emptyList(), emptyList(), emptyList())
    }

    override suspend fun stream(id: String): AddonStream {
        val answer = api("/json/url/$id").obj() // Also counts the click, which is how the directory ranks stations.
        val url = answer?.get("url").str() ?: throw AddonException("That station is no longer listed.")
        return AddonStream(url, null, null, emptyMap())
    }

    override suspend fun album(id: String) = AddonDetail(null, null, emptyList(), emptyList())
    override suspend fun artist(id: String) = AddonDetail(null, null, emptyList(), emptyList())
    override suspend fun playlist(id: String) = AddonDetail(null, null, emptyList(), emptyList())

    override suspend fun catalog(id: String, skip: Int): AddonCatalogPage {
        val path = if (id.startsWith("tag:")) "/json/stations/bytag/${java.net.URLEncoder.encode(id.removePrefix("tag:"), "UTF-8")}?limit=30&offset=$skip&hidebroken=true&order=votes&reverse=true"
        else "/json/stations/topvote/30?offset=$skip&hidebroken=true"
        return AddonCatalogPage(stations(api(path)), emptyList(), emptyList(), emptyList())
    }
}

val RadioProvider = BuiltinProvider(RadioManifest, { RadioClient() })
