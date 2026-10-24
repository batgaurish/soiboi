package com.music.bitchord.desktop

import com.music.bitchord.data.model.Song
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.pathString

 data class LocalLibraryTrack(val localUri: String, val title: String, val artist: String, val album: String?, val format: String?) {
    fun toSong() = Song(videoId = localUri, title = title, artist = artist, thumbnailUrl = null,
        albumName = album, localUri = localUri, localPath = Path.of(java.net.URI(localUri)).pathString)
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
        val tags = if (path.name.substringAfterLast('.', "").equals("mp3", ignoreCase = true)) readId3v2(path) else emptyMap()
        return LocalLibraryTrack(path.toAbsolutePath().normalize().toUri().toString(),
            tags["TIT2"]?.takeIf(String::isNotBlank) ?: fallbackTitle,
            tags["TPE1"]?.takeIf(String::isNotBlank) ?: "Unknown Artist",
            tags["TALB"]?.takeIf(String::isNotBlank),
            path.name.substringAfterLast('.', "").uppercase().takeIf(String::isNotBlank))
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
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "dsf", "dff")
    }
}
