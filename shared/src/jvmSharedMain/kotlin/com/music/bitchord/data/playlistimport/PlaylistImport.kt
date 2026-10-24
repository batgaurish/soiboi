package com.music.bitchord.data.playlistimport

import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

/** Imported entries are references only; they never introduce a playback source. */
enum class PlaylistImportProvider { PASTED, SPOTIFY, DEEZER, YOUTUBE_MUSIC, TIDAL, SOUNDCLOUD, JIOSAAVN, QOBUZ, GAANA, BANDCAMP }

data class PlaylistEntry(val title: String, val artist: String, val isrc: String? = null, val sourceIndex: Int)
data class ImportedPlaylist(val title: String, val provider: PlaylistImportProvider, val entries: List<PlaylistEntry>)
data class ImportCandidate(val id: String, val title: String, val artist: String, val isrc: String? = null)

sealed interface ImportMatchState {
    data class Matched(val candidate: ImportCandidate, val confidence: Double, val method: Method) : ImportMatchState
    data object Unmatched : ImportMatchState
    data class ManualConfirmed(val candidate: ImportCandidate) : ImportMatchState
    enum class Method { ISRC, ARTIST_TITLE }
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
        val tracks = (root["tracks"] as? JsonObject)?.get("data") as? JsonArray ?: JsonArray(emptyList())
        val entries = tracks.mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val name = track.stringAny("title")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val artist = (track["artist"] as? JsonObject)?.stringAny("name")?.trim()?.takeIf(String::isNotEmpty)
                ?: track.stringAny("artistName")?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val isrc = track.stringAny("isrc")?.trim()?.takeIf(String::isNotEmpty)?.uppercase(Locale.ROOT)
            PlaylistEntry(name, artist, isrc, 0)
        }
        return ImportedPlaylist(title, PlaylistImportProvider.DEEZER, entries.mapIndexed { index, entry -> entry.copy(sourceIndex = index) })
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
    private fun JsonObject.stringAny(vararg keys: String): String? = keys.firstNotNullOfOrNull { key -> this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } }
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
}

private fun String.normalizeIsrc() = filter(Char::isLetterOrDigit).uppercase(Locale.ROOT).takeIf { it.length == 12 }
private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
