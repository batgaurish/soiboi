package com.music.bitchord.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsProviderErrorTest {
    @Test fun lrclibMalformedPayloadDoesNotProduceLyrics() {
        assertTrue(LrcLib.parseLrc("not-json and not LRC").isEmpty())
    }

    @Test fun paxsenixMalformedPayloadDoesNotProduceLyrics() {
        assertNull(PaxSenix.parseTimedApple("{"))
        assertNull(PaxSenix.parseLrcGet("not-json", "title", "artist", 180_000))
    }

    @Test fun musixmatchMalformedRichSyncDoesNotProduceLyrics() {
        assertTrue(Musixmatch.parseRichSyncBody("not-json").isEmpty())
    }

    @Test fun paxsenixTokenNormalizationAcceptsMissingAndBearerPrefixedKeys() {
        assertEquals("", normalizePaxSenixApiKey("  "))
        assertEquals("secret", normalizePaxSenixApiKey(" Bearer secret "))
        assertEquals("secret", normalizePaxSenixApiKey("secret"))
    }
}
