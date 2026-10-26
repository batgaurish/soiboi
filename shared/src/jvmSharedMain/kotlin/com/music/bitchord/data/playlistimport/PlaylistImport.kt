package com.music.bitchord.data.playlistimport

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale
import com.music.bitchord.data.model.Song

/** Imported entries are references only; they never introduce a playback source. */
enum class PlaylistImportProvider { PASTED, SPOTIFY, DEEZER, YOUTUBE_MUSIC, TIDAL, SOUNDCLOUD, JIOSAAVN, QOBUZ, GAANA, BANDCAMP, APPLE_MUSIC }

data class PlaylistEntry(
    val title: String,
    val artist: String,
    val isrc: String? = null,
    val sourceIndex: Int,
    val sourceId: String? = null,
)
data class ImportedPlaylist(
    val title: String,
    val provider: PlaylistImportProvider,
    val entries: List<PlaylistEntry>,
    val sourceUrl: String? = null,
)
data class ImportCandidate(val id: String, val title: String, val artist: String, val isrc: String? = null)

sealed interface ImportMatchState {
    data class Matched(val candidate: ImportCandidate, val confidence: Double, val method: Method) : ImportMatchState
    data object Unmatched : ImportMatchState
    data class ManualConfirmed(val candidate: ImportCandidate) : ImportMatchState
    enum class Method { ISRC, ARTIST_TITLE }
}

data class ImportRowState(
    val entry: PlaylistEntry,
    val matchState: ImportMatchState,
    val candidateSong: Song? = null,
    val availableCandidates: List<Song> = emptyList(),
) {
    val isPlayable: Boolean get() = candidateSong != null && (matchState is ImportMatchState.Matched || matchState is ImportMatchState.ManualConfirmed)
}

object PlaylistImportParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val LEADING_TRACK_NUMBER_REGEX = Regex("^\\s*(?:[-*]\\s+)?(?:\\d{1,3}\\s*[:.)\\-–—]\\s*|\\d{1,3}[.)]\\s*)")
    private val VISUAL_SEPARATOR_REGEX = Regex("\\s+[-–—:|/]\\s+|\\s*[–—]\\s*")

    /** Documented deterministic separators for pasted tracklists: tabs, commas (CSV), visual delimiters (` - `, ` – `, ` — `, ` | `, ` : `, ` / `). */
    fun parseTracklist(text: String, title: String = "Imported playlist"): ImportedPlaylist {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
            .toList()
        if (lines.isEmpty()) return ImportedPlaylist(title, PlaylistImportProvider.PASTED, emptyList())

        val header = parseHeader(lines.first())
        val contentLines = if (header.isHeader) lines.drop(1) else lines

        val entries = contentLines.mapNotNull { line ->
            parseLine(line, header)
        }.mapIndexed { index, track ->
            PlaylistEntry(
                title = track.title,
                artist = track.artist,
                isrc = track.isrc,
                sourceIndex = index
            )
        }

        return ImportedPlaylist(title, PlaylistImportProvider.PASTED, entries)
    }

    fun parsePastedTracklist(text: String, title: String = "Imported playlist"): ImportedPlaylist =
        parseTracklist(text, title)

    fun parse(text: String, title: String = "Imported playlist"): ImportedPlaylist =
        parseTracklist(text, title)

    fun parseAuto(input: String, defaultTitle: String = "Imported playlist"): ImportedPlaylist {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ImportedPlaylist(defaultTitle, PlaylistImportProvider.PASTED, emptyList())

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            if (trimmed.contains("\"tracks\"") && trimmed.contains("\"data\"")) {
                runCatching { parseDeezerPlaylistResponse(trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            }
            if (trimmed.contains("\"publisher_metadata\"")) {
                runCatching { parseSoundcloudPlaylistResponse(trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            }
            if (trimmed.contains("\"artistMap\"") || trimmed.contains("primary_artists")) {
                runCatching { parseJioSaavnPlaylistResponse(trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            }
            if (trimmed.contains("\"musicResponsiveListItemRenderer\"")) {
                runCatching { parseYouTubeMusicPlaylistResponse(trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            }
            if (trimmed.contains("\"trackList\"") || trimmed.contains("playlistV2")) {
                runCatching { parseSpotifyPlaylistResponse(trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            }
            if (trimmed.contains("\"@type\"") && (trimmed.contains("MusicPlaylist") || trimmed.contains("MusicAlbum"))) {
                runCatching { parseQobuzPlaylistResponse(trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            }
            if (trimmed.contains("\"items\"") && trimmed.contains("\"version\"")) {
                runCatching { parseTidalPlaylistResponse(trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            }
            for (provider in PlaylistImportProvider.entries.filterNot { it == PlaylistImportProvider.PASTED }) {
                runCatching { parseProviderFixture(provider, trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
            }
        }

        if (trimmed.contains("data-tralbum")) {
            runCatching { parseBandcampPlaylistResponse(trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
        }

        if (trimmed.contains("<script") && trimmed.contains("application/ld+json")) {
            runCatching { parseQobuzPlaylistResponse(trimmed) }.getOrNull()?.takeIf { it.entries.isNotEmpty() }?.let { return it }
        }

        return parseTracklist(trimmed, defaultTitle)
    }

    private data class ParsedTrack(val title: String, val artist: String, val isrc: String?)

    private data class HeaderInfo(
        val isHeader: Boolean,
        val isCsv: Boolean,
        val titleIndex: Int = -1,
        val artistIndex: Int = -1,
        val isrcIndex: Int = -1,
    ) {
        val titleFirst: Boolean get() = titleIndex in 0 until artistIndex
    }

    private fun parseHeader(firstLine: String): HeaderInfo {
        val isCsv = firstLine.contains(',') && !firstLine.contains('\t')
        val cells = when {
            firstLine.contains('\t') -> firstLine.split('\t').map { it.trim().lowercase(Locale.ROOT) }
            isCsv -> splitCsvLine(firstLine).map { it.trim().lowercase(Locale.ROOT) }
            VISUAL_SEPARATOR_REGEX.containsMatchIn(firstLine) -> firstLine.split(VISUAL_SEPARATOR_REGEX, limit = 2).map { it.trim().lowercase(Locale.ROOT) }
            else -> return HeaderInfo(isHeader = false, isCsv = false)
        }
        val titleIndex = cells.indexOfFirst { it == "title" || it == "track" || it == "name" || it == "track name" || it == "track_name" || it == "song" }
        val artistIndex = cells.indexOfFirst { it == "artist" || it == "artists" || it == "artist name" || it == "artist_name" || it == "singer" || it == "uploader" }
        val isrcIndex = cells.indexOfFirst { it == "isrc" || it == "isrc code" || it == "isrc_code" }
        val isHeader = titleIndex != -1 && artistIndex != -1
        return HeaderInfo(
            isHeader = isHeader,
            isCsv = isCsv,
            titleIndex = titleIndex,
            artistIndex = artistIndex,
            isrcIndex = isrcIndex
        )
    }

    private fun parseLine(rawLine: String, header: HeaderInfo): ParsedTrack? {
        val cleaned = rawLine.trim().removePrefix("- ").removePrefix("* ")
        if (cleaned.isBlank() || cleaned.startsWith("#") || cleaned.startsWith("//")) return null
        val stripped = cleaned.replaceFirst(LEADING_TRACK_NUMBER_REGEX, "").trim()
        if (stripped.isBlank()) return null

        val fields: List<String> = when {
            header.isCsv -> splitCsvLine(stripped)
            stripped.contains('\t') -> stripped.split('\t').map(String::trim)
            VISUAL_SEPARATOR_REGEX.containsMatchIn(stripped) -> stripped.split(VISUAL_SEPARATOR_REGEX, limit = 2).map(String::trim)
            stripped.contains(',') -> splitCsvLine(stripped)
            else -> return null
        }

        if (fields.size < 2) return null
        if (fields[0].isBlank() || fields[1].isBlank()) return null

        val (title, artist, isrc) = if (header.isHeader) {
            val t = if (header.titleIndex in fields.indices) fields[header.titleIndex] else if (header.titleFirst) fields[0] else fields[1]
            val a = if (header.artistIndex in fields.indices) fields[header.artistIndex] else if (header.titleFirst) fields[1] else fields[0]
            val i = if (header.isrcIndex in fields.indices) fields[header.isrcIndex].takeIf(String::isNotBlank)?.normalizeIsrc() else null
            Triple(t, a, i)
        } else {
            val a = fields[0]
            val t = fields[1]
            val i = if (fields.size > 2) fields[2].takeIf(String::isNotBlank)?.normalizeIsrc() else null
            Triple(t, a, i)
        }

        if (title.isBlank() || artist.isBlank()) return null
        return ParsedTrack(title = title, artist = artist, isrc = isrc)
    }

    private fun splitCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    cells.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        cells.add(current.toString().trim())
        return cells
    }

    /** Parses an Apple public catalog playlist or paginated tracks response. */
    fun parseAppleCatalogPlaylistResponse(response: String): ImportedPlaylist {
        val root = json.parseToJsonElement(response).jsonObject
        val data = (root["data"] as? JsonArray).orEmpty()
        val first = data.firstOrNull() as? JsonObject
        val isTrackPage = first?.stringAny("type") == "songs"
        val playlist = first.takeUnless { isTrackPage }
        val title = if (playlist != null) {
            (playlist["attributes"] as? JsonObject)?.stringAny("name")?.trim()?.takeIf(String::isNotEmpty)
                ?: "Imported Apple Music playlist"
        } else {
            "Imported Apple Music playlist"
        }
        val includedById = (root["included"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.stringAny("type") == "songs" }
            .mapNotNull { track -> track.stringAny("id")?.let { it to track } }
            .toMap()
        val trackRows = if (playlist != null) {
            (((playlist["relationships"] as? JsonObject)?.get("tracks") as? JsonObject)?.get("data") as? JsonArray)
                ?: JsonArray(emptyList())
        } else {
            data
        }
        val entries = trackRows.mapIndexedNotNull { index, row ->
            val reference = row as? JsonObject ?: return@mapIndexedNotNull null
            val type = reference.stringAny("type")
            if (type != null && type != "songs") return@mapIndexedNotNull null
            val track = if (reference["attributes"] is JsonObject) {
                reference
            } else {
                reference.stringAny("id")?.let(includedById::get) ?: return@mapIndexedNotNull null
            }
            val attrs = track["attributes"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = attrs.stringAny("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapIndexedNotNull null
            val artist = attrs.stringAny("artistName")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapIndexedNotNull null
            val isrc = attrs.stringAny("isrc")?.trim()?.takeIf(String::isNotEmpty)?.normalizeIsrc()
            val sourceId = track.stringAny("id")?.trim()?.takeIf(String::isNotEmpty)
                ?: reference.stringAny("id")?.trim()?.takeIf(String::isNotEmpty)
            PlaylistEntry(name, artist, isrc, index, sourceId)
        }
        return ImportedPlaylist(title, PlaylistImportProvider.APPLE_MUSIC, entries)
    }

    /** Parses an offline Spotify Web API playlist object, including tracks.items[].track. */
    fun parseSpotifyPlaylistResponse(response: String): ImportedPlaylist {
        val root = json.parseToJsonElement(response).jsonObject
        val title = root.stringAny("name")?.trim()?.takeIf(String::isNotEmpty) ?: "Imported Spotify playlist"
        val items = ((root["tracks"] as? JsonObject)?.get("items") as? JsonArray).orEmpty()
        val entries = items.mapNotNull { item ->
            val track = (item as? JsonObject)?.get("track") as? JsonObject ?: return@mapNotNull null
            val name = track.stringAny("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val artists = (track["artists"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.stringAny("name")?.trim()?.takeIf(String::isNotEmpty) }
            val isrc = (track["external_ids"] as? JsonObject)?.stringAny("isrc")?.uppercase(Locale.ROOT)
            PlaylistEntry(name, artists.joinToString(", "), isrc, 0)
        }
        return ImportedPlaylist(title, PlaylistImportProvider.SPOTIFY, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
    }

    /** Parses the public Deezer playlist response, including its nested `tracks.data` collection. */
    fun parseDeezerPlaylistResponse(response: String): ImportedPlaylist {
        val root = json.parseToJsonElement(response).jsonObject
        val title = root.stringAny("title")?.trim()?.takeIf(String::isNotEmpty) ?: "Imported Deezer playlist"
        val tracks = (root["tracks"] as? JsonObject)?.get("data") as? JsonArray
            ?: (root["tracks"] as? JsonArray)
            ?: (root["data"] as? JsonArray)
            ?: JsonArray(emptyList())
        val entries = tracks.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val name = track.stringAny("title", "title_short", "name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val artist = (track["artist"] as? JsonObject)?.stringAny("name")?.trim()?.takeIf(String::isNotEmpty)
                ?: track.stringAny("artistName")?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val isrc = track.stringAny("isrc")?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
            PlaylistEntry(name, artist, isrc, 0)
        }
        return ImportedPlaylist(title, PlaylistImportProvider.DEEZER, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
    }

    /** Parses an offline Tidal playlist response, filtering track items and preserving version in title. */
    fun parseTidalPlaylistResponse(response: String): ImportedPlaylist {
        val rootElement = json.parseToJsonElement(response)
        val root = if (rootElement is JsonArray) {
            JsonObject(mapOf("items" to rootElement))
        } else {
            rootElement.jsonObject
        }
        val title = root.stringAny("title", "name")?.trim()?.takeIf(String::isNotEmpty) ?: "Imported Tidal playlist"
        val items = (root["items"] as? JsonArray)
            ?: (root["tracks"] as? JsonArray)
            ?: (root["data"] as? JsonArray).orEmpty()
        val entries = items.mapNotNull { element ->
            val itemObj = element as? JsonObject ?: return@mapNotNull null
            val type = itemObj.stringAny("type")?.trim()
            if (type != null && type != "track") return@mapNotNull null
            val track = (itemObj["item"] as? JsonObject) ?: itemObj
            val rawTitle = track.stringAny("title", "name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val version = track.stringAny("version")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            val fullTitle = if (version != null) "$rawTitle ($version)" else rawTitle
            val artistsList = (track["artists"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonObject)?.stringAny("name")?.trim()?.takeIf(String::isNotEmpty)
            }
            val artist = if (artistsList.isNotEmpty()) {
                artistsList.joinToString(", ")
            } else {
                (track["artist"] as? JsonObject)?.stringAny("name")?.trim()?.takeIf(String::isNotEmpty)
                    ?: track.stringAny("artistName", "artist")?.trim()?.takeIf(String::isNotEmpty)
            } ?: return@mapNotNull null
            val isrc = track.stringAny("isrc")?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
            PlaylistEntry(fullTitle, artist, isrc, 0)
        }
        return ImportedPlaylist(title, PlaylistImportProvider.TIDAL, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
    }

    /** Parses an offline SoundCloud set response, extracting publisher or user artist metadata. */
    fun parseSoundcloudPlaylistResponse(response: String): ImportedPlaylist {
        val rootElement = json.parseToJsonElement(response)
        val root = if (rootElement is JsonArray) {
            JsonObject(mapOf("tracks" to rootElement))
        } else {
            rootElement.jsonObject
        }
        val title = root.stringAny("title", "name")?.trim()?.takeIf(String::isNotEmpty) ?: "Imported SoundCloud playlist"
        val tracks = (root["tracks"] as? JsonArray)
            ?: (root["collection"] as? JsonArray)
            ?: (root["items"] as? JsonArray).orEmpty()
        val entries = tracks.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val name = track.stringAny("title")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val publisher = track["publisher_metadata"] as? JsonObject
            val publisherArtist = publisher?.stringAny("artist")?.trim()?.takeIf(String::isNotEmpty)
            val userArtist = (track["user"] as? JsonObject)?.stringAny("username")?.trim()?.takeIf(String::isNotEmpty)
            val artist = publisherArtist ?: userArtist ?: return@mapNotNull null
            val isrc = publisher?.stringAny("isrc")?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
            PlaylistEntry(name, artist, isrc, 0)
        }
        return ImportedPlaylist(title, PlaylistImportProvider.SOUNDCLOUD, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
    }

    /** Parses an offline JioSaavn playlist response, unescaping HTML entities and parsing primary artists. */
    fun parseJioSaavnPlaylistResponse(response: String): ImportedPlaylist {
        val root = json.parseToJsonElement(response).jsonObject
        val rawTitle = root.stringAny("title", "name")?.trim()?.takeIf(String::isNotEmpty) ?: "Imported JioSaavn playlist"
        val title = unescapeHtml(rawTitle)
        val list = (root["list"] as? JsonArray).orEmpty()
        val entries = list.mapNotNull { element ->
            val song = element as? JsonObject ?: return@mapNotNull null
            val rawSongTitle = song.stringAny("title", "song")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val moreInfo = song["more_info"] as? JsonObject
            val artistMap = moreInfo?.get("artistMap") as? JsonObject
            val primaryArtists = (artistMap?.get("primary_artists") as? JsonArray).orEmpty()
            val artistNames = primaryArtists.mapNotNull {
                (it as? JsonObject)?.stringAny("name")?.trim()?.takeIf(String::isNotEmpty)?.let(::unescapeHtml)
            }
            if (artistNames.isEmpty()) return@mapNotNull null
            val songTitle = unescapeHtml(rawSongTitle)
            val artist = artistNames.joinToString(", ")
            val isrc = (moreInfo?.stringAny("isrc") ?: song.stringAny("isrc"))?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
            PlaylistEntry(songTitle, artist, isrc, 0)
        }
        return ImportedPlaylist(title, PlaylistImportProvider.JIOSAAVN, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
    }

    /** Parses an offline YouTube Music playlist response (yt-dlp flat extraction or Innertube browse response). */
    fun parseYouTubeMusicPlaylistResponse(response: String): ImportedPlaylist {
        val rootElement = runCatching { json.parseToJsonElement(response) }.getOrNull()
            ?: return ImportedPlaylist("Imported YouTube Music playlist", PlaylistImportProvider.YOUTUBE_MUSIC, emptyList())
        val root = rootElement as? JsonObject
            ?: return ImportedPlaylist("Imported YouTube Music playlist", PlaylistImportProvider.YOUTUBE_MUSIC, emptyList())

        val title = root.stringAny("title", "name")?.trim()?.takeIf(String::isNotEmpty)
            ?: extractInnertubeTitle(root)
            ?: "Imported YouTube Music playlist"

        val rawEntries = sequenceOf("entries", "items", "tracks")
            .mapNotNull { root[it] as? JsonArray }
            .firstOrNull()

        val entries = if (rawEntries != null) {
            rawEntries.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val rawTitle = obj.stringAny("title", "name")?.trim() ?: return@mapNotNull null
                if (rawTitle.isBlank() || rawTitle.lowercase(Locale.ROOT) in PLACEHOLDER_TITLES) return@mapNotNull null

                val duration = obj["duration"]?.let {
                    runCatching { it.jsonPrimitive.longOrNull ?: it.jsonPrimitive.doubleOrNull?.toLong() }.getOrNull()
                }
                if (duration != null && duration > MAX_TRACK_SECONDS) return@mapNotNull null

                val uploader = obj.stringAny("channel", "uploader", "artist", "artistName")?.trim().orEmpty()
                val isrc = obj.stringAny("isrc", "isrcCode")?.trim()

                normalizeYouTubeTrack(rawTitle, uploader, isrc)
            }
        } else {
            val innertubeItems = collectRenderers(root, "musicResponsiveListItemRenderer")
            innertubeItems.mapNotNull { parseInnertubeItem(it) }
        }

        return ImportedPlaylist(
            title = title,
            provider = PlaylistImportProvider.YOUTUBE_MUSIC,
            entries = entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) }
        )
    }

    fun parseYouTubePlaylistResponse(response: String): ImportedPlaylist =
        parseYouTubeMusicPlaylistResponse(response)

    /** Parses an offline Qobuz playlist response from either schema.org JSON-LD (page scrape) or API JSON. */
    fun parseQobuzPlaylistResponse(response: String): ImportedPlaylist {
        val jsonLdNodes = extractJsonLdNodes(response)
        val ldNode = jsonLdNodes.firstOrNull { node ->
            val type = node.stringAny("@type")
            type == "MusicPlaylist" || type == "MusicAlbum"
        }

        if (ldNode != null) {
            val title = ldNode.stringAny("name", "title")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: "Imported Qobuz playlist"
            val trackElements = extractJsonLdTrackElements(ldNode)
            val entries = trackElements.mapNotNull { elem ->
                val recording = (elem as? JsonObject)?.let { (it["item"] as? JsonObject) ?: it } ?: return@mapNotNull null
                val rawTitle = recording.stringAny("name", "title")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val artist = extractArtistNames(recording["byArtist"])
                    ?: extractArtistNames(recording["performer"])
                    ?: extractArtistNames(ldNode["byArtist"])
                    ?: recording.stringAny("artist", "artistName")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val isrc = recording.stringAny("isrcCode", "isrc")?.trim()?.takeIf(String::isNotEmpty)?.normalizeIsrc()
                    ?: recording.stringAny("isrcCode", "isrc")?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
                PlaylistEntry(rawTitle, artist, isrc, 0)
            }
            return ImportedPlaylist(title, PlaylistImportProvider.QOBUZ, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
        }

        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
            ?: return ImportedPlaylist("Imported Qobuz playlist", PlaylistImportProvider.QOBUZ, emptyList())
        val title = root.stringAny("title", "name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
            ?: "Imported Qobuz playlist"
        val trackList = ((root["tracks"] as? JsonObject)?.get("items") as? JsonArray)
            ?: (root["tracks"] as? JsonArray)
            ?: (root["items"] as? JsonArray).orEmpty()

        val entries = trackList.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val rawTitle = track.stringAny("title", "name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val version = track.stringAny("version")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            val fullTitle = if (version != null) "$rawTitle ($version)" else rawTitle
            val artist = (track["performer"] as? JsonObject)?.stringAny("name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: extractArtistNames(track["artists"])
                ?: (track["artist"] as? JsonObject)?.stringAny("name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: track.stringAny("artist", "artistName")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val isrc = track.stringAny("isrc", "isrcCode")?.trim()?.takeIf(String::isNotEmpty)?.normalizeIsrc()
                ?: track.stringAny("isrc", "isrcCode")?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
            PlaylistEntry(fullTitle, artist, isrc, 0)
        }
        return ImportedPlaylist(title, PlaylistImportProvider.QOBUZ, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
    }

    /** Parses an offline Gaana playlist response from either schema.org JSON-LD (page scrape) or API JSON. */
    fun parseGaanaPlaylistResponse(response: String): ImportedPlaylist {
        val jsonLdNodes = extractJsonLdNodes(response)
        val ldNode = jsonLdNodes.firstOrNull { node ->
            val type = node.stringAny("@type")
            type == "MusicPlaylist" || type == "MusicAlbum"
        }

        if (ldNode != null) {
            val title = ldNode.stringAny("name", "title")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: "Imported Gaana playlist"
            val trackElements = extractJsonLdTrackElements(ldNode)
            val entries = trackElements.mapNotNull { elem ->
                val recording = (elem as? JsonObject)?.let { (it["item"] as? JsonObject) ?: it } ?: return@mapNotNull null
                val rawTitle = recording.stringAny("name", "title")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val artist = extractArtistNames(recording["byArtist"])
                    ?: extractArtistNames(ldNode["byArtist"])
                    ?: recording.stringAny("artist", "artistName")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val isrc = recording.stringAny("isrcCode", "isrc")?.trim()?.takeIf(String::isNotEmpty)?.normalizeIsrc()
                    ?: recording.stringAny("isrcCode", "isrc")?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
                PlaylistEntry(rawTitle, artist, isrc, 0)
            }
            return ImportedPlaylist(title, PlaylistImportProvider.GAANA, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
        }

        val root = runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
            ?: return ImportedPlaylist("Imported Gaana playlist", PlaylistImportProvider.GAANA, emptyList())
        val title = root.stringAny("title", "name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
            ?: "Imported Gaana playlist"
        val trackList = sequenceOf("tracks", "track", "items", "entries")
            .mapNotNull { root[it] as? JsonArray }
            .firstOrNull().orEmpty()

        val entries = trackList.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val rawTitle = track.stringAny("title", "track_title", "song_title", "name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val artist = extractArtistNames(track["primary_artists"])
                ?: extractArtistNames(track["artists"])
                ?: extractArtistNames(track["byArtist"])
                ?: (track["artist"] as? JsonObject)?.stringAny("name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: track.stringAny("artist", "artistName")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val isrc = track.stringAny("isrc", "isrcCode")?.trim()?.takeIf(String::isNotEmpty)?.normalizeIsrc()
                ?: track.stringAny("isrc", "isrcCode")?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
            PlaylistEntry(rawTitle, artist, isrc, 0)
        }
        return ImportedPlaylist(title, PlaylistImportProvider.GAANA, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
    }

    /** Parses an offline Bandcamp album response from page HTML (data-tralbum) or JSON. */
    fun parseBandcampPlaylistResponse(response: String): ImportedPlaylist {
        val root = if (response.contains("data-tralbum")) {
            val match = Regex("data-tralbum=[\"']([^\"']*)[\"']").find(response)
            match?.let { m ->
                val unescaped = unescapeHtml(m.groupValues[1])
                runCatching { json.parseToJsonElement(unescaped).jsonObject }.getOrNull()
            }
        } else {
            runCatching { json.parseToJsonElement(response).jsonObject }.getOrNull()
        } ?: return ImportedPlaylist("Imported Bandcamp album", PlaylistImportProvider.BANDCAMP, emptyList())

        val title = (root["current"] as? JsonObject)?.stringAny("title")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
            ?: root.stringAny("title", "name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
            ?: "Imported Bandcamp album"

        val albumArtist = root.stringAny("artist", "album_artist", "band_name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)

        val trackinfo = sequenceOf("trackinfo", "tracks", "items")
            .mapNotNull { root[it] as? JsonArray }
            .firstOrNull().orEmpty()

        val entries = trackinfo.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val trackTitle = track.stringAny("title", "name")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val trackArtist = track.stringAny("artist")?.let(::unescapeHtml)?.let(::tidyWhitespace)?.takeIf(String::isNotEmpty)
                ?: albumArtist
                ?: return@mapNotNull null
            val isrc = track.stringAny("isrc", "isrcCode")?.trim()?.takeIf(String::isNotEmpty)?.normalizeIsrc()
                ?: track.stringAny("isrc", "isrcCode")?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
            PlaylistEntry(trackTitle, trackArtist, isrc, 0)
        }

        return ImportedPlaylist(
            title = title,
            provider = PlaylistImportProvider.BANDCAMP,
            entries = entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) }
        )
    }

    fun parseBandcampAlbumResponse(response: String): ImportedPlaylist =
        parseBandcampPlaylistResponse(response)

    private const val MAX_TRACK_SECONDS = 20 * 60L
    private val PLACEHOLDER_TITLES = setOf("[private video]", "[deleted video]", "[unavailable video]")
    private val YOUTUBE_NOISE_REGEX = Regex(
        "[(\\[]\\s*(?:" +
            "official\\s*(?:music\\s*)?(?:video|audio|visualizer|lyric[s]?\\s*video)?|" +
            "lyric[s]?(?:\\s*video)?|audio|visuali[sz]er|" +
            "hd|hq|4k|full\\s*hd|remaster(?:ed)?(?:\\s*\\d{4})?|" +
            "explicit|clean|radio\\s*edit|music\\s*video|" +
            "with\\s*lyrics|letra|legendado" +
            ")\\s*[)\\]]",
        RegexOption.IGNORE_CASE,
    )
    private val FEATURING_REGEX = Regex("\\s+(?:ft\\.?|feat\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val TOPIC_SUFFIX_REGEX = Regex("\\s*-\\s*Topic\\s*$", RegexOption.IGNORE_CASE)
    private val YOUTUBE_SEPARATOR_REGEX = Regex("\\s+[-\u2013\u2014]\\s+")

    private fun cleanYouTubeString(value: String): String = value
        .replace(YOUTUBE_NOISE_REGEX, " ")
        .replace(Regex("\\s{2,}"), " ")
        .replace(Regex("[\\s\\-\u2013\u2014|]+$"), "")
        .replace(Regex("^[\\s\\-\u2013\u2014|]+"), "")
        .trim()

    private fun normalizeYouTubeTrack(rawTitle: String, rawUploader: String, isrc: String?): PlaylistEntry? {
        val trimmedTitle = rawTitle.trim()
        if (trimmedTitle.isBlank() || trimmedTitle.lowercase(Locale.ROOT) in PLACEHOLDER_TITLES) return null
        val uploader = rawUploader.trim()
        val isTopic = TOPIC_SUFFIX_REGEX.containsMatchIn(uploader)
        val artistFromUploader = cleanYouTubeString(uploader.replace(TOPIC_SUFFIX_REGEX, ""))

        val (title, artist) = if (isTopic) {
            val t = cleanYouTubeString(trimmedTitle).replace(FEATURING_REGEX, "").trim()
            val a = artistFromUploader
            Pair(t, a)
        } else {
            val cleaned = cleanYouTubeString(trimmedTitle)
            val sepMatch = YOUTUBE_SEPARATOR_REGEX.find(cleaned)
            if (sepMatch != null) {
                val a = cleaned.substring(0, sepMatch.range.first).trim().replace(FEATURING_REGEX, "").trim()
                val t = cleaned.substring(sepMatch.range.last + 1).trim().replace(FEATURING_REGEX, "").trim()
                if (a.isNotEmpty() && t.isNotEmpty()) {
                    Pair(t, a)
                } else {
                    Pair(cleaned.replace(FEATURING_REGEX, "").trim(), artistFromUploader)
                }
            } else {
                Pair(cleaned.replace(FEATURING_REGEX, "").trim(), artistFromUploader)
            }
        }

        if (title.isBlank() || artist.isBlank()) return null
        val normalizedIsrc = isrc?.trim()?.takeIf(String::isNotEmpty)?.normalizeIsrc()
            ?: isrc?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
        return PlaylistEntry(title = title, artist = artist, isrc = normalizedIsrc, sourceIndex = 0)
    }

    private fun extractInnertubeTitle(root: JsonObject): String? {
        val header = root["header"] as? JsonObject ?: return null
        val detailHeader = (header["musicDetailHeaderRenderer"] as? JsonObject)
            ?: (header["musicEditablePlaylistDetailHeaderRenderer"] as? JsonObject)?.get("header")?.let { (it as? JsonObject)?.get("musicDetailHeaderRenderer") as? JsonObject }
        val runs = (detailHeader?.get("title") as? JsonObject)?.get("runs") as? JsonArray
        return runs?.mapNotNull { (it as? JsonObject)?.stringAny("text") }?.joinToString("")?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun collectRenderers(element: JsonElement, key: String): List<JsonObject> {
        val results = mutableListOf<JsonObject>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node[key] as? JsonObject)?.let { results.add(it) }
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(element)
        return results
    }

    private fun parseInnertubeItem(item: JsonObject): PlaylistEntry? {
        val flexColumns = item["flexColumns"] as? JsonArray ?: return null
        if (flexColumns.isEmpty()) return null

        val col0 = flexColumns.getOrNull(0) as? JsonObject
        val titleRuns = (col0?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject)
            ?.get("text")?.let { (it as? JsonObject)?.get("runs") as? JsonArray }
        val rawTitle = titleRuns?.mapNotNull { (it as? JsonObject)?.stringAny("text") }?.joinToString("")?.trim().orEmpty()
        if (rawTitle.isBlank() || rawTitle.lowercase(Locale.ROOT) in PLACEHOLDER_TITLES) return null

        val col1 = flexColumns.getOrNull(1) as? JsonObject
        val artistRuns = (col1?.get("musicResponsiveListItemFlexColumnRenderer") as? JsonObject)
            ?.get("text")?.let { (it as? JsonObject)?.get("runs") as? JsonArray }.orEmpty()

        val artistNames = mutableListOf<String>()
        for (run in artistRuns) {
            val runObj = run as? JsonObject ?: continue
            val text = runObj.stringAny("text")?.trim().orEmpty()
            if (text == "•" || text == "|" || text.isEmpty()) break
            if (text != "," && text != "&") {
                artistNames.add(text)
            }
        }
        val rawArtist = if (artistNames.isNotEmpty()) {
            artistNames.joinToString(", ")
        } else {
            artistRuns.firstOrNull()?.let { (it as? JsonObject)?.stringAny("text") }?.trim().orEmpty()
        }

        return normalizeYouTubeTrack(rawTitle, rawArtist, isrc = null)
    }

    private fun unescapeHtml(text: String): String = text
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun tidyWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun extractJsonLdNodes(input: String): List<JsonObject> {
        val trimmed = input.trim()
        val jsonTexts = if (trimmed.contains("<script", ignoreCase = true)) {
            val scriptRegex = Regex("<script[^>]*application/ld\\+json[^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            scriptRegex.findAll(trimmed).map { it.groupValues[1] }.toList()
        } else {
            listOf(trimmed)
        }

        val results = mutableListOf<JsonObject>()
        for (text in jsonTexts) {
            val element = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: continue
            fun collectNodes(elem: JsonElement) {
                when (elem) {
                    is JsonObject -> {
                        val type = elem.stringAny("@type")
                        if (type == "MusicPlaylist" || type == "MusicAlbum") {
                            results.add(elem)
                        } else if (elem.containsKey("@graph")) {
                            elem["@graph"]?.let(::collectNodes)
                        }
                    }
                    is JsonArray -> elem.forEach(::collectNodes)
                    else -> Unit
                }
            }
            collectNodes(element)
        }
        return results
    }

    private fun extractJsonLdTrackElements(node: JsonObject): List<JsonElement> {
        val track = node["track"]
        if (track is JsonArray) return track
        if (track is JsonObject) {
            val list = track["itemListElement"] as? JsonArray
            if (list != null) return list
        }
        val items = node["itemListElement"] as? JsonArray
        if (items != null) return items
        val tracks = node["tracks"] as? JsonArray
        if (tracks != null) return tracks
        return emptyList()
    }

    private fun extractArtistNames(byArtist: JsonElement?): String? {
        if (byArtist == null || byArtist is JsonNull) return null
        val items: List<JsonElement> = when (byArtist) {
            is JsonArray -> byArtist.toList()
            else -> listOf(byArtist)
        }
        val names = items.flatMap { a ->
            val name = when (a) {
                is JsonObject -> a.stringAny("name")
                is JsonPrimitive -> runCatching { a.content }.getOrNull()?.takeUnless { it == "null" && a is JsonNull }
                else -> null
            }
            name?.split(',')?.map { tidyWhitespace(unescapeHtml(it)) }?.filter { it.isNotEmpty() } ?: emptyList()
        }
        return if (names.isEmpty()) null else names.take(2).joinToString(", ")
    }

    /** Offline provider fixtures use the common track-list shape emitted by provider adapters. */
    fun parseProviderFixture(provider: PlaylistImportProvider, fixture: String): ImportedPlaylist {
        require(provider != PlaylistImportProvider.PASTED)
        val root = json.parseToJsonElement(fixture).jsonObject
        val title = root.stringAny("name", "title") ?: "Imported ${provider.name.lowercase().replace('_',' ')} playlist"
        val array = sequenceOf("tracks", "items", "entries").mapNotNull { root[it] as? JsonArray }.firstOrNull() ?: JsonArray(emptyList())
        val entries = array.mapNotNull { item ->
            val obj = when (item) { is JsonObject -> item; else -> (item as? JsonPrimitive)?.content?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } }
                ?: (item as? JsonObject)?.get("track")?.jsonObject ?: return@mapNotNull null
            val trackObj = (obj["track"] as? JsonObject) ?: obj
            val name = trackObj.stringAny("title", "name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val artists = trackObj["artists"] as? JsonArray
            val artist = trackObj.stringAny("artist", "artistName", "uploader") ?: artists?.mapNotNull { (it as? JsonObject)?.stringAny("name") }?.joinToString(", ").orEmpty()
            PlaylistEntry(name, artist, trackObj.stringAny("isrc", "isrcCode")?.uppercase(Locale.ROOT), 0)
        }
        return ImportedPlaylist(title, provider, entries.mapIndexed { i, e -> e.copy(sourceIndex = i) })
    }
    private fun JsonObject.stringAny(vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> this[key]?.takeUnless { it is JsonNull }?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } }
}

object PlaylistImportMatcher {
    fun match(entry: PlaylistEntry, candidates: List<ImportCandidate>): ImportMatchState.Matched? {
        val isrc = entry.isrc?.normalizeIsrc()
        if (isrc != null) candidates.filter { it.isrc?.normalizeIsrc() == isrc }.sortedBy { it.id }.firstOrNull()?.let {
            return ImportMatchState.Matched(it, 1.0, ImportMatchState.Method.ISRC)
        }
        val title = normalize(entry.title)
        val artist = normalize(entry.artist)
        if (title.isBlank() || artist.isBlank()) return null
        val found = candidates.asSequence().filter { normalize(it.title) == title && normalize(it.artist).contains(artist) || normalize(it.title) == title && artist.contains(normalize(it.artist)) }
            .sortedBy { it.id }.firstOrNull() ?: return null
        return ImportMatchState.Matched(found, 0.92, ImportMatchState.Method.ARTIST_TITLE)
    }
    fun resolve(entry: PlaylistEntry, candidates: List<ImportCandidate>): ImportMatchState = match(entry, candidates)?.takeIf { it.confidence >= 0.85 } ?: ImportMatchState.Unmatched
    fun manualResolution(entry: PlaylistEntry, candidateId: String, candidates: List<ImportCandidate>, confirmed: Boolean): ImportMatchState {
        if (!confirmed) return ImportMatchState.Unmatched
        return candidates.firstOrNull { it.id == candidateId }?.let(ImportMatchState::ManualConfirmed) ?: ImportMatchState.Unmatched
    }

    /**
     * Resolves a [PlaylistEntry] against candidates and an optional external ISRC resolver (such as [com.music.bitchord.data.sources.addon.AddonIsrcResolver]).
     *
     * When [isrcResolver] is supplied and [entry] has an ISRC, queries the resolver first.
     * If resolved, returns [ImportMatchState.Matched] with confidence 1.0 and method [ImportMatchState.Method.ISRC].
     * Otherwise falls back to candidate / title / artist matching.
     * Note: References only; does not create streaming playback.
     */
    suspend fun resolveWithIsrc(
        entry: PlaylistEntry,
        candidates: List<ImportCandidate> = emptyList(),
        isrcResolver: (suspend (String) -> String?)? = null,
    ): ImportMatchState {
        val normalizedIsrc = entry.isrc?.normalizeIsrc()
        if (normalizedIsrc != null && isrcResolver != null) {
            val resolvedId = try {
                isrcResolver(normalizedIsrc)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!resolvedId.isNullOrBlank()) {
                val candidate = candidates.firstOrNull { it.id == resolvedId }
                    ?: ImportCandidate(id = resolvedId, title = entry.title, artist = entry.artist, isrc = normalizedIsrc)
                return ImportMatchState.Matched(candidate, 1.0, ImportMatchState.Method.ISRC)
            }
        }
        return resolve(entry, candidates)
    }

    /**
     * Resolves playlist [entries] against an [com.music.bitchord.data.sources.addon.AddonIsrcResolver], matching tracks by ISRC where available
     * and falling back to [candidates]. References only; does not create streaming playback.
     */
    suspend fun resolveAllWithAddon(
        entries: List<PlaylistEntry>,
        candidates: List<ImportCandidate> = emptyList(),
        addonResolver: com.music.bitchord.data.sources.addon.AddonIsrcResolver,
    ): List<ImportMatchState> {
        return entries.map { entry ->
            resolveWithIsrc(entry, candidates) { isrc ->
                addonResolver.resolveIsrc(isrc).getOrNull()
            }
        }
    }
}

private fun String.normalizeIsrc() = filter(Char::isLetterOrDigit).uppercase(Locale.ROOT).takeIf { it.length == 12 }
private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
