package com.music.bitchord.desktop

import com.music.bitchord.data.accounts.AccountSync
import com.music.bitchord.data.accounts.AppleMusicSession
import com.music.bitchord.data.accounts.FileAccountSyncCursorStore
import com.music.bitchord.data.accounts.SessionRegistry
import com.music.bitchord.data.accounts.SoundCloudMusicSession
import com.music.bitchord.data.accounts.SpotifyMusicSession
import com.music.bitchord.data.accounts.YouTubeMusicSession
import com.music.bitchord.data.accounts.normalizedTrackKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Desktop account synchronization coordinator.
 * Bridges [AccountSync] with BitChord desktop services:
 * - Credentials from secure stores ([DesktopSecretStore], [DesktopAppleAuth])
 * - New tracks enqueued directly to [DesktopDownloadQueue]
 * - Downloaded tracks verified against [DesktopPersistence.downloads]
 * - Periodic background loop honoring user settings
 */
internal object DesktopAccountSync {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val registry = SessionRegistry()
    val sync: AccountSync

    init {
        val dataDir = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
            ?: "${System.getProperty("user.home")}/.local/share"
        val cursorDir = File(dataDir, "bitchord/account_sync_cursors")
        val cursorStore = FileAccountSyncCursorStore(cursorDir)

        val ownedKeysProvider = {
            DesktopPersistence().downloads().map {
                normalizedTrackKey(it.artist, it.title)
            }.toSet()
        }

        sync = AccountSync(
            sessions = registry,
            cursorStore = cursorStore,
            ownedKeys = ownedKeysProvider,
            onEnqueueDownload = { song, _ ->
                DesktopDownloadQueue.enqueue(song)
                true
            },
            scope = scope,
        )

        val ytSession = YouTubeMusicSession(
            isSignedIn = { DesktopYouTubeAuth.isSignedIn },
            accountName = { DesktopAccounts.active()?.name },
        )
        registry.register(ytSession)

        val spotifySession = SpotifyMusicSession(
            isConnected = {
                DesktopPersistence().string("spotify_spdc_token", "").isNotBlank()
            },
        )
        registry.register(spotifySession)

        val appleSession = AppleMusicSession(
            cookieHeader = { DesktopAppleAuth.cookie() },
        )
        registry.register(appleSession)

        registry.register(SoundCloudMusicSession())

        val persistence = DesktopPersistence()
        val enabled = persistence.accountSyncEnabled()
        val intervalMins = persistence.accountSyncIntervalMinutes()
        if (enabled) {
            sync.setEnabled(true, intervalMins * 60 * 1000L)
        }
    }

    fun setEnabled(enabled: Boolean) {
        val persistence = DesktopPersistence()
        persistence.saveAccountSyncEnabled(enabled)
        val intervalMins = persistence.accountSyncIntervalMinutes()
        sync.setEnabled(enabled, intervalMins * 60 * 1000L)
    }
}
