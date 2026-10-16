package app.soiboi.engine.providers

import app.soiboi.engine.addons.*
import app.soiboi.engine.sources.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val SC_API = "https://api-v2.soundcloud.com"

val SoundCloudManifest = AddonManifest(
    id = "soundcloud", name = "SoundCloud", version = "1", description = "Search and play public SoundCloud tracks, sets and artists. No account needed.",
    icon = null, resources = setOf("search", "stream"), types = setOf("track", "album", "artist", "playlist"), contentType = "music",
    catalogs = listOf(AddonCatalog("personalised-curated-global", "playlist", "Curated by SoundCloud"), AddonCatalog("buzzing", "playlist", "Artists to watch")), settings = emptyList(),
)

internal fun scArtwork(o: JsonObject): String? = (o["artwork_url"].str() ?: o["user"]["avatar_url"].str())?.replace("-large.", "-t500x500.")

/** A SoundCloud track object as a catalog track. Null for the id-only stubs a playlist returns for its later entries. */
internal fun scTrack(o: JsonElement?): AddonTrack? {
    val obj = o.obj() ?: return null
    val id = (obj["id"].long() ?: return null).toString()
    val title = obj["title"].str()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val meta = obj["publisher_metadata"]
    val artist = meta["artist"].str()?.trim()?.ifEmpty { null } ?: obj["user"]["username"].str() ?: "Unknown Artist"
    // SoundCloud serves only a 30-second clip of some tracks to people without Go+; say so rather than cut off mid-song.
    val label = if (obj["policy"].str() == "SNIP") "$title (30s preview)" else title
    return AddonTrack(id, label, artist, meta["album_title"].str(), obj["full_duration"].long() ?: obj["duration"].long(), scArtwork(obj), meta["isrc"].str(), null, null)
}

class SoundCloudClient(private val ids: ClientIds = ClientIds()) : CatalogClient {
    /** The public web-app id SoundCloud's own site uses; it rotates, so a refusal fetches a new one once. */
    class ClientIds { @Volatile var value: String? = null }

    private suspend fun clientId(refresh: Boolean = false): String {
        if (!refresh) ids.value?.let { return it }
        ids.value = fetchText("https://soundcloud.com/")?.let(::parseSoundcloudClientId)
        return ids.value ?: throw AddonException("Could not reach SoundCloud.")
    }

    private suspend fun api(path: String, query: Map<String, String?> = emptyMap()): JsonElement? {
        for (attempt in 0..1) {
            val id = clientId(refresh = attempt == 1)
            val body = fetchText(urlWith("$SC_API$path", query + ("client_id" to id)), mapOf("Accept" to "application/json")) ?: continue
            return try { Http.json.parseToJsonElement(body) } catch (_: Exception) { null }
        }
        return null
    }

    override suspend fun search(q: String): AddonSearchResult = coroutineScope {
        val tracks = async { api("/search/tracks", mapOf("q" to q, "limit" to "25")).obj()?.get("collection").arr().orEmpty().mapNotNull(::scTrack) }
        val sets = async { api("/search/playlists", mapOf("q" to q, "limit" to "12")).obj()?.get("collection").arr().orEmpty().mapNotNull(::scSet) }
        val users = async { api("/search/users", mapOf("q" to q, "limit" to "8")).obj()?.get("collection").arr().orEmpty().mapNotNull(::scUser) }
        AddonSearchResult(tracks.await(), emptyList(), users.await(), sets.await())
    }

    private fun scSet(e: JsonElement): AddonPlaylist? {
        val o = e.obj() ?: return null
        return AddonPlaylist((o["id"].long() ?: return null).toString(), o["title"].str() ?: return null, scArtwork(o), o["user"]["username"].str())
    }
    private fun scUser(e: JsonElement): AddonArtist? {
        val o = e.obj() ?: return null
        return AddonArtist((o["id"].long() ?: return null).toString(), o["username"].str() ?: return null, o["avatar_url"].str()?.replace("-large.", "-t500x500."))
    }

