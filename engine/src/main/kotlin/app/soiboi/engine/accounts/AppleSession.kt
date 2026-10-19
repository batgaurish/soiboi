package app.soiboi.engine.accounts

import app.soiboi.engine.discovery.AppleLibrary
import app.soiboi.engine.download.PipelineRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Apple Music session. Wraps the existing AppleLibrary for playlist/track access
 * and adds streaming via Widevine decryption in the pipeline.
 */
class AppleSession(
    private val library: AppleLibrary,
    private val pipeline: PipelineRunner,
    private val cacheDir: File,
    private val cookiesPath: () -> String?,
    private val wrapperUrl: () -> String?,
) : MusicSession {

    override val id = "apple"
    override val label = "Apple Music"

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Check if signed in and update state. Call on startup and after sign-in. */
    suspend fun refresh() {
        val cookies = cookiesPath()
        if (cookies.isNullOrBlank() || !File(cookies).exists()) {
            _state.value = SessionState.SignedOut
            return
        }
        try {
            val storefront = library.accountStorefront()
            _state.value = SessionState.SignedIn(
                account = "Apple Music",
                market = storefront,
            )
        } catch (e: Exception) {
            _state.value = SessionState.Error(e.message ?: "Failed to verify Apple Music session")
        }
    }

    override suspend fun libraryPlaylists(): List<SessionPlaylist> {
        val result = library.fetchPlaylists()
        return result.playlists.map { playlist ->
            SessionPlaylist(
                id = playlist.libraryId,
                name = playlist.name,
                trackCount = playlist.trackCount,
                artworkUrl = playlist.artworkUrl,
            )
        }
    }

    override suspend fun playlistTracks(playlistId: String): List<SessionTrack> {
        return library.fetchPlaylistTracks(playlistId).map { track ->
            SessionTrack(
                id = track.catalogId ?: track.title,
                title = track.title,
                artist = track.artist,
                album = track.album,
                isrc = null, // Apple doesn't expose ISRC in playlist tracks
            )
        }
    }

    override suspend fun recentlyAdded(sinceMs: Long): List<SessionTrack> {
        // Apple's library API lacks dateAdded; would need get_library_songs
        return emptyList()
    }

    override suspend fun search(query: String): SessionSearchResult {
        // Not implemented; would need AppleCatalog wiring
        return SessionSearchResult()
    }

    override suspend fun streamUrl(trackId: String): StreamResult? {
        val cookies = cookiesPath() ?: return null
        val streamCacheDir = File(cacheDir, "apple_stream").also { it.mkdirs() }

        val payload = buildJsonObject {
            put("track_id", trackId)
            put("cookies_path", cookies)
            wrapperUrl()?.let { put("wrapper_url", it) }
            put("cache_dir", streamCacheDir.absolutePath)
            put("quality", "aac")
        }

        var result: app.soiboi.engine.download.PipelineEvent? = null
        pipeline.run("apple_stream", payload).collect { event ->
            if (event.isDone || event.isError) result = event
        }

        val r = result ?: return null
        if (r.isError) return null

        val path = (r.raw["path"] as? JsonPrimitive)?.content ?: return null
        val format = (r.raw["format"] as? JsonPrimitive)?.content ?: "m4a"
        val bitrate = (r.raw["bitrate"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 256

        return StreamResult(
            url = "file://$path",
            format = format,
            bitrate = bitrate,
        )
    }

    override suspend fun downloadRef(trackId: String): DownloadRef {
        // For Apple, the trackId is already a catalog ID usable for download
        return DownloadRef(
            session = id,
            trackId = trackId,
            label = "Apple Music track $trackId",
            artist = null,
            album = null,
            isrc = null,
        )
    }

    override val canStream: Boolean = true
}
