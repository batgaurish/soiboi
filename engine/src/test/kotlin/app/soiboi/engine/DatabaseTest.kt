package app.soiboi.engine

import app.soiboi.engine.library.Database
import app.soiboi.engine.library.FAVORITE
import app.soiboi.engine.library.Library
import app.soiboi.engine.library.PlaylistStore
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.library.SongStore
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseTest {
    private fun db(): Database = Database(File.createTempFile("soiboi-test", ".db").also { it.deleteOnExit() })

    @Test fun songsRoundTripAndReplaceByOrigin() = runTest {
        val store = SongStore(db())
        store.replaceAll(SongOrigin.Local, listOf(Song("/a.m4a", path = "/a.m4a", title = "A", artist = "X", bitrate = 900_000, durationMs = 1000, energy = 0.5)))
        store.replaceAll(SongOrigin.Navidrome, listOf(Song("n1", origin = SongOrigin.Navidrome, title = "N")))
        store.replaceAll(SongOrigin.Local, listOf(Song("/b.m4a", title = "B")))
        assertEquals(setOf("/b.m4a", "n1"), store.all().map { it.id }.toSet())
        store.replaceAll(SongOrigin.Local, listOf(Song("/a.m4a", title = "A", energy = 0.5, durationMs = 7)))
        assertEquals(0.5, store.get("/a.m4a")!!.energy); assertEquals(7, store.get("/a.m4a")!!.durationMs)
    }

    @Test fun playsAreCountedOnce() = runTest {
        val store = SongStore(db()); store.upsert(listOf(Song("x", title = "X")))
        store.recordPlay("x", Instant.parse("2026-01-01T00:00:00Z")); assertEquals(1, store.get("x")!!.playCount); assertNotNull(store.get("x")!!.lastPlayed)
    }

    @Test fun favoriteExistsAndPlaylistsKeepOrder() = runTest {
        val p = PlaylistStore(db()); p.load()
        assertTrue(p.byName(FAVORITE)!!.pinned)
        assertTrue(p.create("Road")); assertFalse(p.create("Road"))
        p.add("Road", listOf("a", "b")); p.add("Road", listOf("c", "a"))
        assertEquals(listOf("c", "b", "a"), p.byName("Road")!!.songIds) // newest first, as the original did
        p.remove("Road", setOf("a")); assertEquals(listOf("c", "b"), p.byName("Road")!!.songIds)
        p.toggleFavorite("c"); assertTrue("c" in p.favoriteIds); p.toggleFavorite("c"); assertFalse("c" in p.favoriteIds)
        assertTrue(p.rename("Road", "Trip")); assertEquals(listOf("c", "b"), p.byName("Trip")!!.songIds)
        p.delete(FAVORITE); assertNotNull(p.byName(FAVORITE))
    }

    @Test fun freeNameAvoidsCollisions() = runTest { val p = PlaylistStore(db()); p.load(); p.create("Mix"); assertEquals("Mix (2)", p.freeName("Mix")) }

    @Test fun kvBlobsPersist() = runTest { val d = db(); d.putKv("k", "v"); assertEquals("v", d.getKv("k")); assertNull(d.getKv("missing")) }

    @Test fun libraryGroupsAlbumsAndCreditedArtists() = runTest {
        val lib = Library(SongStore(db()))
        lib.set(listOf(Song("1", title = "a", artist = "X & Y", album = "Al", track = 2), Song("2", title = "b", artist = "X", album = "Al", track = 1)))
        assertEquals(1, lib.albums.value.size); assertEquals(listOf("2", "1"), lib.albums.value[0].songs.map { it.id })
        assertEquals(setOf("X", "Y"), lib.artists.value.map { it.name }.toSet()); assertEquals(2, lib.artistNamed("X")!!.songs.size)
    }
}