    override suspend fun stream(id: String): AddonStream {
        val track = api("/tracks/$id").obj() ?: throw AddonException("SoundCloud has no track $id.")
        val auth = track["track_authorization"].str()
        val transcodings = track["media"]["transcodings"].arr().orEmpty().mapNotNull { it.obj() }
        // Progressive MP3 plays everywhere and seeks; HLS is the fallback for tracks SoundCloud only serves that way.
        val pick = transcodings.firstOrNull { it["format"]["protocol"].str() == "progressive" } ?: transcodings.firstOrNull { it["format"]["protocol"].str() == "hls" }
            ?: throw AddonException("SoundCloud offers no stream for this track. It may be restricted in your region.")
        val resolveUrl = pick["url"].str() ?: throw AddonException("SoundCloud sent a stream without an address.")
        val answer = fetchJson(urlWith(resolveUrl, mapOf("client_id" to clientId(), "track_authorization" to auth))).obj()
        val url = answer?.get("url").str() ?: throw AddonException("SoundCloud would not hand over the stream.")
        val mime = pick["format"]["mime_type"].str().orEmpty()
        return AddonStream(url, if ("mpeg" in mime) "mp3" else if ("mp4" in mime || "aac" in mime) "aac" else if ("opus" in mime || "ogg" in mime) "opus" else null, null, emptyMap())
    }

    private suspend fun setDetail(id: String): AddonDetail {
        val set = api("/playlists/$id").obj() ?: return AddonDetail(null, null, emptyList(), emptyList())
        val entries = set["tracks"].arr().orEmpty()
        val full = entries.mapNotNull(::scTrack).associateBy { it.id }
        val missing = entries.mapNotNull { it.obj()?.get("id").long()?.toString() }.filter { it !in full }
        val fetched = missing.chunked(50).flatMap { chunk -> api("/tracks", mapOf("ids" to chunk.joinToString(","))).arr().orEmpty().mapNotNull(::scTrack) }.associateBy { it.id }
        val ordered = entries.mapNotNull { it.obj()?.get("id").long()?.toString() }.mapNotNull { full[it] ?: fetched[it] }
        return AddonDetail(set["title"].str(), scArtwork(set), ordered, emptyList())
    }

    override suspend fun album(id: String) = setDetail(id)
    override suspend fun playlist(id: String) = setDetail(id)

    override suspend fun artist(id: String): AddonDetail = coroutineScope {
        val user = async { api("/users/$id").obj() }
        val tracks = async { api("/users/$id/tracks", mapOf("limit" to "30")).obj()?.get("collection").arr().orEmpty().mapNotNull(::scTrack) }
        val sets = async { api("/users/$id/playlists_without_albums", mapOf("limit" to "20")).obj()?.get("collection").arr().orEmpty().mapNotNull { e -> e.obj()?.let { o -> AddonAlbum((o["id"].long() ?: return@let null).toString(), o["title"].str() ?: return@let null, o["user"]["username"].str(), scArtwork(o), null) } } }
        val u = user.await()
        AddonDetail(u?.get("username").str(), u?.get("avatar_url").str()?.replace("-large.", "-t500x500."), tracks.await(), sets.await())
    }

    override suspend fun catalog(id: String, skip: Int): AddonCatalogPage {
        if (skip > 0) return AddonCatalogPage(emptyList(), emptyList(), emptyList(), emptyList())
        val rows = api("/mixed-selections", mapOf("limit" to "12")).obj()?.get("collection").arr().orEmpty()
        val row = rows.firstOrNull { it["urn"].str()?.endsWith(":$id") == true }
        val items = row["items"]["collection"].arr().orEmpty()
        return AddonCatalogPage(items.mapNotNull(::scTrack), emptyList(), items.filter { it["kind"].str() == "playlist" }.mapNotNull(::scSet), emptyList())
    }
}

val SoundCloudProvider = BuiltinProvider(SoundCloudManifest, { SoundCloudClient() })
