package com.music.bitchord.data.account

import android.content.Context
import com.music.bitchord.BitChordApplication
import com.music.bitchord.data.accounts.AccountSync
import com.music.bitchord.data.accounts.AppleMusicSession
import com.music.bitchord.data.accounts.FileAccountSyncCursorStore
import com.music.bitchord.data.accounts.SessionRegistry
import com.music.bitchord.data.accounts.SoundCloudMusicSession
import com.music.bitchord.data.accounts.SpotifyMusicSession
import com.music.bitchord.data.accounts.YouTubeMusicSession
import com.music.bitchord.data.accounts.normalizedTrackKey
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Android account synchronization coordinator.
 * Bridges [AccountSync] with Android services:
 * - Credentials from encrypted [AuthStore]
 * - Downloads dispatched via [Downloads.enqueue]
 * - Existing downloads filtered via [Downloads.saved] and [Downloads.savedMetadata]
 * - Periodic background sync loop honoring [AppSettings.accountSyncEnabled]
 */
object AndroidAccountSync {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val registry = SessionRegistry()
    lateinit var sync: AccountSync
        private set

    fun init(context: Context) {
        val appContext = context.applicationContext
        val cursorDir = File(appContext.filesDir, "account_sync_cursors")
        val cursorStore = FileAccountSyncCursorStore(cursorDir)

        val ownedKeysProvider = {
            val savedKeys = Downloads.saved.value.keys.mapNotNull { videoId ->
                val meta = Downloads.savedMetadata.value[videoId]
                if (meta != null) normalizedTrackKey(meta.artist, meta.title) else null
            }.toSet()
            savedKeys
        }

        sync = AccountSync(
            sessions = registry,
            cursorStore = cursorStore,
            ownedKeys = ownedKeysProvider,
            onEnqueueDownload = { song, from ->
                if (!AppSettings.downloadsAllowedNow) {
                    false
                } else {
                    Downloads.enqueue(appContext, song, from)
                    true
                }
            },
            scope = scope,
        )

        val ytSession = YouTubeMusicSession(
            isSignedIn = {
                BitChordApplication.authStore.isSignedIn
            },
            accountName = {
                BitChordApplication.authStore.activeSession?.name
            },
        )
        registry.register(ytSession)

        val spotifySession = SpotifyMusicSession(
            isConnected = {
                AppSettings.spotifySpdcToken.value.isNotBlank()
            },
        )
        registry.register(spotifySession)

        val appleSession = AppleMusicSession(
            cookieHeader = {
                BitChordApplication.authStore.appleMusicCookie
            },
        )
        registry.register(appleSession)

        registry.register(SoundCloudMusicSession())

        scope.launch {
            AppSettings.accountSyncEnabled.collectLatest { enabled ->
                val intervalMins = AppSettings.accountSyncIntervalMinutes.value
                sync.setEnabled(enabled, intervalMins * 60 * 1000L)
            }
        }
    }
}
