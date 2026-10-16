package app.soiboi.desktop

import app.soiboi.engine.Engine
import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.filterSongs
import app.soiboi.engine.player.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The player's command line. A running Soiboi (with a window, or started with `--headless`) listens on 127.0.0.1 for
 * commands; `soiboi ctl <command>` sends one and prints the answer. The port and a random token sit in `control.json`
 * beside the library, readable only by the person who owns it, so other users on the machine cannot drive playback.
 */
class ControlServer(private val engine: Engine, private val scope: CoroutineScope, private val supportDir: File, private val onQuit: () -> Unit) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "soiboi-control").apply { isDaemon = true } }
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val file get() = File(supportDir, "control.json")

    fun start() {
        val s = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")); socket = s
        supportDir.mkdirs()
        file.writeText(buildJsonObject { put("port", s.localPort); put("token", token) }.toString())
        runCatching { java.nio.file.Files.setPosixFilePermissions(file.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        pool.execute { while (!s.isClosed) { try { val c = s.accept(); pool.execute { serve(c) } } catch (_: Exception) { break } } }
    }

    fun stop() { socket?.close(); file.delete() }

    private fun serve(c: Socket) { c.use {
        try {
            c.soTimeout = 15_000
            val input = c.getInputStream().bufferedReader()
            val line = input.readLine() ?: return@use
            while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break }
            val query = line.split(' ').getOrElse(1) { "" }.substringAfter('?', "")
            val params = query.split('&').filter { it.isNotEmpty() }.associate { p -> p.substringBefore('=').let { URLDecoder.decode(it, "UTF-8") } to URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }
            val (status, body) = if (params["token"] != token) 403 to error("Wrong token") else 200 to runCatching { runBlocking { handle(params["cmd"].orEmpty(), params["arg"].orEmpty()) } }.getOrElse { error(it.message ?: "Failed") }
            val bytes = body.toString().toByteArray()
            c.getOutputStream().apply { write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(bytes); flush() }
        } catch (e: Exception) { EngineLog.output("control: $e") }
    } }

    private fun error(message: String) = buildJsonObject { put("ok", false); put("error", message) }

    private fun songJson(s: Song) = buildJsonObject { put("id", s.id); put("title", s.displayTitle); put("artist", s.displayArtist); put("album", s.displayAlbum) }

    private suspend fun handle(cmd: String, arg: String): JsonObject {
        val p = engine.player
        fun ok(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("ok", true); extra() }
        return when (cmd) {
            "status" -> {
                val st = p.state.value; val song = p.queue.value.current
                ok { put("playing", st.playing); put("positionMs", st.positionMs); put("durationMs", st.durationMs); put("volume", (p.volume.value * 100).toInt()); put("mode", p.mode.value.name); song?.let { put("song", songJson(it)) } }
            }
            "play" -> { p.play(); ok() }
            "pause" -> { p.pause(); ok() }
            "toggle" -> { p.togglePlay(); ok() }
            "next" -> { p.next(); ok() }
            "prev" -> { p.previous(); ok() }
            "stop" -> { p.stop(); ok() }
            "seek" -> { p.seek((arg.toDoubleOrNull() ?: return error("seek needs seconds")).times(1000).toLong()); ok() }
            "volume" -> { p.setVolume((arg.toDoubleOrNull() ?: return error("volume needs 0 to 100")) / 100.0); ok() }
            "shuffle" -> { p.setMode(if (p.mode.value == PlayMode.Shuffle) PlayMode.Order else PlayMode.Shuffle); ok { put("mode", p.mode.value.name) } }
            "repeat" -> { p.toggleRepeat(); ok { put("mode", p.mode.value.name) } }
            "queue" -> ok { put("index", p.queue.value.currentIndex); put("songs", JsonArray(p.queue.value.songs.take(200).map(::songJson))) }
            "find" -> ok { put("songs", JsonArray(filterSongs(engine.library.songs.value, arg).take(30).map(::songJson))) }
            "play-find" -> { val hits = filterSongs(engine.library.songs.value, arg); if (hits.isEmpty()) error("Nothing in your library matches that") else { p.setQueue(hits); ok { put("queued", hits.size) } } }
            "search" -> {
                val hits = engine.addons.search(arg)
                ok { put("sources", JsonArray(hits.map { h -> buildJsonObject { put("source", h.addon.id); h.error?.let { put("error", it) }; put("tracks", JsonArray(h.result?.tracks.orEmpty().take(10).map { t -> buildJsonObject { put("id", t.id); put("title", t.title); put("artist", t.artist) } })) } })) }
            }
            "play-online" -> {
                val source = arg.substringBefore(' ').trim(); val q = arg.substringAfter(' ', "").trim()
                val addon = engine.addons.instance(source) ?: return error("No source called $source. Try ytmusic, soundcloud or radio.")
                val tracks = addon.client.search(q).tracks
                if (tracks.isEmpty()) error("${addon.manifest.name} found nothing for that") else { p.setQueue(tracks.map { engine.addons.songFor(addon, it) }); ok { put("queued", tracks.size) } }
            }
            "sleep" -> { p.sleepTimer(arg.toIntOrNull()?.takeIf { it > 0 }); ok() }
            "quit" -> { ok().also { scope.launchQuit() } }
            else -> error("Unknown command '$cmd'. Try: status play pause toggle next prev stop seek volume shuffle repeat queue find play-find search play-online sleep quit")
        }
    }

    private fun CoroutineScope.launchQuit() { Thread { Thread.sleep(200); onQuit() }.start() }
}

/** Client side of `soiboi ctl`. Returns the process exit code. */
object ControlClient {
    fun run(args: List<String>, supportDir: File): Int {
        if (args.isEmpty()) { System.err.println("usage: soiboi ctl <status|play|pause|toggle|next|prev|stop|seek SEC|volume 0-100|shuffle|repeat|queue|find Q|play-find Q|search Q|play-online SOURCE Q|sleep MIN|quit>"); return 2 }
        val info = runCatching { Json.parseToJsonElement(File(supportDir, "control.json").readText()).jsonObject }.getOrNull()
        if (info == null) { System.err.println("Soiboi is not running. Open it, or start it without a window: soiboi --headless"); return 1 }
        val port = info["port"]!!.jsonPrimitive.int; val token = info["token"]!!.jsonPrimitive.content
        val cmd = args[0]; val arg = args.drop(1).joinToString(" ")
        return try {
            val url = "http://127.0.0.1:$port/?token=$token&cmd=${URLEncoder.encode(cmd, "UTF-8")}&arg=${URLEncoder.encode(arg, "UTF-8")}"
            val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            conn.readTimeout = 30_000
            val text = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream).readBytes().decodeToString()
            val json = Json.parseToJsonElement(text).jsonObject
            if (json["ok"]?.jsonPrimitive?.boolean == true) { println(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json)); 0 }
            else { System.err.println(json["error"]?.jsonPrimitive?.content ?: "failed"); 1 }
        } catch (e: java.io.IOException) { System.err.println("Soiboi did not answer. If it was closed, remove ${File(supportDir, "control.json")} and start it again."); 1 }
    }
}
