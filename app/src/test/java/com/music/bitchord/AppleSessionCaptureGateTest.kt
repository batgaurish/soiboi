package com.music.bitchord

import com.music.bitchord.auth.AppleSessionCaptureGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleSessionCaptureGateTest {
    @Test
    fun doesNotCompleteSignInFromCookieAlreadyPresentWhenFlowStarts() {
        val gate = AppleSessionCaptureGate("media-user-token=old-session; dsid=123")

        assertNull(gate.newSessionToken("media-user-token=old-session; dsid=123"))
    }

    @Test
    fun completesSignInWhenAppleIssuesANewSessionToken() {
        val gate = AppleSessionCaptureGate("media-user-token=old-session")

        assertEquals("new-session", gate.newSessionToken("dsid=123; media-user-token=new-session"))
    }

    @Test
    fun matchesTheExactCookieNameAndRejectsBlankTokens() {
        val gate = AppleSessionCaptureGate(null)

        assertNull(gate.newSessionToken("not-media-user-token=wrong; media-user-token="))
        assertEquals("token", gate.newSessionToken("not-media-user-token=wrong; MEDIA-USER-TOKEN=token"))
    }
}
