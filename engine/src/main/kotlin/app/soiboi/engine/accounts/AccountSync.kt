package app.soiboi.engine.accounts

import app.soiboi.engine.core.ownedSongKey
import app.soiboi.engine.download.DownloadQueue
import app.soiboi.engine.download.DownloadRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Auto-sync service: monitors signed-in sessions for new playlist additions
 * and queues them for download into the local library.
 *
 * Each playlist has a cursor tracking which tracks have been seen. When new
 * tracks appear, they're resolved via the session's downloadRef and queued.
 *
 * Sync is disabled by default; call setEnabled(true) to start.
 */
class AccountSync(
    private val sessions: SessionRegistry,
    private val queue: DownloadQueue,
    private val cursorDir: File,
    private val ownedKeys: () -> Set<String>,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private var syncJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        cursorDir.mkdirs()
    }

    /** Enable or disable auto-sync. */
    fun setEnabled(value: Boolean) {
        _enabled.value = value
        if (value && syncJob == null) {
            startPeriodicSync()
        } else if (!value) {
            stopSync()
        }
    }

    /** Start periodic sync with the given interval (only if enabled). */
    fun startPeriodicSync(intervalMs: Long = 30 * 60 * 1000) {
        if (!_enabled.value) return
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive && _enabled.value) {
                runCatching { syncAll() }
                delay(intervalMs)
            }
        }
    }

    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /** Sync all signed-in sessions (respects enabled flag). */
    suspend fun syncAll() {
        if (!_enabled.value) return
        for (session in sessions.signedIn()) {
            runCatching { syncSession(session) }
        }
    }

    /** Sync a single session's playlists. */
    suspend fun syncSession(session: MusicSession) {
        val playlists = session.libraryPlaylists()

        for (playlist in playlists) {
            val cursor = loadCursor(session.id, playlist.id)
            val tracks = session.playlistTracks(playlist.id)
            val currentIds = tracks.map { it.id }.toSet()

            val newTracks = tracks.filter { it.id !in cursor.knownTrackIds }

            val owned = ownedKeys()
            val toDownload = newTracks.filter { track ->
                val key = ownedSongKey(track.artist ?: "", track.title)
                key !in owned
            }

            if (toDownload.isNotEmpty()) {
                for (track in toDownload) {
                    val ref = runCatching { session.downloadRef(track.id) }.getOrNull() ?: continue
                    // Apple: direct catalog URL; others: ISRC resolution via session_download
                    val url = when (session.id) {
                        "apple" -> "https://music.apple.com/song/${ref.trackId}"
                        else -> "session://${ref.session}/${ref.trackId}?isrc=${ref.isrc.orEmpty()}&artist=${java.net.URLEncoder.encode(ref.artist.orEmpty(), "UTF-8")}&title=${java.net.URLEncoder.encode(ref.label, "UTF-8")}"
                    }
                    queue.enqueue(listOf(DownloadRequest(url, ref.label, ref.artist, "Sync: ${playlist.name}")))
                }
            }

            saveCursor(session.id, playlist.id, SyncCursor(
                playlistId = playlist.id,
                lastSyncMs = System.currentTimeMillis(),
                knownTrackIds = currentIds,
            ))
        }
    }

    @Serializable
    data class SyncCursor(
        val playlistId: String,
        val lastSyncMs: Long,
        val knownTrackIds: Set<String>,
    )

    private fun cursorFile(sessionId: String, playlistId: String): File {
        val safe = playlistId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(cursorDir, "${sessionId}_${safe}.json")
    }

    private fun loadCursor(sessionId: String, playlistId: String): SyncCursor {
        val file = cursorFile(sessionId, playlistId)
        if (!file.exists()) return SyncCursor(playlistId, 0L, emptySet())
        return runCatching {
            json.decodeFromString<SyncCursor>(file.readText())
        }.getOrElse { SyncCursor(playlistId, 0L, emptySet()) }
    }

    private fun saveCursor(sessionId: String, playlistId: String, cursor: SyncCursor) {
        val file = cursorFile(sessionId, playlistId)
        file.writeText(json.encodeToString(cursor))
    }

    /** Clear all cursors (for testing or reset). */
    fun clearCursors() {
        cursorDir.listFiles()?.forEach { it.delete() }
    }

    /** Get sync status for UI display. */
    fun lastSyncTime(sessionId: String, playlistId: String): Long? {
        return loadCursor(sessionId, playlistId).let {
            if (it.lastSyncMs > 0) it.lastSyncMs else null
        }
    }
}
