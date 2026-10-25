package com.music.bitchord.data.accounts

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * A signed-in music service account session (YouTube Music, Spotify, Apple Music, etc.).
 * Provides discovery of user playlists and track resolution for background account sync.
 */
interface MusicSession {
    val id: String
    val label: String
    val state: StateFlow<SessionState>
    val canSync: Boolean get() = true

    /** Check if signed in and update state. */
    suspend fun refresh()

    /** User's library playlists on this service. */
    suspend fun libraryPlaylists(): List<SessionPlaylist>

    /** Tracks in a specific playlist. */
    suspend fun playlistTracks(playlistId: String): List<SessionTrack>

    /**
     * Resolve a session track to a downloadable BitChord [Song] model.
     * Returns null if resolution fails or is unsupported.
     */
    suspend fun resolveDownload(track: SessionTrack): Song?
}

sealed interface SessionState {
    data object SignedOut : SessionState
    data class SignedIn(val account: String, val market: String? = null) : SessionState
    data class Error(val message: String) : SessionState
}

@Serializable
data class SessionPlaylist(
    val id: String,
    val name: String,
    val trackCount: Int? = null,
    val artworkUrl: String? = null,
)

@Serializable
data class SessionTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val isrc: String? = null,
    val durationMs: Long? = null,
)

@Serializable
data class SyncCursor(
    val playlistId: String,
    val lastSyncMs: Long,
    val knownTrackIds: Set<String>,
)

@Serializable
data class AccountSyncStatus(
    val sessionId: String,
    val isSyncing: Boolean = false,
    val lastSyncMs: Long = 0L,
    val syncedPlaylists: Int = 0,
    val newTracksFound: Int = 0,
    val error: String? = null,
)

data class AccountSyncReport(
    val timestampMs: Long,
    val sessionStatuses: Map<String, AccountSyncStatus>,
    val totalNewTracks: Int,
    val totalQueuedTracks: Int,
)

fun normalizedTrackKey(artist: String?, title: String?): String {
    val cleanArtist = artist.orEmpty().trim().lowercase().replace(Regex("[^a-z0-9]"), "")
    val cleanTitle = title.orEmpty().trim().lowercase()
        .replace(Regex("""\s*[\(\[](feat|ft|with|remix|official|video|audio).*?[\)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^a-z0-9]"), "")
    return "$cleanArtist|$cleanTitle"
}
