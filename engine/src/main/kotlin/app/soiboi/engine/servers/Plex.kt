package app.soiboi.engine.servers

import app.soiboi.engine.core.EngineLog
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.time.Instant

private const val PLEX_CLIENT_ID = "soiboi-player"

/**
 * Plex Media Server music libraries, read over the plain HTTP API with the user's `X-Plex-Token`.
 * The token is either pasted in or obtained through [PlexSignIn], plex.tv's PIN flow, which never
 * shows Soiboi a password.
 */
class PlexServer(private val baseUrl: String, private val token: String) : MusicServer {
    override val origin = SongOrigin.Plex
    override val label = "Plex"
    private val root = baseUrl.trimEnd('/')

    private val headers get() = mapOf(
        "X-Plex-Token" to token, "X-Plex-Client-Identifier" to PLEX_CLIENT_ID, "X-Plex-Product" to "Soiboi", "Accept" to "application/json",
    )

    private suspend fun get(path: String, query: Map<String, String?> = emptyMap()) =
        requestJson(HttpMethod.Get, urlWith("$root$path", query), headers, label = label).obj()?.get("MediaContainer").obj()

    override suspend fun ping() = get("/identity") != null || get("/") != null

    /** The server's music libraries (section type `artist`). */
    suspend fun musicSections(): List<Pair<String, String>> = get("/library/sections")?.get("Directory").arr().orEmpty().mapNotNull { it.obj() }
        .filter { it["type"].str() == "artist" }.map { (it["key"].str() ?: "") to (it["title"].str() ?: "Music") }

    private fun song(m: JsonObject): Song? {
        val ratingKey = m["ratingKey"].str() ?: return null
        val media = m["Media"].arr()?.firstOrNull().obj()
        val part = media?.get("Part").arr()?.firstOrNull().obj()
        return Song(
            id = ratingKey, origin = origin, path = part?.get("key").str(), // the part key doubles as the stream path
            format = media?.get("audioCodec").str(), title = m["title"].str(), artist = m["originalTitle"].str() ?: m["grandparentTitle"].str(),
            album = m["parentTitle"].str(), albumArtist = m["grandparentTitle"].str(), year = m["parentYear"].int() ?: m["year"].int(),
            track = m["index"].int(), disc = m["parentIndex"].int(), bitrate = media?.get("bitrate").int()?.times(1000),
            samplerate = media?.get("audioSampleRate").int(), durationMs = m["duration"].long(), playCount = m["viewCount"].int() ?: 0,
            lastPlayed = m["lastViewedAt"].long()?.let(Instant::ofEpochSecond), modified = m["addedAt"].long()?.let(Instant::ofEpochSecond),
            artistId = m["grandparentRatingKey"].str(), albumId = m["parentRatingKey"].str(), coverId = m["parentThumb"].str() ?: m["thumb"].str(),
        )
    }

    override suspend fun allSongs(onProgress: (Int) -> Unit): List<Song> {
        val out = ArrayList<Song>()
        for ((key, _) in musicSections()) {
            var start = 0; val size = 500
            while (true) {
                val container = get("/library/sections/$key/all", mapOf("type" to "10", "X-Plex-Container-Start" to "$start", "X-Plex-Container-Size" to "$size")) ?: break
                val page = container["Metadata"].arr().orEmpty().mapNotNull { it.obj()?.let(::song) }
                out += page; onProgress(out.size)
                val total = container["totalSize"].int() ?: container["size"].int() ?: 0
                if (page.isEmpty() || start + size >= total) break
                start += size
            }
        }
        return out
    }

    override fun streamUrl(song: Song) = urlWith("$root${song.path}", mapOf("X-Plex-Token" to token))
    override fun coverUrl(song: Song, size: Int) = song.coverId?.let { urlWith("$root/photo/:/transcode", mapOf("url" to it, "width" to "$size", "height" to "$size", "X-Plex-Token" to token)) }

    override suspend fun lyrics(song: Song): String? {
        // Plex exposes lyrics as a stream on the part; fetch it when the server has one.
        val meta = get("/library/metadata/${song.id}")?.get("Metadata").arr()?.firstOrNull().obj() ?: return null
        val part = meta["Media"].arr()?.firstOrNull().obj()?.get("Part").arr()?.firstOrNull().obj() ?: return null
        val lyric = part["Stream"].arr().orEmpty().mapNotNull { it.obj() }.firstOrNull { it["streamType"].int() == 4 && it["key"].str() != null } ?: return null
        return fetchText(urlWith("$root${lyric["key"].str()}", mapOf("X-Plex-Token" to token)))
    }

    private suspend fun fetchText(url: String): String? = app.soiboi.engine.sources.fetchText(url)

    override suspend fun playlists() = get("/playlists", mapOf("playlistType" to "audio"))?.get("Metadata").arr().orEmpty().mapNotNull { it.obj() }
        .map { RemotePlaylist(it["ratingKey"].str() ?: "", it["title"].str() ?: "") }

    override suspend fun playlistSongs(id: String) = get("/playlists/$id/items")?.get("Metadata").arr().orEmpty().mapNotNull { it.obj()?.let(::song) }

    override suspend fun deletePlaylist(id: String) = requestJson(HttpMethod.Delete, urlWith("$root/playlists/$id", emptyMap()), headers, label = label) != null

    override suspend fun scrobble(songId: String) =
        requestJson(HttpMethod.Get, urlWith("$root/:/scrobble", mapOf("key" to songId, "identifier" to "com.plexapp.plugins.library")), headers, label = label) != null

    override suspend fun setStarred(songId: String, starred: Boolean) =
        requestJson(HttpMethod.Put, urlWith("$root/:/rate", mapOf("key" to songId, "identifier" to "com.plexapp.plugins.library", "rating" to if (starred) "10" else "0")), headers, label = label) != null
}

/** plex.tv's PIN sign-in: the user approves in a browser, so no Plex password ever reaches Soiboi. */
class PlexSignIn {
    class Pin(val id: Long, val code: String) {
        val authUrl: String get() = "https://app.plex.tv/auth#?clientID=$PLEX_CLIENT_ID&code=$code&context%5Bdevice%5D%5Bproduct%5D=Soiboi"
    }

    suspend fun start(): Pin? {
        val res = requestJson(HttpMethod.Post, "https://plex.tv/api/v2/pins?strong=true", mapOf("X-Plex-Client-Identifier" to PLEX_CLIENT_ID, "X-Plex-Product" to "Soiboi"), label = "plex.tv").obj() ?: return null
        return Pin(res["id"].long() ?: return null, res["code"].str() ?: return null)
    }

    /** Polls until the user approves, or [timeoutMs] passes. Returns the token. */
    suspend fun awaitToken(pin: Pin, timeoutMs: Long = 120_000): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val res = requestJson(HttpMethod.Get, "https://plex.tv/api/v2/pins/${pin.id}", mapOf("X-Plex-Client-Identifier" to PLEX_CLIENT_ID), label = "plex.tv").obj()
            res?.get("authToken").str()?.let { return it }
            delay(2000)
        }
        EngineLog.output("plex sign-in: timed out")
        return null
    }
}
