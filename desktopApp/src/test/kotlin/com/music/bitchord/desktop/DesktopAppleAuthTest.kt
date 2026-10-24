package com.music.bitchord.desktop

import com.music.bitchord.data.apple.AppleHomeCatalog
import com.music.bitchord.data.apple.extractMusicUserToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAppleAuthTest {

    @Test
    fun appleCookieIsNotSavedWhenSecretServiceIsUnavailable() {
        val storage = AppleCookieCredentialStore(
            lookup = { null },
            store = { _, _, _ -> false },
            remove = { true },
        )

        assertFalse(storage.saveCookie("media-user-token=must-not-fallback-to-plain-prefs"))
        assertNull(storage.cookie())
    }

    @Test
    fun appleCookieRoundTripsThroughSecretStore() {
        var stored: ByteArray? = null
        val storage = AppleCookieCredentialStore(
            lookup = { stored },
            store = { _, _, secret -> stored = secret; true },
            remove = { stored = null; true },
        )

        assertNull(storage.cookie())
        assertTrue(storage.saveCookie("media-user-token=desktop-test-token"))
        val cookie = storage.cookie()
        assertEquals("media-user-token=desktop-test-token", cookie)
        assertEquals("desktop-test-token", extractMusicUserToken(cookie))

        storage.clear()
        assertNull(storage.cookie())
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
