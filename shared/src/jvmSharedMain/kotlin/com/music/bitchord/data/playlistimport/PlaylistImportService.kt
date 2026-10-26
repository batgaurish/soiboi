package com.music.bitchord.data.playlistimport

import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.spotify.SpotifyImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service coordinating playlist loading, source track parsing, candidate search,
 * and deterministic match resolution across all supported providers.
 */
object PlaylistImportService {

    private val APPLE_MUSIC_URL_REGEX = Regex("""^/([a-z]{2})/playlist/[^/?#]+/(pl\.[A-Za-z0-9-]+)/?$""", RegexOption.IGNORE_CASE)

    fun extractApplePlaylist(input: String): Pair<String, String>? {
        val url = input.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host.lowercase() !in setOf("music.apple.com", "www.music.apple.com") || url.port != 443) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val match = APPLE_MUSIC_URL_REGEX.matchEntire(url.encodedPath) ?: return null
        return match.groupValues[1].lowercase() to match.groupValues[2]
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Public provider URL patterns
    private val DEEZER_URL_REGEX = Regex("""deezer\.com/(?:[a-zA-Z-]+/)?(playlist|album)/(\d+)""", RegexOption.IGNORE_CASE)
    private val DEEZER_PAGE_LINK_REGEX = Regex("""deezer\.page\.link/[A-Za-z0-9]+""", RegexOption.IGNORE_CASE)
    private val YOUTUBE_PLAYLIST_REGEX = Regex("""(?:youtube\.com|youtu\.be|music\.youtube\.com)/.*[?&]list=([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val YOUTUBE_BROWSE_REGEX = Regex("""(?:music\.youtube\.com)/browse/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
    private val BANDCAMP_URL_REGEX = Regex("""https?://[a-zA-Z0-9.-]+\.bandcamp\.com/(?:album|track)/[a-zA-Z0-9_-]+""", RegexOption.IGNORE_CASE)
    private val QOBUZ_URL_REGEX = Regex("""https?://(?:open\.qobuz\.com|www\.qobuz\.com)/[a-zA-Z0-9/_-]+""", RegexOption.IGNORE_CASE)
    private val GAANA_URL_REGEX = Regex("""https?://gaana\.com/(?:playlist|album)/[a-zA-Z0-9_-]+""", RegexOption.IGNORE_CASE)
    private val JIOSAAVN_URL_REGEX = Regex("""https?://(?:www\.)?jiosaavn\.com/(?:featured|s/playlist|album)/[a-zA-Z0-9/_,-]+""", RegexOption.IGNORE_CASE)

    // Auth-required provider URL hosts and path patterns (exact provider hosts only)
    private val TIDAL_HOSTS = setOf(
        "tidal.com",
        "www.tidal.com",
        "listen.tidal.com",
        "listen.tidal.co",
    )
    private val SOUNDCLOUD_HOSTS = setOf(
        "soundcloud.com",
        "www.soundcloud.com",
        "m.soundcloud.com",
        "mobile.soundcloud.com",
    )

    private val TIDAL_PATH_REGEX = Regex(
        """^/(?:browse/)?playlist/([0-9a-fA-F-]+)/?$""",
        RegexOption.IGNORE_CASE
    )
    private val SOUNDCLOUD_PATH_REGEX = Regex(
        """^/[a-zA-Z0-9_-]+/(?:sets/)?[a-zA-Z0-9_-]+/?$""",
        RegexOption.IGNORE_CASE
    )

    private val TIDAL_URL_REGEX = Regex(
        """^https?://(?:(?:www\.)?tidal\.com|listen\.tidal\.(?:com|co))(?::\d+)?/(?:browse/)?playlist/([0-9a-fA-F-]+)(?:[?#/].*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val SOUNDCLOUD_URL_REGEX = Regex(
        """^https?://(?:(?:m\.|mobile\.|www\.)?soundcloud\.com)(?::\d+)?/[a-zA-Z0-9_-]+/(?:sets/)?[a-zA-Z0-9_-]+(?:[?#/].*)?$""",
        RegexOption.IGNORE_CASE
    )

    fun extractTidalPlaylistId(input: String): String? {
        val trimmed = input.trim()
        val httpUrl = trimmed.toHttpUrlOrNull()
        if (httpUrl != null) {
            val host = httpUrl.host.lowercase().removeSuffix(".")
            if (host in TIDAL_HOSTS) {
                return TIDAL_PATH_REGEX.find(httpUrl.encodedPath)?.groupValues?.get(1)
            }
            return null
        }
        return TIDAL_URL_REGEX.find(trimmed)?.groupValues?.get(1)
    }

    fun isSoundcloudUrl(input: String): Boolean {
        val trimmed = input.trim()
        val httpUrl = trimmed.toHttpUrlOrNull()
        if (httpUrl != null) {
            val host = httpUrl.host.lowercase().removeSuffix(".")
            if (host in SOUNDCLOUD_HOSTS) {
                return SOUNDCLOUD_PATH_REGEX.matches(httpUrl.encodedPath)
            }
            return false
        }
        return SOUNDCLOUD_URL_REGEX.matches(trimmed)
    }

    enum class AuthCredentialKind {
        TIDAL,
        SOUNDCLOUD;

        val label: String
            get() = when (this) {
                TIDAL -> "Tidal bearer/session token (optional)"
                SOUNDCLOUD -> "SoundCloud client ID (optional)"
            }

        val placeholder: String
            get() = when (this) {
                TIDAL -> "Bearer / session token"
                SOUNDCLOUD -> "Client ID"
            }

        val helperText: String
            get() = when (this) {
                TIDAL -> "Optional token for live API import. You can also paste raw playlist JSON."
                SOUNDCLOUD -> "Optional client ID for live API import. You can also paste raw set JSON."
            }
    }

    /**
     * Detects whether the input URL points to an auth-required provider (Tidal or SoundCloud).
     * Returns null for public URLs, JSON payloads, or pasted tracklists.
     */
    fun detectAuthCredentialKind(input: String): AuthCredentialKind? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return when {
            extractTidalPlaylistId(trimmed) != null -> AuthCredentialKind.TIDAL
            isSoundcloudUrl(trimmed) -> AuthCredentialKind.SOUNDCLOUD
            else -> null
        }
    }

