package app.soiboi.engine

import app.soiboi.engine.player.MpvBackend
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvReapTest {
    @Test fun aPlayerLeftByADeadSoiboiIsKilledOnTheNextStart() {
        val sock = java.io.File(System.getProperty("java.io.tmpdir"), "soiboi-mpv-999999.sock").apply { writeText("") }
        val orphan = ProcessBuilder("sh", "-c", "sleep 300", "orphan", sock.path).start()
        Thread.sleep(300)
        assertTrue(orphan.isAlive)
        MpvBackend()
        orphan.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse(orphan.isAlive)
        assertFalse(sock.exists())
    }
}
