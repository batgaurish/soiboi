package app.soiboi.engine.discovery

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Library
import app.soiboi.engine.core.normaliseForMatch
import app.soiboi.engine.core.albumKey
import app.soiboi.engine.sources.ExternalPlaylist
import app.soiboi.engine.sources.ExternalPlaylistSource
import app.soiboi.engine.sources.ExternalTrack
import app.soiboi.engine.sources.Http
import app.soiboi.engine.sources.arr
import app.soiboi.engine.sources.get
import app.soiboi.engine.sources.int
import app.soiboi.engine.sources.obj
import app.soiboi.engine.sources.str
import app.soiboi.engine.sources.urlWith
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val HOST = "https://api.listenbrainz.org"

/** How far back the rankings look. */
enum class LbRange(val api: String, val label: String) {
    Week("week", "This week"), Month("month", "This month"), Year("year", "This year"), AllTime("all_time", "All time")
}

/** One ranked item, plus whatever it matched locally. */
class LbEntry(
    val name: String, val listenCount: Int, val artistName: String? = null, val mbid: String? = null, val artworkUrl: String? = null,
    /** Names of the matching local artist or album, when the library has it. */
    val localArtist: String? = null, val localAlbum: String? = null,
) {
    /** False means you listen to this but don't own it: a gap the download pipeline could fill. */
    val isInLibrary: Boolean get() = localArtist != null || localAlbum != null
}

class LbPlaylist(val mbid: String, val title: String, val lastModified: String?)
class LbTrack(val title: String, val artist: String, val recordingMbid: String?)

/**
 * ListenBrainz statistics and generated playlists. Stats endpoints are public and need only a username,
 * so an offline-first player's top artists don't depend on a home server. Without a username the Home
 * shelves are derived from local play counts instead.
 */
class ListenBrainz(private val user: () -> String, private val library: Library) {
    val connected: Boolean get() = user().trim().isNotEmpty()

    /** The range the last successful fetch actually used, so a shelf that widened can say so instead of hiding it. */
    @Volatile var lastUsedRange: LbRange? = null

    private suspend fun get(url: String, timeoutMs: Long = 12_000): Pair<HttpStatusCode, JsonElement?>? = try {
        withTimeout(timeoutMs) {
            val r = Http.client.get(url)
            r.status to if (r.status == HttpStatusCode.OK) Http.json.parseToJsonElement(r.bodyAsText()) else null
        }
    } catch (e: Exception) {
        EngineLog.output("listenbrainz: $e"); null
    }

    /** Widen only from the default; an explicitly chosen range is the caller's decision and answering with a different one would be a lie. */
    private suspend fun fetch(endpoint: String, listKey: String, range: LbRange, count: Int = 12): List<JsonObject>? {
        val ranges = if (range == LbRange.Month) listOf(LbRange.Month, LbRange.Year, LbRange.AllTime) else listOf(range)
        for (candidate in ranges) {
            val items = fetchOne(endpoint, listKey, candidate, count) ?: return null
            if (items.isNotEmpty()) { lastUsedRange = candidate; return items }
        }
        return emptyList()
    }

    private suspend fun fetchOne(endpoint: String, listKey: String, range: LbRange, count: Int): List<JsonObject>? {
        val u = user().trim(); if (u.isEmpty()) return null
        val (status, body) = get(urlWith("$HOST/1/stats/user/$u/$endpoint", mapOf("count" to "$count", "range" to range.api))) ?: return null
        // 204 means no stats for this range yet: a normal state for a new account, not a failure.
        if (status == HttpStatusCode.NoContent) return emptyList()
        if (status != HttpStatusCode.OK) { EngineLog.output("listenbrainz $endpoint: HTTP ${status.value}"); return null }
        return body["payload"][listKey].arr()?.mapNotNull { it.obj() }
    }

    private fun coverArt(item: JsonObject): String? {
        val caaId = item["caa_id"]?.let { (it as? JsonPrimitive)?.content } ?: return null
        val release = item["caa_release_mbid"].str() ?: return null
        return "https://archive.org/download/mbid-$release/mbid-$release-${caaId}_thumb250.jpg"
    }

