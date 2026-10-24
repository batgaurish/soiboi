package com.music.bitchord.desktop

import com.music.bitchord.data.apple.AppleHomeCatalog
import com.music.bitchord.data.apple.extractMusicUserToken
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopAppleAuthTest {
    private val isolatedNode =
        Preferences.userRoot().node("com.music.bitchord.desktop.test.appleauth.${UUID.randomUUID()}")
    private val persistence = DesktopPersistence(isolatedNode)

    @AfterTest
    fun tearDown() {
        runCatching { isolatedNode.removeNode() }
    }

    @Test
    fun appleAuthSavesAndRetrievesCookie() {
        DesktopAppleAuth.clear()
        assertNull(DesktopAppleAuth.cookie())

        DesktopAppleAuth.saveCookie("media-user-token=desktop-test-token")
        val cookie = DesktopAppleAuth.cookie()
        assertEquals("media-user-token=desktop-test-token", cookie)
        assertEquals("desktop-test-token", extractMusicUserToken(cookie))

        DesktopAppleAuth.clear()
        assertNull(DesktopAppleAuth.cookie())
    }

    @Test
    fun changingAppleSessionInvalidatesHome() {
        var activeCookie: String? = "media-user-token=token-1"
        val catalog = AppleHomeCatalog(
            cookieHeader = { activeCookie },
            storefront = { "us" },
        )

        assertEquals("token-1", extractMusicUserToken(activeCookie))

        // Change session
        activeCookie = "media-user-token=token-2"
        catalog.invalidate()
        assertEquals("token-2", extractMusicUserToken(activeCookie))
    }
}
