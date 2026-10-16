package app.soiboi.engine

import app.soiboi.engine.core.FileSecretStore
import app.soiboi.engine.spotify.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SpotifyTests {
    private fun store() = FileSecretStore(kotlin.io.path.createTempDirectory("sp").toFile())

    @Test fun loginUrlCarriesPkceAndNoSecret() {
        val l = SpotifyLogin("abc123")
        assertTrue(l.url.contains("code_challenge_method=S256") && l.url.contains("client_id=abc123") && l.url.contains(java.net.URLEncoder.encode(SPOTIFY_REDIRECT, "UTF-8")))
        assertFalse(l.url.contains(l.verifier))
        assertTrue(l.verifier.length >= 43)
    }

    @Test fun signInStoresTokensAndSearchParsesTracks() = runBlocking {
        val seen = mutableListOf<String>()
        val client = HttpClient(MockEngine { req ->
            seen += req.url.toString()
            when {
                req.url.host == "accounts.spotify.com" -> respond("""{"access_token":"A1","refresh_token":"R1","expires_in":3600}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                req.url.encodedPath.endsWith("/search") -> { assertEquals("Bearer A1", req.headers["Authorization"]); respond("""{"tracks":{"items":[{"id":"t1","name":"Song","duration_ms":200000,"artists":[{"name":"Ann"},{"name":"Bo"}],"album":{"name":"LP","images":[{"url":"http://img/1.jpg"}]},"external_ids":{"isrc":"X1"}}]},"albums":{"items":[]},"artists":{"items":[]},"playlists":{"items":[null]}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
                else -> respond("{}", HttpStatusCode.OK)
            }
        })
        val api = SpotifyApi(store(), client); api.saveClientId("cid")
        assertNull(api.finishLogin(SpotifyLogin("cid"), "code"))
        assertTrue(api.connected)
        val r = SpotifyClient(api).search("song")
        val t = r.tracks.single()
        assertEquals("Ann, Bo", t.artist); assertEquals("spotify:track:t1", t.streamUrl); assertEquals(200000L, t.durationMs); assertEquals("X1", t.isrc)
        assertEquals("spotify:track:t1", SpotifyClient(api).stream("t1").url)
    }

    @Test fun expiredTokenRefreshesAndKeepsTheRefreshToken() = runBlocking {
        val st = store(); st.put("spotify.client", "cid")
        st.put("spotify.tokens", """{"access":"old","refresh":"R1","expires":1}""")
        var refreshed = 0
        val client = HttpClient(MockEngine { req ->
            if (req.url.host == "accounts.spotify.com") { refreshed++; respond("""{"access_token":"NEW","expires_in":3600}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            else { assertEquals("Bearer NEW", req.headers["Authorization"]); respond("""{"devices":[{"id":"d1","name":"Kitchen","type":"Speaker","is_active":true,"volume_percent":40}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        })
        val api = SpotifyApi(st, client)
        val d = api.devices().single()
        assertEquals("Kitchen", d.name); assertEquals(1, refreshed)
        api.devices(); assertEquals(1, refreshed) // the new token is reused
        assertTrue(st.get("spotify.tokens")!!.contains("R1"))
    }

    @Test fun noDeviceIsExplained() = runBlocking {
        val st = store(); st.put("spotify.client", "cid"); st.put("spotify.tokens", """{"access":"a","refresh":"r","expires":${System.currentTimeMillis() + 600000}}""")
        val api = SpotifyApi(st, HttpClient(MockEngine { respond("""{"error":{"status":404,"message":"Player command failed: No active device found"}}""", HttpStatusCode.NotFound) }))
        val e = assertFailsWith<app.soiboi.engine.addons.AddonException> { api.play("spotify:track:t1", null, 0) }
        assertTrue(e.message!!.contains("No Spotify device is open"))
    }
}