    /** Top artists. Null means the request failed: callers should fall back to local rankings rather than showing an empty shelf. */
    suspend fun topArtists(range: LbRange = LbRange.Month): List<LbEntry>? = fetch("artists", "artists", range)?.map { item ->
        val name = item["artist_name"].str() ?: ""
        LbEntry(name, item["listen_count"].int() ?: 0, mbid = item["artist_mbid"].str(), localArtist = matchArtist(name))
    }?.filter { it.name.isNotEmpty() }

    suspend fun topAlbums(range: LbRange = LbRange.Month): List<LbEntry>? = fetch("releases", "releases", range)?.map { item ->
        val name = item["release_name"].str() ?: ""
        LbEntry(name, item["listen_count"].int() ?: 0, item["artist_name"].str(), item["release_mbid"].str(), coverArt(item), localAlbum = matchAlbum(name))
    }?.filter { it.name.isNotEmpty() }

    suspend fun topTracks(range: LbRange = LbRange.Month): List<LbEntry>? = fetch("recordings", "recordings", range)?.map { item ->
        LbEntry(item["track_name"].str() ?: "", item["listen_count"].int() ?: 0, item["artist_name"].str(), item["recording_mbid"].str(), coverArt(item))
    }?.filter { it.name.isNotEmpty() }

    /** Cheap check that a username exists and has stats. */
    suspend fun verifyUser(name: String): Boolean = get("$HOST/1/user/$name/listen-count")?.first == HttpStatusCode.OK

    /** Playlists ListenBrainz generated for the user. Empty when no username is set: not being connected is an ordinary state. */
    suspend fun discoveryPlaylists(): List<LbPlaylist> {
        val u = user().trim(); if (u.isEmpty()) return emptyList()
        val body = get("$HOST/1/user/$u/playlists/createdfor")?.second ?: return emptyList()
        return body["playlists"].arr().orEmpty().mapNotNull { entry ->
            val p = entry["playlist"].obj() ?: return@mapNotNull null
            val identifier = p["identifier"].str() ?: return@mapNotNull null
            // The identifier is a full URL; the mbid is its last path segment.
            LbPlaylist(identifier.trimEnd('/').substringAfterLast('/'), p["title"].str() ?: "Untitled", (p["date"] ?: p["last_modified_at"]).str())
        }
    }

    /** Tracks in a discovery playlist: artist and title only, since Apple resolution is a per-track call needed only when a playlist is opened. */
    suspend fun discoveryTracks(mbid: String): List<LbTrack> {
        val body = get("$HOST/1/playlist/$mbid", 25_000)?.second ?: return emptyList()
        return body["playlist"]["track"].arr().orEmpty().mapNotNull { it.obj() }.map { entry ->
            val ids = entry["identifier"]
            val recording = (ids.arr()?.firstOrNull() ?: ids).str()?.substringAfterLast('/')
            LbTrack(entry["title"].str() ?: "", entry["creator"].str() ?: "", recording)
        }.filter { it.title.isNotEmpty() && it.artist.isNotEmpty() }
    }

    private fun matchArtist(name: String): String? {
        val target = normaliseForMatch(name)
        return library.artists.value.firstOrNull { normaliseForMatch(it.name) == target }?.name
    }

    /** Album names without Apple's release-type suffix, so "White Keys - Single" matches "White Keys". */
    private fun matchAlbum(name: String): String? {
        val target = albumKey(name)
        return library.albums.value.firstOrNull { albumKey(it.name) == target }?.name
    }
}

/** ListenBrainz as a playlist source, a thin wrapper that keeps the rankings (not a playlist concept elsewhere) reachable on their own. */
class ListenBrainzPlaylistSource(private val lb: ListenBrainz) : ExternalPlaylistSource() {
    override val id = "listenbrainz"
    override val displayName = "ListenBrainz"
    override suspend fun playlists() = lb.discoveryPlaylists().map { ExternalPlaylist(id, it.mbid, it.title, it.lastModified) }
    override suspend fun tracks(playlistId: String) = lb.discoveryTracks(playlistId).map { ExternalTrack(it.title, it.artist) }
}
