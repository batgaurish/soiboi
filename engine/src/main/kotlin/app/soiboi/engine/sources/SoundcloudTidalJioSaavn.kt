package app.soiboi.engine.sources

import kotlinx.serialization.json.JsonObject

private const val SC_API = "https://api-v2.soundcloud.com"
private const val SC_BATCH = 50

/** The set URL in a SoundCloud link, normalised, or null. A set is identified by `soundcloud.com/<user>/sets/<slug>`. */
fun soundcloudSetUrl(input: String): String? {
    val uri = try { java.net.URI(input.trim()) } catch (_: Exception) { return null }
    val host = uri.host ?: return null
    if (!hostMatches(host, "soundcloud.com")) return null
    val segments = uri.path.split('/').filter { it.isNotEmpty() }
    if (segments.size < 3 || segments[1] != "sets") return null
    return "https://soundcloud.com/${segments.take(3).joinToString("/")}"
}

/** The public web client id in a SoundCloud page. It identifies the web app, not a user, and SoundCloud rotates it. */
fun parseSoundcloudClientId(html: String): String? {
    val match = Regex("window\\.__sc_hydration\\s*=\\s*(\\[.*?\\]);\\s*</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
    return try {
        Http.json.parseToJsonElement(match.groupValues[1]).arr().orEmpty()
            .firstOrNull { it["hydratable"].str() == "apiClient" }?.get("data")["id"].str()
    } catch (_: Exception) { null }
}

/** One SoundCloud track, or null if it is only an id. The label's metadata names the real artist. */
fun parseSoundcloudTrack(json: JsonObject): ExternalTrack? {
    val title = json["title"].str()?.trim()
    if (title.isNullOrEmpty()) return null
    val publisher = json["publisher_metadata"]
    val artist = publisher["artist"].str()?.trim()?.ifEmpty { null } ?: (json["user"]["username"].str() ?: "").trim()
    if (artist.isEmpty()) return null
    return ExternalTrack(title, artist, publisher["isrc"].str()?.trim()?.ifEmpty { null })
}

/** SoundCloud sets by link, no account. A resolved set carries full details for its first few tracks; the rest arrive as ids. */
class SoundcloudPlaylistSource : ExternalPlaylistSource() {
    override val id = "soundcloud"
    override val displayName = "SoundCloud"
    override suspend fun playlists() = emptyList<ExternalPlaylist>()
    override val acceptsLinks = true
    override fun playlistIdFromUrl(url: String) = soundcloudSetUrl(url)

    @Volatile private var error: String? = null
    override val lastError: String? get() = error
    @Volatile private var clientId: String? = null

    private suspend fun client(): String? {
        clientId?.let { return it }
        clientId = fetchText("https://soundcloud.com/")?.let(::parseSoundcloudClientId)
        return clientId
    }

    private suspend fun resolve(setUrl: String): JsonObject? {
        val client = client() ?: run { error = "Could not reach SoundCloud."; return null }
        val set = fetchJson(urlWith("$SC_API/resolve", mapOf("url" to setUrl, "client_id" to client))).obj()
        error = if (set == null) "SoundCloud did not return that set. It may be private." else null
        return set
    }

    override suspend fun titleFor(playlistId: String) = resolve(playlistId)?.get("title").str()

    override suspend fun tracks(playlistId: String): List<ExternalTrack> {
        val set = resolve(playlistId)
        val entries = set?.get("tracks").arr().orEmpty().mapNotNull { it.obj() }
        val byId = HashMap<String?, ExternalTrack>()
        val missing = ArrayList<String?>()
        for (entry in entries) {
            val id = entry["id"].long()?.toString()
            val track = parseSoundcloudTrack(entry)
            if (track == null) missing += id else byId[id] = track
        }
        val client = client()
        if (client != null) for (chunk in missing.chunked(SC_BATCH)) {
            val batch = fetchJson(urlWith("$SC_API/tracks", mapOf("ids" to chunk.joinToString(","), "client_id" to client))).arr().orEmpty()
            for (entry in batch.mapNotNull { it.obj() }) parseSoundcloudTrack(entry)?.let { byId[entry["id"].long()?.toString()] = it }
        }
        // Keep the set's own order.
        return entries.mapNotNull { byId[it["id"].long()?.toString()] }
    }
}

private const val TIDAL_API = "https://api.tidal.com/v1"
private const val TIDAL_WEB_TOKEN = "CzET4vdadNUFQ5JU"
private const val TIDAL_PAGE = 100
private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)

/** The playlist UUID in a Tidal link (`tidal.com/playlist/<uuid>`, `/browse/playlist/`, `listen.tidal.com`). */
fun tidalPlaylistId(input: String): String? {
    val uri = try { java.net.URI(input.trim()) } catch (_: Exception) { return null }
    val host = uri.host ?: return null
    if (!hostMatches(host, "tidal.com")) return null
    val segments = uri.path.split('/').filter { it.isNotEmpty() }
    val i = segments.indexOf("playlist")
    if (i == -1 || i + 1 >= segments.size) return null
    return segments[i + 1].takeIf { uuid.matches(it) }?.lowercase()
}