    private fun safeAppleCatalogPath(href: String): String? {
        val absolute = if (href.startsWith("/")) "https://api.music.apple.com$href" else href
        val parsed = absolute.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" || parsed.host != "api.music.apple.com" || parsed.port != 443) return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        return buildString { append(parsed.encodedPath); parsed.encodedQuery?.let { append('?').append(it) } }
    }

    private fun appleNextPagePath(responseBody: String, playlistPage: Boolean): String? {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val linkContainer = if (playlistPage) {
            val playlist = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject
            val relationships = playlist?.get("relationships") as? JsonObject
            relationships?.get("tracks") as? JsonObject ?: return null
        } else {
            root
        }
        val next = linkContainer["next"]
            ?: (linkContainer["links"] as? JsonObject)?.get("next")
        return runCatching { next?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun applePageTrackCount(responseBody: String, playlistPage: Boolean): Int {
        val root = json.parseToJsonElement(responseBody).jsonObject
        val data = root["data"] as? JsonArray ?: return 0
        if (!playlistPage) return data.size
        val playlist = data.firstOrNull() as? JsonObject ?: return 0
        val relationships = playlist["relationships"] as? JsonObject ?: return 0
        val tracks = relationships["tracks"] as? JsonObject ?: return 0
        return (tracks["data"] as? JsonArray)?.size ?: 0
    }

    private fun fetchAppleWebBundle(storefront: String): String {
        var lastStatus: Int? = null
        for (pageUrl in listOf("https://music.apple.com/", "https://music.apple.com/$storefront/browse")) {
            val (status, homepage) = Http.client.newCall(Request.Builder().url(pageUrl).build()).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
            if (status !in 200..299) {
                lastStatus = status
                continue
            }
            val asset = com.music.bitchord.data.apple.discoverWebPlayerAsset(homepage) ?: continue
            return Http.client.newCall(Request.Builder().url("https://music.apple.com$asset").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Apple Music token asset failed (HTTP ${response.code}).")
                response.body?.string().orEmpty()
            }
        }
        val detail = lastStatus?.let { "Apple Music token page failed (HTTP $it) and the storefront page had no web-player asset." }
            ?: "Apple Music token asset unavailable on the home or storefront page."
        throw IOException(detail)
    }

    /**
     * Sanitizes diagnostic messages and exception text so auth tokens and client IDs
     * are never leaked in error logs or UI messages.
     */
    fun sanitizeDiagnostics(message: String?, secret: String? = null): String? {
        if (message == null) return null
        var result = message
        if (!secret.isNullOrBlank()) {
            val trimmedSecret = secret.trim()
            result = result.replace(trimmedSecret, "[REDACTED]")
            try {
                val encoded = URLEncoder.encode(trimmedSecret, "UTF-8")
                result = result.replace(encoded, "[REDACTED]")
            } catch (_: Exception) {
            }
        }
        result = result.replace(Regex("""(?i)(client_id|token|authToken|bearer|key)=([^&\s]+)"""), "$1=[REDACTED]")
        result = result.replace(Regex("""(?i)(bearer\s+)([a-zA-Z0-9_\-.]+)"""), "$1[REDACTED]")
        result = result.replace(Regex("""(?i)(x-tidal-token:\s*)([^\s]+)"""), "$1[REDACTED]")
        return result
    }

    /**
     * Loads an imported playlist from a raw input string.
     * The input may be:
     * 1. A public provider link (Spotify, Deezer, YouTube Music, Bandcamp, Qobuz, Gaana, JioSaavn)
     * 2. An auth-required link (Tidal, SoundCloud) with an optional ephemeral [authToken]
     * 3. An offline JSON response or fixture from any supported provider
     * 4. A pasted text tracklist with deterministic separators (tabs, commas, hyphens, pipes)
     */
    suspend fun loadPlaylist(
        input: String,
        customTitle: String? = null,
        authToken: String? = null,
    ): ImportedPlaylist = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Playlist input cannot be empty." }

        // Check if input is a URL or provider URI
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("spotify:playlist:", ignoreCase = true) ||
            trimmed.startsWith("spotify:album:", ignoreCase = true)
        ) {
            val applePlaylist = extractApplePlaylist(trimmed)
            if (applePlaylist != null) {
                val (storefront, id) = applePlaylist
                val collected = mutableListOf<PlaylistEntry>()
                var nextPath: String? = "/v1/catalog/$storefront/playlists/$id/tracks?limit=100"
                var pageCount = 0
                var sourceOffset = 0
                val token = com.music.bitchord.data.apple.selectWebPlayerToken(fetchAppleWebBundle(storefront))
                    ?: throw IOException("Apple Music developer token unavailable.")
                val playlistMetadataPath = safeAppleCatalogPath("/v1/catalog/$storefront/playlists/$id?include=tracks")
                    ?: throw IOException("Apple Music playlist path is invalid.")
                val metadataRequest = Request.Builder().url("https://api.music.apple.com$playlistMetadataPath")
                    .header("Authorization", "Bearer $token")
                    .header("Origin", "https://music.apple.com")
                    .build()
                val metadataBody = Http.client.newCall(metadataRequest).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Apple Music catalog request failed (HTTP ${response.code}).")
                    response.body?.string().orEmpty()
                }
                val playlistTitle = PlaylistImportParser.parseAppleCatalogPlaylistResponse(metadataBody).title
                val expectedTrackPath = "/v1/catalog/$storefront/playlists/$id/tracks"
                while (nextPath != null && pageCount < 10 && collected.size < 1000) {
                    pageCount++
                    val path = safeAppleCatalogPath(nextPath)
                        ?: throw IOException("Apple Music returned an invalid catalog page link.")
                    if (path.substringBefore('?') != expectedTrackPath) {
                        throw IOException("Apple Music returned an unexpected catalog page path.")
                    }
                    val request = Request.Builder().url("https://api.music.apple.com$path")
                        .header("Authorization", "Bearer $token")
                        .header("Origin", "https://music.apple.com")
                        .build()
                    val body = Http.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Apple Music catalog request failed (HTTP ${response.code}).")
                        response.body?.string().orEmpty()
                    }
                    val parsed = PlaylistImportParser.parseAppleCatalogPlaylistResponse(body)
                    val rawTrackCount = applePageTrackCount(body, playlistPage = false)
                    val pageOffset = sourceOffset
                    collected.addAll(parsed.entries.take(1000 - collected.size).map { it.copy(sourceIndex = pageOffset + it.sourceIndex) })
                    sourceOffset += rawTrackCount
                    val nextHref = appleNextPagePath(body, playlistPage = false)
                    nextPath = nextHref?.let { safeAppleCatalogPath(it) ?: throw IOException("Apple Music returned an invalid catalog page link.") }
                }
                if (nextPath != null) {
                    throw IOException("Apple Music playlist exceeds the safe 1,000-track import limit.")
                }
                return@withContext ImportedPlaylist(
                    customTitle?.takeIf(String::isNotBlank) ?: playlistTitle,
                    PlaylistImportProvider.APPLE_MUSIC,
                    collected,
                    sourceUrl = "https://music.apple.com/$storefront/playlist/imported/$id",
                )
            }

