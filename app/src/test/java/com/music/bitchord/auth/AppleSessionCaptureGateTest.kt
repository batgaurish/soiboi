package com.music.bitchord.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleSessionCaptureGateTest {
    @Test
    fun cookieCleanupExpiresHostOnlyAndEveryAppleParentDomainVariant() {
        val headers = appleCookieExpirationHeaders(
            cookieHeader = "media-user-token=old; dsid=store-id",
            url = "https://music.apple.com/us/browse",
        )

        assertTrue(headers.any { it.startsWith("media-user-token=;") && "; Domain=.music.apple.com" in it })
        assertTrue(headers.any { it.startsWith("media-user-token=;") && "; Domain=.apple.com" in it })
        assertTrue(headers.any { it.startsWith("dsid=;") && "; Domain=.music.apple.com" in it })
        assertTrue(headers.any { it.startsWith("dsid=;") && "; Domain=.apple.com" in it })
        assertTrue(headers.all { "Max-Age=0" in it })
        assertTrue(headers.any { "Path=/" in it })
        assertTrue(headers.any { "Path=/us" in it })
        assertTrue(headers.any { "Path=/us/browse" in it })
        assertFalse(headers.any { "old" in it || "store-id" in it })
    }

    @Test
    fun captureGateRejectsAnUnchangedTokenButAcceptsANewToken() {
        val gate = AppleSessionCaptureGate("media-user-token=old")

        assertEquals(null, gate.newSessionToken("media-user-token=old"))
        assertEquals("new", gate.newSessionToken("media-user-token=new"))
    }
}
