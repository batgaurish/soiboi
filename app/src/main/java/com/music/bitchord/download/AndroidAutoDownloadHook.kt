package com.music.bitchord.download

import android.content.Context
import android.os.Environment
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.download.AutoDownloadBlockReason
import com.music.bitchord.data.download.AutoDownloadDecision
import com.music.bitchord.data.download.AutoDownloadPolicy
import com.music.bitchord.data.download.AutoDownloadTrigger
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Android hook connecting verified application events to the existing [Downloads] pipeline
 * according to user-configured [AutoDownloadPolicy] (Task 11 SOI-DWN-003).
 *
 * Preserves deduplication, cancellation, storage limits, and user controls.
 * Excludes TorBox, AirLock, and Apple Music streaming playback.
 */
object AndroidAutoDownloadHook {

    private const val TAG = "BitChordAutoDownload"
    private const val PREFS_NAME = "bitchord_auto_download_stats"
    private const val KEY_CUMULATIVE_BYTES = "cumulative_auto_download_bytes"

    /** Tracks video IDs queued by this auto-download hook to support auto-download cancellation. */
    private val autoEnqueuedVideoIds = ConcurrentHashMap.newKeySet<String>()

    /** Returns the active user-configured auto-download policy. */
    fun policy(): AutoDownloadPolicy = AppSettings.currentAutoDownloadPolicy()

    /**
     * Determines whether [song] is already present in local storage.
     */
    fun isAlreadyDownloaded(song: Song): Boolean {
        if (!song.localUri.isNullOrBlank() || !song.localPath.isNullOrBlank()) return true
        val saved = Downloads.saved.value
        if (song.videoId in saved) return true
        return Downloads.verifiedSavedUri(song.videoId) != null
    }

    /**
     * Determines whether [song] is currently queued or running in [Downloads].
     */
    fun isAlreadyInQueue(song: Song): Boolean {
        val active = Downloads.active.value[song.videoId]
        return active is DownloadState.Queued || active is DownloadState.Running
    }

    /**
     * Returns usable free space in bytes on the destination storage filesystem.
     */
    fun getAvailableFreeBytes(context: Context): Long {
        val dir = if (AppSettings.exportDownloads.value) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        } else {
            context.filesDir
        }
        return try {
            dir.usableSpace.takeIf { it > 0 } ?: dir.freeSpace
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Returns cumulative bytes auto-downloaded by this policy.
     */
    fun getCumulativeAutoDownloadBytes(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_CUMULATIVE_BYTES, 0L)
    }

    /**
     * Records bytes when an auto-downloaded song finishes.
     */
    fun recordDownloadedBytes(context: Context, bytes: Long) {
        if (bytes <= 0L) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getLong(KEY_CUMULATIVE_BYTES, 0L)
        prefs.edit().putLong(KEY_CUMULATIVE_BYTES, current + bytes).apply()
    }

    /**
     * Clears tracked auto-download usage stats.
     */
    fun resetCumulativeBytes(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CUMULATIVE_BYTES).apply()
    }

    /**
     * Evaluates and conditionally enqueues an auto-download for [song] triggered by [trigger].
     */
    fun evaluateAndEnqueue(
        context: Context,
        song: Song,
        trigger: AutoDownloadTrigger,
        fromLabel: String,
    ): AutoDownloadDecision {
        val activePolicy = policy()
        val downloaded = isAlreadyDownloaded(song)
        val inQueue = isAlreadyInQueue(song)
        val networkAllowed = AppSettings.downloadsAllowedNow
        val freeBytes = getAvailableFreeBytes(context)
        val currentAutoBytes = getCumulativeAutoDownloadBytes(context)

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
                Log.d(TAG, "Auto-download approved for '${song.title}' (${song.videoId}) from $fromLabel")
                autoEnqueuedVideoIds.add(song.videoId)
                Downloads.enqueue(context, song, from = fromLabel)
            }
            is AutoDownloadDecision.Blocked -> {
                Log.d(TAG, "Auto-download blocked for '${song.title}' (${song.videoId}): ${decision.reason} - ${decision.message}")
            }
        }
        return decision
    }

    /**
     * Hook called when user marks a song as Liked.
     */
    fun handleSongLiked(context: Context, song: Song): AutoDownloadDecision {
        return evaluateAndEnqueue(
            context = context,
            song = song,
            trigger = AutoDownloadTrigger.LIKED_SONG,
            fromLabel = "Auto-download: Liked Music",
        )
    }

    /**
     * Hook called when songs are added to a playlist.
     */
    fun handlePlaylistSongsAdded(
        context: Context,
        playlistTitle: String,
        songs: List<Song>,
    ): List<Pair<Song, AutoDownloadDecision>> {
        return songs.map { song ->
            val decision = evaluateAndEnqueue(
                context = context,
                song = song,
                trigger = AutoDownloadTrigger.PLAYLIST_ADDITION,
                fromLabel = "Auto-download: $playlistTitle",
            )
            song to decision
        }
    }

    /**
     * Hook called when account sync (SOI-ACC-001) discovers tracks.
     */
    fun handleAccountSyncDiscovered(
        context: Context,
        sessionName: String,
        songs: List<Song>,
    ): List<Pair<Song, AutoDownloadDecision>> {
        return songs.map { song ->
            val decision = evaluateAndEnqueue(
                context = context,
                song = song,
                trigger = AutoDownloadTrigger.ACCOUNT_SYNC,
                fromLabel = "Auto-download: $sessionName",
            )
            song to decision
        }
    }

    /**
     * Library completion hook: called when a track finishes downloading in [Downloads].
     */
    fun onDownloadCompleted(context: Context, song: Song, bytesWritten: Long = 0L) {
        if (song.videoId in autoEnqueuedVideoIds) {
            val estimatedBytes = if (bytesWritten > 0L) {
                bytesWritten
            } else {
                // If bytesWritten is not directly passed, check the saved file size or fallback to 8MB estimate
                val savedUri = Downloads.verifiedSavedUri(song.videoId)
                val file = savedUri?.let { if (it.startsWith("file://")) File(it.removePrefix("file://")) else null }
                file?.length()?.takeIf { it > 0 } ?: (8L * 1024 * 1024)
            }
            recordDownloadedBytes(context, estimatedBytes)
            autoEnqueuedVideoIds.remove(song.videoId)
            Log.d(TAG, "Auto-download finished for '${song.title}', recorded $estimatedBytes bytes")
        }
    }

    /**
     * Cancels all pending or running auto-downloaded jobs, preserving user control.
     */
    fun cancelAll() {
        val targets = autoEnqueuedVideoIds.toList()
        for (id in targets) {
            Downloads.cancel(id)
            autoEnqueuedVideoIds.remove(id)
        }
    }
}
