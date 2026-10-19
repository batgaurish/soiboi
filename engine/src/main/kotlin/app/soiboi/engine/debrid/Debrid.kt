package app.soiboi.engine.debrid

import app.soiboi.engine.resolve.Playable
import app.soiboi.engine.resolve.Resolver
import app.soiboi.engine.resolve.TrackRef
import app.soiboi.engine.core.normaliseForMatch
import app.soiboi.engine.core.bareSongTitle
import app.soiboi.engine.servers.requestJson
import app.soiboi.engine.sources.Http
import app.soiboi.engine.sources.arr
import app.soiboi.engine.sources.get
import app.soiboi.engine.sources.int
import app.soiboi.engine.sources.long
import app.soiboi.engine.sources.obj
import app.soiboi.engine.sources.str
import app.soiboi.engine.sources.urlWith
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A file inside a debrid download that looks like audio. */
class DebridFile(val provider: String, val downloadId: String, val fileId: String, val name: String, val size: Long, val airlocked: Boolean = false) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

private val audioExt = setOf("flac", "m4a", "mp3", "aac", "wav", "alac", "ogg", "opus", "aiff", "ape", "wv")

/** Whether [file] plausibly is the track: artist and title both appear in the path-and-name, ignoring case and punctuation. */
internal fun fileMatches(file: DebridFile, track: TrackRef): Boolean {
    if (file.extension !in audioExt) return false
    val haystack = normaliseForMatch(file.name)
    val title = normaliseForMatch(bareSongTitle(track.title))
    if (title.isEmpty() || title !in haystack) return false
    val artist = normaliseForMatch(track.artist.split(Regex(",|&|feat\\.?|ft\\.?", RegexOption.IGNORE_CASE)).first())
    return artist.isEmpty() || artist in haystack
}

/**
 * TorBox as a playback source. Debrid has no catalog: it holds what is in the user's own account, so this
 * resolver searches those downloads (torrents, Usenet and web downloads) for the track and asks TorBox for a
 * direct link to the matching file. It also adds magnets, and flips a download's `airlocked` flag, which
 * exempts it from the 30-day inactivity deletion (an account-side setting on files already in TorBox; TorBox
 * has no upload API, so Airlock cannot hold files this app downloaded elsewhere).
 */
class TorboxResolver(private val apiKey: () -> String) : Resolver {
    override val id = "torbox"
    private val api = "https://api.torbox.app/v1/api"
    private val auth get() = mapOf("Authorization" to "Bearer ${apiKey()}")
    private var index: List<DebridFile>? = null

    private suspend fun listOf(kind: String): List<JsonObject> =
        requestJson(HttpMethod.Get, "$api/$kind/mylist?bypass_cache=true", auth, label = "TorBox")["data"].arr().orEmpty().mapNotNull { it.obj() }

