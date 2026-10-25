package com.music.bitchord.data.download

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isUnresolvedSpotify
import kotlinx.serialization.Serializable

/**
 * Triggers that can request an automatic offline download.
 */
enum class AutoDownloadTrigger {
    /** User marked a track as Liked/Favorite. */
    LIKED_SONG,

    /** A track was added to a local or synced playlist. */
    PLAYLIST_ADDITION,

    /** Discovered during account or library background sync (SOI-ACC-001). */
    ACCOUNT_SYNC,
}

/**
 * Reasons why a candidate auto-download may be rejected or blocked.
 */
enum class AutoDownloadBlockReason {
    /** Auto-download master switch is off. */
    DISABLED,

    /** Specific trigger category (e.g. liked songs, playlists) is disabled. */
    TRIGGER_DISABLED,

    /** Wi-Fi is required by policy but current network is metered/cellular. */
    NETWORK_NOT_ALLOWED,

    /** Track is already saved on local storage. */
    ALREADY_DOWNLOADED,

    /** Track is currently in the download queue or actively downloading. */
    ALREADY_IN_QUEUE,

    /** Configured auto-download quota limit in MB would be exceeded. */
    STORAGE_QUOTA_EXCEEDED,

    /** Target storage volume free space is below the safe minimum threshold. */
    INSUFFICIENT_DISK_SPACE,

    /** Source is excluded by policy (e.g. TorBox, AirLock, Debrid, Apple streaming). */
    EXCLUDED_SOURCE,

    /** Track metadata is unresolved, unplayable, or lacks a valid video ID. */
    UNRESOLVED_TRACK,
}

/**
 * Result of evaluating an auto-download candidate against the active policy.
 */