            // 1. Spotify
            val spotifyId = SpotifyImporter.extractPlaylistId(trimmed)
            if (spotifyId != null) {
                val (fetchedTitle, tracks) = SpotifyImporter.fetchPlaylistTracks(spotifyId)
                val entries = tracks.mapIndexed { idx, t ->
                    PlaylistEntry(
                        title = t.title,
                        artist = t.artist,
                        isrc = null,
                        sourceIndex = idx,
                    )
                }
                return@withContext ImportedPlaylist(
                    title = customTitle?.takeIf(String::isNotBlank) ?: fetchedTitle,
                    provider = PlaylistImportProvider.SPOTIFY,
                    entries = entries,
                )
            }

            // 2. Deezer (supports deezer.com/playlist, deezer.com/album, and deezer.page.link)
            val isDeezerShortLink = DEEZER_PAGE_LINK_REGEX.containsMatchIn(trimmed)
            val initialDeezerMatch = DEEZER_URL_REGEX.find(trimmed)
            if (isDeezerShortLink || initialDeezerMatch != null) {
                val resolvedUrl = if (isDeezerShortLink) {
                    val headRequest = Request.Builder().url(trimmed).header("User-Agent", USER_AGENT).build()
                    runCatching {
                        Http.client.newCall(headRequest).execute().use { it.request.url.toString() }
                    }.getOrDefault(trimmed)
                } else trimmed

                val deezerMatch = DEEZER_URL_REGEX.find(resolvedUrl) ?: initialDeezerMatch
                if (deezerMatch != null) {
                    val resourceType = deezerMatch.groupValues[1].lowercase()
                    val resourceId = deezerMatch.groupValues[2]
                    val endpoint = "https://api.deezer.com/$resourceType/$resourceId"
                    val request = Request.Builder()
                        .url("$endpoint?limit=100")
                        .header("User-Agent", USER_AGENT)
                        .build()
                    val responseBody = Http.client.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) error("Failed to load Deezer $resourceType (HTTP ${res.code})")
                        res.body?.string().orEmpty()
                    }

