package app.soiboi.engine.library

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.download.PipelineRunner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.time.Instant

private val audioExtensions = setOf(
    "mp2", "mp3", "flac", "m4a", "m4b", "m4r", "mp4", "aac", "wav", "aiff", "aif", "ogg", "opus", "ape", "mpc", "wv", "spx",
)

/** Reads tags through the pipeline's `read_tags` command, so both platforms use the same mutagen. */
class TagReader(private val runner: PipelineRunner) {
    suspend fun read(paths: List<String>): List<Song> {
        if (paths.isEmpty()) return emptyList()
        EngineLog.output("read_tags: ${paths.size} files")
        val payload = buildJsonObject { put("paths", JsonArray(paths.map(::JsonPrimitive))) }
        var songs = emptyList<Song>()
        runner.run("read_tags", payload).collect { e ->
            if (e.isDone) songs = (e.raw["tracks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::songFrom) }
            else if (e.isError) EngineLog.output("read_tags failed: ${e.code} ${e.message}")
        }
        return songs
    }

    private fun songFrom(o: JsonObject): Song? {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun l(k: String) = (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()
        val path = s("path") ?: return null
        return Song(
            id = path, path = path, modified = l("modified")?.let(Instant::ofEpochMilli), format = s("format"),
            title = s("title"), artist = s("artist"), album = s("album"), albumArtist = s("album_artist"), genre = s("genre"),
            year = l("year")?.toInt(), track = l("track")?.toInt(), disc = l("disc")?.toInt(),
            bitrate = l("bitrate")?.toInt(), samplerate = l("samplerate")?.toInt(), durationMs = l("duration_ms"), lyrics = s("lyrics"),
        )
    }
}

/** Where the analyser keeps sidecars it could not write beside the audio. Must match `private_sidecar_path` in acoustic.py. */
class AcousticSidecars(private val storeDir: File) {
    private fun sidecarFor(audio: String): File? {
        val beside = File("$audio.soiboi-acoustic.json")
        if (beside.exists()) return beside
        val digest = MessageDigest.getInstance("SHA-1").digest(audio.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(storeDir, "$digest.json").takeIf { it.exists() }
    }

    /** [song] with its acoustic features filled in from the sidecar, if one exists. A corrupt sidecar never drops a track. */
    fun load(song: Song): Song {
        val path = song.path ?: return song
        val file = sidecarFor(path) ?: return song
        return try {
            val o = app.soiboi.engine.sources.Http.json.parseToJsonElement(file.readText()) as? JsonObject ?: return song
            fun d(k: String) = (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
            song.copy(
                energy = d("energy"), danceable = d("danceable"), brightness = d("brightness"),
                aggressive = d("aggressive"), relaxed = d("relaxed"), bpm = d("bpm"),
                musicalKey = (o["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            )
        } catch (_: Exception) {
            song
        }
    }
}

/**
 * Walks the registered folders and keeps the local song table in step. A file whose modified time
 * has not moved keeps its stored tags; only new or changed files are read again.
 */
class FolderScanner(
    private val songs: SongStore,
    private val db: Database,
    private val tags: TagReader,
    private val sidecars: AcousticSidecars,
    /** The archive folder is scanned recursively whatever the preference: the downloader writes Artist/Album/NN Title.m4a. */
    private val alwaysRecursive: () -> String,
    private val recursive: () -> Boolean,
) {
    suspend fun folders(): List<String> = db.run { query("SELECT path FROM folders ORDER BY position") { it.text(0)!! } }

    suspend fun setFolders(paths: List<String>) = db.transaction {
        exec("DELETE FROM folders")
        batch<IndexedValue<String>>("INSERT INTO folders(path, position) VALUES(?,?)", paths.withIndex()) { arrayOf<Any?>(it.value, it.index) }
    }

    suspend fun addFolder(path: String) {
        val current = folders()
        if (path !in current) setFolders(current + path)
    }

    /** Scans every folder; returns the songs now in the library. */
    suspend fun sync(onProgress: (Int) -> Unit = {}): List<Song> {
        val known = songs.all(SongOrigin.Local).associateBy { it.id }
        val found = LinkedHashMap<String, Long>()
        for (folder in folders()) {
            val root = File(folder)
            if (!root.isDirectory) continue
            val walk = if (recursive() || folder == alwaysRecursive()) root.walkTopDown() else root.walkTopDown().maxDepth(1)
            walk.filter { it.isFile && it.extension.lowercase() in audioExtensions }.forEach { found[it.path] = it.lastModified() }
        }
        EngineLog.output("scan: ${found.size} audio files in ${folders().size} folders")
        val stale = found.filter { (path, mod) -> known[path]?.modified?.toEpochMilli()?.let { kotlin.math.abs(it - mod) > 1000 } != false }.keys.toList()
        val fresh = ArrayList<Song>()
        for ((i, chunk) in stale.chunked(200).withIndex()) {
            fresh += tags.read(chunk).map { prior -> sidecars.load(prior.copy(playCount = known[prior.id]?.playCount ?: 0, lastPlayed = known[prior.id]?.lastPlayed)) }
            onProgress(minOf((i + 1) * 200, stale.size))
        }
        // A track whose file is unchanged may still have gained a sidecar since the last scan.
        val unchanged = found.keys.filter { it !in stale && known[it] != null }.map { known[it]!! }.map {
            if (it.energy == null && it.path != null) sidecars.load(it) else it
        }
        songs.replaceAll(SongOrigin.Local, fresh + unchanged)
        return fresh + unchanged
    }
}
