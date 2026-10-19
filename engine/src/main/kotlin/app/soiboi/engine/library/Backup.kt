package app.soiboi.engine.library

import app.soiboi.engine.core.Settings
import app.soiboi.engine.core.SettingsStore
import app.soiboi.engine.core.ownedSongKey
import app.soiboi.engine.smart.SmartPlaylist
import app.soiboi.engine.sources.Http
import app.soiboi.engine.sources.arr
import app.soiboi.engine.sources.get
import app.soiboi.engine.sources.obj
import app.soiboi.engine.sources.str
import app.soiboi.engine.sources.int
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

private const val BACKUP_VERSION = 2

/**
 * How a library is organised, as one JSON file the user controls: settings, playlists, smart playlists, the matches they
 * chose and the linked tracklists. Never audio and never secrets. A playlist stores each song as its path plus artist and
 * title, so restoring on another device matches by name when the paths no longer exist.
 */
class Backup(private val db: Database, private val library: Library, private val playlists: PlaylistStore, private val settings: SettingsStore, private val smart: () -> List<SmartPlaylist>) {
    private val json = Json2

    suspend fun export(target: File) {
        val kv = db.run { query("SELECT key, value FROM kv WHERE key LIKE 'manual:%' OR key LIKE 'linked:%' OR key = 'smart:playlists'") { it.text(0)!! to it.text(1)!! } }
        val doc = buildJsonObject {
            put("version", BACKUP_VERSION); put("exportedAt", Instant.now().toString())
            put("settings", json.parseToJsonElement(json.encodeToString(Settings.serializer(), settings.value)))
            put("playlists", buildJsonArray {
                for (p in playlists.playlists.value) add(buildJsonObject {
                    put("name", p.name); put("pinned", p.pinned)
                    put("songs", buildJsonArray { p.songIds.forEach { id -> add(buildJsonObject { put("id", id); library.song(id)?.let { s -> put("artist", s.artist ?: ""); put("title", s.title ?: "") } }) } })
                })
            })
            put("smart", json.parseToJsonElement(json.encodeToString(ListSerializer(SmartPlaylist.serializer()), smart())))
            put("kv", buildJsonObject { kv.forEach { (k, v) -> put(k, v) } })
        }
        target.parentFile?.mkdirs()
        target.writeText(doc.toString())
    }

    /** Restores from [source]. Returns false when the file is not a Soiboi backup. */
    suspend fun import(source: File): Boolean {
        val doc = try { json.parseToJsonElement(source.readText()).obj() } catch (_: Exception) { null } ?: return false
        if (doc["version"].int() == null || doc["playlists"].arr() == null) return false
        doc["settings"]?.let { runCatching { json.decodeFromString(Settings.serializer(), it.toString()) }.getOrNull() }?.let { restored -> settings.update { restored } }
        val byKey = library.songs.value.filter { !it.title.isNullOrEmpty() }.associateBy { ownedSongKey(it.artist ?: "", it.title!!) }
        for (p in doc["playlists"].arr().orEmpty().mapNotNull { it.obj() }) {
            val name = p["name"].str() ?: continue
            if (playlists.byName(name) == null) playlists.create(name)
            val ids = p["songs"].arr().orEmpty().mapNotNull { it.obj() }.mapNotNull { s ->
                s["id"].str()?.takeIf { library.song(it) != null } ?: byKey[ownedSongKey(s["artist"].str() ?: "", s["title"].str() ?: "")]?.id
            }
            if (name == FAVORITE || ids.isNotEmpty()) playlists.setSongs(name, ids)
            if (p["pinned"]?.let { (it as? JsonPrimitive)?.content } == "true") playlists.setPinned(name, true)
        }
        for ((k, v) in (doc["kv"] as? JsonObject).orEmpty()) (v as? JsonPrimitive)?.takeIf { it.isString }?.let { db.putKv(k, it.content) }
        return true
    }
}

private object Json2 {
    private val j = Http.json
    fun parseToJsonElement(s: String): JsonElement = j.parseToJsonElement(s)
    fun <T> encodeToString(ser: kotlinx.serialization.SerializationStrategy<T>, v: T) = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(ser, v)
    fun <T> decodeFromString(ser: kotlinx.serialization.DeserializationStrategy<T>, s: String): T = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(ser, s)
}
