package app.soiboi.engine.discovery

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.core.ownedSongKey
import app.soiboi.engine.library.Database
import app.soiboi.engine.sources.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant

/** The override meaning "there is no right match; stop asking". */
const val SKIP_OVERRIDE = "SKIP"

/** The same song whatever the source's spelling of credits and "(From ...)". */
fun overrideKey(artist: String, title: String) = ownedSongKey(artist, title)

/** A playlist track with no catalog match, waiting for the user's choice. */
@Serializable
data class UnresolvedTrack(
    val artist: String, val title: String,
    /** The playlist it was archived from: the download joins that group, and the pick replaces this track in the linked local playlist. */
    val playlist: String,
    val added: String = Instant.now().toString(),
) {
    val key: String get() = overrideKey(artist, title)
}

@Serializable
data class PickedSong(val artist: String, val title: String)

/**
 * Tracks automatic matching could not place, and the choices people made. Archiving a playlist adds its
 * unmatched tracks to a list where the user can pick the right song, paste a link, or skip. A pick or a
 * skip is kept for good (an override), so the same track never asks twice, and discovery consults
 * overrides before searching at all.
 */
class ManualResolution(private val db: Database) {
    private val _unresolved = MutableStateFlow<List<UnresolvedTrack>>(emptyList())
    /** What still needs a choice, oldest first. */
    val unresolved: StateFlow<List<UnresolvedTrack>> = _unresolved.asStateFlow()

    private val overrides = HashMap<String, String>()
    private val picks = HashMap<String, PickedSong>()

    suspend fun load() {
        try {
            db.getKv("manual:overrides")?.let { overrides.putAll(Http.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it)) }
            db.getKv("manual:picks")?.let { picks.putAll(Http.json.decodeFromString(MapSerializer(String.serializer(), PickedSong.serializer()), it)) }
            db.getKv("manual:unresolved")?.let { _unresolved.value = Http.json.decodeFromString(ListSerializer(UnresolvedTrack.serializer()), it) }
        } catch (e: Exception) {
            EngineLog.output("manual resolution: read failed: $e") // A corrupt entry costs the list, never the app.
        }
    }

    private suspend fun save() {
        try {
            db.putKv("manual:overrides", Http.json.encodeToString(MapSerializer(String.serializer(), String.serializer()), overrides))
            db.putKv("manual:picks", Http.json.encodeToString(MapSerializer(String.serializer(), PickedSong.serializer()), picks))
            db.putKv("manual:unresolved", Http.json.encodeToString(ListSerializer(UnresolvedTrack.serializer()), _unresolved.value))
        } catch (e: Exception) {
            EngineLog.output("manual resolution: write failed: $e")
        }
    }

    /** The user's choice for this song: an Apple URL, [SKIP_OVERRIDE], or null when they never made one. */
    fun overrideFor(artist: String, title: String): String? = overrides[overrideKey(artist, title)]

    /** The song the user picked for this one, when artist or title differ (a cover, a remix), so a linked playlist can list what will be in the library. */
    fun pickedAs(artist: String, title: String): PickedSong? = picks[overrideKey(artist, title)]

    /** Adds tracks archiving could not match. Ones already listed, or already decided, are left alone. */
    suspend fun addUnresolved(tracks: Iterable<UnresolvedTrack>) {
        val list = _unresolved.value.toMutableList()
        val keys = list.map { it.key }.toHashSet()
        var changed = false
        for (t in tracks) {
            if (t.artist.isBlank() || t.title.isBlank()) continue
            if (overrides.containsKey(t.key) || !keys.add(t.key)) continue
            list += t; changed = true
        }
        if (!changed) return
        _unresolved.value = list
        save()
    }

    /** Remembers [url] as the song for [track] and takes it off the list. [artist] and [title] are the picked song's, when known. */
    suspend fun resolve(track: UnresolvedTrack, url: String, artist: String? = null, title: String? = null) {
        if (artist != null && title != null) picks[track.key] = PickedSong(artist, title)
        decide(track, url)
    }

    /** Remembers that [track] has no right match and takes it off the list. */
    suspend fun skip(track: UnresolvedTrack) { picks.remove(track.key); decide(track, SKIP_OVERRIDE) }

    private suspend fun decide(track: UnresolvedTrack, choice: String) {
        overrides[track.key] = choice
        _unresolved.value = _unresolved.value.filter { it.key != track.key }
        save()
    }
}
