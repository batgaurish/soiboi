package com.music.bitchord.desktop

import com.music.bitchord.data.playlistimport.ApplePlaylistMirror
import com.music.bitchord.data.playlistimport.ApplePlaylistMirrorEntry
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopApplePlaylistMirrorStoreTest {
    private val isolatedNode: Preferences =
        Preferences.userRoot().node("bitchord-apple-mirror-${UUID.randomUUID()}")
    private val persistence = DesktopPersistence(isolatedNode)

    @AfterTest
    fun tearDown() {
        runCatching { isolatedNode.removeNode() }
    }

    @Test
    fun mirrorsSurviveReloadAndReplaceAnExistingTarget() {
        val original = ApplePlaylistMirror(
            sourceUrl = "https://music.apple.com/us/playlist/imported/123",
            targetId = "local-1",
            targetIsLocal = true,
            title = "Old title",
            tracks = listOf(ApplePlaylistMirrorEntry("id:a", "video-a", "A", "Artist")),
            lastSyncedMs = 100L,
        )
        persistence.saveApplePlaylistMirror(original)
        persistence.saveApplePlaylistMirror(original.copy(title = "New title", lastSyncedMs = 200L))

        val restored = DesktopPersistence(isolatedNode).applePlaylistMirrors()

        assertEquals(1, restored.size)
        assertEquals("New title", restored.single().title)
        assertEquals(200L, restored.single().lastSyncedMs)
        assertEquals(original.tracks, restored.single().tracks)
    }
}
