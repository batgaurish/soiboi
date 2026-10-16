package app.soiboi.engine.addons

import app.soiboi.engine.library.Database
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
private data class SavedAddon(val url: String, val manifest: String, val enabled: Boolean = true, val settings: Map<String, String> = emptyMap())

/** One installed addon: where it lives, what it said it could do, and the person's settings for it. */
class AddonInstance(val url: String, val manifest: AddonManifest, val enabled: Boolean, val settings: Map<String, String>, val builtin: BuiltinProvider? = null) {
    val client: CatalogClient by lazy { builtin?.create(settings) ?: AddonClient(url, settings) }
    fun with(enabled: Boolean = this.enabled, settings: Map<String, String> = this.settings) = AddonInstance(url, manifest, enabled, settings, builtin)
    val id get() = manifest.id

    /** The setting's value as the person set it, or the addon's own default. */
    fun setting(def: AddonSettingDef) = settings[def.key] ?: def.default
}

/** A source that ships inside Soiboi but is listed, switched and searched like an installed addon. */
class BuiltinProvider(val manifest: AddonManifest, val create: (settings: Map<String, String>) -> CatalogClient, val enabledByDefault: Boolean = true)

/** What one addon answered to a search: its results, or why it could not. */
class AddonHit(val addon: AddonInstance, val result: AddonSearchResult?, val error: String?)

private const val KEY = "addons.installed"
private const val BUILTIN_KEY = "addons.builtin"

@Serializable
private data class SavedBuiltin(val id: String, val enabled: Boolean, val settings: Map<String, String> = emptyMap())

/** The addons a person has installed, kept in the library database, and the plumbing that turns their answers into songs. */
class AddonRegistry(private val db: Database, private val builtins: List<BuiltinProvider> = emptyList(), private val clientFor: (String) -> AddonClient = { AddonClient(it) }) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _installed = MutableStateFlow<List<AddonInstance>>(emptyList())
    val installed: StateFlow<List<AddonInstance>> = _installed.asStateFlow()

    suspend fun load() {
        val saved = db.getKv(KEY)?.let { runCatching { json.decodeFromString(ListSerializer(SavedAddon.serializer()), it) }.getOrNull() }.orEmpty()
        val builtinState = db.getKv(BUILTIN_KEY)?.let { runCatching { json.decodeFromString(ListSerializer(SavedBuiltin.serializer()), it) }.getOrNull() }.orEmpty().associateBy { it.id }
        val builtInstances = builtins.map { b -> val st = builtinState[b.manifest.id]; AddonInstance("builtin:${b.manifest.id}", b.manifest, st?.enabled ?: b.enabledByDefault, st?.settings.orEmpty(), b) }
        _installed.value = builtInstances + saved.mapNotNull { s ->
            val manifest = runCatching { parseManifest(json.parseToJsonElement(s.manifest) as JsonObject) }.getOrNull() ?: return@mapNotNull null
            raws[manifest.id] = s.manifest
            AddonInstance(s.url, manifest, s.enabled, s.settings)
        }
    }

    private val raws = HashMap<String, String>()

    private suspend fun persist() {
        val list = _installed.value.filter { it.builtin == null }.map { SavedAddon(it.url, raws[it.id] ?: rawFor(it), it.enabled, it.settings) }
        db.putKv(KEY, json.encodeToString(ListSerializer(SavedAddon.serializer()), list))
        val built = _installed.value.filter { it.builtin != null }.map { SavedBuiltin(it.id, it.enabled, it.settings) }
        db.putKv(BUILTIN_KEY, json.encodeToString(ListSerializer(SavedBuiltin.serializer()), built))
    }

    /** A manifest read back from storage has no raw copy in memory; rebuild the fields the rest of the app reads. */
    private fun rawFor(a: AddonInstance): String = kotlinx.serialization.json.buildJsonObject {
        put("id", kotlinx.serialization.json.JsonPrimitive(a.manifest.id)); put("name", kotlinx.serialization.json.JsonPrimitive(a.manifest.name))
        put("version", kotlinx.serialization.json.JsonPrimitive(a.manifest.version)); put("description", kotlinx.serialization.json.JsonPrimitive(a.manifest.description))
        put("resources", kotlinx.serialization.json.JsonArray(a.manifest.resources.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }.toString()

    /** Fetches the manifest at [url] without installing, for the confirmation step. Throws [AddonException] with a sentence a person can act on. */
    suspend fun preview(url: String): Pair<AddonManifest, String> = clientFor(url).manifestWithRaw()

    suspend fun install(url: String): AddonInstance {
        val (manifest, raw) = preview(url)
        if (!manifest.canSearch && !manifest.canStream && !manifest.hasCatalogs) throw AddonException("${manifest.name} offers nothing Soiboi can use: no search, streams or catalogs.")
        val instance = AddonInstance(AddonClient.normalise(url), manifest, true, emptyMap())
        raws[manifest.id] = raw
        _installed.update { list -> list.filter { it.id != manifest.id } + instance }
        persist()
        return instance
    }

    suspend fun remove(id: String) { if (instance(id)?.builtin != null) return; _installed.update { l -> l.filter { it.id != id } }; raws.remove(id); persist() }

    suspend fun setEnabled(id: String, enabled: Boolean) { replace(id) { it.with(enabled = enabled) }; persist() }

    suspend fun setSetting(id: String, key: String, value: String) { replace(id) { it.with(settings = it.settings + (key to value)) }; persist() }

    private fun replace(id: String, change: (AddonInstance) -> AddonInstance) = _installed.update { l -> l.map { if (it.id == id) change(it) else it } }

    fun instance(id: String): AddonInstance? = _installed.value.firstOrNull { it.id == id }

    /** Asks every enabled addon that can search, at once. One slow or broken addon never holds up the rest. */
    suspend fun search(query: String): List<AddonHit> = coroutineScope {
        _installed.value.filter { it.enabled && it.manifest.canSearch }.map { a ->
            async {
                try { AddonHit(a, a.client.search(query), null) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { AddonHit(a, null, e.message ?: "It did not answer.") }
            }
        }.awaitAll()
    }

    fun songFor(addon: AddonInstance, t: AddonTrack) = Song(
        id = "addon:${addon.id}:${t.id}", origin = SongOrigin.Addon, path = t.streamUrl, format = t.format,
        title = t.title, artist = t.artist, album = t.album, durationMs = t.durationMs, coverId = t.artworkUrl,
    )

    /** The playable URL for an addon [song]: the one the search result carried, or a fresh answer from `/stream`. */
    suspend fun streamFor(song: Song): Pair<String, Map<String, String>> {
        song.path?.takeIf { it.startsWith("http") }?.let { return it to emptyMap() }
        val (addonId, trackId) = split(song.id) ?: throw AddonException("That track no longer belongs to an installed addon.")
        val addon = instance(addonId) ?: throw AddonException("The addon for this track was removed.")
        val s = addon.client.stream(trackId)
        return s.url to s.headers
    }

    private fun split(songId: String): Pair<String, String>? {
        val rest = songId.removePrefix("addon:").takeIf { songId.startsWith("addon:") } ?: return null
        val i = rest.indexOf(':'); if (i <= 0) return null
        return rest.substring(0, i) to rest.substring(i + 1)
    }
}
