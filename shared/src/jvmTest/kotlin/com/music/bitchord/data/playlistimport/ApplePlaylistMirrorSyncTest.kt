package com.music.bitchord.data.playlistimport

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplePlaylistMirrorSyncTest {
    @Test
    fun applePlaylistMirrorReviewShowsOnlyDistinctUnmappedEntriesInSourceOrder() {
        val mirror = ApplePlaylistMirror(
            sourceUrl = "https://music.apple.com/us/playlist/imported/pl.123",
            targetId = "VLtarget",
            targetIsLocal = false,
            title = "Apple playlist",
            tracks = listOf(ApplePlaylistMirrorEntry("id:mapped", "yt-mapped", "Mapped", "Artist")),
        )
        val entries = listOf(
            PlaylistEntry("Mapped", "Artist", sourceIndex = 0, sourceId = "mapped"),
            PlaylistEntry("Unmatched", "Artist", sourceIndex = 1, sourceId = "pending"),
            PlaylistEntry("Duplicate source row", "Artist", sourceIndex = 2, sourceId = "pending"),
        )

        val unmatched = applePlaylistMirrorUnmatchedTracks(mirror, entries)

        assertEquals(1, unmatched.size)
        assertEquals("id:pending", unmatched.single().sourceKey)
        assertEquals("Unmatched", unmatched.single().title)
        assertEquals(1, unmatched.single().sourceIndex)
    }

    @Test
    fun applePlaylistMirrorManualResolutionReplacesOnlySelectedSourceKeys() {
        val mirror = ApplePlaylistMirror(
            sourceUrl = "https://music.apple.com/us/playlist/imported/pl.123",
            targetId = "VLtarget",
            targetIsLocal = false,
            title = "Apple playlist",
            tracks = listOf(
                ApplePlaylistMirrorEntry("id:keep", "yt-keep", "Keep", "Artist"),
                ApplePlaylistMirrorEntry("id:replace", "yt-old", "Old match", "Artist"),
            ),
        )
        val selected = Song(
            videoId = "yt-new",
            title = "Chosen YouTube match",
            artist = "Matched Artist",
            thumbnailUrl = "https://img.example/cover.jpg",
            fromAutoplay = false,
        )

        val updated = applePlaylistMirrorWithManualMatches(
            mirror,
            mapOf("id:replace" to selected, "id:pending" to selected.copy(videoId = "yt-pending")),
        )

        assertEquals(listOf("id:keep", "id:replace", "id:pending"), updated.tracks.map { it.sourceKey })
        assertEquals("yt-keep", updated.tracks[0].videoId)
        assertEquals("yt-new", updated.tracks[1].videoId)
        assertEquals("Chosen YouTube match", updated.tracks[1].title)
        assertEquals("yt-pending", updated.tracks[2].videoId)
    }

    @Test
    fun appleMirrorFallbackMatcherAcceptsPackagingDifferenceButRequiresSameArtist() {
        val candidate = Song(videoId = "yt-song", title = "A Song", artist = "Artist", thumbnailUrl = null, fromAutoplay = false)
        val row = ImportRowState(
            entry = PlaylistEntry("A Song (feat. Guest)", "Artist, Guest", sourceIndex = 0, sourceId = "new-id"),
            matchState = ImportMatchState.Unmatched,
            availableCandidates = listOf(candidate),
        )

        val matched = resolveAppleMirrorFallbackMatches(listOf(row)).single()

        assertTrue(matched.isPlayable)
        assertEquals("yt-song", matched.candidateSong?.videoId)

        val unrelated = row.copy(entry = row.entry.copy(artist = "Completely Different"))
        val unmatched = resolveAppleMirrorFallbackMatches(listOf(unrelated)).single()
        assertEquals(false, unmatched.isPlayable)
    }

    @Test
    fun mirrorSyncScheduleUsesItsOwnOptInAndConfiguredInterval() {
        assertEquals(null, applePlaylistMirrorSyncIntervalMs(enabled = false, minutes = 30))
        assertEquals(30L * 60_000L, applePlaylistMirrorSyncIntervalMs(enabled = true, minutes = 30))
        assertEquals(5L * 60_000L, applePlaylistMirrorSyncIntervalMs(enabled = true, minutes = 1))
    }

    @Test
    fun mirrorSyncEditsAreSplitIntoProviderSizedBatchesWithoutReordering() {
        val ids = (0 until 174).toList()
        val batches = applePlaylistSyncBatches(ids)
        assertEquals(listOf(50, 50, 50, 24), batches.map { it.size })
        assertEquals(ids, batches.flatten())
    }

    @Test
    fun failedPlaylistAdditionBatchStopsBeforeLaterBatches() = runBlocking {
        val calls = mutableListOf<List<Int>>()
        val result = applyApplePlaylistSyncBatches((0 until 120).toList()) { batch ->
            calls += batch
            if (calls.size == 2) Result.failure(IllegalStateException("provider rejected batch"))
            else Result.success(Unit)
        }

        assertTrue(result.isFailure)
        assertEquals(listOf(50, 50), calls.map { it.size })
        assertEquals((0 until 100).toList(), calls.flatten())
    }

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
                Result.success(setOf("yt-d"))
            },
            clock = { 1234L },
        )

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message.orEmpty())
        val refreshed = result.getOrThrow()
        assertEquals("New title", refreshed.title)
        assertEquals(1234L, refreshed.lastSyncedMs)
        assertEquals(listOf("id:c", "id:a"), refreshed.tracks.map { it.sourceKey })
        assertEquals(listOf("yt-c", "yt-a"), refreshed.tracks.map { it.videoId })
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
                Result.success(emptySet())
            },
        )

        assertTrue(result.isFailure)
        assertEquals(false, targetWasTouched)
        assertEquals("Existing", mirror.title)
    }
}
