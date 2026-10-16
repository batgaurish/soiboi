package app.soiboi.engine.servers

import app.soiboi.engine.core.EngineLog
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/** A file the proxy can read at any offset, such as one on an SMB share. */
interface RangeFile : AutoCloseable {
    val size: Long
    /** Reads up to [len] bytes at [offset] into [buf]; returns the count, or -1 at the end. */
    fun read(offset: Long, buf: ByteArray, len: Int): Int
}

/**
 * A loopback HTTP server that makes a seekable source look like a web file, with Range support. Players open an
 * address and cannot read SMB themselves; this lets them play and seek an SMB track without copying it first.
 * It listens on 127.0.0.1 only, on a random port.
 */
class LocalRangeServer(private val open: (id: String) -> RangeFile?) : AutoCloseable {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "range-server").apply { isDaemon = true } }

    @Synchronized private fun ensureStarted(): ServerSocket = socket ?: ServerSocket(0, 20, InetAddress.getByName("127.0.0.1")).also { s ->
        socket = s
        pool.execute { while (!s.isClosed) { try { val c = s.accept(); pool.execute { serve(c) } } catch (_: Exception) { break } } }
    }

    fun url(id: String): String = "http://127.0.0.1:${ensureStarted().localPort}/f/${URLEncoder.encode(id, "UTF-8").replace("+", "%20")}"

    private fun mime(id: String) = when (id.substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "m4a", "m4b", "mp4", "aac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "application/octet-stream"
    }

    private fun serve(c: Socket) = c.use {
        try {
            c.soTimeout = 30_000
            val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = input.readLine() ?: return
            val (method, target) = request.split(' ').let { it[0] to it.getOrElse(1) { "" } }
            var range: String? = null
            while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break; if (h.startsWith("Range:", true)) range = h.substringAfter(':').trim() }
            val id = URLDecoder.decode(target.removePrefix("/f/"), "UTF-8")
            val file = open(id)
            val out = c.getOutputStream()
            if (file == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
            file.use {
                val size = file.size
                var start = 0L; var end = size - 1; var partial = false
                range?.let { r -> Regex("bytes=(\\d*)-(\\d*)").find(r)?.let { m ->
                    val a = m.groupValues[1]; val b = m.groupValues[2]
                    if (a.isEmpty() && b.isNotEmpty()) { start = (size - b.toLong()).coerceAtLeast(0) } else if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1) }
                    partial = true
                } }
                if (start >= size && size > 0) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return }
                val length = end - start + 1
                val head = buildString {
                    append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${mime(id)}\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\nConnection: close\r\n")
                    if (partial) append("Content-Range: bytes $start-$end/$size\r\n")
                    append("\r\n")
                }
                out.write(head.toByteArray())
                if (method == "HEAD") return
                val buf = ByteArray(64 * 1024); var pos = start; var left = length
                while (left > 0) {
                    val n = file.read(pos, buf, minOf(buf.size.toLong(), left).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n); pos += n; left -= n
                }
                out.flush()
            }
        } catch (e: java.io.IOException) {
            // The player closes the connection when it seeks; that is normal.
        } catch (e: Exception) { EngineLog.output("range server: $e") }
    }

    @Synchronized override fun close() { socket?.close(); socket = null }
}
