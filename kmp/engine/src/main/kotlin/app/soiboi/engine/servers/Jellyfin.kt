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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

private const val FIELDS = "Id,Name,Album,AlbumId,Artists,ArtistItems,AlbumArtist,RunTimeTicks,Genres,ProductionYear,IndexNumber,ParentIndexNumber,MediaSources,UserData,ImageTags,DateCreated"

/**
 * Jellyfin and Emby. The two share one API for everything this app needs (the `X-Emby-*` headers and the
 * `/Users/{id}/Items` queries), so a single client serves both; Emby needs `X-Emby-Token`, Jellyfin accepts
 * it too. Signs in by name and password once and holds the token in memory only.
 */
class JellyfinServer(
    private val baseUrl: String, private val username: String, private val password: String,
    override val origin: SongOrigin = SongOrigin.Jellyfin, private val appVersion: String = "2.0",
) : MusicServer {
    override val label get() = if (origin == SongOrigin.Emby) "Emby" else "Jellyfin"
    private val root = baseUrl.trimEnd('/')
    private var token: String? = null
    private var userId: String? = null
    private var libraryId: String = ""

    private val baseHeaders get() = mapOf(
        "X-Emby-Authorization" to "MediaBrowser Client=\"Soiboi\", Device=\"Desktop\", DeviceId=\"soiboi\", Version=\"$appVersion\"",
    ) + (token?.let { mapOf("X-Emby-Token" to it) } ?: emptyMap())

    private suspend fun login(): Boolean {
        if (token != null) return true
        val body = buildJsonObject { put("Username", username); put("Pw", password) }.toString()
        val res = requestJson(HttpMethod.Post, "$root/Users/AuthenticateByName", baseHeaders, body, label).obj() ?: return false
        token = res["AccessToken"].str(); userId = res["User"]["Id"].str()
        if (token == null || userId == null) return false
        val views = requestJson(HttpMethod.Get, "$root/Users/$userId/Views", baseHeaders, label = label)["Items"].arr().orEmpty().mapNotNull { it.obj() }
        libraryId = views.firstOrNull { it["CollectionType"].str() == "music" }?.get("Id").str() ?: ""
        return true
    }

    private suspend fun get(path: String, query: Map<String, String?> = emptyMap()): JsonObject? {
        if (!login()) return null
        return requestJson(HttpMethod.Get, urlWith("$root$path", query), baseHeaders, label = label).obj()
    }

    override suspend fun ping() = requestJson(HttpMethod.Get, "$root/System/Info/Public", label = label) != null && login()

    private fun song(m: JsonObject): Song {
        val source = m["MediaSources"].arr()?.firstOrNull().obj()
        val streams = source?.get("MediaStreams").arr().orEmpty().mapNotNull { it.obj() }
        val audio = streams.firstOrNull { it["Type"].str() == "Audio" }
        return Song(
            id = m["Id"].str() ?: "", origin = origin,
            format = audio?.get("Codec").str() ?: source?.get("Container").str(), title = m["Name"].str(),
            artist = m["ArtistItems"].arr()?.mapNotNull { it["Name"].str() }?.joinToString("/") ?: m["Artists"].arr()?.mapNotNull { it.str() }?.joinToString("/"),
            album = m["Album"].str(), albumArtist = m["AlbumArtist"].str(), genre = m["Genres"].arr()?.mapNotNull { it.str() }?.joinToString("/"),
            year = m["ProductionYear"].int(), track = m["IndexNumber"].int(), disc = m["ParentIndexNumber"].int(),
            bitrate = audio?.get("BitRate").int() ?: source?.get("Bitrate").int(), samplerate = audio?.get("SampleRate").int(),
            durationMs = m["RunTimeTicks"].long()?.div(10_000), albumId = m["AlbumId"].str(),
            playCount = m["UserData"]["PlayCount"].int() ?: 0,
            lastPlayed = m["UserData"]["LastPlayedDate"].str()?.let { runCatching { Instant.parse(it) }.getOrNull() },
            coverId = m["AlbumId"].str() ?: m["Id"].str(),
            modified = m["DateCreated"].str()?.let { runCatching { Instant.parse(it) }.getOrNull() },
        )
    }

    private fun songs(res: JsonObject?) = res?.get("Items").arr().orEmpty().mapNotNull { it.obj()?.let(::song) }.filter { it.id.isNotEmpty() }

    override suspend fun allSongs(onProgress: (Int) -> Unit): List<Song> {
        if (!login()) return emptyList()
        val out = ArrayList<Song>(); var offset = 0; val size = 500
        while (true) {
            val page = songs(get("/Users/$userId/Items", mapOf(
                "ParentId" to libraryId.ifEmpty { null }, "IncludeItemTypes" to "Audio", "Recursive" to "true",
                "StartIndex" to "$offset", "Limit" to "$size", "Fields" to FIELDS, "SortBy" to "SortName",
            )))
            out += page; onProgress(out.size)
            if (page.size < size) break
            offset += size
        }
        return out
    }

    override fun streamUrl(song: Song) = "$root/Audio/${song.id}/stream?UserId=$userId&api_key=$token&static=true"
    override fun coverUrl(song: Song, size: Int) = song.coverId?.let { "$root/Items/$it/Images/Primary?maxWidth=$size&api_key=$token" }

    override suspend fun lyrics(song: Song): String? {
        val res = get("/Audio/${song.id}/Lyrics") ?: return null
        val lines = res["Lyrics"].arr() ?: return null
        return lines.joinToString("\n") { l ->
            val ms = ((l["Start"].long() ?: 0) / 10_000)
            "[%02d:%02d.%03d]%s".format(ms / 60000, (ms % 60000) / 1000, ms % 1000, l["Text"].str() ?: "")
        }
    }

    override suspend fun playlists() = get("/Users/$userId/Items", mapOf("IncludeItemTypes" to "Playlist", "Recursive" to "true"))
        ?.get("Items").arr().orEmpty().mapNotNull { it.obj() }.map { RemotePlaylist(it["Id"].str() ?: "", it["Name"].str() ?: "") }

    override suspend fun playlistSongs(id: String) = songs(get("/Playlists/$id/Items", mapOf("UserId" to userId, "Fields" to FIELDS)))

    override suspend fun createPlaylist(name: String): String? {
        if (!login()) return null
        val body = buildJsonObject { put("Name", name); put("UserId", userId!!); put("MediaType", "Audio") }.toString()
        return requestJson(HttpMethod.Post, "$root/Playlists", baseHeaders, body, label)["Id"].str()
    }

    override suspend fun deletePlaylist(id: String) = login() && requestJson(HttpMethod.Delete, "$root/Items/$id", baseHeaders, label = label) != null

    override suspend fun updatePlaylist(id: String, songIds: List<String>): Boolean {
        if (!login()) return false
        // Items carry a per-playlist entry id; remove those in batches, then add the new order in batches.
        val entries = get("/Playlists/$id/Items", mapOf("UserId" to userId))?.get("Items").arr().orEmpty().mapNotNull { it["PlaylistItemId"].str() }
        for (batch in entries.chunked(100)) {
            if (requestJson(HttpMethod.Delete, urlWith("$root/Playlists/$id/Items", mapOf("EntryIds" to batch.joinToString(","))), baseHeaders, label = label) == null) return false
        }
        for (batch in songIds.chunked(100)) {
            if (requestJson(HttpMethod.Post, urlWith("$root/Playlists/$id/Items", mapOf("Ids" to batch.joinToString(","), "UserId" to userId)), baseHeaders, label = label) == null) return false
        }
        return true
    }

    override suspend fun starred() = songs(get("/Users/$userId/Items", mapOf("IncludeItemTypes" to "Audio", "Recursive" to "true", "Filters" to "IsFavorite", "Fields" to FIELDS)))

    override suspend fun setStarred(songId: String, starred: Boolean): Boolean {
        if (!login()) return false
        return requestJson(if (starred) HttpMethod.Post else HttpMethod.Delete, "$root/Users/$userId/FavoriteItems/$songId", baseHeaders, label = label) != null
    }

    override suspend fun scrobble(songId: String) = login() && requestJson(HttpMethod.Post, "$root/Users/$userId/PlayedItems/$songId", baseHeaders, label = label) != null
}
