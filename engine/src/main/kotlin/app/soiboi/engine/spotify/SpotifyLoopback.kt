package app.soiboi.engine.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/** Catches the browser's redirect from Spotify on 127.0.0.1 and returns the sign-in code, or null if it never arrives or the state is wrong. */
suspend fun awaitSpotifyCode(login: SpotifyLogin, openBrowser: (String) -> Unit, timeoutMs: Long = 180_000): String? = withContext(Dispatchers.IO) {
    val server = try { ServerSocket(8898, 5, InetAddress.getByName("127.0.0.1")) } catch (e: java.io.IOException) { return@withContext null }
    server.use {
        openBrowser(login.url)
        withTimeoutOrNull(timeoutMs) {
            var result: String? = null
            while (result == null) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                c.use { s ->
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                    val target = line.split(' ').getOrElse(1) { "" }
                    if (!target.startsWith("/callback")) { s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); return@use }
                    val params = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                    val ok = params["state"] == login.state && params["code"] != null
                    val page = "<html><body style=\"font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding-top:20vh\"><h2>${if (ok) "Spotify is connected" else "That did not work"}</h2><p>You can close this tab and go back to Soiboi.</p></body></html>".toByteArray()
                    s.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(page); flush() }
                    if (ok) result = params["code"]
                }
            }
            result
        }
    }
}