/** One Tidal playlist item, or null for anything not a playable track (playlists can hold videos). */
fun parseTidalItem(json: JsonObject): ExternalTrack? {
    val type = json["type"].str()
    if (type != null && type != "track") return null
    val item = json["item"].obj() ?: return null
    val title = item["title"].str()?.trim()
    val artist = item["artist"]["name"].str()?.trim()
    if (title.isNullOrEmpty() || artist.isNullOrEmpty()) return null
    val version = item["version"].str()?.trim()
    // Tidal keeps "Remastered" and "Live" apart from the title; Apple folds them in, so matching needs them back.
    return ExternalTrack(if (version.isNullOrEmpty()) title else "$title ($version)", artist, item["isrc"].str()?.trim()?.ifEmpty { null })
}

/** Tidal playlists by link, via the fixed client token Tidal's own web player ships. Tracks carry an ISRC. */
class TidalPlaylistSource : ExternalPlaylistSource() {
    override val id = "tidal"
    override val displayName = "Tidal"
    override suspend fun playlists() = emptyList<ExternalPlaylist>()
    override val acceptsLinks = true
    override fun playlistIdFromUrl(url: String) = tidalPlaylistId(url)

    @Volatile private var error: String? = null
    override val lastError: String? get() = error

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()) =
        fetchJson(urlWith("$TIDAL_API$path", mapOf("countryCode" to "US") + query), mapOf("x-tidal-token" to TIDAL_WEB_TOKEN))

    override suspend fun titleFor(playlistId: String): String? {
        val title = get("/playlists/$playlistId").obj()?.get("title").str()
        error = if (title == null) "Tidal did not return that playlist. It may be private, or Tidal changed how its web player reads playlists." else null
        return title
    }

    override suspend fun tracks(playlistId: String): List<ExternalTrack> {
        val out = ArrayList<ExternalTrack>()
        var offset = 0
        while (true) {
            val page = get("/playlists/$playlistId/items", mapOf("limit" to "$TIDAL_PAGE", "offset" to "$offset")).obj() ?: break
            val items = page["items"].arr().orEmpty()
            items.mapNotNull { it.obj()?.let(::parseTidalItem) }.let(out::addAll)
            val total = page["totalNumberOfItems"].int() ?: 0
            if (items.isEmpty() || offset + TIDAL_PAGE >= total) break
            offset += TIDAL_PAGE
        }
        return out
    }
}

private const val JIO_PAGE = 500

/** The playlist token in a JioSaavn link: the last path segment of `/featured/<slug>/<token>` or `/s/playlist/<hash>/<slug>/<token>`. */
fun jiosaavnPlaylistToken(input: String): String? {
    val uri = try { java.net.URI(input.trim()) } catch (_: Exception) { return null }
    val host = uri.host ?: return null
    if (!hostMatches(host, "jiosaavn.com")) return null
    val segments = uri.path.split('/').filter { it.isNotEmpty() }
    val isPlaylist = "featured" in segments || "playlist" in segments
    if (!isPlaylist || segments.size < 2) return null
    return segments.last().takeIf { Regex("^[A-Za-z0-9_,-]{6,}$").matches(it) }
}

private fun unescape(text: String) = text.replace("&quot;", "\"").replace("&#039;", "'").replace("&amp;", "&")

/** One JioSaavn song, naming its primary artists (yt-dlp reports the label, which is useless for matching). */
fun parseJioSaavnSong(json: JsonObject): ExternalTrack? {
    val title = json["title"].str()?.trim()
    val names = json["more_info"]["artistMap"]["primary_artists"].arr().orEmpty()
        .mapNotNull { it["name"].str()?.trim()?.takeIf { n -> n.isNotEmpty() }?.let(::unescape) }
    if (title.isNullOrEmpty() || names.isEmpty()) return null
    return ExternalTrack(unescape(title), names.joinToString(", "))
}

class JioSaavnPlaylistSource : ExternalPlaylistSource() {
    override val id = "jiosaavn"
    override val displayName = "JioSaavn"
    override suspend fun playlists() = emptyList<ExternalPlaylist>()
    override val acceptsLinks = true
    override fun playlistIdFromUrl(url: String) = jiosaavnPlaylistToken(url)

    private suspend fun fetch(token: String, count: Int = 1): JsonObject? {
        val body = fetchJson(urlWith("https://www.jiosaavn.com/api.php", mapOf(
            "__call" to "webapi.get", "token" to token, "type" to "playlist", "p" to "1", "n" to "$count",
            "includeMetaTags" to "0", "ctx" to "web6dot0", "api_version" to "4", "_format" to "json", "_marker" to "0",
        ))).obj()
        return body?.takeIf { it["list"] != null }
    }

    override suspend fun titleFor(playlistId: String) = fetch(playlistId)?.get("title").str()
    override suspend fun tracks(playlistId: String): List<ExternalTrack> =
        fetch(playlistId, JIO_PAGE)?.get("list").arr().orEmpty().mapNotNull { it.obj()?.let(::parseJioSaavnSong) }
}
