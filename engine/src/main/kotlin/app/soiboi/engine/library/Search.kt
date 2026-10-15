package app.soiboi.engine.library

import app.soiboi.engine.core.normaliseForMatch
import app.soiboi.engine.smart.MoodCardData
import app.soiboi.engine.smart.SmartContext
import app.soiboi.engine.smart.SmartPlaylist

enum class PlaylistKind { Saved, Smart, Mood }

/** Where a playlist hit came from: three kinds of playlist share one section and behave differently when opened. */
class PlaylistHit(val kind: PlaylistKind, val name: String, val trackCount: Int, val saved: Playlist? = null, val smart: SmartPlaylist? = null, val mood: MoodCardData? = null)

class GlobalSearchResults(val query: String, val songs: List<Song>, val albums: List<Album>, val artists: List<Artist>, val playlists: List<PlaylistHit>) {
    val isEmpty get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty() && playlists.isEmpty()
    val total get() = songs.size + albums.size + artists.size + playlists.size
}

/** A name match that ignores case, punctuation and "&" vs "and": "Sigur Ros" finds "Sigur Rós" and agrees with what the app calls "already owned". */
fun matchesName(name: String, query: String): Boolean {
    val target = normaliseForMatch(query)
    return target.isNotEmpty() && normaliseForMatch(name).contains(target)
}

private const val SECTION_LIMIT = 12

/** One search across everything the app knows about. A search for "a" matches most of a library, so each section is capped. */
fun globalSearch(
    rawQuery: String, songs: List<Song>, albums: List<Album>, artists: List<Artist>,
    saved: List<Playlist>, smart: List<SmartPlaylist>, moods: List<MoodCardData>, ctx: SmartContext = SmartContext(), limit: Int = SECTION_LIMIT,
): GlobalSearchResults {
    val q = rawQuery.trim()
    if (q.isEmpty()) return GlobalSearchResults(q, emptyList(), emptyList(), emptyList(), emptyList())
    val hits = buildList {
        saved.filter { matchesName(it.name, q) }.forEach { add(PlaylistHit(PlaylistKind.Saved, it.name, it.songIds.size, saved = it)) }
        smart.filter { matchesName(it.name, q) }.forEach { add(PlaylistHit(PlaylistKind.Smart, it.name, it.evaluate(songs, ctx).size, smart = it)) }
        // Mood playlists are ephemeral, but they are named things a user sees on Home: not finding "Deep Focus" by searching for it would be a hole.
        moods.filter { matchesName(it.name, q) }.forEach { add(PlaylistHit(PlaylistKind.Mood, it.name, it.trackCount, mood = it)) }
    }
    return GlobalSearchResults(
        q, filterSongs(songs, q).take(limit), albums.filter { matchesName(it.name, q) }.take(limit),
        artists.filter { matchesName(it.name, q) }.take(limit), hits.take(limit),
    )
}

/** What a search for an artist you only partly own should say. */
fun artistSubtitle(artist: Artist): String {
    val a = artist.albums.size; val t = artist.songs.size
    return "$a ${if (a == 1) "album" else "albums"} · $t ${if (t == 1) "track" else "tracks"}"
}

/** How many items a Home shelf shows: enough to be worth scrolling, few enough that building them all stays cheap. */
const val SHELF_LIMIT = 12

/** Newest files in the library by modification time: "recently added" in the only sense a local library can know. */
fun recentlyAddedSongs(songs: List<Song>) = songs.filter { it.modified != null }.sortedByDescending { it.modified }.take(SHELF_LIMIT)

/** Albums newest release first. Tags carry only a year, so albums from the same year fall back to when they arrived. */
fun recentlyAddedAlbums(albums: List<Album>): List<Album> {
    fun year(a: Album) = a.songs.maxOfOrNull { it.year ?: 0 } ?: 0
    fun added(a: Album) = a.songs.mapNotNull { it.modified }.maxOrNull() ?: java.time.Instant.EPOCH
    return albums.filter { it.songs.isNotEmpty() }.sortedWith(compareByDescending<Album> { year(it) }.thenByDescending { added(it) }).take(SHELF_LIMIT)
}

private fun totalPlays(songs: List<Song>) = songs.sumOf { it.playCount }

/** There is no favourite-artist or favourite-album flag in the data model, so top artists and albums are derived from play counts, which is what the big streaming apps show under those headings. */
fun topArtists(artists: List<Artist>) = artists.filter { totalPlays(it.songs) > 0 }.sortedByDescending { totalPlays(it.songs) }.take(SHELF_LIMIT)
fun topAlbums(albums: List<Album>) = albums.filter { totalPlays(it.songs) > 0 }.sortedByDescending { totalPlays(it.songs) }.take(SHELF_LIMIT)

/** Ranking by play count, ties broken by who was played earliest, as the original History did. */
fun rankedByPlays(songs: List<Song>) = songs.filter { it.playCount > 0 && it.lastPlayed != null }
    .sortedWith(compareByDescending<Song> { it.playCount }.thenBy { it.lastPlayed })
fun recentlyPlayed(songs: List<Song>) = songs.filter { it.playCount > 0 && it.lastPlayed != null }.sortedByDescending { it.lastPlayed }
