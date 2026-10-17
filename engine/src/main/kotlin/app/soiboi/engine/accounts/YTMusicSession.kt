package app.soiboi.engine.accounts

import app.soiboi.engine.download.PipelineRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * YouTube Music session. Uses yt-dlp for both metadata and streaming.
 *
 * For premium features (personal mixes, full-length tracks), requires a cookies file.
 */
class YTMusicSession(
    private val pipeline: PipelineRunner,
    private val cookiesPath: () -> String?,
) : MusicSession {

    override val id = "ytmusic"
    override val label = "YouTube Music"
    override val canStream = true

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Check if cookies exist and update state. */
    suspend fun refresh() {
        val cookies = cookiesPath()
        if (cookies.isNullOrBlank() || !File(cookies).exists()) {
            _state.value = SessionState.SignedOut
            return
        }
        // YT Music doesn't have a simple "am I signed in" check;
        // assume signed in if cookies exist
        _state.value = SessionState.SignedIn(
            account = "YouTube Music",
            market = "US",
        )
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> {
        // Requires ytmusicapi; not wired
        return emptyList()
    }

    override suspend fun playlistTracks(playlistId: String): List<SessionTrack> {
        // Extract playlist via yt-dlp
        return emptyList()
    }

    override suspend fun recentlyAdded(sinceMs: Long): List<SessionTrack> {
        return emptyList()
    }

    override suspend fun search(query: String): SessionSearchResult {
        return SessionSearchResult()
    }

    override suspend fun streamUrl(trackId: String): StreamResult? {
        // Use existing youtube_stream handler
        val payload = buildJsonObject {
            put("id", trackId)
            cookiesPath()?.let { put("cookies_path", it) }
        }

        var result: app.soiboi.engine.download.PipelineEvent? = null
        pipeline.run("youtube_stream", payload).collect { event ->
            if (event.isDone || event.isError) result = event
        }

        val r = result ?: return null
        if (r.isError) return null

        val url = (r.raw["url"] as? JsonPrimitive)?.content ?: return null
        val ext = (r.raw["ext"] as? JsonPrimitive)?.content ?: "m4a"

        return StreamResult(
            url = url,
            format = ext,
            bitrate = 128, // YT varies; estimate
            headers = (r.raw["headers"] as? kotlinx.serialization.json.JsonObject)
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }
                ?.toMap()
                .orEmpty(),
        )
    }

    override suspend fun downloadRef(trackId: String): DownloadRef {
        return DownloadRef(
            session = id,
            trackId = trackId,
            label = "YouTube Music track $trackId",
            artist = null,
            album = null,
            isrc = null,
        )
    }
}
