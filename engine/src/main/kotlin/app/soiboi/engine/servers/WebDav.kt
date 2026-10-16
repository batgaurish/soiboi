package app.soiboi.engine.servers

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.sources.Http
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

private val audioExt = setOf("mp3", "flac", "m4a", "m4b", "mp4", "aac", "wav", "aiff", "aif", "ogg", "opus", "ape", "mpc", "wv", "mp2")

private val propfindBody = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified/><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""

class WebDavEntry(val path: String, val name: String, val isDirectory: Boolean, val modified: Instant?, val size: Long?)

/**
 * A WebDAV share (Nextcloud, ownCloud, a NAS) as a music library. Listing is a PROPFIND walk; WebDAV carries no
 * audio tags, so the artist, album and title come from the `Artist/Album/NN Title.ext` folder layout (what the
 * downloader writes) and the file name, and the duration is learned the first time a track plays.
 */
class WebDavServer(
    private val baseUrl: String, private val username: String, private val password: String, private val recursive: Boolean = true,
    override val origin: SongOrigin = SongOrigin.WebDav, override val label: String = "WebDAV",
) : MusicServer {

    private val uri = java.net.URI(baseUrl.trimEnd('/'))
    private val hostRoot = "${uri.scheme}://${uri.authority}"
    private val basePath = URLDecoder.decode(uri.rawPath ?: "", "UTF-8").trimEnd('/')
    private val authHeader get() = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    override fun streamHeaders(song: Song) = mapOf("Authorization" to authHeader)
    override fun streamUrl(song: Song) = song.id

    override suspend fun ping(): Boolean = try { list(basePath.ifEmpty { "/" }) != null } catch (_: Exception) { false }

    private suspend fun list(path: String): List<WebDavEntry>? = try {
        withTimeout(20_000) {
            val response = Http.client.request(hostRoot + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }) {
                method = HttpMethod("PROPFIND"); header("Authorization", authHeader); header("Depth", "1"); header("Content-Type", "application/xml"); setBody(propfindBody)
            }
            if (response.status.value != 207 && !response.status.isSuccess()) { EngineLog.output("[WebDAV] PROPFIND $path -> ${response.status.value}"); null }
            else parse(response.bodyAsText(), path)
        }
    } catch (e: Exception) {
        EngineLog.output("[WebDAV] list $path: $e"); null
    }

    private fun parse(xml: String, requested: String): List<WebDavEntry> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val out = ArrayList<WebDavEntry>()
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        for (i in 0 until responses.length) {
            val r = responses.item(i) as Element
            val href = r.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
            val path = URLDecoder.decode(href.substringAfter(uri.authority).let { if (it.startsWith("http")) java.net.URI(it).rawPath else it }, "UTF-8").trimEnd('/')
            if (path == requested.trimEnd('/')) continue
            val isDir = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            val modified = r.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
            val size = r.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent?.toLongOrNull()
            out += WebDavEntry(path, path.substringAfterLast('/'), isDir, modified, size)
        }
        return out
    }

    private fun songFor(entry: WebDavEntry): Song {
        val rel = entry.path.removePrefix(basePath).trim('/').split('/')
        val file = rel.last().substringBeforeLast('.')
        val album = rel.getOrNull(rel.size - 2)?.takeIf { rel.size >= 2 }
        val artist = rel.getOrNull(rel.size - 3)?.takeIf { rel.size >= 3 }
        // "03 Title" or "03 - Title": strip a leading track number.
        val numbered = Regex("^(\\d{1,3})[\\s._-]+(.+)$").find(file)
        val title = numbered?.groupValues?.get(2) ?: file
        val encoded = entry.path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return Song(
            id = hostRoot + encoded, origin = origin, path = null, format = entry.name.substringAfterLast('.').lowercase(),
            title = title, artist = artist, album = album, albumArtist = artist, track = numbered?.groupValues?.get(1)?.toIntOrNull(), modified = entry.modified,
        )
    }

    override suspend fun allSongs(onProgress: (Int) -> Unit): List<Song> {
        val out = ArrayList<Song>()
        suspend fun walk(path: String) {
            val entries = list(path) ?: return
            for (e in entries) {
                if (e.isDirectory) { if (recursive) walk(e.path) }
                else if (e.name.substringAfterLast('.', "").lowercase() in audioExt) { out += songFor(e); if (out.size % 25 == 0) onProgress(out.size) }
            }
        }
        walk(basePath.ifEmpty { "/" })
        onProgress(out.size)
        return out
    }
}
