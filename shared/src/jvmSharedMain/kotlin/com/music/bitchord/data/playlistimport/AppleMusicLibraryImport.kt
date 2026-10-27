package com.music.bitchord.data.playlistimport

import com.music.bitchord.data.accounts.AppleMusicSession
import com.music.bitchord.data.accounts.SessionPlaylist

/** Imports playlists already in the signed-in Apple account; no share URL is needed. */
object AppleMusicLibraryImport {
    private const val SOURCE_PREFIX = "apple-library://playlist/"

    suspend fun load(session: AppleMusicSession): List<ImportedPlaylist> =
        session.libraryPlaylists().map { playlist -> load(session, playlist) }

    suspend fun load(session: AppleMusicSession, playlist: SessionPlaylist): ImportedPlaylist {
        val entries = session.playlistTracks(playlist.id).mapIndexed { index, track ->
            PlaylistEntry(
                title = track.title,
                artist = track.artist.orEmpty(),
                isrc = track.isrc,
                sourceIndex = index,
                sourceId = track.id,
            )
        }
        return ImportedPlaylist(
            title = playlist.name,
            provider = PlaylistImportProvider.APPLE_MUSIC,
            entries = entries,
            sourceUrl = SOURCE_PREFIX + playlist.id,
        )
    }

    fun playlistId(source: ImportedPlaylist): String? = playlistId(source.sourceUrl)

    fun playlistId(sourceUrl: String?): String? =
        sourceUrl?.takeIf { it.startsWith(SOURCE_PREFIX) }
            ?.removePrefix(SOURCE_PREFIX)
            ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,200}")) }
}