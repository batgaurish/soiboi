package app.soiboi.engine.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val FAVORITE = "Favorite"

/** A named, ordered list of song ids. Smart playlists are evaluated from rules and never stored here. */
data class Playlist(
    val name: String,
    val songIds: List<String> = emptyList(),
    val pinned: Boolean = false,
    val remoteId: String? = null,
    /** Path of a user-chosen cover image, shown instead of the first song's artwork. */
    val cover: String? = null,
) {
    val isFavorite: Boolean get() = name == FAVORITE
}

/** Playlist storage on top of the library database. Favorite always exists and is always first and pinned. */
class PlaylistStore(private val db: Database) {
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** Ids in Favorite, for a heart toggle without scanning the list each time. */
    val favoriteIds: Set<String> get() = _playlists.value.firstOrNull { it.isFavorite }?.songIds?.toSet().orEmpty()

    suspend fun load() {
        _playlists.value = db.run {
            query("SELECT name, pinned, remote_id, cover FROM playlists ORDER BY position") { r ->
                Playlist(r.text(0)!!, emptyList(), r.int(1) == 1 || r.text(0) == FAVORITE, r.text(2), r.text(3))
            }.map { p -> p.copy(songIds = query("SELECT song_id FROM playlist_songs WHERE playlist=? ORDER BY position", p.name) { it.text(0)!! }) }
        }
    }

    fun byName(name: String): Playlist? = _playlists.value.firstOrNull { it.name == name }

    /** Creates [name]; returns false when a playlist of that name already exists. */
    suspend fun create(name: String, remoteId: String? = null): Boolean {
        if (byName(name) != null) return false
        db.run { exec("INSERT INTO playlists(name, position, remote_id) VALUES(?, (SELECT COALESCE(MAX(position),0)+1 FROM playlists), ?)", name, remoteId) }
        load(); return true
    }

    suspend fun delete(name: String) {
        if (name == FAVORITE) return
        db.run { exec("DELETE FROM playlists WHERE name=?", name) }
        load()
    }

    suspend fun rename(from: String, to: String): Boolean {
        if (from == FAVORITE || byName(to) != null) return false
        db.run { exec("UPDATE playlists SET name=? WHERE name=?", to, from) }
        load(); return true
    }

    suspend fun setPinned(name: String, pinned: Boolean) {
        if (name == FAVORITE) return
        db.run { exec("UPDATE playlists SET pinned=? WHERE name=?", pinned, name) }
        load()
    }

    suspend fun setCover(name: String, path: String?) {
        db.run { exec("UPDATE playlists SET cover=? WHERE name=?", path, name) }
        load()
    }

    /** Replaces the playlist's songs with [ids], in order. */
    suspend fun setSongs(name: String, ids: List<String>) {
        db.transaction {
            exec("DELETE FROM playlist_songs WHERE playlist=?", name)
            batch<IndexedValue<String>>("INSERT INTO playlist_songs(playlist, position, song_id) VALUES(?,?,?)", ids.withIndex()) { arrayOf<Any?>(name, it.index, it.value) }
        }
        load()
    }

    /** New songs go to the top, like the original; songs already in the list are skipped. */
    suspend fun add(name: String, ids: List<String>) {
        val current = byName(name)?.songIds ?: return
        val fresh = ids.filter { it !in current }.distinct()
        if (fresh.isNotEmpty()) setSongs(name, fresh.reversed() + current)
    }

    suspend fun remove(name: String, ids: Set<String>) {
        val current = byName(name)?.songIds ?: return
        setSongs(name, current.filter { it !in ids })
    }

    suspend fun toggleFavorite(id: String) {
        if (id in favoriteIds) remove(FAVORITE, setOf(id)) else add(FAVORITE, listOf(id))
    }

    /** [name], or "name (2)" and so on if a playlist already has it. */
    fun freeName(name: String): String {
        val base = if (name.length > 60) name.substring(0, 60) else name
        var candidate = base; var n = 2
        while (byName(candidate) != null) { candidate = "$base ($n)"; n++ }
        return candidate
    }
}