    /** Every audio file across the account's downloads, cached until [refresh]. */
    suspend fun files(): List<DebridFile> {
        index?.let { return it }
        val out = ArrayList<DebridFile>()
        for ((kind, field) in listOf("torrents" to "torrent_id", "usenet" to "usenet_download_id", "webdl" to "webdownload_id")) {
            for (d in listOf(kind)) {
                val downloadId = (d["id"].long() ?: continue).toString()
                val airlocked = d["airlocked"].str() == "true" || (d["airlocked"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                for (f in d["files"].arr().orEmpty().mapNotNull { it.obj() }) {
                    out += DebridFile("torbox:$kind", downloadId, (f["id"].long() ?: continue).toString(), f["name"].str() ?: f["short_name"].str() ?: continue, f["size"].long() ?: 0, airlocked)
                }
            }
        }
        index = out
        return out
    }

    fun refresh() { index = null }

    override suspend fun resolve(track: TrackRef): Playable? {
        if (apiKey().isBlank()) return null
        val file = files().firstOrNull { fileMatches(it, track) } ?: return null
        val kind = file.provider.substringAfter(':')
        val (path, idKey, fileKey) = when (kind) {
            "usenet" -> Triple("usenet/requestdl", "usenet_id", "file_id")
            "webdl" -> Triple("webdl/requestdl", "web_id", "file_id")
            else -> Triple("torrents/requestdl", "torrent_id", "file_id")
        }
        val res = requestJson(HttpMethod.Get, urlWith("$api/$path", mapOf("token" to apiKey(), idKey to file.downloadId, fileKey to file.fileId, "zip_link" to "false")), auth, label = "TorBox")
        val url = res["data"].str() ?: return null
        return Playable(url = url, format = file.extension, providerId = id)
    }

    /** Adds a magnet link to the account. Returns the new download's id, or null when TorBox refused it. */
    suspend fun addMagnet(magnet: String): String? {
        val response = Http.client.submitForm("$api/torrents/createtorrent", Parameters.build { append("magnet", magnet); append("seed", "1") }) { header("Authorization", "Bearer ${apiKey()}") }
        refresh()
        return Http.json.parseToJsonElement(response.bodyAsText()).obj()?.get("data")["torrent_id"].long()?.toString()
    }

    /** Whether the hash is already cached on TorBox, so it can play at once instead of downloading first. */
    suspend fun isCached(hash: String): Boolean =
        requestJson(HttpMethod.Get, urlWith("$api/torrents/checkcached", mapOf("hash" to hash, "format" to "object")), auth, label = "TorBox")["data"].obj()?.containsKey(hash.lowercase()) == true

    /** Sets or clears AirLock on a torrent, Usenet or web download that is already in the account. */
    suspend fun setAirlocked(file: DebridFile, airlocked: Boolean): Boolean {
        val kind = file.provider.substringAfter(':')
        val (path, key) = when (kind) {
            "usenet" -> "usenet/editusenetdownload" to "usenet_download_id"
            "webdl" -> "webdl/editwebdownload" to "webdl_id"
            else -> "torrents/edittorrent" to "torrent_id"
        }
        val body = buildJsonObject { put(key, file.downloadId.toLong()); put("airlocked", airlocked) }.toString()
        val ok = requestJson(HttpMethod.Post, "$api/$path", auth, body, "TorBox") != null
        if (ok) refresh()
        return ok
    }
}

/** Real-Debrid as a playback source: the account's finished torrents are searched, and a matching file's link is unrestricted into a direct URL. */
class RealDebridResolver(private val apiToken: () -> String) : Resolver {
    override val id = "realdebrid"
    private val api = "https://api.real-debrid.com/rest/1.0"
    private val auth get() = mapOf("Authorization" to "Bearer ${apiToken()}")

    private class Entry(val file: DebridFile, val link: String)
    private var index: List<Entry>? = null

    private suspend fun entries(): List<Entry> {
        index?.let { return it }
        val out = ArrayList<Entry>()
        val torrents = requestJson(HttpMethod.Get, "$api/torrents?limit=100", auth, label = "Real-Debrid").arr().orEmpty().mapNotNull { it.obj() }.filter { it["status"].str() == "downloaded" }
        for (t in torrents) {
            val tid = t["id"].str() ?: continue
            val info = requestJson(HttpMethod.Get, "$api/torrents/info/$tid", auth, label = "Real-Debrid").obj() ?: continue
            val selected = info["files"].arr().orEmpty().mapNotNull { it.obj() }.filter { it["selected"].int() == 1 }
            val links = info["links"].arr().orEmpty().mapNotNull { it.str() }
            // Links come back aligned with the selected files, in order.
            selected.zip(links).forEach { (f, link) ->
                out += Entry(DebridFile("realdebrid", tid, (f["id"].long() ?: return@forEach).toString(), (f["path"].str() ?: return@forEach).trimStart('/'), f["bytes"].long() ?: 0), link)
            }
        }
        index = out
        return out
    }

    fun refresh() { index = null }

    override suspend fun resolve(track: TrackRef): Playable? {
        if (apiToken().isBlank()) return null
        val hit = entries().firstOrNull { fileMatches(it.file, track) } ?: return null
        val response = Http.client.submitForm("$api/unrestrict/link", Parameters.build { append("link", hit.link) }) { header("Authorization", "Bearer ${apiToken()}") }
        val url = Http.json.parseToJsonElement(response.bodyAsText()).obj()?.get("download").str() ?: return null
        return Playable(url = url, format = hit.file.extension, providerId = id)
    }

    /** Adds a magnet and selects every file. Returns the torrent id. */
    suspend fun addMagnet(magnet: String): String? {
        val added = Http.client.submitForm("$api/torrents/addMagnet", Parameters.build { append("magnet", magnet) }) { header("Authorization", "Bearer ${apiToken()}") }
        val id = Http.json.parseToJsonElement(added.bodyAsText()).obj()?.get("id").str() ?: return null
        Http.client.submitForm("$api/torrents/selectFiles/$id", Parameters.build { append("files", "all") }) { header("Authorization", "Bearer ${apiToken()}") }
        refresh()
        return id
    }
}
