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
                Column(Modifier.weight(1f)) { Txt(song.displayTitle, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(song.displayArtist, style = Soiboi.type.caption, color = c.soft, maxLines = 1) }
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
                Txt(song.displayTitle, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(song.displayArtist, style = Soiboi.type.caption, color = c.soft, maxLines = 1)
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
                SSlider(seekDrag ?: fraction, { seekDrag = it }, Modifier.weight(1f), onChangeFinished = { seekDrag?.let { f -> scope.launch { player.seek((f * state.durationMs).toLong()) } }; seekDrag = null }, label = "Seek", step = 0.02f, valueText = "${formatTime(state.positionMs)} of ${formatTime(state.durationMs)}")
                Txt(formatTime(state.durationMs), Modifier.width(44.dp), style = Soiboi.type.caption, color = c.soft, maxLines = 1)
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
fun QueuePanel(app: AppController, modifier: Modifier = Modifier) {
    val c = Soiboi.colors
    val player = app.engine.player
    val queue by player.queue.collectAsState()
    val state by player.state.collectAsState()
    val mode by player.mode.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val favs = app.engine.playlists.favoriteIds
    val listState = rememberLazyListState()
    LaunchedEffect(queue.currentIndex) { if (queue.currentIndex >= 0) listState.animateScrollToItem(queue.currentIndex.coerceAtLeast(0)) }
    Column(modifier.background(c.paper)) {
        Row(Modifier.fillMaxWidth().padding(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Txt("Queue", Modifier.weight(1f), style = Soiboi.type.headline)
            SIconButton(Glyph.Trash, "Clear the queue", { scope.launch { player.clear() } }, size = 40.dp, iconSize = 18.dp)
            SIconButton(Glyph.Close, "Close the queue", { app.queueOpen = false }, size = 40.dp, iconSize = 18.dp)
        }
        Hairline()
        if (queue.songs.isEmpty()) EmptyState("The queue is empty", "Play an album or a playlist, or choose Add to queue on any song.", glyph = Glyph.Queue)
        else LazyColumn(Modifier.weight(1f), state = listState) {
            itemsIndexed(queue.songs, key = { i, s -> "$i:${s.id}" }) { i, s ->
                SongRow(s, i + 1, i == queue.currentIndex, s.id in favs, { scope.launch { player.jumpTo(i) } }, { app.songMenu = SongMenu(s) }, showAlbum = false, compact = true)
            }
        }
    }
}


/** The full player: the cover as the lead with a hard shadow, the controls beneath, and lyrics or the queue beside it. */
@Composable
fun NowPlayingScreen(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine; val player = engine.player
    val queue by player.queue.collectAsState()
    val state by player.state.collectAsState()
    val mode by player.mode.collectAsState()
    val song = queue.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var seekDrag by remember { mutableStateOf<Float?>(null) }
    var eqOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    val sleepLeft by player.sleepRemaining.collectAsState()
    val fraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    val isFav = song != null && song.id in engine.playlists.favoriteIds

    if (song == null) { Column(Modifier.fillMaxSize().padding(Space.l)) { SIconButton(Glyph.ChevronDown, "Close the player", { app.nowPlayingOpen = false }); EmptyState("Nothing is playing", "Choose a song from your library and it opens here.") }; return }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 860.dp
        val controls: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Txt(song.displayTitle, style = if (wide) Soiboi.type.display else Soiboi.type.headline.copy(fontSize = 32.sp, lineHeight = 36.sp), maxLines = 2)
                        Txt(song.displayArtist, Modifier.press({ app.go(Route.Artist(song.artists.first())) }, "Go to ${song.displayArtist}", shape = Soiboi.look.shape(6)), style = Soiboi.type.title, color = c.soft, maxLines = 1)
                        Txt(song.displayAlbum, Modifier.press({ app.go(Route.Album(song.displayAlbum)) }, "Go to ${song.displayAlbum}", shape = Soiboi.look.shape(6)), style = Soiboi.type.body, color = c.muted, maxLines = 1)
                    }
                    SIconButton(if (isFav) Glyph.HeartFilled else Glyph.Heart, if (isFav) "Remove from favorites" else "Add to favorites", { app.toggleFavorite(song) }, tint = if (isFav) c.accent else c.ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    QualitySticker(QualityInfo.of(song), large = true)
                    QualityInfo.of(song).detail?.let { Txt(it, style = Soiboi.type.caption, color = c.soft) }
                    song.bpm?.let { Txt("${it.toInt()} BPM", style = Soiboi.type.caption, color = c.soft) }
                }
                Column {
                    SSlider(seekDrag ?: fraction, { seekDrag = it }, Modifier.fillMaxWidth(), onChangeFinished = { seekDrag?.let { f -> scope.launch { player.seek((f * state.durationMs).toLong()) } }; seekDrag = null }, label = "Seek", step = 0.02f, valueText = "${formatTime(state.positionMs)} of ${formatTime(state.durationMs)}")
                    Row(Modifier.fillMaxWidth()) { Txt(formatTime(state.positionMs), Modifier.weight(1f), style = Soiboi.type.caption, color = c.soft); Txt(formatTime(state.durationMs), style = Soiboi.type.caption, color = c.soft) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SIconButton(Glyph.Shuffle, "Shuffle", { scope.launch { player.setMode(if (mode == PlayMode.Shuffle) PlayMode.Order else PlayMode.Shuffle) } }, tint = if (mode == PlayMode.Shuffle) c.accent else c.ink)
                    SIconButton(Glyph.Previous, "Previous", { scope.launch { player.previous() } }, size = 52.dp, iconSize = 26.dp)
                    PlayButton(state.playing, { player.togglePlay() }, size = 68.dp)
                    SIconButton(Glyph.Next, "Next", { scope.launch { player.next() } }, size = 52.dp, iconSize = 26.dp)
                    SIconButton(if (mode == PlayMode.Repeat) Glyph.RepeatOne else Glyph.Repeat, "Repeat this song", { player.toggleRepeat() }, tint = if (mode == PlayMode.Repeat) c.accent else c.ink)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Chip("Equalizer", false, { eqOpen = true }, glyph = Glyph.Sliders)
                    Chip(if (sleepLeft != null) "Sleep ${formatTime(sleepLeft!! * 1000L)}" else "Sleep timer", sleepLeft != null, { sleepOpen = true }, glyph = Glyph.Moon)
                }
            }
        }
        val side: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(bottom = Space.m)) {
                    Chip("Lyrics", tab == 0, { tab = 0 }, glyph = Glyph.Lyrics); Chip("Up next", tab == 1, { tab = 1 }, glyph = Glyph.Queue)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { if (tab == 0) LyricsPane(app, song, state.positionMs) else QueuePanel(app, Modifier.fillMaxSize()) }
            }
        }
        Column(Modifier.fillMaxSize().padding(Space.l)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SIconButton(Glyph.ChevronDown, "Close the player", { app.nowPlayingOpen = false })
                Spacer(Modifier.weight(1f)); Txt("Playing from ${queue.songs.size} songs", style = Soiboi.type.caption, color = c.muted)
            }
            Spacer(Modifier.height(Space.s))
            if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                    Box(Modifier.leadShadow(Soiboi.look.shape(24))) { Cover(song, 360.dp, shape = Soiboi.look.shape(24), contentDescription = "Cover of ${song.displayAlbum}") }
                    controls()
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { side() }
            } else Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Box(Modifier.leadShadow(Soiboi.look.shape(24))) { Cover(song, 240.dp, shape = Soiboi.look.shape(24), contentDescription = "Cover of ${song.displayAlbum}") } }
                controls()
                Box(Modifier.weight(1f).fillMaxWidth()) { side() }
            }
        }
    }
    if (eqOpen) EqualizerDialog(app) { eqOpen = false }
    if (sleepOpen) SDialog("Sleep timer", { sleepOpen = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(15, 30, 45, 60, 90).forEach { m -> SButton("Stop after $m minutes", { player.sleepTimer(m); sleepOpen = false }, Modifier.fillMaxWidth()) }
            SButton("Stop after this song", { player.setPauseAfterTrack(true); sleepOpen = false }, Modifier.fillMaxWidth())
            if (sleepLeft != null) SButton("Turn the timer off", { player.sleepTimer(null); sleepOpen = false }, Modifier.fillMaxWidth(), tone = Tone.Ghost)
        }
    }
}

