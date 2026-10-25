package com.music.bitchord.desktop

import com.music.bitchord.data.download.AccountSyncAutoDownloadSeam
import com.music.bitchord.data.download.AutoDownloadAccountSyncDispatcher
import com.music.bitchord.data.download.AutoDownloadBlockReason
import com.music.bitchord.data.download.AutoDownloadDecision
import com.music.bitchord.data.download.AutoDownloadPolicy
import com.music.bitchord.data.download.AutoDownloadTrigger
import com.music.bitchord.data.model.Song
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop auto-download hook coordinating automatic downloads for Linux desktop
 * according to user-configured [AutoDownloadPolicy] (Task 11 SOI-DWN-003).
 *
 * Preserves deduplication, cancellation, storage limits, and user controls.
 * Excludes TorBox, AirLock, and Apple Music streaming playback.
 */
object DesktopAutoDownloadHook : AutoDownloadAccountSyncDispatcher {

    private val persistence = DesktopPersistence()

    /** Tracks video IDs queued by this auto-download hook. */
    private val autoEnqueuedVideoIds = ConcurrentHashMap.newKeySet<String>()

    init {
        AccountSyncAutoDownloadSeam.registerDispatcher(this)
    }

    /** Returns the active user-configured auto-download policy. */
    fun policy(): AutoDownloadPolicy = persistence.currentAutoDownloadPolicy()

    /**
     * Determines whether [song] is already present in local storage.
     */
    fun isAlreadyDownloaded(song: Song): Boolean {
        if (!song.localPath.isNullOrBlank() || !song.localUri.isNullOrBlank()) return true
        val localDownloads = persistence.downloads()
        return localDownloads.any { it.videoId == song.videoId }
    }

    /**
     * Determines whether [song] is currently queued or running in [DesktopDownloadQueue].
     */
    fun isAlreadyInQueue(song: Song): Boolean {
        val active = DesktopDownloadQueue.active.value[song.videoId]
        return active != null && (active.state is DesktopDownloadState.Queued || active.state is DesktopDownloadState.Running)
    }

    /**
     * Returns usable free space in bytes on the desktop download storage directory.
     */
    fun getAvailableFreeBytes(): Long {
        val directory = Path.of(System.getProperty("user.home"), "Music", "BitChord").toFile()
        return try {
            if (!directory.exists()) directory.mkdirs()
            directory.usableSpace.takeIf { it > 0 } ?: directory.freeSpace
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Returns cumulative bytes auto-downloaded by this policy.
     */
    fun getCumulativeAutoDownloadBytes(): Long = persistence.autoDownloadedBytes()

    /**
     * Records bytes when an auto-downloaded song finishes.
     */
    fun recordDownloadedBytes(bytes: Long) {
        if (bytes <= 0L) return
        val current = persistence.autoDownloadedBytes()
        persistence.saveAutoDownloadedBytes(current + bytes)
    }

    /**
     * Clears tracked auto-download usage stats.
     */
    fun resetCumulativeBytes() {
        persistence.saveAutoDownloadedBytes(0L)
    }

    /**
     * Evaluates and conditionally enqueues an auto-download for [song] triggered by [trigger].
     */
    fun evaluateAndEnqueue(
        song: Song,
        trigger: AutoDownloadTrigger,
        fromLabel: String,
    ): AutoDownloadDecision {
        val activePolicy = policy()
        val downloaded = isAlreadyDownloaded(song)
        val inQueue = isAlreadyInQueue(song)
        // Desktop network connection is considered allowed (unmetered unless specified)
        val networkAllowed = true
        val freeBytes = getAvailableFreeBytes()
        val currentAutoBytes = getCumulativeAutoDownloadBytes()

        val decision = activePolicy.evaluate(
            song = song,
            trigger = trigger,
            isAlreadyDownloaded = downloaded,
            isAlreadyInQueue = inQueue,
            isNetworkAllowed = networkAllowed,
            availableFreeBytes = freeBytes,
            currentAutoDownloadBytes = currentAutoBytes,
        )

        when (decision) {
            is AutoDownloadDecision.Allowed -> {
                DesktopTrackLog.log("auto-download: queued '${song.title}' (${song.videoId}) from $fromLabel")
                autoEnqueuedVideoIds.add(song.videoId)
                DesktopDownloadQueue.enqueue(song)
            }
            is AutoDownloadDecision.Blocked -> {
                DesktopTrackLog.log("auto-download: blocked '${song.title}' (${song.videoId}): ${decision.reason} - ${decision.message}")
            }
        }
        return decision
    }

    /**
     * Hook called when user marks a song as Liked.
     */
    fun onSongLiked(song: Song): AutoDownloadDecision {
        return evaluateAndEnqueue(
            song = song,
            trigger = AutoDownloadTrigger.LIKED_SONG,
            fromLabel = "Liked Music",
        )
    }

    /**
     * Hook called when songs are added to a playlist.
     */
    fun onPlaylistSongsAdded(
        playlistTitle: String,
        songs: List<Song>,
    ): List<Pair<Song, AutoDownloadDecision>> {
        return songs.map { song ->
            val decision = evaluateAndEnqueue(
                song = song,
                trigger = AutoDownloadTrigger.PLAYLIST_ADDITION,
                fromLabel = playlistTitle,
            )
            song to decision
        }
    }

    /**
     * Hook called when account sync (SOI-ACC-001) discovers tracks.
     */
    override fun onAccountSyncDiscovered(
        sessionName: String,
        songs: List<Song>,
    ): List<Pair<Song, AutoDownloadDecision>> {
        return songs.map { song ->
            val decision = evaluateAndEnqueue(
                song = song,
                trigger = AutoDownloadTrigger.ACCOUNT_SYNC,
                fromLabel = sessionName,
            )
            song to decision
        }
    }

    /**
     * Completion hook called when [DesktopDownloadQueue] finishes downloading a track.
     */
    fun onDownloadFinished(song: Song) {
        if (song.videoId in autoEnqueuedVideoIds) {
            val fileBytes = song.localPath?.let { File(it).length() }?.takeIf { it > 0 }
                ?: (8L * 1024 * 1024)
            recordDownloadedBytes(fileBytes)
            autoEnqueuedVideoIds.remove(song.videoId)
            DesktopTrackLog.log("auto-download: completed '${song.title}', recorded $fileBytes bytes")
        }
    }

    /**
     * Called when a download fails to prevent memory leaks in auto-enqueued set.
     */
    fun onDownloadFailed(videoId: String) {
        autoEnqueuedVideoIds.remove(videoId)
    }

    /**
     * Called when a download is cleared or cancelled.
     */
    fun onDownloadCleared(videoId: String) {
        autoEnqueuedVideoIds.remove(videoId)
    }

    /**
     * Cancels an individual auto-downloaded job if queued or running.
     */
    fun cancelAutoDownload(videoId: String): Boolean {
        if (autoEnqueuedVideoIds.remove(videoId)) {
            DesktopDownloadQueue.cancel(videoId)
            return true
        }
        return false
    }

    /**
     * Cancels all pending or running auto-downloaded jobs, preserving user control.
     */
    fun cancelAll() {
        val targets = autoEnqueuedVideoIds.toList()
        for (id in targets) {
            DesktopDownloadQueue.cancel(id)
            autoEnqueuedVideoIds.remove(id)
        }
    }
}
