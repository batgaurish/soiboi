package app.soiboi.desktop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordIpcTest {
    private fun frame(ch: java.nio.channels.SocketChannel): Pair<Int, String> {
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN); while (h.hasRemaining()) ch.read(h); h.flip()
        val op = h.int; val len = h.int; val b = ByteBuffer.allocate(len); while (b.hasRemaining()) ch.read(b)
        return op to String(b.array())
    }
    private fun reply(ch: java.nio.channels.SocketChannel, op: Int, text: String) {
        val body = text.toByteArray(); ch.write(ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(body.size).put(body).flip())
    }

    @Test fun handshakeThenActivity() {
        val dir = kotlin.io.path.createTempDirectory("discord").toFile()
        val path = java.io.File(dir, "discord-ipc-0")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path.toPath()))
        val seen = java.util.concurrent.LinkedBlockingQueue<Pair<Int, String>>()
        val t = Thread {
            val c = server.accept()
            seen += frame(c); reply(c, 1, """{"cmd":"DISPATCH","evt":"READY","data":{}}""")
            seen += frame(c); reply(c, 1, """{"cmd":"SET_ACTIVITY","evt":null,"data":{}}""")
        }.apply { isDaemon = true; start() }
        val ipc = DiscordIpc("12345", listOf(dir.path))
        assertTrue(ipc.connect())
        ipc.setActivity(buildJsonObject { put("details", "Around the World") })
        t.join(3000)
        val hello = seen.poll(); val act = seen.poll()
        assertEquals(0, hello!!.first); assertTrue(hello.second.contains("\"client_id\":\"12345\""))
        assertEquals(1, act!!.first); assertTrue(act.second.contains("SET_ACTIVITY") && act.second.contains("Around the World"))
        ipc.close(); server.close(); dir.deleteRecursively()
    }

    @Test fun noDiscordMeansNoConnection() {
        val dir = kotlin.io.path.createTempDirectory("nodiscord").toFile()
        assertFalse(DiscordIpc("1", listOf(dir.path)).connect()); dir.deleteRecursively()
    }
}
