package app.soiboi.engine

import app.soiboi.engine.apple.AppleCatalog
import app.soiboi.engine.apple.pickOriginalRelease
import app.soiboi.engine.apple.sameArtist
import app.soiboi.engine.download.DownloadFailure
import app.soiboi.engine.download.DownloadJobState
import app.soiboi.engine.download.DownloadQueue
import app.soiboi.engine.download.DownloadRequest
import app.soiboi.engine.download.PipelineCapabilities
import app.soiboi.engine.download.StatusFix
import app.soiboi.engine.download.StatusInputs
import app.soiboi.engine.download.StatusLevel
import app.soiboi.engine.download.TrackStatus
import app.soiboi.engine.download.WrapperStage
import app.soiboi.engine.download.WrapperState
import app.soiboi.engine.download.buildStatusChecks
import app.soiboi.engine.library.Database
import app.soiboi.engine.library.FAVORITE
import app.soiboi.engine.library.Library
import app.soiboi.engine.library.PlaylistStore
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.library.SongStore
import app.soiboi.engine.sources.Http
import app.soiboi.engine.sources.PlaylistSources
import app.soiboi.engine.sources.YouTubePlaylistSource
import app.soiboi.engine.sources.deezerPlaylistId
import app.soiboi.engine.sources.hostMatches
import app.soiboi.engine.sources.jiosaavnPlaylistToken
import app.soiboi.engine.sources.parseBandcampAlbum
import app.soiboi.engine.sources.parseDeezerTrack
import app.soiboi.engine.sources.parseJsonLdPlaylist
import app.soiboi.engine.sources.parseSoundcloudClientId
import app.soiboi.engine.sources.parseSpotifyEmbed
import app.soiboi.engine.sources.parseTidalItem
import app.soiboi.engine.sources.parseTracklist
import app.soiboi.engine.sources.parseYouTubeTrack
import app.soiboi.engine.sources.soundcloudSetUrl
import app.soiboi.engine.sources.spotifyPlaylistId
import app.soiboi.engine.sources.tidalPlaylistId
import app.soiboi.engine.sources.youTubePlaylistId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkParsingTest {
    @Test fun spotifyAcceptsUrlsAndUris() {
        val id = "37i9dQZF1DX"
        assertEquals(id, spotifyPlaylistId("https://open.spotify.com/playlist/$id?si=abc")); assertEquals(id, spotifyPlaylistId("spotify:playlist:$id"))
        assertEquals(id, spotifyPlaylistId("open.spotify.com/embed/playlist/$id")); assertNull(spotifyPlaylistId("https://evilspotify.com/playlist/$id"))
    }

    @Test fun hostMatchIsABoundaryNotASuffix() { assertTrue(hostMatches("www.deezer.com", "deezer.com")); assertFalse(hostMatches("notdeezer.com", "deezer.com")) }

    @Test fun deezerTidalYouTubeSoundcloudJiosaavn() {
        assertEquals("123", deezerPlaylistId("https://www.deezer.com/en/playlist/123"))
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", tidalPlaylistId("https://tidal.com/browse/playlist/AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
        assertEquals("PLabcdefghijk", youTubePlaylistId("https://music.youtube.com/playlist?list=PLabcdefghijk")); assertEquals("PLabcdefghijk", youTubePlaylistId("PLabcdefghijk")); assertNull(youTubePlaylistId("https://youtube.com/watch?v=x"))
        assertEquals("https://soundcloud.com/me/sets/mix", soundcloudSetUrl("https://soundcloud.com/me/sets/mix?ref=x")); assertNull(soundcloudSetUrl("https://soundcloud.com/me/track"))
        assertEquals("tok_en123", jiosaavnPlaylistToken("https://www.jiosaavn.com/featured/best/tok_en123"))
    }

    @Test fun theRegistryAsksEachSourceAndPastedTextGoesLast() {
        val sources = PlaylistSources.standard(YouTubePlaylistSource(FakePipeline()))
        assertEquals("spotify", sources.forUrl("https://open.spotify.com/playlist/abc123")?.source?.id)
        assertEquals("tracklist", sources.forUrl("A - B\nC - D")?.source?.id); assertNull(sources.forUrl("hello"))
    }
}

