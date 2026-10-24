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
    fun readsEmbeddedId3TitleArtistAndAlbumWithFilenameFallbackForUntaggedMp3() {
        val root = Files.createTempDirectory("bitchord-id3")
        try {
            Files.write(root.resolve("ignored-name.mp3"), id3Fixture("Real Title", "Real Artist", "Real Album"))
            Files.write(root.resolve("Fallback.mp3"), byteArrayOf(1))
            val tracks = LocalLibraryRepository(listOf(root)).refresh().associateBy { it.title }

            assertEquals("Real Artist", tracks.getValue("Real Title").artist)
            assertEquals("Real Album", tracks.getValue("Real Title").album)
            assertEquals("Fallback", tracks.getValue("Fallback").title)
            assertEquals("Unknown Artist", tracks.getValue("Fallback").artist)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun readsEmbeddedId3v22Andv24TagsWithRobustFrameSizeHandling() {
        val root = Files.createTempDirectory("bitchord-id3-versions")
        try {
            Files.write(root.resolve("v22.mp3"), id3v22Fixture("V22 Title", "V22 Artist", "V22 Album"))
            Files.write(root.resolve("v24-syncsafe.mp3"), id3v24SyncSafeFixture("V24 Title", "V24 Artist", "V24 Album"))
            val longTitle = "A".repeat(150)
            Files.write(root.resolve("v24-standard.mp3"), id3v24StandardSizeFixture(longTitle, "V24 Standard Artist", "V24 Standard Album"))

            val tracks = LocalLibraryRepository(listOf(root)).refresh().associateBy { it.title }

            val v22Track = tracks.getValue("V22 Title")
            assertEquals("V22 Artist", v22Track.artist)
            assertEquals("V22 Album", v22Track.album)
            assertEquals("MP3", v22Track.format)
            assertTrue(v22Track.localUri.startsWith("file:/"))

            val v24Track = tracks.getValue("V24 Title")
            assertEquals("V24 Artist", v24Track.artist)
            assertEquals("V24 Album", v24Track.album)
            assertEquals("MP3", v24Track.format)
            assertTrue(v24Track.localUri.startsWith("file:/"))

            val v24StandardTrack = tracks.getValue(longTitle)
            assertEquals("V24 Standard Artist", v24StandardTrack.artist)
            assertEquals("V24 Standard Album", v24StandardTrack.album)
            assertEquals("MP3", v24StandardTrack.format)
            assertTrue(v24StandardTrack.localUri.startsWith("file:/"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fallsBackToFilenameAndUnknownArtistWhenTagIsMalformedOrTruncated() {
        val root = Files.createTempDirectory("bitchord-id3-malformed")
        try {
            // Truncated header (< 10 bytes)
            Files.write(root.resolve("TruncatedHeader.mp3"), byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()))
            // Header says 1000 bytes tag, file truncated after header
            Files.write(root.resolve("TruncatedTagBody.mp3"), byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 7, 0x68.toByte()))
            // Truncated frame inside tag
            val incompleteFrameTag = byteArrayOf(
                73, 68, 51, 3, 0, 0, 0, 0, 0, 20, // 20 bytes tag
                'T'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte(), '2'.code.toByte(),
                0, 0, 0, 50, 0, 0, 0 // claims 50 bytes frame, but tag ends here
            )
            Files.write(root.resolve("TruncatedFrame.mp3"), incompleteFrameTag)
            // Invalid syncsafe tag size in header (high bit set)
            val invalidSyncSafeHeader = byteArrayOf(73, 68, 51, 4, 0, 0, 0x80.toByte(), 0, 0, 10, 0, 1, 2, 3)
            Files.write(root.resolve("InvalidHeaderSize.mp3"), invalidSyncSafeHeader)

            val tracks = LocalLibraryRepository(listOf(root)).refresh().associateBy { it.title }

            assertEquals(4, tracks.size)
            listOf("TruncatedHeader", "TruncatedTagBody", "TruncatedFrame", "InvalidHeaderSize").forEach { expectedTitle ->
                val track = tracks.getValue(expectedTitle)
                assertEquals("Unknown Artist", track.artist)
                assertEquals(null, track.album)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun id3v22Fixture(title: String, artist: String, album: String): ByteArray {
        fun frame(id: String, value: String): ByteArray {
            val data = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
            val size = data.size
            return id.toByteArray(Charsets.US_ASCII) + byteArrayOf(
                ((size shr 16) and 0xff).toByte(),
                ((size shr 8) and 0xff).toByte(),
                (size and 0xff).toByte()
            ) + data
        }
        val emptyLeadingFrame = "COM".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0)
        val body = emptyLeadingFrame + frame("TT2", title) + frame("TP1", artist) + frame("TAL", album)
        val size = body.size
        val syncSafe = byteArrayOf(((size shr 21) and 0x7f).toByte(), ((size shr 14) and 0x7f).toByte(), ((size shr 7) and 0x7f).toByte(), (size and 0x7f).toByte())
        return byteArrayOf(73, 68, 51, 2, 0, 0) + syncSafe + body
    }

    private fun id3v24SyncSafeFixture(title: String, artist: String, album: String): ByteArray {
        fun frame(id: String, value: String): ByteArray {
            val data = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
            val size = data.size
            val sizeBytes = byteArrayOf(
                ((size shr 21) and 0x7f).toByte(),
                ((size shr 14) and 0x7f).toByte(),
                ((size shr 7) and 0x7f).toByte(),
                (size and 0x7f).toByte()
            )
            return id.toByteArray(Charsets.US_ASCII) + sizeBytes + byteArrayOf(0, 0) + data
        }
        val emptyLeadingFrame = "COMM".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 0, 0, 0)
        val body = emptyLeadingFrame + frame("TIT2", title) + frame("TPE1", artist) + frame("TALB", album)
        val size = body.size
        val syncSafe = byteArrayOf(((size shr 21) and 0x7f).toByte(), ((size shr 14) and 0x7f).toByte(), ((size shr 7) and 0x7f).toByte(), (size and 0x7f).toByte())
        return byteArrayOf(73, 68, 51, 4, 0, 0) + syncSafe + body
    }

    private fun id3v24StandardSizeFixture(title: String, artist: String, album: String): ByteArray {
        fun frame(id: String, value: String): ByteArray {
            val data = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
            val size = data.size
            val sizeBytes = byteArrayOf(
                ((size shr 24) and 0xff).toByte(),
                ((size shr 16) and 0xff).toByte(),
                ((size shr 8) and 0xff).toByte(),
                (size and 0xff).toByte()
            )
            return id.toByteArray(Charsets.US_ASCII) + sizeBytes + byteArrayOf(0, 0) + data
        }
        val body = frame("TIT2", title) + frame("TPE1", artist) + frame("TALB", album)
        val size = body.size
        val syncSafe = byteArrayOf(((size shr 21) and 0x7f).toByte(), ((size shr 14) and 0x7f).toByte(), ((size shr 7) and 0x7f).toByte(), (size and 0x7f).toByte())
        return byteArrayOf(73, 68, 51, 4, 0, 0) + syncSafe + body
    }

    private fun id3Fixture(title: String, artist: String, album: String): ByteArray {
        fun frame(id: String, value: String): ByteArray {
            val data = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)
            val size = data.size
            return id.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, size.toByte(), 0, 0) + data
        }
        val body = frame("TIT2", title) + frame("TPE1", artist) + frame("TALB", album)
        val size = body.size
        val syncSafe = byteArrayOf(((size shr 21) and 0x7f).toByte(), ((size shr 14) and 0x7f).toByte(), ((size shr 7) and 0x7f).toByte(), (size and 0x7f).toByte())
        return byteArrayOf(73, 68, 51, 3, 0, 0) + syncSafe + body
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
