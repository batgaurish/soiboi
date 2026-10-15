package app.soiboi.engine.discovery

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.core.ownedSongKey
import app.soiboi.engine.library.Database
import app.soiboi.engine.library.FAVORITE
import app.soiboi.engine.library.Library
import app.soiboi.engine.library.PlaylistStore
import app.soiboi.engine.library.Song
import app.soiboi.engine.sources.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class LinkedTrack(val artist: String, val title: String)

class LinkResult(val playlistName: String, val matched: Int, val total: Int)

/**
 * Local playlists that mirror an imported playlist's tracklist. Importing downloads the songs, but they
 * used to land in the library loose, so the playlist itself was lost. A linked playlist keeps the source
 * tracklist and fills a normal local playlist from whatever of it is in the library; songs still
 * downloading are added when they arrive, because [refreshAll] runs after every library sync.
 */
class LinkedPlaylists(private val db: Database, private val library: Library, private val playlists: PlaylistStore) {
    private val links = HashMap<String, List<LinkedTrack>>()
    private val serializer = MapSerializer(String.serializer(), ListSerializer(LinkedTrack.serializer()))

    suspend fun load() {
        try { db.getKv("linked:playlists")?.let { links.putAll(Http.json.decodeFromString(serializer, it)) } }
        catch (e: Exception) { EngineLog.output("linked playlists: read failed: $e") }
    }

    private suspend fun save() { try { db.putKv("linked:playlists", Http.json.encodeToString(serializer, links)) } catch (e: Exception) { EngineLog.output("linked playlists: write failed: $e") } }

    /** Playlist names double as file names elsewhere, so a slash would point somewhere else. Never the built-in favourites list, which this would overwrite. */
    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\]"), "-").trim()
        if (cleaned.isEmpty()) return "Imported playlist"
        return if (cleaned == FAVORITE) "$FAVORITE (imported)" else cleaned
    }

    /** Creates (or reuses) the local playlist [name] for [tracks] and fills it from the library now. */
    suspend fun link(name: String, tracks: List<LinkedTrack>): LinkResult? {
        if (tracks.isEmpty()) return null
        val playlistName = safeName(name)
        links[playlistName] = tracks.toList(); save()
        if (playlists.byName(playlistName) == null) playlists.create(playlistName)
        val matched = fill(playlistName, tracks, Index())
        return LinkResult(playlistName, matched, tracks.size)
    }

    /**
     * Puts [to] where [from] was in the linked playlist [name]: a song the user matched by hand, which may be a
     * different artist or title (a cover, a remix) and would never match the source's entry. False when [name] is
     * not linked or has no such track.
     */
    suspend fun replaceTrack(name: String, from: LinkedTrack, to: LinkedTrack): Boolean {
        val tracks = links[safeName(name)]?.toMutableList() ?: return false
        val key = ownedSongKey(from.artist, from.title)
        var replaced = false
        for (i in tracks.indices) if (ownedSongKey(tracks[i].artist, tracks[i].title) == key) { tracks[i] = to; replaced = true }
        if (replaced) { links[safeName(name)] = tracks; save() }
        return replaced
    }

    fun tracksOf(name: String): List<LinkedTrack>? = links[safeName(name)]

    /** Adds newly downloaded songs to every linked playlist. Run after a library sync; cheap when nothing changed. */
    suspend fun refreshAll() {
        if (links.isEmpty()) return
        val index = Index()
        for ((name, tracks) in links) {
            // Deleted by the user: respect that rather than bring it back.
            if (playlists.byName(name) == null) continue
            fill(name, tracks, index)
        }
    }

    /** Puts the matched songs in source order, followed by anything the user added by hand. Writes only when the list actually changes. */
    private suspend fun fill(name: String, tracks: List<LinkedTrack>, index: Index): Int {
        val current = playlists.byName(name)?.songIds.orEmpty()
        val ordered = LinkedHashSet<String>()
        for (t in tracks) index.find(t)?.let { ordered += it.id }
        val next = ordered.toList() + current.filter { it !in ordered }
        if (next != current) playlists.setSongs(name, next)
        return ordered.size
    }

    /** Library lookups by artist and title, built once per refresh. */
    private inner class Index {
        private val exact = HashMap<String, Song>(); private val loose = HashMap<String, Song>()
        init {
            for (s in library.songs.value) {
                val title = s.title.orEmpty(); if (title.isEmpty()) continue
                val artist = s.artist.orEmpty()
                exact.putIfAbsent(ownedSongKey(artist, title), s); loose.putIfAbsent(looseKey(artist, title), s)
            }
        }
        fun find(t: LinkedTrack): Song? = exact[ownedSongKey(t.artist, t.title)] ?: loose[looseKey(t.artist, t.title)]

        /** Main artist and bare title. Sources disagree on "(feat. X)", "[From 'Film']" and "- Remastered" suffixes, and on how co-artists are listed. */
        private fun looseKey(artist: String, title: String): String {
            val main = artist.split(Regex(",|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b", RegexOption.IGNORE_CASE)).first()
            val bare = title.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").replace(Regex("\\s+-\\s+.*$"), "")
            return ownedSongKey(main, bare)
        }
    }
}