                    var parsed = PlaylistImportParser.parseDeezerPlaylistResponse(responseBody)
                    // Check if Deezer provides pagination
                    val rootObj = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
                    var nextUrl = ((rootObj?.get("tracks") as? JsonObject)?.get("next") ?: rootObj?.get("next"))?.let {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    }

                    if (!nextUrl.isNullOrBlank() && parsed.entries.size < 1000) {
                        val allEntries = parsed.entries.toMutableList()
                        var pages = 0
                        while (!nextUrl.isNullOrBlank() && pages < 10 && allEntries.size < 1000) {
                            pages++
                            val nextReq = Request.Builder().url(nextUrl).header("User-Agent", USER_AGENT).build()
                            val nextBody = runCatching {
                                Http.client.newCall(nextReq).execute().use { it.body?.string().orEmpty() }
                            }.getOrNull() ?: break
                            val nextRoot = runCatching { json.parseToJsonElement(nextBody).jsonObject }.getOrNull() ?: break
                            val nextPageParsed = PlaylistImportParser.parseDeezerPlaylistResponse(nextBody)
                            if (nextPageParsed.entries.isEmpty()) break
                            allEntries.addAll(nextPageParsed.entries)
                            nextUrl = nextRoot.get("next")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                        }
                        parsed = parsed.copy(entries = allEntries.mapIndexed { idx, e -> e.copy(sourceIndex = idx) })
                    }