@Composable
private fun LyricsPane(app: AppController, song: Song, positionMs: Long) {
    val c = Soiboi.colors
    val lyrics by produceState<app.soiboi.engine.lyrics.ParsedLyrics?>(null, song.id) { value = null; value = app.engine.lyrics.parsedFor(song) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val parsed = lyrics
    if (parsed == null) { Loading("Looking for lyrics"); return }
    val current = parsed.lineAt(positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (parsed.isSynced && current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    if (parsed.lines.size == 1 && parsed.lines[0].tokens.isEmpty() && parsed.lines[0].startMs == 0L && !parsed.isSynced && parsed.lines[0].text.startsWith("No lyrics")) {
        EmptyState("No lyrics for this song", "Soiboi checks the file, a .lrc beside it, then LRCLIB. Turn LRCLIB on in Settings to fetch synced lyrics.", glyph = Glyph.Lyrics)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(parsed.lines) { i, line ->
            val active = parsed.isSynced && i == current
            Column(Modifier.fillMaxWidth().then(if (parsed.isSynced) Modifier.press({ scope.launch { app.engine.player.seek(line.startMs) } }, "Jump to this line", shape = Soiboi.look.shape(8)) else Modifier).padding(vertical = 2.dp)) {
                Txt(line.text, style = if (active) Soiboi.type.headline else Soiboi.type.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600), color = if (active) c.ink else c.muted)
                line.translates.forEach { Txt(it, style = Soiboi.type.caption, color = c.soft) }
            }
        }
    }
}

@Composable
private fun EqualizerDialog(app: AppController, onDismiss: () -> Unit) {
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
