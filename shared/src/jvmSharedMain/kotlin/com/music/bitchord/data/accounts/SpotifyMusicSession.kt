package com.music.bitchord.data.accounts

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyImporter
import com.music.bitchord.data.spotify.SpotifyLibrary
import com.music.bitchord.data.spotify.SpotifyTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Spotify account session backed by [SpotifyLibrary] and [SpotifyImporter].
 * Reads user library playlists (including Liked Songs) and matches tracks
 * to playable and downloadable BitChord songs via ISRC and metadata resolution.
 */
class SpotifyMusicSession(
    private val isConnected: () -> Boolean,
    private val spotifyLibrary: SpotifyLibrary = SpotifyLibrary,
    private val matchTrack: suspend (SpotifyTrack) -> Song? = { SpotifyImporter.matchTrack(it) },
) : MusicSession {

    override val id = "spotify"
    override val label = "Spotify"

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        updateState()
    }

    private fun updateState() {
        if (isConnected()) {
            _state.value = SessionState.SignedIn("Spotify", "US")
        } else {
            _state.value = SessionState.SignedOut
        }
    }

    override suspend fun refresh() {
        updateState()
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> {
        if (!isConnected()) return emptyList()
        return runCatching {
            spotifyLibrary.playlists().map { playlist ->
                SessionPlaylist(
                    id = playlist.id,
                    name = playlist.name,
                    artworkUrl = playlist.imageUrl,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlistTracks(playlistId: String): List<SessionTrack> {
        if (!isConnected()) return emptyList()
        return runCatching {
            spotifyLibrary.tracks(playlistId).map { track ->
                SessionTrack(
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs.toLong(),
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun resolveDownload(track: SessionTrack): Song? {
        val spotifyTrack = SpotifyTrack(
            id = track.id,
            title = track.title,
            artist = track.artist.orEmpty(),
            album = track.album,
            durationMs = track.durationMs?.toInt() ?: 0,
            imageUrl = null,
        )
        return runCatching { matchTrack(spotifyTrack) }.getOrNull()
    }
}
