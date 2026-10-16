package app.soiboi.engine.ai

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Database
import app.soiboi.engine.library.Song
import app.soiboi.engine.sources.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Where a tagging run stands, for the panel to show. */
data class TagProgress(val done: Int, val total: Int)

/** Tracks per request: large enough that a library takes tens of requests, small enough to stay inside a free tier's limits. */
private const val BATCH_SIZE = 40

val TagSystemPrompt = "You tag songs by how they feel. For each numbered song, use what you know about the actual recording; judge from the title and " +
    "artist, not from guesses about the genre. Reply with JSON only, no prose, in exactly this shape: {\"songs\": [{\"n\": 0, \"m\": [\"calm\", \"warm\"], " +
    "\"v\": \"high\", \"e\": \"low\", \"l\": \"hindi\"}]}. \"m\" is one to three moods chosen only from: ${VibeMoods.joinToString(", ")}. " +
    "\"v\" is how positive the song feels and \"e\" how energetic, each one of low, mid, high. A quiet, slow song can be happy (v high, e low) or sad " +
    "(v low, e low); do not treat them alike. \"l\" is the language of the vocals in lower case, or \"instrumental\", or \"unknown\" if you are not sure. " +
    "Include every song, in order."

/**
 * What each song feels like, asked of the AI once and kept on the device. The acoustic analyser can
 * say a track is quiet and slow but not whether it is happy or sad; a language model can. So the
 * model tags each track once, and smart playlists, the Home mood shelf and AI candidate picking all
 * read the saved tags locally for free. Only title, artist, album and genre are sent.
 */
class SongTags(private val db: Database) {
    private val byId = HashMap<String, SongVibe>()
    private val _count = MutableStateFlow(0)
    /** Bumps whenever tags change, so shelves and playlists can rebuild. */
    val version: StateFlow<Int> = _count.asStateFlow()
    private val _progress = MutableStateFlow<TagProgress?>(null)
    val progress: StateFlow<TagProgress?> = _progress.asStateFlow()
    @Volatile private var cancelled = false

    fun of(song: Song): SongVibe? = byId[song.id]
    val count: Int get() = byId.size

    suspend fun load() {
        try {
            val rows = db.run { query("SELECT key, value FROM kv WHERE key LIKE 'aitag:%'") { it.text(0)!!.removePrefix("aitag:") to it.text(1)!! } }
            for ((id, json) in rows) SongVibe.fromJson(Http.json.parseToJsonElement(json))?.let { byId[id] = it }
            _count.value++
        } catch (e: Exception) {
            EngineLog.output("ai tags: $e") // A corrupt entry costs the tags, which can be asked for again.
        }
    }

    private suspend fun save(ids: Collection<String>) = db.transaction {
        batch("INSERT OR REPLACE INTO kv(key,value) VALUES(?,?)", ids.mapNotNull { id -> byId[id]?.let { id to it } }) { arrayOf("aitag:${it.first}", it.second.toJson().toString()) }
    }

    /** Share of [songs] that carry a tag, 0 to 1. */
    fun coverage(songs: List<Song>): Double = if (songs.isEmpty()) 0.0 else songs.count { it.id in byId }.toDouble() / songs.size

    suspend fun clear() {
        byId.clear()
        db.run { exec("DELETE FROM kv WHERE key LIKE 'aitag:%'") }
        _count.value++
    }

    fun cancel() { cancelled = true }

    /**
     * Tags every song that has none yet, saving after each batch so a run that stops halfway keeps
     * what it finished. Returns how many songs it tagged. Throws [AiException] when the very first
     * batch fails, so a bad key shows as one clear message; a later failure ends the run quietly.
     */
    suspend fun tagLibrary(config: AiConfig?, songs: List<Song>): Int {
        if (config == null) throw AiException("AI is not set up yet")
        val todo = songs.filter { byId[it.id] == null && !it.title.isNullOrBlank() }
        if (todo.isEmpty()) return 0
        cancelled = false
        var tagged = 0
        _progress.value = TagProgress(0, todo.size)
        try {
            for ((start, batch) in todo.chunked(BATCH_SIZE).withIndex().map { it.index * BATCH_SIZE to it.value }) {
                if (cancelled) break
                try { tagged += tagBatch(config, batch) } catch (e: AiException) { EngineLog.output("ai tags: batch at $start failed: ${e.message}"); if (tagged == 0 && start == 0) throw e else break }
                _count.value++
                _progress.value = TagProgress(minOf(start + batch.size, todo.size), todo.size)
            }
        } finally {
            _progress.value = null
        }
        return tagged
    }

    private suspend fun tagBatch(config: AiConfig, batch: List<Song>): Int {
        fun clean(v: String?) = (v ?: "").replace('|', '/').trim()
        val lines = batch.withIndex().joinToString("\n") { (i, s) -> "$i | ${clean(s.title)} | ${clean(s.artist)} | ${clean(s.album)} | ${clean(s.genre)}" }
        val answer = aiComplete(config, TagSystemPrompt, "Songs (number | title | artist | album | genre):\n$lines")
        val ids = batch.map { it.id }
        val saved = applyTagAnswer(answer, ids)
        EngineLog.output("ai tags: ${config.provider} ${config.effectiveModel} tagged $saved of ${ids.size}")
        if (saved == 0) {
            EngineLog.output("ai tags: unreadable answer: ${answer.take(600)}")
            throw AiException("The model answered, but not in a form Soiboi could read. It said: ${answer.trim().take(160)}")
        }
        save(ids)
        return saved
    }

    /** Saves the tags in [answer], where entry `n` is an index into [ids]. Anything the model got wrong is skipped. */
    fun applyTagAnswer(answer: String, ids: List<String>): Int {
        val json = extractJsonObject(answer)
        var saved = 0
        for (entry in (json["songs"] as? JsonArray).orEmpty()) {
            val o = entry as? JsonObject ?: continue
            val n = (o["n"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: continue
            if (n < 0 || n >= ids.size) continue
            val vibe = SongVibe.fromJson(o) ?: continue
            byId[ids[n]] = vibe; saved++
        }
        return saved
    }
}
