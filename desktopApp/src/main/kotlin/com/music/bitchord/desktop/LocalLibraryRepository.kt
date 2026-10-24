package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString

data class LocalLibraryTrack(
    val localUri: String,
    val title: String,
    val artist: String,
    val album: String?,
    val format: String?,
    val trackNumber: Int? = null,
) {
    fun toSong() = Song(
        videoId = localUri,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        albumName = album,
        localUri = localUri,
        localPath = Path.of(java.net.URI(localUri)).pathString,
        trackNumber = trackNumber,
    )
}

/** Desktop filesystem index; queue and playback remain BitChord-owned. */
class LocalLibraryRepository(private val roots: List<Path>) {
    private val indexed = AtomicReference<List<LocalLibraryTrack>>(emptyList())
    private val playlists = ConcurrentHashMap<String, List<LocalLibraryTrack>>()
    private val queue = AtomicReference<List<LocalLibraryTrack>>(emptyList())

    @Synchronized fun refresh(): List<LocalLibraryTrack> {
        val tracks = roots.flatMap { root ->
            if (!Files.exists(root)) return@flatMap emptyList()
            Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() && it.fileName.toString().substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS }
                    .map(::fromPath).toList()
            }
        }.distinctBy { it.localUri }.sortedBy { it.title.lowercase() }
        indexed.set(tracks)
        return tracks
    }

    fun watch(): List<LocalLibraryTrack> = indexed.get()
    fun search(query: String): List<LocalLibraryTrack> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return indexed.get()
        return indexed.get().filter { it.title.lowercase().contains(needle) || it.artist.lowercase().contains(needle) || it.album.orEmpty().lowercase().contains(needle) }
    }
    fun putPlaylist(id: String, tracks: List<LocalLibraryTrack>) { playlists[id] = tracks.toList() }
    fun playlist(id: String): List<LocalLibraryTrack>? = playlists[id]?.toList()
    fun setQueue(tracks: List<LocalLibraryTrack>) { queue.set(tracks.toList()) }
    fun queueSnapshot(): List<LocalLibraryTrack> = queue.get().toList()

    private fun fromPath(path: Path): LocalLibraryTrack {
        val fallbackTitle = path.name.substringBeforeLast('.')
        val ext = path.name.substringAfterLast('.', "")
        val title: String
        val artist: String
        val album: String?
        val trackNumber: Int?

        when {
            ext.equals("mp3", ignoreCase = true) -> {
                val tags = readId3v2(path)
                title = tags["TIT2"]?.takeIf(String::isNotBlank) ?: fallbackTitle
                artist = tags["TPE1"]?.takeIf(String::isNotBlank) ?: "Unknown Artist"
                album = tags["TALB"]?.takeIf(String::isNotBlank)
                trackNumber = null
            }
            ext.equals("flac", ignoreCase = true) -> {
                val tags = readFlacComments(path)
                title = tags["TITLE"]?.takeIf(String::isNotBlank) ?: fallbackTitle
                artist = tags["ARTIST"]?.takeIf(String::isNotBlank) ?: "Unknown Artist"
                album = tags["ALBUM"]?.takeIf(String::isNotBlank)
                trackNumber = parseTrackNumber(tags["TRACKNUMBER"] ?: tags["TRACK"])
            }
            else -> {
                title = fallbackTitle
                artist = "Unknown Artist"
                album = null
                trackNumber = null
            }
        }

        return LocalLibraryTrack(
            localUri = path.toAbsolutePath().normalize().toUri().toString(),
            title = title,
            artist = artist,
            album = album,
            format = ext.uppercase().takeIf(String::isNotBlank),
            trackNumber = trackNumber
        )
    }

    private fun parseTrackNumber(raw: String?): Int? {
        val head = raw?.substringBefore('/')?.trim() ?: return null
        return head.toIntOrNull()?.takeIf { it in 1..999 }
    }

    private fun readFlacComments(path: Path): Map<String, String> = try {
        Files.newInputStream(path).use { input ->
            val marker = ByteArray(4)
            if (input.readNBytes(marker, 0, 4) != 4) return@use emptyMap()
            if (marker[0] != 'f'.code.toByte() || marker[1] != 'L'.code.toByte() ||
                marker[2] != 'a'.code.toByte() || marker[3] != 'C'.code.toByte()) {
                return@use emptyMap()
            }

            var isLast = false
            while (!isLast) {
                val header = ByteArray(4)
                if (input.readNBytes(header, 0, 4) != 4) return@use emptyMap()
                val byte0 = header[0].toInt() and 0xFF
                isLast = (byte0 and 0x80) != 0
                val blockType = byte0 and 0x7F
                if (blockType == 127) return@use emptyMap()

                val blockLength = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                if (blockLength < 0 || blockLength > MAX_FLAC_METADATA_BLOCK_SIZE) return@use emptyMap()

                if (blockType == 4) {
                    val blockData = input.readNBytes(blockLength)
                    if (blockData.size != blockLength) return@use emptyMap()
                    return@use parseVorbisComment(blockData)
                } else {
                    if (!skipFully(input, blockLength.toLong())) return@use emptyMap()
                }
            }
            emptyMap()
        }
    } catch (_: Exception) { emptyMap() }

    private fun skipFully(input: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) return false
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return true
    }

    private fun parseVorbisComment(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 0

        val vendorLength = readUint32Le(bytes, offset)
        if (vendorLength < 0 || vendorLength > (bytes.size - offset - 4)) return emptyMap()
        offset += 4 + vendorLength.toInt()

        val commentCount = readUint32Le(bytes, offset)
        if (commentCount < 0 || commentCount > MAX_VORBIS_COMMENT_COUNT) return emptyMap()
        if (commentCount > (bytes.size - offset - 4) / 4) return emptyMap()
        offset += 4

        for (i in 0 until commentCount.toInt()) {
            val commentLength = readUint32Le(bytes, offset)
            if (commentLength < 0 || commentLength > (bytes.size - offset - 4)) return emptyMap()
            offset += 4

            val commentStr = bytes.copyOfRange(offset, offset + commentLength.toInt()).toString(Charsets.UTF_8)
            offset += commentLength.toInt()

            val eqIndex = commentStr.indexOf('=')
            if (eqIndex > 0) {
                val key = commentStr.substring(0, eqIndex).trim().uppercase(Locale.ROOT)
                val value = commentStr.substring(eqIndex + 1).trim()
                if (value.isNotEmpty() && !result.containsKey(key)) {
                    result[key] = value
                }
            }
        }
        return result
    }

    private fun readUint32Le(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) return -1L
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readId3v2(path: Path): Map<String, String> = try {
        Files.newInputStream(path).use { input ->
            val header = ByteArray(10)
            if (input.read(header) != 10 || header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return@use emptyMap()
            val version = header[3].toInt() and 0xff
            if (version !in 2..4) return@use emptyMap()
            val tagSize = syncSafe(header, 6)
            if (tagSize !in 1..MAX_ID3_TAG_SIZE) return@use emptyMap()
            val tag = input.readNBytes(tagSize)
            val result = mutableMapOf<String, String>()
            var offset = 0
            while (offset + (if (version == 2) 6 else 10) <= tag.size) {
                val frameHeaderSize = if (version == 2) 6 else 10
                val idLength = if (version == 2) 3 else 4
                val id = tag.copyOfRange(offset, offset + idLength).toString(Charsets.ISO_8859_1)
                if (id.all { it == '\u0000' }) break
                val frameSize = if (version == 2) {
                    ((tag[offset + 3].toInt() and 255) shl 16) or ((tag[offset + 4].toInt() and 255) shl 8) or (tag[offset + 5].toInt() and 255)
                } else if (version == 4) {
                    val ss = syncSafe(tag, offset + 4)
                    val raw = ((tag[offset + 4].toInt() and 255) shl 24) or
                        ((tag[offset + 5].toInt() and 255) shl 16) or
                        ((tag[offset + 6].toInt() and 255) shl 8) or
                        (tag[offset + 7].toInt() and 255)
                    if (ss >= 0 && offset + frameHeaderSize + ss <= tag.size &&
                        (raw < 0 || offset + frameHeaderSize + raw > tag.size || isValidNextFrame(tag, offset + frameHeaderSize + ss, version))) {
                        ss
                    } else if (raw in 0..(tag.size - offset - frameHeaderSize)) {
                        raw
                    } else {
                        ss
                    }
                } else {
                    ((tag[offset + 4].toInt() and 255) shl 24) or
                        ((tag[offset + 5].toInt() and 255) shl 16) or
                        ((tag[offset + 6].toInt() and 255) shl 8) or
                        (tag[offset + 7].toInt() and 255)
                }
                if (frameSize < 0 || offset + frameHeaderSize + frameSize > tag.size) break
                if (frameSize == 0) {
                    offset += frameHeaderSize
                    continue
                }
                val normalizedId = when (id) { "TT2" -> "TIT2"; "TP1" -> "TPE1"; "TAL" -> "TALB"; else -> id }
                if (normalizedId in setOf("TIT2", "TPE1", "TALB") && frameSize > 1) {
                    decodeText(tag, offset + frameHeaderSize, frameSize)?.let { if (it.isNotBlank()) result[normalizedId] = it }
                }
                offset += frameHeaderSize + frameSize
            }
            result
        }
    } catch (_: Exception) { emptyMap() }

    private fun isValidNextFrame(bytes: ByteArray, offset: Int, version: Int): Boolean {
        if (offset == bytes.size) return true
        val idLength = if (version == 2) 3 else 4
        if (offset + idLength > bytes.size) return false
        val nextId = bytes.copyOfRange(offset, offset + idLength)
        if (nextId.all { it == 0.toByte() }) return true
        return nextId.all {
            val c = it.toInt().toChar()
            (c in 'A'..'Z') || (c in '0'..'9')
        }
    }

    private fun syncSafe(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return (0..3).fold(0) { value, index -> val byte = bytes[offset + index].toInt() and 255; if (byte > 127) return -1; (value shl 7) or byte }
    }

    private fun decodeText(bytes: ByteArray, offset: Int, size: Int): String? {
        val encoding = bytes[offset].toInt() and 255
        val charset = when (encoding) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> return null }
        return bytes.copyOfRange(offset + 1, offset + size).toString(charset).trimEnd('\u0000').removePrefix("\uFEFF").trim()
    }

    companion object {
        private const val MAX_ID3_TAG_SIZE = 16 * 1024 * 1024
        private const val MAX_FLAC_METADATA_BLOCK_SIZE = 16 * 1024 * 1024
        private const val MAX_VORBIS_COMMENT_COUNT = 10_000
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "dsf", "dff")
    }
}
