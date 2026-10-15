package app.soiboi.engine.library

import java.time.Instant

/** What kind of place a song lives. Local files carry a [Song.path]; connector songs are fetched by id. */
enum class SongOrigin { Local, Navidrome, OpenSubsonic, Jellyfin, Emby, Plex, WebDav, GoogleDrive }

/** A track in the library, whether a local file or a connector's remote item. */
data class Song(
    /** The file path for local songs, the server's id for connector songs. */
    val id: String,
    val origin: SongOrigin = SongOrigin.Local,
    val path: String? = null,
    val modified: Instant? = null,
    val format: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val track: Int? = null,
    val disc: Int? = null,
    /** bit/s from some sources, kbit/s from others; see [QualityInfo]. */
    val bitrate: Int? = null,
    val samplerate: Int? = null,
    val durationMs: Long? = null,
    val lyrics: String? = null,
    val playCount: Int = 0,
    val lastPlayed: Instant? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val coverId: String? = null,
    // Acoustic features from the analyser's sidecar. Null when the track has not been analysed.
    val energy: Double? = null,
    val danceable: Double? = null,
    val brightness: Double? = null,
    val aggressive: Double? = null,
    val relaxed: Double? = null,
    val bpm: Double? = null,
    val musicalKey: String? = null,
) {
    val displayTitle: String get() = title?.takeIf { it.isNotEmpty() } ?: id.substringAfterLast('/').substringBeforeLast('.')
    val displayArtist: String get() = artist?.takeIf { it.isNotEmpty() } ?: "Unknown Artist"
    val displayAlbum: String get() = album?.takeIf { it.isNotEmpty() } ?: "Unknown Album"
    val displayAlbumArtist: String get() = albumArtist?.takeIf { it.isNotEmpty() } ?: "Unknown Album Artist"
    val displayGenre: String get() = genre?.takeIf { it.isNotEmpty() } ?: "Unknown Genre"
    val artists: List<String> get() = splitArtists(displayArtist)
    val duration get() = durationMs?.let { java.time.Duration.ofMillis(it) }
}

/** Splits a credit on `/`, `&` and `,`; a blank part means the credit is kept whole. */
fun splitArtists(artist: String): List<String> {
    val out = ArrayList<String>()
    for (part in artist.split(Regex("[/&,]"))) {
        val name = part.trim()
        if (name.isEmpty()) return listOf(artist)
        out += name
    }
    return out
}

enum class QualityTier { Lossless, Lossy, Unknown }

/** The codec and tier a badge shows, decided from tag format and bitrate. */
data class QualityInfo(val tier: QualityTier, val label: String, val detail: String? = null) {
    companion object {
        private val losslessFormats = setOf("alac", "flac", "wav", "wave", "ape", "wavpack", "wv", "aiff", "aif", "tta")

        /**
         * Above this, an MP4/M4A stream is ALAC rather than AAC. Tag libraries report the container
         * ("MP4") for local files, so only bitrate settles it: ALAC at 16-bit/44.1 kHz runs roughly
         * 700-1100 kbit/s, AAC tops out near 400, and nothing lands in between.
         */
        private const val ALAC_BITRATE_FLOOR = 500

        fun of(song: Song) = classify(song.format, song.bitrate, song.samplerate)

        fun classify(format: String?, bitrate: Int?, sampleRate: Int? = null): QualityInfo {
            val trimmed = format?.trim()
            if (trimmed.isNullOrEmpty()) return QualityInfo(QualityTier.Unknown, "—")
            val f = trimmed.lowercase()
            val detail = if (sampleRate == null || sampleRate <= 0) null else "%.1f kHz".format(sampleRate / 1000.0)
            // Bitrate arrives in bit/s from some sources and kbit/s from others.
            val kbps = if (bitrate == null || bitrate <= 0) null else if (bitrate > 10000) Math.round(bitrate / 1000.0).toInt() else bitrate

            if (losslessFormats.any { f.contains(it) }) return QualityInfo(QualityTier.Lossless, codecName(f), detail)
            // An MP4 container holds either ALAC or AAC; only the bitrate tells us.
            if (listOf("mp4", "m4a", "mpeg-4", "mp4a").any { f.contains(it) }) {
                return if (kbps != null && kbps >= ALAC_BITRATE_FLOOR) QualityInfo(QualityTier.Lossless, "ALAC", detail)
                else QualityInfo(QualityTier.Lossy, if (kbps == null) "AAC" else "AAC $kbps", detail)
            }
            val codec = codecName(f)
            return QualityInfo(QualityTier.Lossy, if (kbps == null) codec else "$codec $kbps", detail)
        }

        private fun codecName(f: String) = when {
            "alac" in f -> "ALAC"; "flac" in f -> "FLAC"; "aac" in f || "m4a" in f -> "AAC"
            "mp3" in f || "mpeg" in f -> "MP3"; "opus" in f -> "Opus"; "vorbis" in f || "ogg" in f -> "Vorbis"
            "wavpack" in f || "wv" in f -> "WavPack"; "wav" in f -> "WAV"; "aiff" in f || "aif" in f -> "AIFF"
            "ape" in f -> "APE"; else -> f.uppercase()
        }
    }
}

/** "AAC 256" becomes "AAC": the codec without its bitrate. */
fun QualityInfo.codec(): String = label.split(' ').first()

/** A genre as the filter lists it; untagged songs share one entry. */
fun genreOf(genre: String?): String = genre?.trim().orEmpty().ifEmpty { "No genre" }

/** Filters for the Songs page: quality, codec, genre, favourites. */
data class SongFilter(
    val quality: QualityTier? = null,
    val codecs: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val favoritesOnly: Boolean = false,
) {
    val activeCount: Int get() = (if (quality == null) 0 else 1) + (if (codecs.isEmpty()) 0 else 1) + (if (genres.isEmpty()) 0 else 1) + (if (favoritesOnly) 1 else 0)
    val isEmpty: Boolean get() = activeCount == 0

    fun matches(song: Song, favorite: Boolean): Boolean {
        val info = QualityInfo.of(song)
        if (quality != null && info.tier != quality) return false
        if (codecs.isNotEmpty() && info.codec() !in codecs) return false
        if (genres.isNotEmpty() && genreOf(song.genre) !in genres) return false
        if (favoritesOnly && !favorite) return false
        return true
    }

    fun apply(songs: List<Song>, favorites: Set<String>): List<Song> =
        if (isEmpty) songs else songs.filter { matches(it, it.id in favorites) }
}

/** The codecs and genres present in [songs], each with a count, most common first: what the filter sheet offers. */
fun filterOptions(songs: List<Song>): Pair<Map<String, Int>, Map<String, Int>> {
    val codecs = HashMap<String, Int>(); val genres = HashMap<String, Int>()
    for (song in songs) {
        val codec = QualityInfo.of(song).codec()
        if (codec != "—") codecs.merge(codec, 1, Int::plus)
        genres.merge(genreOf(song.genre), 1, Int::plus)
    }
    fun ranked(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    return ranked(codecs) to ranked(genres)
}
