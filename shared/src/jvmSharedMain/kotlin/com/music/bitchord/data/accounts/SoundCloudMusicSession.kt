package com.music.bitchord.data.accounts

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SoundCloud account session contract.
 *
 * Blocker: SoundCloud's public API does not provide authenticated library sync
 * endpoints for personal accounts without proprietary client keys or web scrapers.
 * Rather than fabricating fictitious endpoints or insecure workarounds, this session
 * surfaces the verified contract and indicates the capability is unavailable.
 */
class SoundCloudMusicSession : MusicSession {

    override val id = "soundcloud"
    override val label = "SoundCloud"
    override val canSync = false

    private val _state = MutableStateFlow<SessionState>(
        SessionState.Error("SoundCloud personal library sync is unavailable: public API lacks authenticated playlist sync endpoints."),
    )
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    override suspend fun refresh() {
        // No-op: API contract unavailable
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> = emptyList()

    override suspend fun playlistTracks(playlistId: String): List<SessionTrack> = emptyList()

    override suspend fun resolveDownload(track: SessionTrack): Song? = null
}
