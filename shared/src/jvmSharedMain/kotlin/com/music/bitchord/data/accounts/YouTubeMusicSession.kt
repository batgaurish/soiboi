package com.music.bitchord.data.accounts

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * YouTube Music account session backed by [YtMusicRepository].
 * Reads user library playlists and resolves tracks for download directly.
 */
class YouTubeMusicSession(
    private val isSignedIn: () -> Boolean,
    private val accountName: () -> String? = { "YouTube Music" },
    private val repository: YtMusicRepository = YtMusicRepository,
) : MusicSession {

    override val id = "youtube"
    override val label = "YouTube Music"

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        updateState()
    }

    private fun updateState() {
        if (isSignedIn()) {
            _state.value = SessionState.SignedIn(
                account = accountName()?.ifBlank { null } ?: "YouTube Music",
                market = "US",
            )
        } else {
            _state.value = SessionState.SignedOut
        }
    }

    override suspend fun refresh() {
        updateState()
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> {
        if (!isSignedIn()) return emptyList()
        val result = repository.libraryPlaylists().getOrNull().orEmpty()
        return result.mapNotNull { item ->
            val browseId = item.browseId ?: return@mapNotNull null
            SessionPlaylist(
                id = browseId,
                name = item.title,
                artworkUrl = item.thumbnailUrl,
            )
        }
    }

    override suspend fun playlistTracks(playlistId: String): List<SessionTrack> {
        if (!isSignedIn()) return emptyList()
        val result = repository.playlistEntries(playlistId).getOrNull().orEmpty()
        return result.map { song ->
            SessionTrack(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                album = song.albumName,
                durationMs = null,
            )
        }
    }

    override suspend fun resolveDownload(track: SessionTrack): Song? {
        if (track.id.isBlank()) return null
        return Song(
            videoId = track.id,
            title = track.title,
            artist = track.artist.orEmpty(),
            thumbnailUrl = null,
            albumName = track.album,
        )
    }
}
