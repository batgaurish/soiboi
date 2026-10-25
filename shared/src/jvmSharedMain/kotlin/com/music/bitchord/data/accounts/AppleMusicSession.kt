package com.music.bitchord.data.accounts

import com.music.bitchord.data.Http
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.apple.discoverWebPlayerAsset
import com.music.bitchord.data.apple.extractMusicUserToken
import com.music.bitchord.data.apple.selectWebPlayerToken
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Apple Music account session backed by Apple's public Web API.
 * Uses the Music-User-Token stored in secure credentials and the scraped web-player
 * developer token to query the user's library playlists and track metadata.
 * Tracks are resolved to download references via catalogue matching. Streaming is excluded.
 */
class AppleMusicSession(
    private val cookieHeader: () -> String?,
    private val storefront: () -> String = { "us" },
    private val client: OkHttpClient = Http.client,
    private val clock: () -> Long = System::currentTimeMillis,
) : MusicSession {

    override val id = "apple"
    override val label = "Apple Music"

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private var cachedDeveloperToken: String? = null
    private var tokenFetchedAt = 0L
    private val json = Json { ignoreUnknownKeys = true }

    init {
        updateState()
    }

    private fun updateState() {
        val mut = extractMusicUserToken(cookieHeader())
        if (!mut.isNullOrBlank()) {
            _state.value = SessionState.SignedIn("Apple Music", storefront())
        } else {
            _state.value = SessionState.SignedOut
        }
    }

    override suspend fun refresh() {
        updateState()
    }

    private suspend fun developerToken(): String {
        val current = cachedDeveloperToken
        if (current != null && clock() - tokenFetchedAt < TOKEN_TTL) return current

        val home = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url("https://music.apple.com/").build()).execute().use { response ->
                if (!response.isSuccessful) error("Apple Music homepage returned HTTP ${response.code}")
                response.body.string()
            }
        }
        val asset = discoverWebPlayerAsset(home) ?: error("Apple Music web-player token asset was not found")
        val bundle = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url("https://music.apple.com$asset").build()).execute().use { response ->
                if (!response.isSuccessful) error("Apple Music web-player bundle returned HTTP ${response.code}")
                response.body.string()
            }
        }
        val jwt = selectWebPlayerToken(bundle) ?: error("Apple Music web-player token was not found")
        cachedDeveloperToken = jwt
        tokenFetchedAt = clock()
        return jwt
    }

    private suspend fun apiGet(path: String, mut: String): String = withContext(Dispatchers.IO) {
        var token = developerToken()
        val makeRequest = { devToken: String ->
            Request.Builder()
                .url("https://api.music.apple.com$path")
                .header("Authorization", "Bearer $devToken")
                .header("Origin", "https://music.apple.com")
                .header("Music-User-Token", mut)
                .build()
        }

        var response = client.newCall(makeRequest(token)).execute()
        if (response.code == 401) {
            response.close()
            cachedDeveloperToken = null
            token = developerToken()
            response = client.newCall(makeRequest(token)).execute()
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                error("Apple Music API returned HTTP ${resp.code}")
            }
            resp.body.string()
        }
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> {
        val mut = extractMusicUserToken(cookieHeader()) ?: return emptyList()
        return runCatching {
            val body = apiGet("/v1/me/library/playlists?limit=100", mut)
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray.orEmpty()
            data.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val attr = obj["attributes"]?.jsonObject ?: return@mapNotNull null
                val name = attr["name"]?.jsonPrimitive?.content ?: "Untitled playlist"
                val trackCount = attr["trackCount"]?.jsonPrimitive?.content?.toIntOrNull()
                val artworkRaw = attr["artwork"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                val artwork = artworkRaw?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg")
                SessionPlaylist(id = id, name = name, trackCount = trackCount, artworkUrl = artwork)
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun playlistTracks(playlistId: String): List<SessionTrack> {
        val mut = extractMusicUserToken(cookieHeader()) ?: return emptyList()
        return runCatching {
            val body = apiGet("/v1/me/library/playlists/$playlistId/tracks?limit=100", mut)
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray.orEmpty()
            data.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val attr = obj["attributes"]?.jsonObject ?: return@mapNotNull null
                val title = attr["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val artist = attr["artistName"]?.jsonPrimitive?.content
                val album = attr["albumName"]?.jsonPrimitive?.content
                val durationMs = attr["durationInMillis"]?.jsonPrimitive?.content?.toLongOrNull()
                val isrc = attr["isrc"]?.jsonPrimitive?.content
                val playParams = attr["playParams"]?.jsonObject
                val catalogId = playParams?.get("catalogId")?.jsonPrimitive?.content
                    ?: playParams?.get("id")?.jsonPrimitive?.content
                SessionTrack(
                    id = catalogId ?: id,
                    title = title,
                    artist = artist,
                    album = album,
                    isrc = isrc,
                    durationMs = durationMs,
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun resolveDownload(track: SessionTrack): Song? {
        val query = listOfNotNull(track.title, track.artist).filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) return null
        val candidates = YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            ?.filterIsInstance<SearchResult.Track>()
            ?.map { it.song }
            .orEmpty()
        return TrackMatcher.best(
            candidates,
            TrackMatcher.Target(
                title = track.title,
                artist = track.artist.orEmpty(),
                durationSec = track.durationMs?.takeIf { it > 0 }?.div(1000)?.toInt(),
                album = track.album,
            ),
        ) ?: candidates.firstOrNull()
    }

    companion object {
        private const val TOKEN_TTL = 30 * 60 * 1000L
    }
}
