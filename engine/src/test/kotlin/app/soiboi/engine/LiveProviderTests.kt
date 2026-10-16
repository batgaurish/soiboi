package app.soiboi.engine

import app.soiboi.engine.providers.SoundCloudClient
import app.soiboi.engine.providers.RadioClient
import app.soiboi.engine.providers.YouTubeMusicClient
import app.soiboi.engine.addons.AddonStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/** Hits the real services. Run with SOIBOI_LIVE=1; skipped otherwise so the suite stays offline. */
class LiveProviderTests {
    private val live = System.getenv("SOIBOI_LIVE") == "1"

    @Test fun soundcloud() { if (!live) return; runBlocking {
        val c = SoundCloudClient()
        val r = c.search("daft punk")
        println("SC tracks=${r.tracks.size} sets=${r.playlists.size} users=${r.artists.size} first=${r.tracks.firstOrNull()}")
        assertTrue(r.tracks.isNotEmpty())
        val s = c.stream(r.tracks.first().id); println("SC stream ${s.format} ${s.url.take(80)}")
        assertTrue(s.url.startsWith("http"))
        val top = c.catalog("personalised-curated-global"); println("SC curated=${top.playlists.size}"); assertTrue(top.playlists.isNotEmpty())
        val set = r.playlists.firstOrNull()?.let { c.playlist(it.id) }; println("SC set ${set?.title} ${set?.tracks?.size}")
    } }

    @Test fun radio() { if (!live) return; runBlocking {
        val c = RadioClient()
        val r = c.search("bbc radio 1"); println("RADIO search=${r.tracks.size} first=${r.tracks.firstOrNull()}")
        assertTrue(r.tracks.isNotEmpty())
        val s = c.stream(r.tracks.first().id); println("RADIO stream ${s.url}")
        val t = c.catalog("tag:lofi"); println("RADIO lofi=${t.tracks.size}"); assertTrue(t.tracks.isNotEmpty())
        val top = c.catalog("topvote"); println("RADIO top=${top.tracks.size}"); assertTrue(top.tracks.isNotEmpty())
    } }

    @Test fun ytmusic() { if (!live) return; runBlocking {
        val c = YouTubeMusicClient { AddonStream("x", null, null, emptyMap()) }
        val r = c.search("daft punk around the world")
        println("YT tracks=${r.tracks.size} albums=${r.albums.size} artists=${r.artists.size} playlists=${r.playlists.size}")
        println("YT first=${r.tracks.firstOrNull()}"); println("YT album=${r.albums.firstOrNull()}"); println("YT artist=${r.artists.firstOrNull()}"); println("YT pl=${r.playlists.firstOrNull()}")
        assertTrue(r.tracks.isNotEmpty())
        r.albums.firstOrNull()?.let { val d = c.album(it.id); println("YT albumdetail ${d.title} tracks=${d.tracks.size} ${d.tracks.firstOrNull()}") }
        r.artists.firstOrNull()?.let { val d = c.artist(it.id); println("YT artistdetail ${d.title} tracks=${d.tracks.size} albums=${d.albums.size}") }
        r.playlists.firstOrNull()?.let { val d = c.playlist(it.id); println("YT playlist ${d.title} tracks=${d.tracks.size}") }
        val radio = c.playlist("RDAMVM" + r.tracks.first().id); println("YT radio=${radio.tracks.size} ${radio.tracks.firstOrNull()?.title}"); assertTrue(radio.tracks.isNotEmpty())
        val home = c.catalog("home"); println("YT home t=${home.tracks.size} a=${home.albums.size} p=${home.playlists.size}")
        val na = c.catalog("new_albums"); println("YT new albums=${na.albums.size}")
    } }
}
