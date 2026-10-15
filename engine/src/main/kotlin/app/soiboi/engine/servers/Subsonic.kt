package app.soiboi.engine.servers

import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.sources.arr
import app.soiboi.engine.sources.get
import app.soiboi.engine.sources.int
import app.soiboi.engine.sources.long
import app.soiboi.engine.sources.obj
import app.soiboi.engine.sources.str
import app.soiboi.engine.sources.urlWith
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.time.Instant
import kotlin.random.Random

private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

/**
 * Navidrome and any other OpenSubsonic server, over the Subsonic REST API with token auth (md5 of password
 * and a random salt, so the password is never sent). OpenSubsonic is a superset, so one client serves both;
 * [origin] only labels which one the user connected.
 */
class SubsonicServer(
    private val baseUrl: String, private val username: String, private val password: String,
    override val origin: SongOrigin = SongOrigin.Navidrome,
) : MusicServer {
    override val label get() = if (origin == SongOrigin.OpenSubsonic) "OpenSubsonic" else "Navidrome"
    private val root = baseUrl.trimEnd('/')

    private fun params(extra: Map<String, String?> = emptyMap()): Map<String, String?> {
        val salt = List(8) { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
        return mapOf("u" to username, "t" to md5(password + salt), "s" to salt, "v" to "1.16.1", "c" to "Soiboi", "f" to "json") + extra
    }

    private fun url(path: String, extra: Map<String, String?> = emptyMap()) = urlWith("$root/rest/$path.view", params(extra))

    /** Repeated ids are sent as repeated parameters, which a Map cannot express, so they are appended by hand. */
    private fun urlMulti(path: String, key: String, values: List<String>, extra: Map<String, String?> = emptyMap()) =
        url(path, extra) + values.joinToString("") { "&$key=${java.net.URLEncoder.encode(it, "UTF-8")}" }

    private suspend fun call(path: String, extra: Map<String, String?> = emptyMap()): JsonObject? = callUrl(url(path, extra))

    private suspend fun callUrl(u: String): JsonObject? {
        val response = requestJson(HttpMethod.Get, u, label = label)["subsonic-response"].obj() ?: return null
        return if (response["status"].str() == "ok") response else null
    }

    override suspend fun ping() = call("ping") != null

    private fun song(m: JsonObject) = Song(
        id = m["id"].str() ?: "", origin = origin, path = m["path"].str(),
        format = m["contentType"].str()?.substringAfter("audio/"), title = m["title"].str(), artist = m["artist"].str(), album = m["album"].str(),
        albumArtist = m["displayAlbumArtist"].str() ?: m["albumArtist"].str(), genre = m["genre"].str(), year = m["year"].int(),
        track = m["track"].int(), disc = m["discNumber"].int(), bitrate = m["bitRate"].int(), samplerate = m["samplingRate"].int(),
        durationMs = m["duration"].long()?.times(1000), playCount = m["playCount"].int() ?: 0,
        lastPlayed = m["played"].str()?.let { runCatching { Instant.parse(it) }.getOrNull() },
        artistId = m["artistId"].str(), albumId = m["albumId"].str(), coverId = m["coverArt"].str() ?: m["id"].str(),
    )

    private fun songs(list: JsonElement?) = list.arr().orEmpty().mapNotNull { it.obj()?.let(::song) }.filter { it.id.isNotEmpty() }

    override suspend fun allSongs(onProgress: (Int) -> Unit): List<Song> {
        val out = ArrayList<Song>(); var offset = 0; val size = 500
        while (true) {
            // search3 with an empty query is the documented way to page every song on Navidrome.
            val page = songs(call("search3", mapOf("query" to "", "albumCount" to "0", "artistCount" to "0", "songCount" to "$size", "songOffset" to "$offset"))
                ?.get("searchResult3")["song"])
            out += page; onProgress(out.size)
            if (page.size < size) break
            offset += size
        }
        return out
    }

    override fun streamUrl(song: Song) = url("stream", mapOf("id" to song.id))
    override fun coverUrl(song: Song, size: Int) = song.coverId?.let { url("getCoverArt", mapOf("id" to it, "size" to "$size")) }

    override suspend fun lyrics(song: Song): String? {
        val res = call("getLyricsBySongId", mapOf("id" to song.id)) ?: return null
        val structured = res["lyricsList"]["structuredLyrics"].arr().orEmpty().mapNotNull { it.obj() }
        if (structured.isNotEmpty()) {
            // Several tracks can come back (original, translation); the one with the longest first line is the most complete.
            val best = structured.maxByOrNull { it["line"].arr()?.firstOrNull()["value"].str()?.length ?: 0 } ?: structured.first()
            return best["line"].arr().orEmpty().joinToString("\n") { l ->
                val start = l["start"].long() ?: 0
                "[%02d:%02d.%03d]%s".format(start / 60000, (start % 60000) / 1000, start % 1000, l["value"].str() ?: "")
            }
        }
        return res["lyrics"]["value"].str()
    }

    override suspend fun playlists() = call("getPlaylists")?.get("playlists")["playlist"].arr().orEmpty()
        .mapNotNull { it.obj() }.map { RemotePlaylist(it["id"].str() ?: "", it["name"].str() ?: "") }

    override suspend fun playlistSongs(id: String) = songs(call("getPlaylist", mapOf("id" to id))?.get("playlist")["entry"])
    override suspend fun createPlaylist(name: String) = call("createPlaylist", mapOf("name" to name))?.get("playlist")["id"].str()
    override suspend fun deletePlaylist(id: String) = call("deletePlaylist", mapOf("id" to id)) != null

    override suspend fun updatePlaylist(id: String, songIds: List<String>): Boolean {
        val old = playlistSongs(id).size
        // Remove from the end so indexes stay valid, in batches of 100, then add in batches of 100.
        for (batch in (old - 1 downTo 0).toList().chunked(100)) {
            if (callUrl(urlMulti("updatePlaylist", "songIndexToRemove", batch.map { "$it" }, mapOf("playlistId" to id))) == null) return false
        }
        for (batch in songIds.chunked(100)) {
            if (callUrl(urlMulti("updatePlaylist", "songIdToAdd", batch, mapOf("playlistId" to id))) == null) return false
        }
        return true
    }

    override suspend fun starred() = songs(call("getStarred2")?.get("starred2")["song"])
    override suspend fun setStarred(songId: String, starred: Boolean) = call(if (starred) "star" else "unstar", mapOf("id" to songId)) != null
    override suspend fun scrobble(songId: String) = call("scrobble", mapOf("id" to songId)) != null
}
