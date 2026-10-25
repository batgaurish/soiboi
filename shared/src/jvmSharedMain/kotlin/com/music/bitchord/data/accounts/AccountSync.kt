package com.music.bitchord.data.accounts

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Account playlist background sync & auto-download loop.
 *
 * Monitors signed-in music service account playlists (YouTube Music, Spotify, Apple Music),
 * compares track additions against persisted sync cursors, filters against already-owned
 * local library tracks, and queues newly discovered tracks to the platform download queue.
 *
 * Disabled by default; runs every 30 minutes when enabled.
 */
class AccountSync(
    private val sessions: SessionRegistry,
    private val cursorStore: AccountSyncCursorStore,
    private val ownedKeys: () -> Set<String>,
    private val onEnqueueDownload: (Song, String) -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    private var syncJob: Job? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _statuses = MutableStateFlow<Map<String, AccountSyncStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, AccountSyncStatus>> = _statuses.asStateFlow()

    /** Enable or disable auto-sync. When enabling, begins periodic diff loop. */
    fun setEnabled(value: Boolean, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        _enabled.value = value
        if (value && syncJob == null) {
            startPeriodicSync(intervalMs)
        } else if (!value) {
            stopSync()
        }
    }

    /** Start periodic sync with the given interval (runs only while enabled). */
    fun startPeriodicSync(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (!_enabled.value) return
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive && _enabled.value) {
                try {
                    syncAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Session errors are tracked individually in _statuses
                }
                delay(intervalMs)
            }
        }
    }

    /** Stop active periodic background sync loop. */
    fun stopSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /** Trigger an immediate manual sync cycle across all signed-in sessions. */
    fun syncNow() {
        scope.launch {
            try {
                syncAll(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Handled in syncAll / syncSession statuses
            }
        }
    }

    /** Sync all signed-in sessions. Respects enabled flag unless [force] is true. */
    suspend fun syncAll(force: Boolean = false): AccountSyncReport {
        if (!_enabled.value && !force) {
            return AccountSyncReport(System.currentTimeMillis(), _statuses.value, 0, 0)
        }
        _isSyncing.value = true
        var totalNew = 0
        var totalQueued = 0
        val queuedKeys = mutableSetOf<String>()
        try {
            val targets = sessions.signedIn().filter { it.canSync }
            for (session in targets) {
                val status = try {
                    syncSession(session, queuedKeys)
                } catch (e: CancellationException) {
                    throw e
                } catch (err: Throwable) {
                    AccountSyncStatus(
                        sessionId = session.id,
                        isSyncing = false,
                        lastSyncMs = System.currentTimeMillis(),
                        error = err.message ?: "Failed to sync ${session.label}",
                    )
                }
                _statuses.update { it + (session.id to status) }
                totalNew += status.newTracksFound
                totalQueued += status.newTracksFound
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            _isSyncing.value = false
        }
        return AccountSyncReport(System.currentTimeMillis(), _statuses.value, totalNew, totalQueued)
    }

    /** Sync a single session's playlists. */
    suspend fun syncSession(
        session: MusicSession,
        queuedKeys: MutableSet<String> = mutableSetOf(),
    ): AccountSyncStatus {
        _statuses.update { current ->
            val prev = current[session.id] ?: AccountSyncStatus(session.id)
            current + (session.id to prev.copy(isSyncing = true, error = null))
        }

        var newTracksCount = 0
        var playlistsSynced = 0
        var sessionError: String? = null

        try {
            val playlists = session.libraryPlaylists()
            for (playlist in playlists) {
                try {
                    val cursor = cursorStore.load(session.id, playlist.id)
                    val tracks = session.playlistTracks(playlist.id)

                    val expectedCount = playlist.trackCount
                    if (expectedCount != null && expectedCount > 0 && tracks.isEmpty()) {
                        sessionError = "Failed to fetch tracks for ${playlist.name}"
                        continue
                    }
                    if (tracks.isEmpty() && cursor.knownTrackIds.isNotEmpty()) {
                        sessionError = "Failed to fetch tracks for ${playlist.name}"
                        continue
                    }

                    val currentIds = tracks.map { it.id }.toSet()
                    val isFirstSync = cursor.lastSyncMs <= 0L
                    val rejectedTrackIds = mutableSetOf<String>()

                    if (!isFirstSync) {
                        val newTracks = tracks.filter { it.id !in cursor.knownTrackIds }
                        val owned = ownedKeys()

                        for (track in newTracks) {
                            val trackKey = normalizedTrackKey(track.artist, track.title)
                            if (trackKey.isNotBlank() && trackKey != "|" && (trackKey in owned || trackKey in queuedKeys)) {
                                continue
                            }

                            val song = try {
                                session.resolveDownload(track)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                null
                            } ?: continue

                            val songKey = normalizedTrackKey(song.artist, song.title)
                            if (songKey.isNotBlank() && songKey != "|" && (songKey in owned || songKey in queuedKeys)) {
                                continue
                            }

                            val enqueued = onEnqueueDownload(song, "Sync: ${playlist.name}")
                            if (enqueued) {
                                if (trackKey.isNotBlank() && trackKey != "|") queuedKeys.add(trackKey)
                                if (songKey.isNotBlank() && songKey != "|") queuedKeys.add(songKey)
                                newTracksCount++
                            } else {
                                rejectedTrackIds.add(track.id)
                            }
                        }
                    }

                    // Never advance cursor before successful fetch and track processing
                    cursorStore.save(
                        session.id,
                        playlist.id,
                        SyncCursor(
                            playlistId = playlist.id,
                            lastSyncMs = System.currentTimeMillis(),
                            knownTrackIds = currentIds - rejectedTrackIds,
                        ),
                    )
                    playlistsSynced++
                } catch (e: CancellationException) {
                    throw e
                } catch (err: Throwable) {
                    // Failed playlist fetch or processing: do not advance cursor for this playlist
                    sessionError = err.message ?: "Failed to sync ${playlist.name}"
                }
            }
        } catch (e: CancellationException) {
            _statuses.update { current ->
                val prev = current[session.id] ?: AccountSyncStatus(session.id)
                current + (session.id to prev.copy(isSyncing = false))
            }
            throw e
        } catch (err: Throwable) {
            val errorStatus = AccountSyncStatus(
                sessionId = session.id,
                isSyncing = false,
                lastSyncMs = System.currentTimeMillis(),
                syncedPlaylists = playlistsSynced,
                newTracksFound = newTracksCount,
                error = err.message ?: "Failed to sync ${session.label}",
            )
            _statuses.update { it + (session.id to errorStatus) }
            return errorStatus
        }

        val updatedStatus = AccountSyncStatus(
            sessionId = session.id,
            isSyncing = false,
            lastSyncMs = System.currentTimeMillis(),
            syncedPlaylists = playlistsSynced,
            newTracksFound = newTracksCount,
            error = sessionError,
        )
        _statuses.update { it + (session.id to updatedStatus) }
        return updatedStatus
    }

    /** Clear cursors for all sessions or a specific session. */
    fun clearCursors(sessionId: String? = null) {
        cursorStore.clear(sessionId)
    }

    /** Last sync timestamp for a specific playlist. */
    fun lastSyncTime(sessionId: String, playlistId: String): Long? =
        cursorStore.lastSyncTime(sessionId, playlistId)

    companion object {
        const val DEFAULT_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
    }
}
