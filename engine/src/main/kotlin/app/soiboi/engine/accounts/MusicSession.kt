package app.soiboi.engine.accounts

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * A signed-in music service account. Provides discovery, sync, and streaming
 * from Apple Music, Spotify, YouTube Music, or SoundCloud.
 *
 * Unlike a MusicServer (Subsonic, Plex), a session is not a peer the user owns;
 * it is a commercial service the user signs into with their own account.
 */
interface MusicSession {
    val id: String
    val label: String
    val state: StateFlow<SessionState>

    /** User's library playlists on this service. */
    suspend fun libraryPlaylists(): List<SessionPlaylist>

    /** Tracks in a specific playlist. */
    suspend fun playlistTracks(playlistId: String): List<SessionTrack>

    /** Tracks added to the user's library since [sinceMs]. */
    suspend fun recentlyAdded(sinceMs: Long): List<SessionTrack>

    /** Search the service's catalog. */
    suspend fun search(query: String): SessionSearchResult

    /**
     * Resolve a track to a streamable URL. Returns null if streaming is not
     * supported (e.g. Spotify without Premium, or the service requires download).
     */
    suspend fun streamUrl(trackId: String): StreamResult?

    /**
     * A download reference for a track. Used by the sync system to queue downloads.
     * The ISRC enables cross-service resolution (Spotify track → Apple download).
     */
    suspend fun downloadRef(trackId: String): DownloadRef?

    /** Whether this session supports in-app streaming (vs download-only). */
    val canStream: Boolean get() = true
}

sealed class SessionState {
    data object SignedOut : SessionState()
    data class SignedIn(val account: String, val market: String) : SessionState()
    data class Error(val message: String) : SessionState()
}

@Serializable
data class SessionPlaylist(
    val id: String,
    val name: String,
    val trackCount: Int?,
    val artworkUrl: String? = null,
)

@Serializable
data class SessionTrack(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val isrc: String? = null,
    val durationMs: Long? = null,
    val addedAt: Long? = null,
)

@Serializable
data class SessionAlbum(
    val id: String,
    val title: String,
    val artist: String?,
    val artworkUrl: String? = null,
)

@Serializable
data class SessionSearchResult(
    val tracks: List<SessionTrack> = emptyList(),
    val albums: List<SessionAlbum> = emptyList(),
)

@Serializable
data class StreamResult(
    val url: String,
    val format: String,
    val bitrate: Int,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: Long? = null,
)

@Serializable
data class DownloadRef(
    val session: String,
    val trackId: String,
    val label: String,
    val artist: String?,
    val album: String?,
    val isrc: String?,
)