class SourceParsersTest {
    @Test fun spotifyEmbedKeepsCreditsAndCollapsesNonBreakingSpaces() {
        val html = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"name":"Mix","trackList":[{"title":"Song","subtitle":"KAROL G, Judeline"},{"title":"","subtitle":"x"}]}}}}}}</script>"""
        val e = parseSpotifyEmbed(html)!!
        assertEquals("Mix", e.title); assertEquals(1, e.tracks.size); assertEquals("KAROL G, Judeline", e.tracks[0].artist)
        assertNull(parseSpotifyEmbed("<html></html>"))
    }

    @Test fun deezerAndTidalCarryIsrcAndVersions() {
        val d = parseDeezerTrack(Http.json.parseToJsonElement("""{"title":"T","artist":{"name":"A"},"isrc":"USRC1"}""") as JsonObject)!!
        assertEquals("USRC1", d.isrc)
        val t = parseTidalItem(Http.json.parseToJsonElement("""{"type":"track","item":{"title":"Song","version":"Remastered","artist":{"name":"A"},"isrc":"X"}}""") as JsonObject)!!
        assertEquals("Song (Remastered)", t.title)
        assertNull(parseTidalItem(Http.json.parseToJsonElement("""{"type":"video","item":{"title":"v"}}""") as JsonObject))
    }

    @Test fun youTubeTitlesAreUnpicked() {
        assertEquals("Instant Crush" to "Daft Punk", parseYouTubeTrack("Daft Punk - Instant Crush ft. Julian Casablancas (Official Video) [HD]", "Daft Punk").let { it.title to it.artist })
        assertEquals("Song" to "Artist", parseYouTubeTrack("Song", "Artist - Topic").let { it.title to it.artist })
        assertEquals("Sun-El" to "Uploader", parseYouTubeTrack("Sun-El", "Uploader").let { it.title to it.artist })
    }

    @Test fun tracklistsParseLinesAndCsv() {
        assertEquals(listOf("Artist" to "Title"), parseTracklist("1. Artist - Title").map { it.artist to it.title })
        val csv = parseTracklist("Artist,Title,ISRC\n\"Foo, Inc\",\"Bar\",US123")
        assertEquals("Foo, Inc", csv.single().artist); assertEquals("US123", csv.single().isrc)
    }

    @Test fun jsonLdAndBandcampPages() {
        val ld = """<script type="application/ld+json">{"@type":"MusicPlaylist","name":"P","track":[{"name":"S1","byArtist":{"name":"A, B, C"}}]}</script>"""
        val p = parseJsonLdPlaylist(ld)!!; assertEquals("P", p.title); assertEquals("A, B", p.tracks.single().artist)
        val bc = """<div data-tralbum="{&quot;artist&quot;:&quot;Band&quot;,&quot;current&quot;:{&quot;title&quot;:&quot;Alb&quot;},&quot;trackinfo&quot;:[{&quot;title&quot;:&quot;One&quot;}]}"></div>"""
        assertEquals("Band", parseBandcampAlbum(bc)!!.tracks.single().artist)
    }

    @Test fun soundcloudClientIdComesFromTheHydrationBlob() =
        assertEquals("abc", parseSoundcloudClientId("""<script>window.__sc_hydration = [{"hydratable":"apiClient","data":{"id":"abc"}}];</script>"""))
}

class AppleMatchingTest {
    private fun row(track: String, artist: String, album: String, collArtist: String = artist) =
        Http.json.parseToJsonElement("""{"trackName":"$track","artistName":"$artist","collectionName":"$album","collectionArtistName":"$collArtist"}""") as JsonObject

    @Test fun prefersTheOriginalAlbumOverCompilationsAndSingles() {
        val rows = listOf(row("Song", "Artist", "Now 99", "Various Artists"), row("Song", "Artist", "Song - Single"), row("Song", "Artist", "The Album"))
        assertEquals("The Album", pickOriginalRelease(rows, "Artist", "Song")!!["collectionName"].toString().trim('"'))
    }

    @Test fun someoneElsesCoverIsNotAMatch() = assertNull(pickOriginalRelease(listOf(row("Slave", "Jaydan Wolf", "Covers")), "Måneskin", "Slave"))

    @Test fun artistMatchingTolertatesSpellingButNotStrangers() {
        assertTrue(sameArtist("Mohammad Irfan", "Mohammed Irfan, Arijit & Saim Bhat")); assertFalse(sameArtist("Sia", "SZA")); assertFalse(sameArtist("Måneskin", "Jaydan Wolf"))
    }

    @Test fun songIdsComeFromBothLinkShapes() {
        assertEquals("123", AppleCatalog.songIdFromUrl("https://music.apple.com/us/album/x/999?i=123")); assertEquals("456", AppleCatalog.songIdFromUrl("https://music.apple.com/us/song/name/456")); assertNull(AppleCatalog.songIdFromUrl("https://example.com/?i=1"))
    }
}

class StatusChecksTest {
    private fun inputs(codec: String = "aac", cookies: Boolean = false, wrapperSigned: Boolean = false, wrapper: WrapperState = WrapperState(WrapperStage.Stopped), free: Long? = 50L shl 30, online: Boolean? = true, expiry: Instant? = null) = StatusInputs(
        Instant.parse("2026-10-15T00:00:00Z"), codec, cookies, expiry, wrapperSigned, wrapper, PipelineCapabilities(true, "3.12", emptyList(), true), "", "/d", free, online,
    )

    @Test fun notSignedInIsTheOneProblem() {
        val checks = buildStatusChecks(inputs()); assertEquals(StatusLevel.Problem, checks[0].level); assertEquals(StatusFix.SignIn, checks[0].fix)
    }

    @Test fun losslessWrapperOnlyMattersForAlac() {
        val missing = WrapperState(WrapperStage.NeedsLibraries)
        assertEquals(StatusLevel.Info, buildStatusChecks(inputs("aac", cookies = true, wrapper = missing))[1].level)
        assertEquals(StatusLevel.Problem, buildStatusChecks(inputs("alac", cookies = true, wrapper = missing))[1].level)
    }

    @Test fun expiringSessionsWarnAndExpiredOnesFail() {
        assertEquals(StatusLevel.Warning, buildStatusChecks(inputs(cookies = true, expiry = Instant.parse("2026-10-18T00:00:00Z")))[0].level)
        assertEquals(StatusLevel.Problem, buildStatusChecks(inputs(cookies = true, expiry = Instant.parse("2026-10-01T00:00:00Z")))[0].level)
    }

    @Test fun lowSpaceWarnsAndNoSpaceFails() {
        assertEquals(StatusLevel.Warning, buildStatusChecks(inputs(cookies = true, free = 500L shl 20))[3].level)
        assertEquals(StatusLevel.Problem, buildStatusChecks(inputs(cookies = true, free = 100L shl 20))[3].level)
    }

    @Test fun offlineIsAProblemAndUnknownIsInfo() {
        assertEquals(StatusLevel.Problem, buildStatusChecks(inputs(online = false))[4].level); assertEquals(StatusLevel.Info, buildStatusChecks(inputs(online = null))[4].level)
    }
}

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

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadQueueTest {
    @Test fun jobsRunOneAtATimeInOrderAndTheBatchCompletes() = runTest {
        val order = mutableListOf<String>(); var running = 0; var maxRunning = 0; var synced = 0
        val queue = DownloadQueue(this,
            archive = { url, progress, log, track, _ -> running++; maxRunning = maxOf(maxRunning, running); order += url; progress(50, "half"); track(TrackStatus(1, "t", app.soiboi.engine.download.TrackState.Done)); running--; null },
            syncLibrary = { synced++ }, stopActive = {}, logDir = File(System.getProperty("java.io.tmpdir"), "soiboi-q"))
        val batch = queue.enqueue(listOf("a", "b", "c").map { DownloadRequest(it, it) })
        advanceUntilIdle(); batch.await()
        assertEquals(listOf("a", "b", "c"), order); assertEquals(1, maxRunning); assertEquals(1, synced, "one library sync when the queue goes quiet")
        assertTrue(batch.jobs.all { it.state == DownloadJobState.Done }); assertEquals(3, batch.completed.value)
    }

    @Test fun failuresAreExplainedAndRetryable() = runTest {
        var attempt = 0
        val queue = DownloadQueue(this, archive = { _, _, _, _, _ -> if (attempt++ == 0) DownloadFailure("Connection reset") else null }, syncLibrary = {}, stopActive = {}, logDir = File(System.getProperty("java.io.tmpdir"), "soiboi-q"))
        val batch = queue.enqueue(listOf(DownloadRequest("u", "Song"))); advanceUntilIdle()
        val job = batch.jobs.single(); assertEquals(DownloadJobState.Failed, job.state); assertEquals("network", job.failure!!.id); assertTrue(job.failure!!.temporary)
        queue.retry(job); advanceUntilIdle(); assertEquals(DownloadJobState.Done, job.state)
    }

    @Test fun emptyBatchesStillComplete() = runTest {
        val queue = DownloadQueue(this, { _, _, _, _, _ -> null }, {}, {}, File("x")); queue.enqueue(emptyList()).await()
    }

    @Test fun cancellingAQueuedJobSkipsIt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val queue = DownloadQueue(this, archive = { _, _, _, _, _ -> gate.await(); null }, syncLibrary = {}, stopActive = { gate.complete(Unit) }, logDir = File(System.getProperty("java.io.tmpdir"), "soiboi-q"))
        val batch = queue.enqueue(listOf(DownloadRequest("1", "one"), DownloadRequest("2", "two")))
        advanceUntilIdle(); queue.cancel(batch.jobs[1]); gate.complete(Unit); advanceUntilIdle()
        assertEquals(DownloadJobState.Done, batch.jobs[0].state); assertEquals(DownloadJobState.Cancelled, batch.jobs[1].state)
    }
}

/** A pipeline that answers nothing, for sources that need one but are not under test. */
class FakePipeline : app.soiboi.engine.download.PipelineRunner() {
    override fun run(command: String, payload: JsonObject?) = kotlinx.coroutines.flow.emptyFlow<app.soiboi.engine.download.PipelineEvent>()
    override suspend fun cancelDownload() {}
}
