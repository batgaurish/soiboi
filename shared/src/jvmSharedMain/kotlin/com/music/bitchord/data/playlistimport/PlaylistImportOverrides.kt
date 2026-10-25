package com.music.bitchord.data.playlistimport

import com.music.bitchord.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared key-value persistence contract for manual match overrides across sessions.
 * Follows the existing key-value contract used by InnerTubeXResolver.ConfigStore.
 */
interface PlaylistImportStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

/**
 * Serialized manual override entity for a source track.
 */
@Serializable
data class StoredImportOverride(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val isExplicit: Boolean? = null,
    val isVideo: Boolean = false,
    val isrc: String? = null,
    val isUnmatched: Boolean = false,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun toRowState(entry: PlaylistEntry): ImportRowState {
        return if (isUnmatched || videoId.isBlank()) {
            ImportRowState(
                entry = entry,
                matchState = ImportMatchState.Unmatched,
                candidateSong = null,
                availableCandidates = emptyList(),
            )
        } else {
            val candidate = ImportCandidate(
                id = videoId,
                title = title,
                artist = artist,
                isrc = isrc,
            )
            val song = Song(
                videoId = videoId,
                title = title,
                artist = artist,
                thumbnailUrl = thumbnailUrl,
                durationText = durationText,
                albumName = albumName,
                isExplicit = isExplicit,
                isVideo = isVideo,
            )
            ImportRowState(
                entry = entry,
                matchState = ImportMatchState.ManualConfirmed(candidate),
                candidateSong = song,
                availableCandidates = listOf(song),
            )
        }
    }
}

/**
 * Manages cross-session persistence of manual match selections.
 * Keys entries deterministically by ISRC (when available) and normalized artist + title metadata.
 */
object PlaylistImportOverrides {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val inMemory = ConcurrentHashMap<String, StoredImportOverride>()

    @Volatile
    private var persistentStore: PlaylistImportStore? = null

    fun installStore(store: PlaylistImportStore) {
        persistentStore = store
    }

    fun isrcKey(isrc: String): String {
        val clean = isrc.filter(Char::isLetterOrDigit).uppercase(Locale.ROOT)
        return "import_override_isrc_$clean"
    }

    fun metadataKey(artist: String, title: String): String {
        val cleanArtist = normalize(artist)
        val cleanTitle = normalize(title)
        return "import_override_meta_${cleanArtist}__${cleanTitle}"
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "_")
        .trim('_')

    /**
     * Looks up a persistent manual override for [entry].
     * Checks ISRC first if present, then falls back to normalized metadata.
     */
    fun getOverride(entry: PlaylistEntry): StoredImportOverride? {
        val isrc = entry.isrc?.trim()?.takeIf(String::isNotEmpty)
        if (isrc != null) {
            val key = isrcKey(isrc)
            inMemory[key]?.let { return it }
            val stored = runCatching { persistentStore?.getString(key) }.getOrNull()
            if (stored != null) {
                runCatching { json.decodeFromString<StoredImportOverride>(stored) }.getOrNull()?.let {
                    inMemory[key] = it
                    return it
                }
            }
        }

        val metaKey = metadataKey(entry.artist, entry.title)
        inMemory[metaKey]?.let { return it }
        val storedMeta = runCatching { persistentStore?.getString(metaKey) }.getOrNull()
        if (storedMeta != null) {
            runCatching { json.decodeFromString<StoredImportOverride>(storedMeta) }.getOrNull()?.let {
                inMemory[metaKey] = it
                return it
            }
        }

        return null
    }

    /**
     * Persists a manual match selection for [entry] to candidate [candidate].
     */
    fun saveOverride(entry: PlaylistEntry, candidate: Song) {
        val override = StoredImportOverride(
            videoId = candidate.videoId,
            title = candidate.title,
            artist = candidate.artist,
            thumbnailUrl = candidate.thumbnailUrl,
            durationText = candidate.durationText,
            albumName = candidate.albumName,
            isExplicit = candidate.isExplicit,
            isVideo = candidate.isVideo,
            isrc = entry.isrc,
            isUnmatched = false,
        )
        val serialized = json.encodeToString(override)

        val metaKey = metadataKey(entry.artist, entry.title)
        inMemory[metaKey] = override
        runCatching { persistentStore?.putString(metaKey, serialized) }

        val isrc = entry.isrc?.trim()?.takeIf(String::isNotEmpty)
        if (isrc != null) {
            val iKey = isrcKey(isrc)
            inMemory[iKey] = override
            runCatching { persistentStore?.putString(iKey, serialized) }
        }
    }

    /**
     * Persists an explicit decision to leave [entry] unmatched / skipped.
     */
    fun saveUnmatched(entry: PlaylistEntry) {
        val override = StoredImportOverride(
            videoId = "",
            title = entry.title,
            artist = entry.artist,
            isrc = entry.isrc,
            isUnmatched = true,
        )
        val serialized = json.encodeToString(override)

        val metaKey = metadataKey(entry.artist, entry.title)
        inMemory[metaKey] = override
        runCatching { persistentStore?.putString(metaKey, serialized) }

        val isrc = entry.isrc?.trim()?.takeIf(String::isNotEmpty)
        if (isrc != null) {
            val iKey = isrcKey(isrc)
            inMemory[iKey] = override
            runCatching { persistentStore?.putString(iKey, serialized) }
        }
    }

    /**
     * Removes any stored manual override for [entry].
     */
    fun removeOverride(entry: PlaylistEntry) {
        val metaKey = metadataKey(entry.artist, entry.title)
        inMemory.remove(metaKey)
        runCatching { persistentStore?.remove(metaKey) }

        val isrc = entry.isrc?.trim()?.takeIf(String::isNotEmpty)
        if (isrc != null) {
            val iKey = isrcKey(isrc)
            inMemory.remove(iKey)
            runCatching { persistentStore?.remove(iKey) }
        }
    }

    fun clearInMemory() {
        inMemory.clear()
    }
}
