package app.soiboi.engine.sources

/** Whether [host] is [domain] or a subdomain of it. `endsWith` alone also accepts `notdeezer.com`. */
fun hostMatches(host: String, domain: String): Boolean {
    val lower = host.lowercase()
    return lower == domain || lower.endsWith(".$domain")
}

/** A playlist as the source describes it, before anything is resolved. */
data class ExternalPlaylist(
    val sourceId: String,
    val id: String,
    val title: String,
    val lastModified: String? = null,
    /** Known up front by some sources, and only after fetching by others. */
    val trackCount: Int? = null,
) {
    /** Cache key across every source: two platforms can share an id space, so the source is part of it. */
    val key: String get() = "$sourceId:$id"
}

/** One track as the source knows it: a name and an artist, plus an ISRC when the platform tags one. */
data class ExternalTrack(val title: String, val artist: String, val isrc: String? = null)

/**
 * Where a playlist can come from. A source answers two questions, what playlists do you have and
 * what is in one; everything downstream (Apple resolution, caching, the download queue) is shared.
 * A source that cannot browse returns an empty list from [playlists] and recognises links instead.
 */
abstract class ExternalPlaylistSource {
    abstract val id: String
    abstract val displayName: String

    /** Playlists this source offers unprompted. Empty is valid: it can only be given a specific link. */
    abstract suspend fun playlists(): List<ExternalPlaylist>

    /** The tracks of one playlist. */
    abstract suspend fun tracks(playlistId: String): List<ExternalTrack>

    open val acceptsLinks: Boolean get() = false

    /** The playlist id inside [url], if this source recognises it. */
    open fun playlistIdFromUrl(url: String): String? = null

    /** A human title for a playlist known only by id, used after an import by link. */
    open suspend fun titleFor(playlistId: String): String? = null

    /** Why the last [titleFor] or [tracks] call came back empty, in the platform's own words. */
    open val lastError: String? get() = null
}

class SourceMatch(val source: ExternalPlaylistSource, val playlistId: String)

/** Every source the app knows about, so a screen iterates sources instead of naming them. */
class PlaylistSources {
    private val list = ArrayList<ExternalPlaylistSource>()
    val all: List<ExternalPlaylistSource> get() = list.toList()

    fun register(source: ExternalPlaylistSource) {
        list.removeAll { it.id == source.id }
        list += source
    }

    fun byId(id: String) = list.firstOrNull { it.id == id }

    /** The source that recognises [url]. Asking the sources keeps each platform's URL knowledge in its own file. */
    fun forUrl(url: String): SourceMatch? {
        for (source in list) source.playlistIdFromUrl(url)?.let { return SourceMatch(source, it) }
        return null
    }

    companion object {
        /** Every platform Soiboi reads, with the pasted-tracklist source last so a link goes to its own source first. */
        fun standard(youtube: YouTubePlaylistSource) = PlaylistSources().apply {
            register(SpotifyPlaylistSource()); register(DeezerPlaylistSource()); register(youtube)
            register(SoundcloudPlaylistSource()); register(TidalPlaylistSource()); register(JioSaavnPlaylistSource())
            register(qobuzPlaylistSource()); register(gaanaPlaylistSource()); register(BandcampAlbumSource())
            register(TracklistSource())
        }
    }
}
