package app.soiboi.engine.player

import app.soiboi.engine.core.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import kotlin.math.log10
import kotlin.math.pow

/**
 * Desktop audio: a long-lived `mpv --idle` process driven over its JSON IPC socket. mpv plays everything
 * the library holds (ALAC, FLAC, AAC, Opus, WebDAV and Drive URLs with auth headers) and applies the
 * equalizer as a `superequalizer` filter, so there is no codec work in the app itself.
 */
class MpvBackend(private val mpvPath: String = "mpv") : AudioBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    private val socketPath = File(System.getProperty("java.io.tmpdir"), "soiboi-mpv-${ProcessHandle.current().pid()}.sock")
    private var process: Process? = null
    private var channel: SocketChannel? = null
    private var writer: java.io.Writer? = null
    private val lock = Any()
    @Volatile override var positionMs: Long = 0; private set
    @Volatile override var durationMs: Long = 0; private set
    @Volatile private var playing = false
    @Volatile private var loading = false

    init {
        // A player left running after its app is gone plays on with no window and no way to stop it, so it dies with us...
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { process?.destroyForcibly() } })
        // ...and one orphaned by a kill the hook could not see is cleared the next time Soiboi starts.
        reapOrphans()
    }

    private fun reapOrphans() {
        val tmp = File(System.getProperty("java.io.tmpdir"))
        tmp.listFiles { f -> f.name.startsWith("soiboi-mpv-") && f.name.endsWith(".sock") }?.forEach { sock ->
            val owner = sock.name.removePrefix("soiboi-mpv-").removeSuffix(".sock").toLongOrNull() ?: return@forEach
            if (owner == ProcessHandle.current().pid() || ProcessHandle.of(owner).map { it.isAlive }.orElse(false)) return@forEach
            ProcessHandle.allProcesses().filter { h -> h.info().commandLine().map { it.contains(sock.path) }.orElse(false) }.forEach { runCatching { it.destroyForcibly() } }
            sock.delete()
        }
    }

    private fun ensureStarted() {
        if (process?.isAlive == true && channel?.isOpen == true) return
        socketPath.delete()
        process = ProcessBuilder(
            mpvPath, "--idle=yes", "--no-video", "--no-terminal", "--force-window=no", "--audio-display=no", "--sub-auto=no",
            "--keep-open=no", "--input-ipc-server=${socketPath.path}",
        ).redirectErrorStream(true).start().also { p -> scope.launch { p.inputStream.bufferedReader().forEachLine { EngineLog.output("mpv: $it") } } }
        // The socket appears a moment after launch.
        repeat(50) { if (socketPath.exists()) return@repeat else Thread.sleep(100) }
        val ch = SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(socketPath.path)) }
        channel = ch
        writer = Channels.newWriter(ch, Charsets.UTF_8)
        scope.launch { readLoop(ch) }
        observe("time-pos", 1); observe("duration", 2); observe("pause", 3); observe("metadata", 4)
    }

    private fun observe(property: String, id: Int) = send(buildJsonArray { add(JsonPrimitive("observe_property")); add(JsonPrimitive(id)); add(JsonPrimitive(property)) })

    private fun send(command: JsonArray) {
        synchronized(lock) {
            try { writer?.apply { write(buildJsonObject { put("command", command) }.toString() + "\n"); flush() } }
            catch (e: Exception) { EngineLog.output("mpv send: $e") }
        }
    }

    private fun readLoop(ch: SocketChannel) {
        try {
            Channels.newReader(ch, Charsets.UTF_8).buffered().forEachLine { line ->
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@forEachLine
                when (o["event"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
                    "property-change" -> {
                        if ((o["name"] as? JsonPrimitive)?.contentOrNull == "metadata") {
                            val meta = o["data"] as? JsonObject
                            val title = meta?.entries?.firstOrNull { it.key.equals("icy-title", true) || it.key.equals("StreamTitle", true) }?.value.let { (it as? JsonPrimitive)?.contentOrNull }?.trim()?.takeIf { it.isNotEmpty() }
                            _events.tryEmit(BackendEvent.StreamTitle(title))
                        }
                        val value = o["data"] as? JsonPrimitive
                        when (o["name"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
                            "time-pos" -> value?.doubleOrNull?.let { positionMs = (it * 1000).toLong(); _events.tryEmit(BackendEvent.Position(positionMs, durationMs)) }
                            "duration" -> value?.doubleOrNull?.let { durationMs = (it * 1000).toLong() }
                            "pause" -> value?.booleanOrNull?.let { paused ->
                                playing = !paused
                                if (!loading) {
                                    EngineLog.event("MPV", "pause_property", "paused=$paused positionMs=$positionMs durationMs=$durationMs")
                                    _events.tryEmit(BackendEvent.Playing(playing))
                                }
                            }
                        }
                    }
                    "end-file" -> {
                        val reason = (o["reason"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                        EngineLog.event("MPV", "end_file", "reason=$reason positionMs=$positionMs durationMs=$durationMs")
                        if (reason == "eof") _events.tryEmit(BackendEvent.Completed)
                        else if (reason == "error") _events.tryEmit(BackendEvent.Error((o["file_error"] as? JsonPrimitive)?.contentOrNull ?: "playback error"))
                    }
                }
            }
        } catch (_: Exception) { /* socket closed on release */ }
    }

    override suspend fun open(source: String, headers: Map<String, String>, startMs: Long, play: Boolean, meta: TrackMeta?) {
        ensureStarted()
        EngineLog.event("MPV", "open_begin", "source=${if (source.startsWith("http")) "http" else "local"} startMs=$startMs play=$play headers=${headers.keys.sorted().joinToString(",")}")
        loading = true; positionMs = 0; durationMs = 0
        val options = buildList {
            if (startMs > 0) add("start=${startMs / 1000.0}")
            if (headers.isNotEmpty()) add("http-header-fields=" + headers.entries.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" })
            add("pause=${if (play) "no" else "yes"}")
        }.joinToString(",")
        send(buildJsonArray { add(JsonPrimitive("loadfile")); add(JsonPrimitive(source)); add(JsonPrimitive("replace")); add(JsonPrimitive(-1)); add(JsonPrimitive(options)) })
        delay(150); loading = false
        _events.tryEmit(BackendEvent.Playing(play))
    }

    private fun setProperty(name: String, value: Any) = send(buildJsonArray {
        add(JsonPrimitive("set_property")); add(JsonPrimitive(name))
        add(when (value) { is Boolean -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> JsonPrimitive(value.toString()) })
    })

    override fun play() { ensureStarted(); setProperty("pause", false) }
    override fun pause() = setProperty("pause", true)
    override fun stop() { send(buildJsonArray { add(JsonPrimitive("stop")) }); positionMs = 0; playing = false; _events.tryEmit(BackendEvent.Playing(false)) }

    override suspend fun seek(positionMs: Long) {
        send(buildJsonArray { add(JsonPrimitive("seek")); add(JsonPrimitive(positionMs / 1000.0)); add(JsonPrimitive("absolute")) })
        this.positionMs = positionMs
    }

    /** A perceptual curve: linear 0..1 feels far too loud at the bottom of the slider, so the controller's value is bent onto a log scale. */
    override fun setVolume(volume: Double) { setProperty("volume", log10(volume.coerceIn(0.0, 1.0) * 9 + 1) * 100) }

    /** Maps the app's 10 bands onto mpv's 18-band `superequalizer`, filling the gaps between with zero gain. */
    override fun setEqualizer(gainsDb: List<Double>) {
        if (gainsDb.all { kotlin.math.abs(it) < 0.01 }) { setProperty("af", ""); return }
        val g = gainsDb
        val bands = listOf(g[0], 0.0, g[1], g[2], 0.0, g[3], g[4], 0.0, g[5], g[6], 0.0, g[7], 0.0, g[8], 0.0, g[9], g[9], g[9])
        val params = bands.withIndex().joinToString(":") { (i, db) -> "${i + 1}b=${"%.3f".format(10.0.pow(db / 20))}" }
        setProperty("af", "superequalizer=$params")
    }

    override fun release() {
        runCatching { send(buildJsonArray { add(JsonPrimitive("quit")) }) }
        runCatching { channel?.close() }
        process?.destroy()
        socketPath.delete()
        scope.cancel()
    }
}
