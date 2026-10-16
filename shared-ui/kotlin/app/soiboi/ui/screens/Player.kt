package app.soiboi.ui.screens

import app.soiboi.ui.SongMenu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soiboi.engine.library.QualityInfo
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.library.Song
import app.soiboi.engine.player.EqFrequencies
import app.soiboi.engine.player.PlayMode
import app.soiboi.ui.AppController
import app.soiboi.ui.RightPanel
import app.soiboi.ui.LocalEngine
import app.soiboi.ui.Route
import app.soiboi.ui.components.Chip
import app.soiboi.ui.components.Cover
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.Hairline
import app.soiboi.ui.components.Loading
import app.soiboi.ui.components.PlayButton
import app.soiboi.ui.components.QualitySticker
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SSlider
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.formatTime
import app.soiboi.ui.components.leadShadow
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch

private fun playModeGlyph(m: PlayMode) = when (m) { PlayMode.Order -> Glyph.Next; PlayMode.Shuffle -> Glyph.Shuffle; PlayMode.Repeat -> Glyph.RepeatOne }

/** The always-present transport: cover and title on the left, controls in the middle, seek and volume on the right. */
@Composable
fun PlayerBar(app: AppController, wide: Boolean) {
    val c = Soiboi.colors
    val player = app.engine.player
    val queue by player.queue.collectAsState()
    val state by player.state.collectAsState()
    val mode by player.mode.collectAsState()
    val volume by player.volume.collectAsState()
    val favorites by app.engine.playlists.playlists.collectAsState()
    val song = queue.current
    val live = song != null && song.origin == SongOrigin.Addon && song.album == "Live radio"
    val scope = rememberCoroutineScope()
    val isFav = song != null && song.id in app.engine.playlists.favoriteIds
    val fraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    var seekDrag by remember { mutableStateOf<Float?>(null) }

    if (song == null) {
        Row(Modifier.fillMaxWidth().background(c.paper).padding(horizontal = Space.l, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Txt("Nothing playing. Pick a song and it shows up here.", style = Soiboi.type.body, color = c.soft, maxLines = 1)
        }
        return
    }

    if (!wide) {
        Column(Modifier.fillMaxWidth().background(c.raised)) {
            Row(Modifier.fillMaxWidth().press({ app.nowPlayingOpen = true }, "Open player, ${song.displayTitle}", shape = Soiboi.look.shape(0)).padding(horizontal = Space.m, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Cover(song, 48.dp, shape = Soiboi.look.shape(8))
                Column(Modifier.weight(1f)) { Txt(song.displayTitle, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(if (live) (state.streamTitle ?: song.displayArtist) else song.displayArtist, style = Soiboi.type.caption, color = c.soft, maxLines = 1) }
                PlayButton(state.playing, { player.togglePlay() }, size = 44.dp)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.rule)) { Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(c.accent)) }
        }
        return
    }

    Row(Modifier.fillMaxWidth().background(c.raised).padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        Row(Modifier.width(300.dp).press({ app.nowPlayingOpen = true }, "Open player, ${song.displayTitle}", shape = Soiboi.look.shape(12)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Cover(song, 56.dp, shape = Soiboi.look.shape(10))
            Column(Modifier.weight(1f)) {
                Txt(song.displayTitle, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(if (live) (state.streamTitle ?: song.displayArtist) else song.displayArtist, style = Soiboi.type.caption, color = c.soft, maxLines = 1)
                Row(Modifier.padding(top = 2.dp)) { QualitySticker(QualityInfo.of(song)) }
            }
        }
        SIconButton(if (isFav) Glyph.HeartFilled else Glyph.Heart, if (isFav) "Remove from favorites" else "Add to favorites", { app.toggleFavorite(song) }, tint = if (isFav) c.accent else c.ink, size = 40.dp, iconSize = 20.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                SIconButton(Glyph.Shuffle, "Shuffle", { scope.launch { player.setMode(if (mode == PlayMode.Shuffle) PlayMode.Order else PlayMode.Shuffle) } }, tint = if (mode == PlayMode.Shuffle) c.accent else c.ink, size = 40.dp, iconSize = 20.dp)
                SIconButton(Glyph.Previous, "Previous", { scope.launch { player.previous() } }, size = 44.dp)
                PlayButton(state.playing, { player.togglePlay() }, size = 52.dp)
                SIconButton(Glyph.Next, "Next", { scope.launch { player.next() } }, size = 44.dp)
                SIconButton(if (mode == PlayMode.Repeat) Glyph.RepeatOne else Glyph.Repeat, "Repeat this song", { player.toggleRepeat() }, tint = if (mode == PlayMode.Repeat) c.accent else c.ink, size = 40.dp, iconSize = 20.dp)
            }
            Row(Modifier.fillMaxWidth().widthIn(max = 560.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt(formatTime(state.positionMs), Modifier.width(44.dp), style = Soiboi.type.caption, color = c.soft, maxLines = 1)
                SSlider(seekDrag ?: fraction, { seekDrag = it }, Modifier.weight(1f), onChangeFinished = { seekDrag?.let { f -> if (!live) scope.launch { player.seek((f * state.durationMs).toLong()) } }; seekDrag = null }, label = "Seek", step = 0.02f, valueText = if (live) "Live radio" else "${formatTime(state.positionMs)} of ${formatTime(state.durationMs)}")
                Txt(if (live) "LIVE" else formatTime(state.durationMs), Modifier.width(44.dp), style = Soiboi.type.caption, color = c.soft, maxLines = 1)
            }
        }
        Row(Modifier.width(250.dp), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            SIconButton(Glyph.Disc, "Now playing panel", { app.rightPanel = if (app.rightPanel == RightPanel.Now) null else RightPanel.Now }, tint = if (app.rightPanel == RightPanel.Now) c.accent else c.ink, size = 40.dp, iconSize = 20.dp)
            SIconButton(Glyph.Lyrics, "Open lyrics full screen", { app.nowPlayingOpen = true }, size = 40.dp, iconSize = 20.dp)
            SIconButton(Glyph.Queue, "Queue", { app.rightPanel = if (app.rightPanel == RightPanel.Queue) null else RightPanel.Queue }, tint = if (app.queueOpen) c.accent else c.ink, size = 40.dp, iconSize = 20.dp)
            GlyphIcon(if (volume == 0.0) Glyph.VolumeMute else Glyph.Volume, size = 20.dp, tint = c.soft)
            SSlider(volume.toFloat(), { player.setVolume(it.toDouble()) }, Modifier.weight(1f), label = "Volume", valueText = "${(volume * 100).toInt()} percent")
        }
    }
}

@Composable
private fun rememberCoroutineScope() = androidx.compose.runtime.rememberCoroutineScope()


/** The queue as a column: what is playing, then what comes next, with a remove button on each upcoming row. */
@Composable
fun QueuePanel(app: AppController, modifier: Modifier = Modifier, embedded: Boolean = false) {
    val c = Soiboi.colors
    val player = app.engine.player
    val queue by player.queue.collectAsState()
    val state by player.state.collectAsState()
    val mode by player.mode.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val favs = app.engine.playlists.favoriteIds
    val listState = rememberLazyListState()
    LaunchedEffect(queue.currentIndex) { if (queue.currentIndex >= 0) listState.animateScrollToItem(queue.currentIndex.coerceAtLeast(0)) }
    Column(if (embedded) modifier else modifier.background(c.paper)) {
        if (!embedded) {
        Row(Modifier.fillMaxWidth().padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Txt("Queue", Modifier.weight(1f), style = Soiboi.type.headline)
            SIconButton(Glyph.Trash, "Clear the queue", { scope.launch { player.clear() } }, size = 40.dp, iconSize = 18.dp)
            SIconButton(Glyph.Close, "Close the queue", { app.queueOpen = false }, size = 40.dp, iconSize = 18.dp)
        }
        Hairline()
        }
        if (queue.songs.isEmpty()) EmptyState("The queue is empty", "Play an album or a playlist, or choose Add to queue on any song.", glyph = Glyph.Queue)
        else LazyColumn(Modifier.weight(1f), state = listState) {
            itemsIndexed(queue.songs, key = { i, s -> "$i:${s.id}" }) { i, s ->
                SongRow(s, i + 1, i == queue.currentIndex, s.id in favs, { scope.launch { player.jumpTo(i) } }, { app.songMenu = SongMenu(s) }, showAlbum = false, compact = true)
            }
        }
    }
}


@Composable
internal fun EqualizerDialog(app: AppController, onDismiss: () -> Unit) {
    val c = Soiboi.colors
    val player = app.engine.player
    val gains by player.equalizer.collectAsState()
    SDialog("Equalizer", onDismiss, width = 560.dp, actions = { SButton("Reset to flat", { player.setEqualizer(List(EqFrequencies.size) { 0.0 }) }, tone = Tone.Ghost) }) {
        Txt("Each band moves from minus 12 to plus 12 decibels. All at zero means no filter.", style = Soiboi.type.caption, color = c.soft)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EqFrequencies.forEachIndexed { i, hz ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("%+.0f".format(gains[i]), style = Soiboi.type.micro, color = c.soft)
                    Box(Modifier.height(150.dp).width(40.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                            val h = maxHeight
                            Box(Modifier.align(Alignment.Center).width(5.dp).fillMaxHeight().background(c.rule, androidx.compose.foundation.shape.CircleShape))
                            val frac = ((gains[i] + 12.0) / 24.0).toFloat().coerceIn(0f, 1f)
                            Box(Modifier.align(Alignment.TopCenter).padding(top = (h - 16.dp) * (1f - frac)).size(16.dp).background(c.accent, androidx.compose.foundation.shape.CircleShape).border(2.dp, c.paper, androidx.compose.foundation.shape.CircleShape))
                            Column(Modifier.fillMaxSize()) {
                                // Three tap zones step the band: it is a control with a keyboard-friendly alternative to dragging.
                                Box(Modifier.weight(1f).fillMaxWidth().press({ player.setEqualizer(gains.toMutableList().also { it[i] = (it[i] + 1).coerceAtMost(12.0) }) }, "Raise ${hzLabel(hz)}", Role.Button, shape = Soiboi.look.shape(6)))
                                Box(Modifier.weight(1f).fillMaxWidth().press({ player.setEqualizer(gains.toMutableList().also { it[i] = (it[i] - 1).coerceAtLeast(-12.0) }) }, "Lower ${hzLabel(hz)}", Role.Button, shape = Soiboi.look.shape(6)))
                            }
                        }
                    }
                    Txt(hzLabel(hz), style = Soiboi.type.micro, color = c.muted)
                }
            }
        }
        Txt("Tap above the middle of a band to raise it and below to lower it, one decibel at a time.", style = Soiboi.type.caption, color = c.soft)
    }
}

private fun hzLabel(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else "$hz"
