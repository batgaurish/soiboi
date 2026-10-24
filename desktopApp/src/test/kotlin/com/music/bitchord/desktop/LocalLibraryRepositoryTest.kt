package com.music.bitchord.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
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
            val repository = LocalLibraryRepository(listOf(root))

            val first = repository.refresh()
            val second = repository.refresh()

            assertEquals(2, first.size)
            assertEquals(first.map { it.localUri }.toSet(), second.map { it.localUri }.toSet())
            assertEquals(listOf("Blue"), repository.search("blue").map { it.title })
            assertEquals("Unknown Artist", repository.search("Blue").single().artist)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun playlistMembershipAndQueueSnapshotPreserveOrderAndDuplicates() {
        val repository = LocalLibraryRepository(emptyList())
        val track = LocalLibraryTrack("file:///a.mp3", "A", "Artist", null, null)
        repository.putPlaylist("mix", listOf(track, track))
        repository.setQueue(listOf(track, track))

        assertEquals(2, repository.playlist("mix")?.size)
        assertEquals(2, repository.queueSnapshot().size)
        assertTrue(repository.search("missing").isEmpty())
    }
}
