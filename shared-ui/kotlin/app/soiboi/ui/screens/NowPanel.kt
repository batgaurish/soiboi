package app.soiboi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.library.QualityInfo
import app.soiboi.engine.library.Song
import app.soiboi.ui.AppController
import app.soiboi.ui.RightPanel
import app.soiboi.ui.Route
import app.soiboi.ui.SongMenu
import app.soiboi.ui.components.AlbumTile
import app.soiboi.ui.components.Cover
import app.soiboi.ui.components.Hairline
import app.soiboi.ui.components.QualitySticker
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.leadShadow
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch

/**
 * The right-hand "now playing" column: the cover, who made it, the lyric line being sung, what is next
 * and more from the same artist. Everything a glance needs without leaving the page you are on.
 */
@Composable
fun NowPlayingPanel(app: AppController, modifier: Modifier = Modifier) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val state by engine.player.state.collectAsState()
    val artists by engine.library.artists.collectAsState()
    val song = queue.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(modifier.background(c.paper)) {
        Row(Modifier.fillMaxWidth().padding(start = Space.l, end = Space.xs, top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Txt(song?.displayAlbum ?: "Now playing", Modifier.weight(1f), style = Soiboi.type.bodyStrong, maxLines = 1)
            SIconButton(Glyph.Queue, "Show the queue", { app.rightPanel = RightPanel.Queue }, size = 40.dp, iconSize = 18.dp)
            SIconButton(Glyph.Close, "Close this panel", { app.rightPanel = null }, size = 40.dp, iconSize = 18.dp)
        }
        Hairline()
        if (song == null) {
            Column(Modifier.fillMaxSize().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt("Nothing is playing", style = Soiboi.type.headline)
                Txt("Pick a song and its cover, lyrics and what comes next show up here.", style = Soiboi.type.body, color = c.soft)
            }
            return@Column
        }
        val isFav = song.id in engine.playlists.favoriteIds
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = maxWidth
                Box(Modifier.leadShadow(Soiboi.look.shape(18))) { Cover(song, side, shape = Soiboi.look.shape(18), contentDescription = "Cover of ${song.displayAlbum}") }
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Txt(song.displayTitle, style = Soiboi.type.headline, maxLines = 2)
                    Txt(song.displayArtist, Modifier.press({ app.go(Route.Artist(song.artists.first())) }, "Go to ${song.displayArtist}", shape = Soiboi.look.shape(6)), style = Soiboi.type.body, color = c.soft, maxLines = 1)
                }
                SIconButton(if (isFav) Glyph.HeartFilled else Glyph.Heart, if (isFav) "Remove from favorites" else "Add to favorites", { app.toggleFavorite(song) }, tint = if (isFav) c.accent else c.ink, size = 40.dp, iconSize = 20.dp)
                SIconButton(Glyph.More, "More for this song", { app.songMenu = SongMenu(song) }, size = 40.dp, iconSize = 20.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                QualitySticker(QualityInfo.of(song))
                QualityInfo.of(song).detail?.let { Txt(it, style = Soiboi.type.caption, color = c.soft, maxLines = 1) }
            }

            PanelCard("Lyrics") {
                LyricPeek(app, song, state.positionMs)
                SButton("Open lyrics", { app.nowPlayingOpen = true }, tone = Tone.Secondary, compact = true, glyph = Glyph.Lyrics)
            }

            PanelCard("About this song") {
                Fact("Artist", song.displayArtist, onClick = { app.go(Route.Artist(song.artists.first())) })
                Fact("Album", song.displayAlbum, onClick = { app.go(Route.Album(song.displayAlbum)) })
                song.year?.let { Fact("Year", "$it") }
                song.track?.let { Fact("Track", "$it" + (song.disc?.let { d -> ", disc $d" } ?: "")) }
                song.genre?.takeIf { it.isNotBlank() }?.let { Fact("Genre", it) }
                song.bpm?.let { Fact("Tempo", "${it.toInt()} BPM" + (song.musicalKey?.let { k -> ", $k" } ?: "")) }
                if (song.playCount > 0) Fact("Plays", "${song.playCount}")
            }

            val upcoming = queue.songs.drop(queue.currentIndex + 1).take(3)
            if (upcoming.isNotEmpty()) PanelCard("Next in queue") {
                val favs = engine.playlists.favoriteIds
                upcoming.forEachIndexed { i, s -> SongRow(s, null, false, s.id in favs, { scope.launch { engine.player.jumpTo(queue.currentIndex + 1 + i) } }, { app.songMenu = SongMenu(s) }, showAlbum = false, compact = true) }
                SButton("Open queue", { app.rightPanel = RightPanel.Queue }, tone = Tone.Ghost, compact = true, glyph = Glyph.Queue)
            }

            val artist = artists.firstOrNull { it.name == song.artists.first() }
            val others = artist?.albums?.filter { it.name != song.displayAlbum }.orEmpty()
            if (others.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt("More from ${artist!!.name}", style = Soiboi.type.bodyStrong)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.m)) { items(others, key = { it.name }) { a -> AlbumTile(a, { app.go(Route.Album(a.name)) }, size = 112.dp) } }
            }
        }
    }
}

@Composable
private fun PanelCard(title: String, content: @Composable () -> Unit) {
    val c = Soiboi.colors
    Column(Modifier.fillMaxWidth().background(c.raised, Soiboi.look.shape(14)).border(1.dp, c.rule, Soiboi.look.shape(14)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Txt(title, style = Soiboi.type.label, color = c.soft)
        content()
    }
}

@Composable
private fun Fact(label: String, value: String, onClick: (() -> Unit)? = null) {
    val c = Soiboi.colors
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.press(onClick, "$label, $value", shape = Soiboi.look.shape(6)) else Modifier), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Txt(label, Modifier.padding(end = 0.dp), style = Soiboi.type.caption, color = c.muted)
        Txt(value, Modifier.weight(1f), style = Soiboi.type.caption, color = if (onClick != null) c.ink else c.soft, maxLines = 2)
    }
}

/** The line being sung and the three after it. Falls back to the opening lines of unsynced words. */
@Composable
private fun LyricPeek(app: AppController, song: Song, positionMs: Long) {
    val c = Soiboi.colors
    val lyrics by produceState<app.soiboi.engine.lyrics.ParsedLyrics?>(null, song.id) { value = null; value = app.engine.lyrics.parsedFor(song) }
    val parsed = lyrics
    when {
        parsed == null -> Txt("Looking for lyrics.", style = Soiboi.type.caption, color = c.soft)
        parsed.lines.isEmpty() || (parsed.lines.size == 1 && parsed.lines[0].text.startsWith("No lyrics")) ->
            Txt("No lyrics for this song. Turn on LRCLIB in Settings to fetch synced ones.", style = Soiboi.type.caption, color = c.soft)
        else -> {
            val current = if (parsed.isSynced) parsed.lineAt(positionMs).coerceAtLeast(0) else 0
            parsed.lines.drop(current).take(4).forEachIndexed { i, line ->
                Txt(line.text, style = if (i == 0 && parsed.isSynced) Soiboi.type.title else Soiboi.type.body, color = if (i == 0 && parsed.isSynced) c.ink else c.soft, maxLines = 2)
            }
        }
    }
}
