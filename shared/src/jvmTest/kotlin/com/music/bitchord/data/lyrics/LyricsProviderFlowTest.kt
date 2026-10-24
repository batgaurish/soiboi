package com.music.bitchord.data.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsProviderFlowTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest fun cleanup() {
        lyricsGetOverride = null
        lyricsGetBearerOverride = null
        lyricsGetAuthorizedOverride = null
        LrcLib.baseUrl = "https://lrclib.net/api"
        PaxSenix.apiBase = "https://api.paxsenix.org"
        PaxSenix.publicProxyBase = "https://lyrics.paxsenix.org"
        PaxSenix.setApiKey("")
        Musixmatch.apiBase = "https://apic.musixmatch.com/ws/1.1"
        Musixmatch.requestOverride = null
        server.shutdown()
    }

    @Test fun lrclibNoMatchAndMalformedHttpBodiesReturnNull() = runBlocking {
        LrcLib.baseUrl = server.url("/api").toString().trimEnd('/')
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{"))
        assertNull(LrcLib.lyrics("Song", "Artist", 180_000))
        assertNull(LrcLib.lyrics("Song", "Artist", 180_000))
    }

    @Test fun lrclibCancellationPropagatesFromRequest() = runBlocking {
        LrcLib.baseUrl = server.url("/api").toString().trimEnd('/')
        lyricsGetOverride = { throw CancellationException("cancelled request") }
        var cancelled = false
        try { LrcLib.lyrics("Song", "Artist", 180_000) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }

    @Test fun paxsenixRequiresTokenAndHandlesNoMatchMalformedResponses() = runBlocking {
        PaxSenix.apiBase = server.url("/api").toString().trimEnd('/')
        PaxSenix.setApiKey(" ")
        var requests = 0
        lyricsGetBearerOverride = { _, _ -> requests++; "[]" }
        assertNull(PaxSenix.spotifyLyrics("Song", "Artist", 180_000))
        assertEquals(0, requests)

        PaxSenix.setApiKey("secret")
        server.enqueue(MockResponse().setBody("not-json"))
        lyricsGetBearerOverride = null
        assertNull(PaxSenix.spotifyLyrics("Song", "Artist", 180_000))
        assertTrue(server.takeRequest().path!!.contains("spotify/search"))
    }

    @Test fun musixmatchCancellationPropagatesThroughProviderFlow() = runBlocking {
        Musixmatch.apiBase = server.url("/ws/1.1").toString().trimEnd('/')
        Musixmatch.requestOverride = { throw CancellationException("cancelled request") }
        var cancelled = false
        try { Musixmatch.lyrics("Song", "Artist", 180_000) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
