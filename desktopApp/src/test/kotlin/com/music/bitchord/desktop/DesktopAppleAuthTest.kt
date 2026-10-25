package com.music.bitchord.desktop

import com.music.bitchord.data.apple.AppleHomeCatalog
import com.music.bitchord.data.apple.extractMusicUserToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAppleAuthTest {

    /**
     * Mock that separates the two credential slots by their attribute map, matching the
     * two keyring entries [AppleCookieCredentialStore] uses in production.
     */
    private fun mockStore(): AppleCookieCredentialStore {
        val store = mutableMapOf<Map<String, String>, ByteArray>()
        return AppleCookieCredentialStore(
            lookup = { attrs -> store[attrs] },
            store = { _, attrs, secret -> store[attrs] = secret; true },
            remove = { attrs -> store.remove(attrs); true },
        )
    }

    @Test
    fun appleCookieIsNotSavedWhenSecretServiceIsUnavailable() {
        val storage = AppleCookieCredentialStore(
            lookup = { null },
            store = { _, _, _ -> false },
            remove = { true },
        )

        assertFalse(storage.saveCookie("media-user-token=must-not-fallback-to-plain-prefs"))
        assertNull(storage.cookie())
        assertFalse(storage.saveNetscapeJar("# Netscape HTTP Cookie File\n"))
        assertNull(storage.netscapeJar())
    }

    @Test
    fun appleCookieRoundTripsThroughSecretStore() {
        val storage = mockStore()

        assertNull(storage.cookie())
        assertTrue(storage.saveCookie("media-user-token=desktop-test-token"))
        val cookie = storage.cookie()
        assertEquals("media-user-token=desktop-test-token", cookie)
        assertEquals("desktop-test-token", extractMusicUserToken(cookie))

        storage.clear()
        assertNull(storage.cookie())
    }

    @Test
    fun appleNetscapeJarRoundTripsThroughSecretStore() {
        val storage = mockStore()
        val jar = "# Netscape HTTP Cookie File\n# Written by BitChord. Do not edit.\n" +
            ".music.apple.com\tTRUE\t/\tTRUE\t0\tmedia-user-token\ttest-mut-value\n" +
            ".apple.com\tTRUE\t/\tTRUE\t0\tdsid\ttest-dsid-value\n"

        assertNull(storage.netscapeJar())
        assertTrue(storage.saveNetscapeJar(jar))
        val stored = storage.netscapeJar()
        assertNotNull(stored)
        assertTrue(stored.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(stored.contains("media-user-token"))
        assertTrue(stored.contains("dsid"))

        storage.clear()
        assertNull(storage.netscapeJar())
    }

    @Test
    fun cookieHeaderAndNetscapeJarAreStoredIndependently() {
        val storage = mockStore()
        val header = "media-user-token=header-token; dsid=dsid-value"
        val jar = "# Netscape HTTP Cookie File\n.music.apple.com\tTRUE\t/\tTRUE\t0\tmedia-user-token\theader-token\n"

        storage.saveCookie(header)
        storage.saveNetscapeJar(jar)

        // Both are retrievable independently
        assertEquals(header, storage.cookie())
        assertNotNull(storage.netscapeJar())

        // clear removes both
        storage.clear()
        assertNull(storage.cookie())
        assertNull(storage.netscapeJar())
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
