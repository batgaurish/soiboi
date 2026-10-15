package app.soiboi.engine.library

import app.soiboi.engine.download.PipelineRunner
import app.soiboi.engine.servers.MusicServer
import app.soiboi.engine.servers.downloadTo
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover art for songs. Local files carry embedded pictures that the pipeline extracts into the cache folder;
 * connector songs fetch their cover URL once. Results, including "this song has none", are remembered so
 * scrolling a long list never asks twice. Reads are throttled: a screenful of rows would otherwise start a
 * Python process each.
 */
class Artwork(private val cacheDir: File, private val runner: PipelineRunner, private val servers: () -> Map<SongOrigin, MusicServer>) {
    // A ConcurrentHashMap refuses null, so "this song has no cover" is an empty Optional.
    private val known = ConcurrentHashMap<String, java.util.Optional<File>>()
    private val gate = Semaphore(3)

    private fun key(song: Song) = "${song.origin}:${song.albumId ?: song.path ?: song.id}"

    /** The cover image file for [song], or null when it has none. Albums share one cover across their songs for connector sources. */
    suspend fun coverFor(song: Song): File? {
        val k = key(song)
        known[k]?.let { return it.orElse(null) }
        val file = gate.withPermit { if (song.origin == SongOrigin.Local) local(song) else remote(song) }
        known[k] = java.util.Optional.ofNullable(file)
        return file
    }

    private suspend fun local(song: Song): File? {
        val path = song.path ?: return null
        var found: File? = null
        runner.run("cover", buildJsonObject { put("path", path); put("out_dir", cacheDir.path) }).collect { e ->
            if (e.isDone) (e.raw["cover"] as? JsonPrimitive)?.contentOrNull?.let { found = File(it) }
        }
        return found
    }

    private suspend fun remote(song: Song): File? {
        // An addon track carries its cover as a plain URL.
        if (song.origin == SongOrigin.Addon) {
            val url = song.coverId?.takeIf { it.startsWith("http") } ?: return null
            val target = File(cacheDir, MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".img")
            if (target.exists()) return target
            return if (downloadTo(url, emptyMap(), target)) target else null
        }
        val server = servers()[song.origin] ?: return null
        val url = server.coverUrl(song) ?: return null
        val name = MessageDigest.getInstance("MD5").digest("$url".toByteArray()).joinToString("") { "%02x".format(it) }
        val target = File(cacheDir, "$name.img")
        if (target.exists()) return target
        return if (downloadTo(url, server.streamHeaders(song), target)) target else null
    }

    fun forget() = known.clear()
}
