package app.soiboi.engine.accounts

import app.soiboi.engine.spotify.SpotifyApi
import app.soiboi.engine.sources.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement

/**
 * Spotify session. Uses existing SpotifyApi for OAuth and Web API access.
 *
 * Streaming is not available in-app (requires Spotify Connect or Web SDK);
 * this session provides discovery and download-ref for cross-service resolution.
 */
class SpotifySession(
    private val api: SpotifyApi,
) : MusicSession {

    override val id = "spotify"
    override val label = "Spotify"
    override val canStream = false // Spotify streaming requires Connect/SDK

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Check if signed in and update state. */
    suspend fun refresh() {
        if (!api.connected) {
            _state.value = SessionState.SignedOut
            return
        }
        try {
            val me = api.call(HttpMethod.Get, "/me")
            _state.value = SessionState.SignedIn(
                account = me["email"].str() ?: me["display_name"].str() ?: "Spotify",
                market = me["country"].str() ?: "US",
            )
        } catch (e: Exception) {
            _state.value = SessionState.Error(e.message ?: "Failed to verify Spotify session")
        }
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> {
        val response = api.call(HttpMethod.Get, "/me/playlists", mapOf("limit" to "50"))
        return response["items"].arr().orEmpty().mapNotNull { playlist ->
            SessionPlaylist(
                id = playlist["id"].str() ?: return@mapNotNull null,
                name = playlist["name"].str() ?: "Untitled",
                trackCount = playlist["tracks"]["total"].int(),
                artworkUrl = playlist["images"].arr()?.firstOrNull()["url"].str(),
            )
        }
    }

    override suspend fun playlistTracks(playlistId: String): List<SessionTrack> {
        val response = api.call(
            HttpMethod.Get,
            "/playlists/$playlistId/tracks",
            mapOf("fields" to "items(added_at,track(id,name,duration_ms,artists(name),album(name),external_ids))", "limit" to "100")
        )
        return response["items"].arr().orEmpty().mapNotNull { item ->
            val track = item["track"]
            SessionTrack(
                id = track["id"].str() ?: return@mapNotNull null,
                title = track["name"].str() ?: "Unknown",
                artist = track["artists"].arr()?.firstOrNull()["name"].str(),
                album = track["album"]["name"].str(),
                isrc = track["external_ids"]["isrc"].str(),
                durationMs = track["duration_ms"].long(),
                addedAt = item["added_at"].str()?.let { parseSpotifyDate(it) },
            )
        }
    }

    override suspend fun recentlyAdded(sinceMs: Long): List<SessionTrack> {
        val response = api.call(HttpMethod.Get, "/me/tracks", mapOf("limit" to "50"))
        return response["items"].arr().orEmpty().mapNotNull { item ->
            val addedAt = item["added_at"].str()?.let { parseSpotifyDate(it) } ?: 0L
            if (addedAt <= sinceMs) return@mapNotNull null
            val track = item["track"]
            SessionTrack(
                id = track["id"].str() ?: return@mapNotNull null,
                title = track["name"].str() ?: "Unknown",
                artist = track["artists"].arr()?.firstOrNull()["name"].str(),
                album = track["album"]["name"].str(),
                isrc = track["external_ids"]["isrc"].str(),
                durationMs = track["duration_ms"].long(),
                addedAt = addedAt,
            )
        }
    }

    override suspend fun search(query: String): SessionSearchResult {
        val results = api.call(HttpMethod.Get, "/search", mapOf("q" to query, "type" to "track,album", "limit" to "20"))
        return SessionSearchResult(
            tracks = results["tracks"]["items"].arr().orEmpty().mapNotNull { track ->
                SessionTrack(
                    id = track["id"].str() ?: return@mapNotNull null,
                    title = track["name"].str() ?: "Unknown",
                    artist = track["artists"].arr()?.firstOrNull()["name"].str(),
                    album = track["album"]["name"].str(),
                    isrc = track["external_ids"]["isrc"].str(),
                )
            },
            albums = results["albums"]["items"].arr().orEmpty().mapNotNull { album ->
                SessionAlbum(
                    id = album["id"].str() ?: return@mapNotNull null,
                    title = album["name"].str() ?: "Unknown",
                    artist = album["artists"].arr()?.firstOrNull()["name"].str(),
                    artworkUrl = album["images"].arr()?.firstOrNull()["url"].str(),
                )
            },
        )
    }

    override suspend fun streamUrl(trackId: String): StreamResult? {
        // Spotify doesn't allow direct streaming; use Connect instead
        return null
    }

    override suspend fun downloadRef(trackId: String): DownloadRef {
        val track = api.call(HttpMethod.Get, "/tracks/$trackId")
        return DownloadRef(
            session = id,
            trackId = trackId,
            label = "${track["artists"].arr()?.firstOrNull()["name"].str() ?: "Unknown"} - ${track["name"].str() ?: "Unknown"}",
            artist = track["artists"].arr()?.firstOrNull()["name"].str(),
            album = track["album"]["name"].str(),
            isrc = track["external_ids"]["isrc"].str(),
        )
    }

    private fun parseSpotifyDate(date: String): Long {
        // Spotify uses ISO 8601: "2024-01-15T12:30:00Z"
        return runCatching {
            java.time.Instant.parse(date).toEpochMilli()
        }.getOrElse { 0L }
    }
}

// Extension helpers for JSON navigation (matching the pattern in SpotifyCatalog.kt)
private fun JsonElement?.str(): String? = (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }
private fun JsonElement?.int(): Int? = (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
private fun JsonElement?.long(): Long? = (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
private fun JsonElement?.arr(): List<JsonElement>? = (this as? kotlinx.serialization.json.JsonArray)?.toList()
private operator fun JsonElement?.get(key: String): JsonElement? = (this as? kotlinx.serialization.json.JsonObject)?.get(key)
