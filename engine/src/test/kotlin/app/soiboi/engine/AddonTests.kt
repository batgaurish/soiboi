package app.soiboi.engine

import app.soiboi.engine.addons.AddonClient
import app.soiboi.engine.addons.AddonException
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonTests {
    private fun serve(routes: Map<String, String>, block: (String, MutableList<String>) -> Unit) {
        val hits = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits += ex.requestURI.toString()
            val body = routes[ex.requestURI.path]
            if (body == null) { ex.sendResponseHeaders(404, -1) } else {
                val bytes = body.toByteArray(); ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}", hits) } finally { server.stop(0) }
    }

    @Test fun readsAManifestAndItsSettings() = serve(mapOf("/manifest.json" to """
        {"id":"org.example.fake","name":"Fake","version":"1.2.0","resources":["search","stream","catalog"],
         "types":["track"],"catalogs":[{"id":"top","type":"track","name":"Top"}],
         "settings":[{"key":"quality","label":"Quality","type":"select","default":"hi","options":["lo","hi"]}]}""")) { base, _ ->
        val m = runBlocking { AddonClient("$base/manifest.json").manifest() }
        assertEquals("Fake", m.name); assertTrue(m.canSearch && m.canStream); assertEquals("top", m.catalogs.single().id)
        assertEquals("hi", m.settings.single().default); assertEquals(listOf("lo" to "lo", "hi" to "hi"), m.settings.single().options)
    }

    @Test fun searchParsesEveryKindAndTolleratesFieldNames() = serve(mapOf("/search" to """
        {"tracks":[{"id":"t1","title":"Song","artist":"A","album":"B","duration":215,"artworkURL":"http://x/c.jpg","isrc":"US123"},
                   {"id":"t2","name":"Other","artists":["X","Y"],"durationMs":180000}],
         "albums":[{"id":"a1","title":"Album","artist":"A","year":2020}],
         "artists":[{"id":"r1","name":"Artist"}],
         "playlists":[{"id":"p1","title":"List"}]}""")) { base, _ ->
        val r = runBlocking { AddonClient(base).search("q") }
        assertEquals(2, r.tracks.size); assertEquals(215_000L, r.tracks[0].durationMs); assertEquals(180_000L, r.tracks[1].durationMs)
        assertEquals("X, Y", r.tracks[1].artist); assertEquals("US123", r.tracks[0].isrc)
        assertEquals(2020, r.albums.single().year); assertEquals("Artist", r.artists.single().name); assertEquals("List", r.playlists.single().title)
    }

    @Test fun settingsRideOnEveryRequestAndStreamsResolve() = serve(mapOf("/stream/abc d" to """{"url":"http://cdn/x.flac","format":"flac","headers":{"Authorization":"t"}}""")) { base, hits ->
        val s = runBlocking { AddonClient(base, mapOf("quality" to "hi")).stream("abc d") }
        assertEquals("http://cdn/x.flac", s.url); assertEquals("t", s.headers["Authorization"])
        assertTrue(hits.single().contains("quality=hi"))
    }

    @Test fun aMissingIsrcResolverIsNotAnError() = serve(emptyMap()) { base, _ -> assertNull(runBlocking { AddonClient(base).resolveIsrc("X") }) }

    @Test fun aNonAddonAddressSaysSo() = serve(emptyMap()) { base, _ -> assertFailsWith<AddonException> { runBlocking { AddonClient(base).manifest() } } }

    @Test fun catalogItemsAreReadAsWhatTheyLookLike() = serve(mapOf("/catalog/top" to """
        {"items":[{"id":"t","title":"S","artist":"A","album":"B"},{"id":"al","title":"Al","artist":"A"},{"id":"p","title":"P","tracks":[]},{"id":"r","name":"R"}]}""")) { base, _ ->
        val page = runBlocking { AddonClient(base).catalog("top") }
        assertEquals(1, page.tracks.size); assertEquals(1, page.albums.size); assertEquals(1, page.playlists.size); assertEquals(1, page.artists.size)
    }

    @Test fun anAddressWithManifestOnTheEndNormalises() {
        assertEquals("https://a.b/x", AddonClient.normalise(" https://a.b/x/manifest.json "))
        assertEquals("https://a.b", AddonClient.normalise("https://a.b/"))
    }
}
