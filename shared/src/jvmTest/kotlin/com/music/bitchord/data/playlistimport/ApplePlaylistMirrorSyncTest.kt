package com.music.bitchord.data.playlistimport

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplePlaylistMirrorSyncTest {
    @Test
    fun refreshMirrorsSourceTitleRemovalsReorderingAndNewTracks() = runBlocking {
        val sourceUrl = "https://music.apple.com/us/playlist/imported/pl.123"
        val mirror = ApplePlaylistMirror(
            sourceUrl = sourceUrl,
            targetId = "VLtarget",
            targetIsLocal = false,
            title = "Old title",
            tracks = listOf(
                ApplePlaylistMirrorEntry("id:a", "yt-a", "Track A", "Artist"),
                ApplePlaylistMirrorEntry("id:b", "yt-b", "Track B", "Artist"),
                ApplePlaylistMirrorEntry("id:c", "yt-c", "Track C", "Artist"),
            ),
        )
        val updatedSource = ImportedPlaylist(
            title = "New title",
            provider = PlaylistImportProvider.APPLE_MUSIC,
            entries = listOf(
                PlaylistEntry("Track C", "Artist", sourceIndex = 0, sourceId = "c"),
                PlaylistEntry("Track A", "Artist", sourceIndex = 1, sourceId = "a"),
                PlaylistEntry("Track D", "Artist", sourceIndex = 2, sourceId = "d"),
            ),
            sourceUrl = sourceUrl,
        )
        var appliedTitle: String? = null
        var appliedSongs: List<Song> = emptyList()

        val result = ApplePlaylistMirrorSync.refresh(
            mirror = mirror,
            loadSource = { url ->
                assertEquals(sourceUrl, url)
                updatedSource
            },
            resolveNewEntries = { playlist ->
                assertEquals(listOf("d"), playlist.entries.map { it.sourceId })
                val entry = playlist.entries.single()
                listOf(
                    ImportRowState(
                        entry = entry,
                        matchState = ImportMatchState.ManualConfirmed(
                            ImportCandidate("yt-d", entry.title, entry.artist),
                        ),
                        candidateSong = Song(videoId = "yt-d", title = "Track D", artist = "Artist", thumbnailUrl = null),
                    ),
                )
            },
            applyChanges = { target, title, songs ->
                assertEquals("VLtarget", target.targetId)
                appliedTitle = title
                appliedSongs = songs
                Result.success(Unit)
            },
            clock = { 1234L },
        )

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
        val refreshed = result.getOrThrow()
        assertEquals("New title", refreshed.title)
        assertEquals(1234L, refreshed.lastSyncedMs)
        assertEquals(listOf("id:c", "id:a", "id:d"), refreshed.tracks.map { it.sourceKey })
        assertEquals(listOf("yt-c", "yt-a", "yt-d"), refreshed.tracks.map { it.videoId })
        assertEquals("New title", appliedTitle)
        assertEquals(listOf("yt-c", "yt-a", "yt-d"), appliedSongs.map { it.videoId })
    }

    @Test
    fun unresolvedReplacementTracksDoNotClearAnExistingTarget() = runBlocking {
        val mirror = ApplePlaylistMirror(
            sourceUrl = "https://music.apple.com/us/playlist/imported/pl.123",
            targetId = "VLtarget",
            targetIsLocal = false,
            title = "Existing",
            tracks = listOf(ApplePlaylistMirrorEntry("id:old", "yt-old", "Old", "Artist")),
        )
        var targetWasTouched = false
        val result = ApplePlaylistMirrorSync.refresh(
            mirror = mirror,
            loadSource = {
                ImportedPlaylist(
                    title = "Changed",
                    provider = PlaylistImportProvider.APPLE_MUSIC,
                    entries = listOf(PlaylistEntry("New track", "Artist", sourceIndex = 0, sourceId = "new")),
                )
            },
            resolveNewEntries = { playlist ->
                listOf(ImportRowState(playlist.entries.single(), ImportMatchState.Unmatched))
            },
            applyChanges = { _, _, _ ->
                targetWasTouched = true
                Result.success(Unit)
            },
        )

        assertTrue(result.isFailure)
        assertEquals(false, targetWasTouched)
        assertEquals("Existing", mirror.title)
    }
}
