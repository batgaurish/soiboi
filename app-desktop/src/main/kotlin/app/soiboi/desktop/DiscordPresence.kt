package app.soiboi.desktop

import app.soiboi.engine.Engine
import app.soiboi.engine.core.EngineLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel

/** Discord's local IPC: a framed JSON conversation over a unix socket the desktop client opens in the runtime folder. */
internal class DiscordIpc(private val clientId: String, private val bases: List<String?> = listOf(System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), "/tmp")) : AutoCloseable {
    private var channel: SocketChannel? = null

    private fun socketPaths(): List<File> {
        val dirs = bases.filterNotNull().distinct().flatMap { b ->
            listOf(File(b), File(b, "app/com.discordapp.Discord"), File(b, "snap.discord"), File(b, "snap.discord-canary"))
        }
        return dirs.flatMap { d -> (0..9).map { File(d, "discord-ipc-$it") } }.filter { it.exists() }
    }

    private fun write(op: Int, json: JsonObject) {
        val body = json.toString().toByteArray()
        val buf = ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(body.size).put(body).flip()
        while (buf.hasRemaining()) channel!!.write(buf)
    }

    private fun readFrame(): String {
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        while (head.hasRemaining()) if (channel!!.read(head) < 0) error("Discord closed the connection")
        head.flip(); head.int; val len = head.int
        val body = ByteBuffer.allocate(len)
        while (body.hasRemaining()) if (channel!!.read(body) < 0) error("Discord closed the connection")
        return String(body.array())
    }

    /** Connects and says hello. Returns false when no Discord client is running. */
    fun connect(): Boolean {
        for (path in socketPaths()) {
            try {
                val ch = SocketChannel.open(StandardProtocolFamily.UNIX); ch.connect(UnixDomainSocketAddress.of(path.toPath())); channel = ch
                write(0, buildJsonObject { put("v", 1); put("client_id", clientId) })
                val reply = readFrame()
                if (reply.contains("\"READY\"")) return true
                EngineLog.output("discord: unexpected hello $reply")
            } catch (e: Exception) { runCatching { channel?.close() }; channel = null }
        }
        return false
    }

    fun setActivity(activity: JsonObject?) {
        write(1, buildJsonObject {
            put("cmd", "SET_ACTIVITY"); put("nonce", java.util.UUID.randomUUID().toString())
            put("args", buildJsonObject { put("pid", ProcessHandle.current().pid()); if (activity != null) put("activity", activity) })
        })
        runCatching { readFrame() }
    }

    override fun close() { runCatching { channel?.close() }; channel = null }
}

/** Shows what is playing on the person's Discord profile, when they turn it on and give their own Discord application id. */
class DiscordPresence(private val engine: Engine, private val scope: CoroutineScope) {
    fun start() {
        scope.launch(Dispatchers.IO) {
            var ipc: DiscordIpc? = null; var key = ""
            combine(engine.player.queue.map { it.current }, engine.player.state.map { it.playing }, engine.settings.state.map { it.discordPresence to it.discordClientId.trim() }) { song, playing, cfg -> Triple(song, playing, cfg) }
                .distinctUntilChanged().collect { (song, playing, cfg) ->
                    val (on, id) = cfg
                    if (!on || id.isEmpty()) { ipc?.let { runCatching { it.setActivity(null) }; it.close() }; ipc = null; key = ""; return@collect }
                    if (ipc == null) { ipc = DiscordIpc(id).takeIf { it.connect() } ?: return@collect }
                    try {
                        if (song == null || !playing) { ipc!!.setActivity(null); return@collect }
                        val now = System.currentTimeMillis() / 1000
                        val live = song.album == "Live radio"
                        ipc!!.setActivity(buildJsonObject {
                            put("type", 2)
                            put("details", song.displayTitle.take(120)); put("state", ("by " + song.displayArtist).take(120))
                            val pos = engine.player.state.value.positionMs / 1000; val dur = (song.durationMs ?: 0) / 1000
                            if (!live) put("timestamps", buildJsonObject { put("start", now - pos); if (dur > 0) put("end", now - pos + dur) })
                            song.coverId?.takeIf { it.startsWith("http") }?.let { cover -> put("assets", buildJsonObject { put("large_image", cover); put("large_text", song.displayAlbum.take(120)) }) }
                        })
                    } catch (e: Exception) { EngineLog.output("discord: $e"); ipc?.close(); ipc = null; delay(5000) }
                }
        }
    }
}