sealed interface AutoDownloadDecision {
    data class Allowed(val trigger: AutoDownloadTrigger) : AutoDownloadDecision
    data class Blocked(val reason: AutoDownloadBlockReason, val message: String) : AutoDownloadDecision

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * User-configurable policy governing automatic background downloads (Task 11 SOI-DWN-003).
 *
 * Defaults to completely disabled (`enabled = false`) to strictly preserve user control.
 */
@Serializable
data class AutoDownloadPolicy(
    /** Master switch for all automatic downloads. Default false. */
    val enabled: Boolean = false,

    /** Whether liking/favoriting a song automatically queues it for download. */
    val downloadLikedSongs: Boolean = false,

    /** Whether adding a song to a playlist automatically queues it for download. */
    val downloadPlaylists: Boolean = false,

    /** Whether account sync (SOI-ACC-001) automatically queues discovered songs. */
    val downloadAccountSync: Boolean = false,

    /** Whether auto-downloads are restricted to unmetered Wi-Fi connections. */
    val wifiOnly: Boolean = true,

    /**
     * Maximum cumulative storage quota allocated for auto-downloads in megabytes.
     * 0 means unlimited quota (subject only to [minFreeSpaceMb]).
     */
    val storageLimitMb: Long = 2048L,

    /**
     * Minimum free disk space required on the destination volume before auto-downloading, in megabytes.
     * Prevents auto-downloads from filling up user storage.
     */
    val minFreeSpaceMb: Long = 512L,
) {
    /**
     * Evaluates a candidate song against this policy and device conditions.
     */
    fun evaluate(
        song: Song,
        trigger: AutoDownloadTrigger,
        isAlreadyDownloaded: Boolean,
        isAlreadyInQueue: Boolean,
        isNetworkAllowed: Boolean,
        availableFreeBytes: Long,
        currentAutoDownloadBytes: Long = 0L,
    ): AutoDownloadDecision {
        if (!enabled) {
            return AutoDownloadDecision.Blocked(
                AutoDownloadBlockReason.DISABLED,
                "Auto-download is disabled",
            )
        }

        when (trigger) {
            AutoDownloadTrigger.LIKED_SONG -> {
                if (!downloadLikedSongs) {
                    return AutoDownloadDecision.Blocked(
                        AutoDownloadBlockReason.TRIGGER_DISABLED,
                        "Auto-download for liked songs is disabled",
                    )
                }
            }
            AutoDownloadTrigger.PLAYLIST_ADDITION -> {
                if (!downloadPlaylists) {
                    return AutoDownloadDecision.Blocked(
                        AutoDownloadBlockReason.TRIGGER_DISABLED,
                        "Auto-download for playlists is disabled",
                    )
                }
            }
            AutoDownloadTrigger.ACCOUNT_SYNC -> {
                if (!downloadAccountSync) {
                    return AutoDownloadDecision.Blocked(
                        AutoDownloadBlockReason.TRIGGER_DISABLED,
                        "Auto-download for account sync is disabled",
                    )
                }
            }
        }

        // Validate track identity
        if (song.videoId.isBlank()) {
            return AutoDownloadDecision.Blocked(
                AutoDownloadBlockReason.UNRESOLVED_TRACK,
                "Song has no valid video ID",
            )
        }

        if (song.isUnresolvedSpotify) {
            return AutoDownloadDecision.Blocked(
                AutoDownloadBlockReason.UNRESOLVED_TRACK,
                "Spotify track is unresolved",
            )
        }

        // Exclusion rules: TorBox, AirLock, Debrid, Apple streaming
        if (isExcludedSource(song)) {
            return AutoDownloadDecision.Blocked(
                AutoDownloadBlockReason.EXCLUDED_SOURCE,
                "Track is from an excluded source (TorBox, AirLock, Debrid, or Apple streaming)",
            )
        }

        // Deduplication
        if (isAlreadyDownloaded || !song.localPath.isNullOrBlank() || !song.localUri.isNullOrBlank()) {
            return AutoDownloadDecision.Blocked(
                AutoDownloadBlockReason.ALREADY_DOWNLOADED,
                "Track '${song.title}' is already downloaded",
            )
        }

        if (isAlreadyInQueue) {
            return AutoDownloadDecision.Blocked(
                AutoDownloadBlockReason.ALREADY_IN_QUEUE,
                "Track '${song.title}' is already in download queue",
            )
        }

        // Network constraint
        if (wifiOnly && !isNetworkAllowed) {
            return AutoDownloadDecision.Blocked(
                AutoDownloadBlockReason.NETWORK_NOT_ALLOWED,
                "Auto-download requires unmetered Wi-Fi connection",
            )
        }

        // Storage safety threshold: minimum free disk space
        if (minFreeSpaceMb > 0) {
            val minFreeBytes = minFreeSpaceMb * 1024L * 1024L
            if (availableFreeBytes < minFreeBytes) {
                return AutoDownloadDecision.Blocked(
                    AutoDownloadBlockReason.INSUFFICIENT_DISK_SPACE,
                    "Available storage (${availableFreeBytes / (1024 * 1024)} MB) is below minimum safe threshold ($minFreeSpaceMb MB)",
                )
            }
        }

        // Storage quota limit
        if (storageLimitMb > 0) {
            val maxQuotaBytes = storageLimitMb * 1024L * 1024L
            if (currentAutoDownloadBytes >= maxQuotaBytes) {
                return AutoDownloadDecision.Blocked(
                    AutoDownloadBlockReason.STORAGE_QUOTA_EXCEEDED,
                    "Auto-download storage limit ($storageLimitMb MB) reached",
                )
            }
        }

        return AutoDownloadDecision.Allowed(trigger)
    }

    companion object {
        /**
         * Checks whether [song] originates from an explicitly excluded source.
         * Enforces design ruling: TorBox, AirLock, Debrid, and Apple streaming are excluded.
         */
        fun isExcludedSource(song: Song): Boolean {
            val checkStrings = listOfNotNull(
                song.videoId,
                song.localUri,
                song.localPath,
                song.albumName,
                song.radioName,
                song.playbackSource,
                song.playbackSourceId,
            )
            val excludedTokens = listOf(
                "torbox",
                "airlock",
                "debrid",
                "apple_stream",
                "session://apple",
                "apple_music_stream",
            )
            for (str in checkStrings) {
                val lower = str.lowercase()
                for (token in excludedTokens) {
                    if (lower.contains(token)) return true
                }
            }
            return false
        }
    }
}

/**
 * Dispatcher interface implemented by platform auto-download hooks to receive
 * tracks discovered during background account sync (SOI-ACC-001).
 */
fun interface AutoDownloadAccountSyncDispatcher {
    /**
     * Evaluates and conditionally enqueues tracks discovered during account sync.
     *
     * @param sessionName Name or descriptor of the syncing account/playlist.
     * @param songs List of candidate tracks discovered.
     * @return List of evaluation decisions per candidate track.
     */
    fun onAccountSyncDiscovered(
        sessionName: String,
        songs: List<Song>,
    ): List<Pair<Song, AutoDownloadDecision>>
}

/**
 * Shared integration seam for AccountSync (SOI-ACC-001) to dispatch tracks to the
 * active platform's auto-download pipeline (SOI-DWN-003) without requiring direct
 * dependencies on Android or Desktop platform modules.
 */
object AccountSyncAutoDownloadSeam {
    private var dispatcher: AutoDownloadAccountSyncDispatcher? = null

    /**
     * Registers the platform auto-download dispatcher.
     */
    fun registerDispatcher(dispatcher: AutoDownloadAccountSyncDispatcher) {
        this.dispatcher = dispatcher
    }

    /**
     * Clears the registered dispatcher.
     */
    fun unregisterDispatcher() {
        this.dispatcher = null
    }

    /**
     * Invoked by AccountSync background loop (SOI-ACC-001) when new songs are discovered.
     */
    fun dispatchDiscoveredSongs(
        sessionName: String,
        songs: List<Song>,
    ): List<Pair<Song, AutoDownloadDecision>> {
        val current = dispatcher ?: return songs.map {
            it to AutoDownloadDecision.Blocked(
                AutoDownloadBlockReason.DISABLED,
                "No auto-download dispatcher registered",
            )
        }
        return current.onAccountSyncDiscovered(sessionName, songs)
    }

    /**
     * Returns true if an active dispatcher is currently registered.
     */
    val hasDispatcher: Boolean get() = dispatcher != null
}