                    return@withContext if (customTitle != null && customTitle.isNotBlank()) {
                        parsed.copy(title = customTitle)
                    } else parsed
                }
            }

            // 3. YouTube / YouTube Music
            val ytPlaylistMatch = YOUTUBE_PLAYLIST_REGEX.find(trimmed)
            val ytBrowseMatch = YOUTUBE_BROWSE_REGEX.find(trimmed)
            if (ytPlaylistMatch != null || ytBrowseMatch != null) {
                val playlistId = ytPlaylistMatch?.groupValues?.get(1) ?: ytBrowseMatch?.groupValues?.get(1).orEmpty()
                val browseId = if (playlistId.startsWith("VL") || playlistId.startsWith("MPREb")) playlistId else "VL$playlistId"
                val songPageResult = YtMusicRepository.browseSongs(browseId)
                val songPage = songPageResult.getOrNull()
                if (songPage != null && songPage.songs.isNotEmpty()) {
                    val allSongs = mutableListOf<Song>()
                    allSongs.addAll(songPage.songs)
                    var continuation = songPage.continuation
                    var iterations = 0
                    while (continuation != null && iterations < 10 && allSongs.size < 1000) {
                        iterations++
                        val nextPage = YtMusicRepository.moreSongs(continuation).getOrNull() ?: break
                        if (nextPage.songs.isEmpty()) break
                        allSongs.addAll(nextPage.songs)
                        continuation = nextPage.continuation
                    }
                    val entries = allSongs.mapIndexed { idx, s ->
                        PlaylistEntry(
                            title = s.title,
                            artist = s.artist,
                            isrc = null,
                            sourceIndex = idx,
                        )
                    }
                    val title = customTitle?.takeIf(String::isNotBlank)
                        ?: songPage.header?.title
                        ?: "Imported YouTube Music playlist"
                    return@withContext ImportedPlaylist(title, PlaylistImportProvider.YOUTUBE_MUSIC, entries)
                }
            }

            // 4. Bandcamp
            val bandcampMatch = BANDCAMP_URL_REGEX.find(trimmed)
            if (bandcampMatch != null) {
                val request = Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val html = runCatching {
                    Http.client.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) error("Failed to load Bandcamp page (HTTP ${res.code})")
                        res.body?.string().orEmpty()
                    }
                }.getOrNull().orEmpty()

                if (html.isNotBlank()) {
                    val parsed = PlaylistImportParser.parseBandcampPlaylistResponse(html)
                    if (parsed.entries.isNotEmpty()) {
                        return@withContext if (customTitle != null && customTitle.isNotBlank()) {
                            parsed.copy(title = customTitle)
                        } else parsed
                    }
                }
            }

            // 5. Qobuz
            val qobuzMatch = QOBUZ_URL_REGEX.find(trimmed)
            if (qobuzMatch != null) {
                val request = Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val html = runCatching {
                    Http.client.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) error("Failed to load Qobuz page (HTTP ${res.code})")
                        res.body?.string().orEmpty()
                    }
                }.getOrNull().orEmpty()

                if (html.isNotBlank()) {
                    val parsed = PlaylistImportParser.parseQobuzPlaylistResponse(html)
                    if (parsed.entries.isNotEmpty()) {
                        return@withContext if (customTitle != null && customTitle.isNotBlank()) {
                            parsed.copy(title = customTitle)
                        } else parsed
                    }
                }
            }

            // 6. Gaana
            val gaanaMatch = GAANA_URL_REGEX.find(trimmed)
            if (gaanaMatch != null) {
                val request = Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val html = runCatching {
                    Http.client.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) error("Failed to load Gaana page (HTTP ${res.code})")
                        res.body?.string().orEmpty()
                    }
                }.getOrNull().orEmpty()

                if (html.isNotBlank()) {
                    val parsed = PlaylistImportParser.parseGaanaPlaylistResponse(html)
                    if (parsed.entries.isNotEmpty()) {
                        return@withContext if (customTitle != null && customTitle.isNotBlank()) {
                            parsed.copy(title = customTitle)
                        } else parsed
                    }
                }
            }

            // 7. JioSaavn
            val jioMatch = JIOSAAVN_URL_REGEX.find(trimmed)
            if (jioMatch != null) {
                val request = Request.Builder()
                    .url(trimmed)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "explicit_content=1")
                    .build()
                val html = runCatching {
                    Http.client.newCall(request).execute().use { res ->
                        if (!res.isSuccessful) error("Failed to load JioSaavn page (HTTP ${res.code})")
                        res.body?.string().orEmpty()
                    }
                }.getOrNull().orEmpty()

                if (html.isNotBlank()) {
                    val parsed = PlaylistImportParser.parseAuto(html, customTitle ?: "Imported JioSaavn playlist")
                    if (parsed.entries.isNotEmpty()) {
                        return@withContext if (customTitle != null && customTitle.isNotBlank()) {
                            parsed.copy(title = customTitle)
                        } else parsed.copy(provider = PlaylistImportProvider.JIOSAAVN)
                    }
                }
            }

            // 8. Auth-required: Tidal
            val tidalPlaylistId = extractTidalPlaylistId(trimmed)
            if (tidalPlaylistId != null) {
                if (!authToken.isNullOrBlank()) {
                    val cleanToken = authToken.trim()
                    val responseBody = try {
                        val request = Request.Builder()
                            .url("https://api.tidal.com/v1/playlists/$tidalPlaylistId/items")
                            .header("Authorization", "Bearer $cleanToken")
                            .header("x-tidal-token", cleanToken)
                            .header("User-Agent", USER_AGENT)
                            .build()
                        Http.client.newCall(request).execute().use { res ->
                            if (!res.isSuccessful) error("Failed to load Tidal playlist via API (HTTP ${res.code})")
                            res.body?.string().orEmpty()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (ex: Throwable) {
                        val sanitized = sanitizeDiagnostics(ex.message, cleanToken)
                            ?: "Failed to load Tidal playlist via API"
                        throw IOException(sanitized)
                    }
                    val parsed = PlaylistImportParser.parseTidalPlaylistResponse(responseBody)
                    return@withContext if (customTitle != null && customTitle.isNotBlank()) {
                        parsed.copy(title = customTitle)
                    } else parsed
                } else {
                    throw IllegalArgumentException(
                        "Tidal playlists require authenticated API access. Paste the playlist JSON response directly into the import box to import offline, or provide an ephemeral Bearer token."
                    )
                }
            }

            // 9. Auth-required: SoundCloud
            if (isSoundcloudUrl(trimmed)) {
                if (!authToken.isNullOrBlank()) {
                    val cleanToken = authToken.trim()
                    val responseBody = try {
                        val encodedClientId = URLEncoder.encode(cleanToken, "UTF-8")
                        val encodedUrl = URLEncoder.encode(trimmed, "UTF-8")
                        val resolveUrl = "https://api-v2.soundcloud.com/resolve?url=$encodedUrl&client_id=$encodedClientId"
                        val request = Request.Builder()
                            .url(resolveUrl)
                            .header("User-Agent", USER_AGENT)
                            .build()
                        Http.client.newCall(request).execute().use { res ->
                            if (!res.isSuccessful) error("Failed to load SoundCloud set via API (HTTP ${res.code})")
                            res.body?.string().orEmpty()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (ex: Throwable) {
                        val sanitized = sanitizeDiagnostics(ex.message, cleanToken)
                            ?: "Failed to load SoundCloud set via API"
                        throw IOException(sanitized)
                    }
                    val parsed = PlaylistImportParser.parseSoundcloudPlaylistResponse(responseBody)
                    return@withContext if (customTitle != null && customTitle.isNotBlank()) {
                        parsed.copy(title = customTitle)
                    } else parsed
                } else {
                    throw IllegalArgumentException(
                        "SoundCloud sets require API authentication. Paste the set JSON response directly into the import box to import offline, or provide an ephemeral client ID."
                    )
                }
            }

            // 10. General public web page scrape fallback
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", USER_AGENT)
                .build()
            val html = runCatching {
                Http.client.newCall(request).execute().use { it.body?.string().orEmpty() }
            }.getOrNull().orEmpty()

            if (html.isNotBlank()) {
                val parsed = PlaylistImportParser.parseAuto(html, customTitle ?: "Imported playlist")
                if (parsed.entries.isNotEmpty()) {
                    return@withContext if (customTitle != null && customTitle.isNotBlank()) {
                        parsed.copy(title = customTitle)
                    } else parsed
                }
            }
        }

        // If not a URL or URL scraping had no entries, parse as payload or text tracklist
        val parsed = PlaylistImportParser.parseAuto(trimmed, customTitle ?: "Imported playlist")
        if (customTitle != null && customTitle.isNotBlank()) {
            parsed.copy(title = customTitle)
        } else parsed
    }

    /**
     * Resolves imported playlist entries against candidate songs on YouTube Music using bounded concurrency.
     * Preserves exact source indices, track order, and duplicate entries.
     * Reapplies persistent manual match overrides before catalog search.
     */
    suspend fun resolveEntries(
        playlist: ImportedPlaylist,
        concurrency: Int = 4,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ImportRowState> = coroutineScope {
        val total = playlist.entries.size
        if (total == 0) return@coroutineScope emptyList()

        val completedCount = AtomicInteger(0)
        val semaphore = Semaphore(concurrency)

        val deferredResults = playlist.entries.map { entry ->
            async(Dispatchers.IO) {
                // 1. Reapply persistent manual overrides before catalog search
                val cachedOverride = PlaylistImportOverrides.getOverride(entry)
                if (cachedOverride != null) {
                    val done = completedCount.incrementAndGet()
                    onProgress(done, total)
                    return@async cachedOverride.toRowState(entry)
                }

                // 2. Query candidates from catalogue
                semaphore.withPermit {
                    val query = "${entry.title} ${entry.artist}".trim()
                    val searchResult = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
                    val candidateSongs = searchResult?.filterIsInstance<SearchResult.Track>()
                        ?.map { it.song }
                        .orEmpty()

                    val candidateModels = candidateSongs.map { song ->
                        ImportCandidate(
                            id = song.videoId,
                            title = song.title,
                            artist = song.artist,
                            isrc = null,
                        )
                    }

                    val candidateMap = candidateSongs.associateBy { it.videoId }
                    val matchState = PlaylistImportMatcher.resolve(entry, candidateModels)

                    val matchedSong = if (matchState is ImportMatchState.Matched) {
                        candidateMap[matchState.candidate.id]
                    } else null

                    val done = completedCount.incrementAndGet()
                    onProgress(done, total)

                    ImportRowState(
                        entry = entry,
                        matchState = matchState,
                        candidateSong = matchedSong,
                        availableCandidates = candidateSongs,
                    )
                }
            }
        }

        deferredResults.awaitAll()
    }

    /**
     * Performs a fresh candidate search for an individual track during manual review.
     */
    suspend fun searchCandidates(query: String): List<Song> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        val searchResult = YtMusicRepository.search(trimmed, SearchFilter.SONGS).getOrNull()
        searchResult?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()
    }

    /**
     * Applies a manual candidate selection to an import row and persists the override across sessions.
     * If [candidate] is null, the row is marked as explicitly unmatched.
     */
    fun applyManualCandidate(row: ImportRowState, candidate: Song?): ImportRowState {
        return if (candidate != null) {
            PlaylistImportOverrides.saveOverride(row.entry, candidate)
            val confirmedCandidate = ImportCandidate(
                id = candidate.videoId,
                title = candidate.title,
                artist = candidate.artist,
                isrc = null,
            )
            row.copy(
                candidateSong = candidate,
                matchState = ImportMatchState.ManualConfirmed(confirmedCandidate),
            )
        } else {
            PlaylistImportOverrides.saveUnmatched(row.entry)
            row.copy(
                candidateSong = null,
                matchState = ImportMatchState.Unmatched,
            )
        }
    }

    /**
     * Clears any manual override for [row], re-evaluating candidate matching against available candidates.
     */
    fun clearManualOverride(row: ImportRowState): ImportRowState {
        PlaylistImportOverrides.removeOverride(row.entry)
        val candidateModels = row.availableCandidates.map { song ->
            ImportCandidate(
                id = song.videoId,
                title = song.title,
                artist = song.artist,
                isrc = null,
            )
        }
        val matchState = PlaylistImportMatcher.resolve(row.entry, candidateModels)
        val candidateMap = row.availableCandidates.associateBy { it.videoId }
        val matchedSong = if (matchState is ImportMatchState.Matched) {
            candidateMap[matchState.candidate.id]
        } else null
        return row.copy(
            candidateSong = matchedSong,
            matchState = matchState,
        )
    }
}
