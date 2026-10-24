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
        val title = path.name.substringBeforeLast('.')
        return LocalLibraryTrack(path.toAbsolutePath().normalize().toUri().toString(), title, "Unknown Artist", null,
            path.name.substringAfterLast('.', "").uppercase().takeIf(String::isNotBlank))
    }
    companion object { private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "dsf", "dff") }
}
