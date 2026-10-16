package app.soiboi.engine.spotify

import app.soiboi.engine.addons.*
import app.soiboi.engine.sources.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement

val SpotifyManifest = AddonManifest(
    id = "spotify", name = "Spotify", version = "1",
    description = "Search Spotify and your library, and play through a Spotify Connect device you own. Needs Premium and your own Spotify app (Settings).",
    icon = null, resources = setOf("search", "stream"), types = setOf("track", "album", "artist", "playlist"), contentType = "music",
    catalogs = listOf(AddonCatalog("liked", "track", "Your liked songs"), AddonCatalog("playlists", "playlist", "Your playlists"), AddonCatalog("saved_albums", "album", "Your saved albums")),
    settings = emptyList(),
)

private fun img(o: JsonElement?): String? = o["images"].arr()?.firstOrNull()["url"].str() ?: o["album"]["images"].arr()?.firstOrNull()["url"].str()

internal fun spTrack(e: JsonElement?): AddonTrack? {
    val id = e["id"].str() ?: return null
    val title = e["name"].str() ?: return null
    val artists = e["artists"].arr()?.mapNotNull { it["name"].str() }?.joinToString(", ").orEmpty().ifEmpty { "Unknown Artist" }
    return AddonTrack(id, title, artists, e["album"]["name"].str(), e["duration_ms"].long(), img(e), e["external_ids"]["isrc"].str(), null, "spotify:track:$id")
}

private fun spAlbum(e: JsonElement?): AddonAlbum? = AddonAlbum(e["id"].str() ?: return null, e["name"].str() ?: return null, e["artists"].arr()?.mapNotNull { it["name"].str() }?.joinToString(", "), img(e), e["release_date"].str()?.take(4)?.toIntOrNull())
private fun spArtist(e: JsonElement?): AddonArtist? = AddonArtist(e["id"].str() ?: return null, e["name"].str() ?: return null, img(e))
private fun spPlaylist(e: JsonElement?): AddonPlaylist? = AddonPlaylist(e["id"].str() ?: return null, e["name"].str() ?: return null, img(e), e["owner"]["display_name"].str())

/** Spotify as a source in search and on the shelves. Playback is [SpotifyBackend]'s job: [stream] only names the track. */
class SpotifyClient(private val api: SpotifyApi) : CatalogClient {
    override suspend fun search(q: String): AddonSearchResult {
        val r = api.call(HttpMethod.Get, "/search", mapOf("q" to q, "type" to "track,album,artist,playlist", "limit" to "20"))
        return AddonSearchResult(
            r["tracks"]["items"].arr().orEmpty().mapNotNull(::spTrack), r["albums"]["items"].arr().orEmpty().mapNotNull(::spAlbum),
            r["artists"]["items"].arr().orEmpty().mapNotNull(::spArtist), r["playlists"]["items"].arr().orEmpty().mapNotNull(::spPlaylist),
        )
    }

    override suspend fun stream(id: String) = AddonStream("spotify:track:$id", null, null, emptyMap())

    override suspend fun album(id: String): AddonDetail {
        val a = api.call(HttpMethod.Get, "/albums/$id")
        val art = img(a); val name = a["name"].str()
        // An album's own track objects omit the album, so it is filled back in.
        val tracks = a["tracks"]["items"].arr().orEmpty().mapNotNull { t -> spTrack(t)?.copy(album = name, artworkUrl = art) }
        return AddonDetail(name, art, tracks, emptyList())
    }

    override suspend fun playlist(id: String): AddonDetail {
        val p = api.call(HttpMethod.Get, "/playlists/$id", mapOf("fields" to "name,images,tracks.items(track(id,name,duration_ms,artists(name),album(name,images),external_ids))"))
        return AddonDetail(p["name"].str(), img(p), p["tracks"]["items"].arr().orEmpty().mapNotNull { spTrack(it["track"]) }, emptyList())
    }

    override suspend fun artist(id: String): AddonDetail = coroutineScope {
        val info = async { api.call(HttpMethod.Get, "/artists/$id") }
        val top = async { runCatching { api.call(HttpMethod.Get, "/artists/$id/top-tracks", mapOf("market" to "from_token"))["tracks"].arr().orEmpty().mapNotNull(::spTrack) }.getOrDefault(emptyList()) }
        val albums = async { runCatching { api.call(HttpMethod.Get, "/artists/$id/albums", mapOf("include_groups" to "album,single", "limit" to "30"))["items"].arr().orEmpty().mapNotNull(::spAlbum) }.getOrDefault(emptyList()) }
        val i = info.await()
        AddonDetail(i["name"].str(), img(i), top.await(), albums.await())
    }

    override suspend fun catalog(id: String, skip: Int): AddonCatalogPage {
        val none = AddonCatalogPage(emptyList(), emptyList(), emptyList(), emptyList())
        return when (id) {
            "liked" -> AddonCatalogPage(api.call(HttpMethod.Get, "/me/tracks", mapOf("limit" to "30", "offset" to skip.toString()))["items"].arr().orEmpty().mapNotNull { spTrack(it["track"]) }, emptyList(), emptyList(), emptyList())
            "playlists" -> AddonCatalogPage(emptyList(), emptyList(), api.call(HttpMethod.Get, "/me/playlists", mapOf("limit" to "30", "offset" to skip.toString()))["items"].arr().orEmpty().mapNotNull(::spPlaylist), emptyList())
            "saved_albums" -> AddonCatalogPage(emptyList(), api.call(HttpMethod.Get, "/me/albums", mapOf("limit" to "30", "offset" to skip.toString()))["items"].arr().orEmpty().mapNotNull { spAlbum(it["album"]) }, emptyList(), emptyList())
            else -> none
        }
    }
}

fun spotifyProvider(api: SpotifyApi) = BuiltinProvider(SpotifyManifest, { SpotifyClient(api) }, enabledByDefault = false)
