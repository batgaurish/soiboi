package com.music.bitchord.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalLibraryRepositoryTest {
    @Test
    fun scansFixtureAudioFilesAndSearchesMetadataWithoutDuplicatePaths() {
        val root = Files.createTempDirectory("bitchord-library")
        try {
            Files.write(root.resolve("Blue.mp3"), byteArrayOf(1))
            Files.write(root.resolve("Ignore.txt"), byteArrayOf(1))
            Files.createDirectories(root.resolve("nested"))
            Files.write(root.resolve("nested/Red.flac"), byteArrayOf(1))
            val repository = LocalLibraryRepository(listOf(root, root))

            val first = repository.refresh()
            val second = repository.refresh()

            assertEquals(2, first.size)
            assertEquals(first.map { it.localUri }.toSet(), second.map { it.localUri }.toSet())
            assertEquals(listOf("Blue"), repository.search("blue").map { it.title })
            assertEquals("Unknown Artist", repository.search("Blue").single().artist)
            assertEquals(first, repository.watch())
            assertTrue(repository.search("missing").isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun localTrackConvertsToExistingBitChordSongForQueuePlayback() {
        val track = LocalLibraryTrack("file:///music/Blue%20Sky.mp3", "Blue Sky", "Artist", "Album", "MP3")
        val song = track.toSong()

        assertEquals(track.localUri, song.localUri)
        assertEquals("Blue Sky", song.title)
        assertEquals("Artist", song.artist)
        assertEquals("Album", song.albumName)
        assertEquals("/music/Blue Sky.mp3", song.localPath)
        assertEquals(track.localUri, song.videoId)
    }

    @Test
    fun playlistAndQueueSnapshotsAreDefensiveAndKeepRepeatedQueueEntries() {
        val repository = LocalLibraryRepository(emptyList())
        val track = LocalLibraryTrack("file:///a.mp3", "A", "Artist", null, null)
        val input = mutableListOf(track, track)
        repository.putPlaylist("mix", input)
        repository.setQueue(input)
        input.clear()

        val playlist = assertNotNull(repository.playlist("mix"))
        val queue = repository.queueSnapshot()
        assertEquals(2, playlist.size)
        assertEquals(2, queue.size)
        assertTrue(repository.search("missing").isEmpty())
    }
}
