package app.soiboi.engine.accounts

import app.soiboi.engine.download.PipelineRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SoundCloud session. Uses yt-dlp for streaming and download.
 *
 * SoundCloud's API is partially public, but full access requires OAuth.
 * For simplicity, this uses yt-dlp which handles both free and Go+ tracks.
 */
class SoundCloudSession(
    private val pipeline: PipelineRunner,
    private val saveCredential: (String) -> Unit = {},
) : MusicSession {

    override val id = "soundcloud"
    override val label = "SoundCloud"
    override val canStream = true

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    // Public access by default; OAuth would enable Go+ features
    init {
        _state.value = SessionState.SignedIn(
            account = "SoundCloud (Public)",
            market = "US",
        )
    }

    fun setCredential(cookies: String) {
        saveCredential(cookies)
        _state.value = SessionState.SignedIn(account = "SoundCloud", market = "US")
    }

    suspend fun refresh() {
        // Public access always works; OAuth check would go here
        _state.value = SessionState.SignedIn(
            account = "SoundCloud (Public)",
            market = "US",
        )
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> {
        // Requires OAuth
        return emptyList()
    }

    override suspend fun playlistTracks(playlistId: String): List<SessionTrack> {
        return emptyList()
    }

    override suspend fun recentlyAdded(sinceMs: Long): List<SessionTrack> {
        return emptyList()
    }

    override suspend fun search(query: String): SessionSearchResult {
        return SessionSearchResult()
    }

    override suspend fun streamUrl(trackId: String): StreamResult? {
        // SoundCloud URLs go through yt-dlp
        val payload = buildJsonObject {
            put("id", trackId)
            put("extractor", "soundcloud")
        }

        var result: app.soiboi.engine.download.PipelineEvent? = null
        pipeline.run("youtube_stream", payload).collect { event ->
            if (event.isDone || event.isError) result = event
        }

        val r = result ?: return null
        if (r.isError) return null

        val url = (r.raw["url"] as? JsonPrimitive)?.content ?: return null
        val ext = (r.raw["ext"] as? JsonPrimitive)?.content ?: "mp3"

        return StreamResult(
            url = url,
            format = ext,
            bitrate = 128,
        )
    }

    override suspend fun downloadRef(trackId: String): DownloadRef {
        return DownloadRef(
            session = id,
            trackId = trackId,
            label = "SoundCloud track $trackId",
            artist = null,
            album = null,
            isrc = null,
        )
    }
}
