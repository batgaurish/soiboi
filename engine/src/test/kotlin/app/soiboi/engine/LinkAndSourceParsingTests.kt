package app.soiboi.engine

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
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

/** A pipeline that answers nothing, for sources that need one but are not under test. */
class FakePipeline : app.soiboi.engine.download.PipelineRunner() {
    override fun run(command: String, payload: JsonObject?) = kotlinx.coroutines.flow.emptyFlow<app.soiboi.engine.download.PipelineEvent>()
    override suspend fun cancelDownload() {}
}
