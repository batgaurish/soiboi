package app.soiboi.engine.discovery

import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.core.SettingsStore
import app.soiboi.engine.download.CookieStore
import app.soiboi.engine.download.DownloadBatch
import app.soiboi.engine.download.DownloadQueue
import app.soiboi.engine.download.DownloadRequest
import app.soiboi.engine.download.PipelineRunner
import app.soiboi.engine.download.WrapperService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One playlist in the account's library. */
class ApplePlaylist(
    val libraryId: String, val name: String,
    /** The `pl.*` catalog id, when Apple has a published copy. Null means this playlist exists only in the user's library. */
    val catalogId: String? = null, val trackCount: Int? = null, val artworkUrl: String? = null, val description: String? = null,
) {
    /** Whether this can be archived as a single URL. */
    val hasCatalogUrl: Boolean get() = !catalogId.isNullOrEmpty()
}

class AppleHomeItem(val id: String, val type: String, val title: String, val subtitle: String, val artworkUrl: String?)
class AppleHomeShelf(val title: String, val items: List<AppleHomeItem>)

/** One track of a library-only playlist. */
class AppleLibraryTrack(val title: String, val artist: String? = null, val album: String? = null, val catalogId: String? = null) {
    /** Null for a track the user uploaded themselves: there is no catalog entry to download. */
    val archivable: Boolean get() = !catalogId.isNullOrEmpty()
}

class ApplePlaylistsResult(
    val playlists: List<ApplePlaylist> = emptyList(),
    /** Needed to build catalog URLs: a playlist id only resolves inside the account's storefront. */
    val storefront: String = "us", val error: String? = null,
    /** The session is gone or was never there, so the fix is signing in rather than retrying. */
    val needsSignIn: Boolean = false,
)

class AppleLibraryException(message: String) : Exception(message)

/** What queueing a playlist actually did, so the screen can be honest about tracks it could not take. */
class AppleImportOutcome(val batch: DownloadBatch?, val queued: Int = 0, val skipped: Int = 0, val error: String? = null)

fun catalogPlaylistUrl(id: String, storefront: String) = "https://music.apple.com/$storefront/playlist/playlist/$id"
fun catalogSongUrl(id: String, storefront: String) = "https://music.apple.com/$storefront/song/song/$id"
fun catalogAlbumUrl(id: String, storefront: String) = "https://music.apple.com/$storefront/album/album/$id"

/**
 * The signed-in account's own Apple Music playlists. The cookies the downloader uses are exactly what Apple's
 * personalised endpoints want, so the library can simply be listed. Catalog-backed playlists carry a `pl.*` id
 * (one request, the downloader handles order and artwork); library-only playlists are listed and queued one
 * track at a time, and uploads with no catalog entry are reported, not silently dropped.
 */
