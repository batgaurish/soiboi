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
    fun parse(text: String, title: String = "Imported playlist"): ImportedPlaylist {
        val entries = text.lineSequence().mapNotNull { line ->
            val cleaned = line.trim().removePrefix("- ").removePrefix("* ")
            if (cleaned.isBlank() || cleaned.startsWith("#")) null else {
                val fields = cleaned.split('\t')
                val (track, artist) = if (fields.size >= 2) fields[0].trim() to fields[1].trim() else {
                    val split = cleaned.split(Regex("\\s+[–—-]\\s+"), limit = 2)
                    split[0].trim() to split.getOrElse(1) { "" }.trim()
                }
                if (track.equals("title", true) && artist.equals("artist", true)) null else track.takeIf { it.isNotBlank() }?.let { it to artist }
            }
        }.mapIndexed { index, pair -> PlaylistEntry(pair.first, pair.second, null, index) }.toList()
        return ImportedPlaylist(title, PlaylistImportProvider.PASTED, entries)
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
    private fun String.normalizeIsrc() = filter(Char::isLetterOrDigit).uppercase(Locale.ROOT).takeIf { it.length == 12 }
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
