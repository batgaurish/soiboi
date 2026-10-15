package app.soiboi.engine.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.time.Instant

/** An album, grouped by its name. */
class Album(val name: String, val songs: List<Song>) {
    val year: Int? get() = songs.firstNotNullOfOrNull { it.year }
    val artworkSong: Song get() = songs.first()
    val sortKey: String = sortKey(name)
    /** Songs by each credited artist, so an artist's page can list only their tracks on a shared album. */
    val byArtist: Map<String, List<Song>> by lazy {
        val m = LinkedHashMap<String, MutableList<Song>>()
        for (s in songs) for (a in s.artists) m.getOrPut(a) { ArrayList() } += s
        m
    }
}

/** An artist: every credited name on every song. */
class Artist(val name: String, val albums: List<Album>) {
    val songs: List<Song> get() = albums.flatMap { it.byArtist[name].orEmpty() }
    val sortKey: String = sortKey(name)
}

/** A comparison key that ignores accents and case, so "Édith" files beside "Edith". */
fun sortKey(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** Sorts songs by one of the Songs page's orders. */
enum class SongSort(val label: String) {
    Default("Default"), TitleAsc("Title A to Z"), TitleDesc("Title Z to A"), ArtistAsc("Artist A to Z"), ArtistDesc("Artist Z to A"),
    AlbumAsc("Album A to Z"), AlbumDesc("Album Z to A"), DurationAsc("Shortest"), DurationDesc("Longest"),
    OldestAdded("Oldest added"), NewestAdded("Recently added"), Shuffle("Shuffle"), YearNewest("Newest year"), YearOldest("Oldest year"), MostPlayed("Most played");

    fun sort(songs: List<Song>): List<Song> {
        fun disc(s: Song) = s.disc ?: 9999
        fun track(s: Song) = s.track ?: 9999
        val byAlbumOrder = compareBy<Song>({ disc(it) }, { track(it) })
        return when (this) {
            Default -> songs
            TitleAsc -> songs.sortedBy { sortKey(it.displayTitle) }
            TitleDesc -> songs.sortedByDescending { sortKey(it.displayTitle) }
            ArtistAsc -> songs.sortedWith(compareBy<Song> { sortKey(it.displayArtist) }.thenBy { it.year ?: 9999 }.thenBy { sortKey(it.displayAlbum) }.then(byAlbumOrder))
            ArtistDesc -> songs.sortedWith(compareByDescending<Song> { sortKey(it.displayArtist) }.thenBy { it.year ?: 9999 }.thenBy { sortKey(it.displayAlbum) }.then(byAlbumOrder))
            AlbumAsc -> songs.sortedWith(compareBy<Song> { sortKey(it.displayAlbum) }.then(byAlbumOrder))
            AlbumDesc -> songs.sortedWith(compareByDescending<Song> { sortKey(it.displayAlbum) }.then(byAlbumOrder))
            DurationAsc -> songs.sortedBy { it.durationMs ?: Long.MAX_VALUE }
            DurationDesc -> songs.sortedByDescending { it.durationMs ?: -1L }
            OldestAdded -> songs.sortedBy { it.modified ?: Instant.MAX }
            NewestAdded -> songs.sortedByDescending { it.modified ?: Instant.MIN }
            Shuffle -> songs.shuffled()
            YearNewest -> songs.sortedByDescending { it.year ?: -1 }
            YearOldest -> songs.sortedBy { it.year ?: 99999 }
            MostPlayed -> songs.sortedByDescending { it.playCount }
        }
    }
}

/** Case-insensitive search over title, artist and album. */
fun filterSongs(songs: List<Song>, query: String): List<Song> {
    if (query.isEmpty()) return songs
    val q = query.lowercase()
    return songs.filter { q in it.displayTitle.lowercase() || q in it.displayArtist.lowercase() || q in it.displayAlbum.lowercase() }
}

/** The in-memory view of the library: every song, with artists and albums derived from them. */
class Library(private val store: SongStore) {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    private var byId: Map<String, Song> = emptyMap()
    fun song(id: String): Song? = byId[id]

    suspend fun load() = set(store.all())

    /** Replaces the in-memory library, e.g. after a scan or a connector sync. */
    fun set(songs: List<Song>) {
        byId = songs.associateBy { it.id }
        _songs.value = songs
        classify(songs)
    }

    /** Swaps one song in place (a play count, an edited tag) without rebuilding from disk. */
    fun replace(updated: Song) {
        if (updated.id !in byId) return
        set(_songs.value.map { if (it.id == updated.id) updated else it })
    }

    private fun classify(songs: List<Song>) {
        val albums = songs.groupBy { it.displayAlbum }.map { (name, list) ->
            Album(name, list.sortedWith(compareBy({ it.disc ?: 9999 }, { it.track ?: 9999 })))
        }.sortedBy { it.sortKey }
        _albums.value = albums
        val byArtist = LinkedHashMap<String, MutableSet<Album>>()
        for (album in albums) for (s in album.songs) for (a in s.artists) byArtist.getOrPut(a) { LinkedHashSet() } += album
        _artists.value = byArtist.map { (name, set) -> Artist(name, set.sortedBy { it.year ?: 9999 }) }.sortedBy { it.sortKey }
    }

    fun albumOf(song: Song): Album? = _albums.value.firstOrNull { it.name == song.displayAlbum }
    fun artistNamed(name: String): Artist? = _artists.value.firstOrNull { it.name == name }
}