class AppleLibrary(
    private val runner: PipelineRunner,
    private val cookies: CookieStore,
    private val wrapper: WrapperService,
    private val settings: SettingsStore,
    private val queue: DownloadQueue,
    private val linked: LinkedPlaylists,
) {
    val hasAuth: Boolean get() = cookies.signedIn.value || wrapper.signedIn.value

    // Cache for home shelves with timestamp, so we don't re-fetch on every navigation
    private var cachedHome: List<AppleHomeShelf>? = null
    private var cachedHomeTimestamp: Long = 0
    private var cachedPlaylists: List<ApplePlaylist>? = null
    private var cachedPlaylistsTimestamp: Long = 0
    private val cacheTtlMs = 5 * 60 * 1000L  // 5 minutes

    /** Clears cached data, forcing the next fetch to hit the network. */
    fun invalidateCache() {
        cachedHome = null
        cachedPlaylists = null
    }

    /** The signed-in wrapper's address, which the pipeline prefers over the cookies file. Empty when it is not signed in. */
    private suspend fun wrapperAuth(): Map<String, String> {
        val url = wrapper.ensureReady()?.get("wrapper_url") as? String
        return if (url == null) emptyMap() else mapOf("wrapper_url" to url)
    }

    private suspend fun payload(vararg extra: Pair<String, String>) = buildJsonObject {
        put("cookies_path", cookies.path)
        wrapperAuth().forEach { (k, v) -> put(k, v) }
        extra.forEach { (k, v) -> put(k, v) }
    }

    /** Lists the account's playlists. Never throws: this runs off a tap on the Downloads screen. Uses cache for 5 minutes. */
    suspend fun fetchPlaylists(): ApplePlaylistsResult {
        val now = System.currentTimeMillis()
        cachedPlaylists?.takeIf { now - cachedPlaylistsTimestamp < cacheTtlMs }?.let {
            return ApplePlaylistsResult(it, settings.value.storefront)
        }
        
        return try {
            var error: String? = null; var needsSignIn = false; var result: ApplePlaylistsResult? = null
            runner.run("apple_playlists", payload()).collect { e ->
                if (e.isError) { error = e.message; needsSignIn = e.code == "no_cookies" || e.code == "auth" }
                else if (e.isDone) {
                    val sf = (e.raw["storefront"] as? JsonPrimitive)?.contentOrNull
                    val playlists = (e.raw["playlists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::playlistFrom) }
                    result = ApplePlaylistsResult(playlists, sf ?: "us")
                    cachedPlaylists = playlists
                    cachedPlaylistsTimestamp = now
                    if (!sf.isNullOrEmpty()) rememberStorefront(sf)
                }
            }
            result ?: ApplePlaylistsResult(error = error ?: "Could not read your Apple Music library", needsSignIn = needsSignIn)
        } catch (e: Exception) {
            ApplePlaylistsResult(error = e.toString())
        }
    }

    /** Fetches personalized and catalog Apple Music shelves for the streaming home. Uses cache for 5 minutes. */
    suspend fun fetchHome(): List<AppleHomeShelf> {
        val now = System.currentTimeMillis()
        cachedHome?.takeIf { now - cachedHomeTimestamp < cacheTtlMs }?.let { return it }
        
        var result: List<AppleHomeShelf> = emptyList()
        runner.run("apple_home", payload("storefront" to settings.value.storefront)).collect { e ->
            if (e.isError) {
                EngineLog.output("apple home pipeline error: code=${e.code} message=${e.message}")
                throw AppleLibraryException(e.message)
            }
            if (e.isDone) {
                result = (e.raw["shelves"] as? JsonArray).orEmpty().mapNotNull { shelf ->
                    val o = shelf as? JsonObject ?: return@mapNotNull null
                    val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                        val i = item as? JsonObject ?: return@mapNotNull null
                        val id = (i["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        AppleHomeItem(
                            id = id,
                            type = (i["type"] as? JsonPrimitive)?.contentOrNull ?: "unknown",
                            title = (i["title"] as? JsonPrimitive)?.contentOrNull ?: "Untitled",
                            subtitle = (i["subtitle"] as? JsonPrimitive)?.contentOrNull ?: "Apple Music",
                            artworkUrl = (i["artwork_url"] as? JsonPrimitive)?.contentOrNull,
                        )
                    }
                    AppleHomeShelf(title, items)
                }
            }
        }
        cachedHome = result
        cachedHomeTimestamp = now
        return result
    }

    private fun playlistFrom(o: JsonObject): ApplePlaylist {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return ApplePlaylist(s("library_id") ?: "", s("name") ?: "Untitled playlist", s("catalog_id"), (o["track_count"] as? JsonPrimitive)?.intOrNull, s("artwork_url"), s("description"))
    }

    /** The tracks of one library playlist. Throws [AppleLibraryException] with the pipeline's own message, so an expired session reads as one rather than an empty playlist. */
    suspend fun fetchPlaylistTracks(libraryId: String): List<AppleLibraryTrack> {
        val out = ArrayList<AppleLibraryTrack>()
        runner.run("apple_playlist_tracks", payload("library_id" to libraryId)).collect { e ->
            if (e.isError) throw AppleLibraryException(e.message)
            if (e.isDone) (e.raw["tracks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { o ->
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
                out += AppleLibraryTrack(s("title") ?: "Unknown track", s("artist"), s("album"), s("catalog_id"))
            }
        }
        return out
    }

    /** Queues [playlist] for archiving: one request when Apple has a catalog copy, otherwise one per track. */
    suspend fun archivePlaylist(playlist: ApplePlaylist, storefront: String): AppleImportOutcome {
        if (playlist.hasCatalogUrl) {
            val batch = queue.enqueue(listOf(DownloadRequest(catalogPlaylistUrl(playlist.catalogId!!, storefront), playlist.name, "Apple Music playlist")))
            // The local playlist is a bonus on top of the download; a failed track listing must not undo the queueing above.
            try { linkTracks(playlist, fetchPlaylistTracks(playlist.libraryId)) } catch (e: AppleLibraryException) { EngineLog.output("apple playlist link: ${e.message}") }
            return AppleImportOutcome(batch, queued = 1)
        }
        val tracks = try { fetchPlaylistTracks(playlist.libraryId) } catch (e: AppleLibraryException) { return AppleImportOutcome(null, error = e.message) }
        linkTracks(playlist, tracks)
        if (tracks.isEmpty()) return AppleImportOutcome(null, error = "That playlist came back empty.")
        val archivable = tracks.filter { it.archivable }
        if (archivable.isEmpty()) return AppleImportOutcome(null, skipped = tracks.size, error = "None of these tracks exist in the Apple catalog.")
        val batch = queue.enqueue(archivable.map { DownloadRequest(catalogSongUrl(it.catalogId!!, storefront), it.title, it.artist, playlist.name) })
        return AppleImportOutcome(batch, queued = archivable.size, skipped = tracks.size - archivable.size)
    }

    /** Creates a local playlist from [playlist] out of songs already in the library, without downloading anything. */
    suspend fun linkPlaylist(playlist: ApplePlaylist): LinkResult? = linkTracks(playlist, fetchPlaylistTracks(playlist.libraryId))

    private suspend fun linkTracks(playlist: ApplePlaylist, tracks: List<AppleLibraryTrack>) =
        linked.link(playlist.name, tracks.map { LinkedTrack(it.artist ?: "", it.title) })

    /** Reads the signed-in account's storefront and remembers it. Null when there is no sign-in or no answer (the remembered one stays). */
    suspend fun refreshStorefront(): String? {
        if (!hasAuth) return null
        try {
            var found: String? = null
            runner.run("apple_storefront", payload()).collect { e ->
                val sf = (e.raw["storefront"] as? JsonPrimitive)?.contentOrNull
                if (e.isDone && !sf.isNullOrEmpty()) { rememberStorefront(sf); found = sf }
            }
            return found
        } catch (e: Exception) {
            EngineLog.output("apple storefront: $e")
        }
        return null
    }

    private fun rememberStorefront(sf: String) { if (settings.value.appleStorefront != sf) settings.update { copy(appleStorefront = sf) } }

    /** The storefront to match catalog tracks in: the remembered one, else asked of the account, else the US. */
    suspend fun accountStorefront(): String {
        if (settings.value.appleStorefront.isEmpty()) refreshStorefront()
        return settings.value.storefront
    }
}
