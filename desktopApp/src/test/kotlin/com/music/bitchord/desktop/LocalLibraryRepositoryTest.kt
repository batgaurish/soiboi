package com.music.bitchord.desktop

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        val track = LocalLibraryTrack("file:///music/Blue%20Sky.mp3", "Blue Sky", "Artist", "Album", "MP3", trackNumber = 3)
        val song = track.toSong()

        assertEquals(track.localUri, song.localUri)
        assertEquals("Blue Sky", song.title)
        assertEquals("Artist", song.artist)
        assertEquals("Album", song.albumName)
        assertEquals("/music/Blue Sky.mp3", song.localPath)
        assertEquals(track.localUri, song.videoId)
        assertEquals(3, song.trackNumber)
    }

    @Test
    fun readsEmbeddedFlacVorbisCommentMetadataAndPreservesTrackNumberIntoSong() {
        val root = Files.createTempDirectory("bitchord-flac-metadata")
        try {
            val paddingBlock = byteArrayOf(0x01, 0x00, 0x00, 0x08) + ByteArray(8)
            val flacBytes = realisticFlacFixture(
                comments = listOf(
                    "TITLE" to "Electric Feel",
                    "ARTIST" to "MGMT",
                    "ALBUM" to "Oracular Spectacular",
                    "TRACKNUMBER" to "4"
                ),
                extraBlocksBefore = listOf(paddingBlock)
            )
            Files.write(root.resolve("04 - Electric Feel.flac"), flacBytes)

            val tracks = LocalLibraryRepository(listOf(root)).refresh()
            assertEquals(1, tracks.size)

            val track = tracks.single()
            assertEquals("Electric Feel", track.title)
            assertEquals("MGMT", track.artist)
            assertEquals("Oracular Spectacular", track.album)
            assertEquals(4, track.trackNumber)
            assertEquals("FLAC", track.format)
            assertTrue(track.localUri.startsWith("file:/"))

            val song = track.toSong()
            assertEquals(4, song.trackNumber)
            assertEquals("Electric Feel", song.title)
            assertEquals("MGMT", song.artist)
            assertEquals("Oracular Spectacular", song.albumName)
            assertEquals(track.localUri, song.videoId)
            assertEquals(track.localUri, song.localUri)
            assertTrue(song.localPath?.endsWith("04 - Electric Feel.flac") == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun readsFlacMetadataWithCaseInsensitiveKeysAndFractionalTrackNumber() {
        val root = Files.createTempDirectory("bitchord-flac-case-track")
        try {
            val track1Bytes = realisticFlacFixture(
                comments = listOf(
                    "title" to "Kids",
                    "Artist" to "MGMT",
                    "album" to "Oracular Spectacular",
                    "tracknumber" to " 5 / 10 "
                )
            )
            val track2Bytes = realisticFlacFixture(
                comments = listOf(
                    "Title" to "Time to Pretend",
                    "ARTIST" to "MGMT",
                    "Album" to "Oracular Spectacular",
                    "TRACK" to "01"
                )
            )
            Files.write(root.resolve("kids.flac"), track1Bytes)
            Files.write(root.resolve("pretend.flac"), track2Bytes)

            val tracks = LocalLibraryRepository(listOf(root)).refresh().associateBy { it.title }

            val kids = tracks.getValue("Kids")
            assertEquals("MGMT", kids.artist)
            assertEquals("Oracular Spectacular", kids.album)
            assertEquals(5, kids.trackNumber)
            assertEquals("FLAC", kids.format)

            val pretend = tracks.getValue("Time to Pretend")
            assertEquals("MGMT", pretend.artist)
            assertEquals("Oracular Spectacular", pretend.album)
            assertEquals(1, pretend.trackNumber)
            assertEquals("FLAC", pretend.format)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fallsBackToFilenameAndDefaultsWhenFlacMetadataIsMalformedOrTruncated() {
        val root = Files.createTempDirectory("bitchord-flac-malformed")
        try {
            // Invalid stream marker (not fLaC)
            Files.write(root.resolve("InvalidMarker.flac"), "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(20))
            // Truncated marker (< 4 bytes)
            Files.write(root.resolve("TruncatedMarker.flac"), byteArrayOf('f'.code.toByte(), 'L'.code.toByte()))
            // Truncated block header after marker
            Files.write(root.resolve("TruncatedHeader.flac"), "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00, 0x00))
            // Invalid block type (127)
            Files.write(root.resolve("InvalidBlockType.flac"), "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x7F, 0x00, 0x00, 0x04, 1, 2, 3, 4))
            // Block length claims 10000 bytes, file ends immediately
            Files.write(root.resolve("TruncatedBlockBody.flac"), "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x84.toByte(), 0x00, 0x27, 0x10.toByte()))
            // Vorbis comment body with vendor length claiming 500 bytes but only 4 bytes available
            val truncatedVendorBody = ByteArrayOutputStream().apply {
                writeUint32Le(this, 500)
                write(byteArrayOf(1, 2, 3, 4))
            }.toByteArray()
            val truncatedVendorHeader = byteArrayOf(
                0x84.toByte(),
                ((truncatedVendorBody.size shr 16) and 0xFF).toByte(),
                ((truncatedVendorBody.size shr 8) and 0xFF).toByte(),
                (truncatedVendorBody.size and 0xFF).toByte()
            )
            Files.write(
                root.resolve("TruncatedVendor.flac"),
                "fLaC".toByteArray(Charsets.US_ASCII) + truncatedVendorHeader + truncatedVendorBody
            )
            // Vorbis comment with comment item length truncated
            val truncatedCommentBody = ByteArrayOutputStream().apply {
                val vendor = "vendor".toByteArray(Charsets.UTF_8)
                writeUint32Le(this, vendor.size)
                write(vendor)
                writeUint32Le(this, 1) // 1 comment
                writeUint32Le(this, 1000) // comment claims 1000 bytes
                write("TITLE=Trunc".toByteArray(Charsets.UTF_8)) // only 11 bytes provided
            }.toByteArray()
            val truncatedCommentHeader = byteArrayOf(
                0x84.toByte(),
                ((truncatedCommentBody.size shr 16) and 0xFF).toByte(),
                ((truncatedCommentBody.size shr 8) and 0xFF).toByte(),
                (truncatedCommentBody.size and 0xFF).toByte()
            )
            Files.write(
                root.resolve("TruncatedComment.flac"),
                "fLaC".toByteArray(Charsets.US_ASCII) + truncatedCommentHeader + truncatedCommentBody
            )
            // Comment without equals separator
            val marker = "fLaC".toByteArray(Charsets.US_ASCII)
            val streamInfo = ByteArray(34)
            val streamInfoHeader = byteArrayOf(0x00, 0x00, 0x00, 0x22)
            val vc = ByteArrayOutputStream().apply {
                val vendor = "test".toByteArray(Charsets.UTF_8)
                writeUint32Le(this, vendor.size)
                write(vendor)
                writeUint32Le(this, 1)
                val badComment = "NO_SEPARATOR_HERE".toByteArray(Charsets.UTF_8)
                writeUint32Le(this, badComment.size)
                write(badComment)
            }.toByteArray()
            val vcHeader = byteArrayOf(0x84.toByte(), 0x00, ((vc.size shr 8) and 0xFF).toByte(), (vc.size and 0xFF).toByte())
            Files.write(root.resolve("NoEquals.flac"), marker + streamInfoHeader + streamInfo + vcHeader + vc)

            // Non-numeric track number and zero track number
            Files.write(root.resolve("NonNumericTrack.flac"), realisticFlacFixture(listOf("TITLE" to "Valid Title", "TRACKNUMBER" to "NaN")))
            Files.write(root.resolve("ZeroTrack.flac"), realisticFlacFixture(listOf("TITLE" to "Valid Title 2", "TRACKNUMBER" to "0")))
            // Untagged FLAC (only STREAMINFO)
            val untaggedFlac = "fLaC".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22) + ByteArray(34)
            Files.write(root.resolve("Untagged.flac"), untaggedFlac)

            val tracks = LocalLibraryRepository(listOf(root)).refresh().associateBy { it.title }

            listOf(
                "InvalidMarker", "TruncatedMarker", "TruncatedHeader",
                "InvalidBlockType", "TruncatedBlockBody", "TruncatedVendor",
                "TruncatedComment", "NoEquals", "Untagged"
            ).forEach { expectedTitle ->
                val track = tracks.getValue(expectedTitle)
                assertEquals("Unknown Artist", track.artist, "Expected Unknown Artist for $expectedTitle")
                assertNull(track.album, "Expected null album for $expectedTitle")
                assertNull(track.trackNumber, "Expected null trackNumber for $expectedTitle")
                assertEquals("FLAC", track.format)
            }

            val nonNumeric = tracks.getValue("Valid Title")
            assertNull(nonNumeric.trackNumber)
            val zeroTrack = tracks.getValue("Valid Title 2")
            assertNull(zeroTrack.trackNumber)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun realisticFlacFixture(
        comments: List<Pair<String, String>>,
        isLast: Boolean = true,
        vendor: String = "reference libFLAC 1.4.3",
        extraBlocksBefore: List<ByteArray> = emptyList(),
    ): ByteArray {
        val streamMarker = "fLaC".toByteArray(Charsets.US_ASCII)

        val streamInfoBody = ByteArray(34).apply {
            this[0] = 0x10
            this[1] = 0x00
            this[2] = 0x10
            this[3] = 0x00
            this[10] = 0x0A
            this[11] = 0xC4.toByte()
            this[12] = 0x42
            this[13] = 0xF0.toByte()
            this[16] = 0x03
            this[17] = 0xE8.toByte()
        }
        val streamInfoHeader = byteArrayOf(0x00, 0x00, 0x00, streamInfoBody.size.toByte())

        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        val vcBody = ByteArrayOutputStream()
        writeUint32Le(vcBody, vendorBytes.size)
        vcBody.write(vendorBytes)
        writeUint32Le(vcBody, comments.size)
        for ((key, value) in comments) {
            val commentBytes = "$key=$value".toByteArray(Charsets.UTF_8)
            writeUint32Le(vcBody, commentBytes.size)
            vcBody.write(commentBytes)
        }
        val vcBodyBytes = vcBody.toByteArray()

        val vcHeaderByte0 = if (isLast) 0x84.toByte() else 0x04.toByte()
        val vcHeader = byteArrayOf(
            vcHeaderByte0,
            ((vcBodyBytes.size shr 16) and 0xFF).toByte(),
            ((vcBodyBytes.size shr 8) and 0xFF).toByte(),
            (vcBodyBytes.size and 0xFF).toByte()
        )

        val out = ByteArrayOutputStream()
        out.write(streamMarker)
        out.write(streamInfoHeader)
        out.write(streamInfoBody)
        for (extra in extraBlocksBefore) {
            out.write(extra)
        }
        out.write(vcHeader)
        out.write(vcBodyBytes)
        return out.toByteArray()
    }

    private fun writeUint32Le(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
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
