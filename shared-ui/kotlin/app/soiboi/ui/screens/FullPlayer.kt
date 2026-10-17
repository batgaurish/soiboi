package app.soiboi.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soiboi.engine.core.ArtworkStyle
import app.soiboi.engine.library.QualityInfo
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.player.PlayMode
import app.soiboi.ui.AppController
import app.soiboi.ui.SongMenu
import app.soiboi.ui.components.*
import app.soiboi.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class PlayerView { Art, Lyrics, Queue }

private val Mono = FontFamily.Monospace

/** A colour for the screen's wash, taken from the cover: its dominant hue, held dark enough that white text always reads. */
private fun coverTint(img: ImageBitmap): Color {
    val w = img.width; val h = img.height
    if (w == 0 || h == 0) return Color(0xFF1C1C1C)
    val px = IntArray(w * h)
    img.readPixels(px, 0, 0, w, h, 0, w)
    var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
    val step = max(1, min(w, h) / 40)
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val p = px[y * w + x]
            val pr = (p shr 16 and 0xFF) / 255.0; val pg = (p shr 8 and 0xFF) / 255.0; val pb = (p and 0xFF) / 255.0
            val mx = max(pr, max(pg, pb)); val mn = min(pr, min(pg, pb))
            val sat = if (mx == 0.0) 0.0 else (mx - mn) / mx
            // Vivid mid-tones say more about a cover than its black or white margins.
            val weight = (0.15 + sat) * (if (mx < 0.12 || mn > 0.92) 0.1 else 1.0)
            r += pr * weight; g += pg * weight; b += pb * weight; total += weight
            x += step
        }
        y += step
    }
    if (total == 0.0) return Color(0xFF1C1C1C)
    val ar = r / total; val ag = g / total; val ab = b / total
    val mx = max(ar, max(ag, ab)); val mn = min(ar, min(ag, ab))
    val delta = mx - mn
    val hue = when {
        delta == 0.0 -> 0.0
        mx == ar -> 60 * (((ag - ab) / delta) % 6)
        mx == ag -> 60 * ((ab - ar) / delta + 2)
        else -> 60 * ((ar - ag) / delta + 4)
    }.let { if (it < 0) it + 360 else it }
    val sat = if (mx == 0.0) 0.0 else delta / mx
    return Color.hsv(hue.toFloat(), (sat.coerceIn(0.18, 0.75)).toFloat(), 0.42f)
}

private fun Color.darker(by: Float) = Color(red * (1 - by), green * (1 - by), blue * (1 - by), alpha)

/** A spinning vinyl record with album art in the center. Spins when playing, stops when paused. */
@Composable
private fun VinylDisc(cover: ImageBitmap?, playing: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "vinyl-spin"
    )
    var lastAngle by remember { mutableStateOf(0f) }
    val displayAngle = if (playing) rotation else lastAngle
    LaunchedEffect(playing, rotation) { if (playing) lastAngle = rotation }

    BoxWithConstraints(modifier.padding(horizontal = Space.l), contentAlignment = Alignment.Center) {
        val discSize = min(maxWidth.value, maxHeight.value).dp
        val artSize = discSize * 0.55f
        val vinylColor = Color(0xFF1A1A1A)
        val grooveColor = Color(0xFF2A2A2A)

        Box(Modifier.size(discSize).graphicsLayer { rotationZ = displayAngle }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = size.width / 2
                val radius = size.width / 2
                drawCircle(vinylColor, radius)
                for (i in 1..12) {
                    val grooveRadius = radius * (0.32f + i * 0.05f)
                    drawCircle(grooveColor, grooveRadius, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
                }
                drawCircle(Color(0xFF333333), radius * 0.28f)
                drawCircle(Color(0xFF444444), radius * 0.08f)
            }
            if (cover != null) {
                Image(cover, null, Modifier.size(artSize).clip(CircleShape), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(artSize).background(Color(0x44FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
                    GlyphIcon(Glyph.Disc, size = artSize * 0.5f, tint = Color(0x66FFFFFF))
                }
            }
        }
    }
}

/** The full-screen player: the cover bleeds off the top, a wash of its colour runs down behind the controls, and lyrics or the queue take the cover's place when asked. */
@Composable
fun NowPlayingScreen(app: AppController) {
    val engine = app.engine; val player = engine.player
    val queue by player.queue.collectAsState()
    val song = queue.current
    if (song == null) {
        CompositionLocalProvider(LocalSoiboiColors provides playerColors()) {
            Column(Modifier.fillMaxSize().background(Color(0xFF121212)).statusBarsPadding().padding(Space.l)) {
                SIconButton(Glyph.ChevronDown, "Close the player", { app.nowPlayingOpen = false })
                EmptyState("Nothing is playing", "Choose a song from your library and it opens here.")
            }
        }
        return
    }
    val cover = rememberCoverBitmap(song)
    val tint by produceState(Color(0xFF1C1C1C), cover) { value = cover?.let { withContext(Dispatchers.Default) { coverTint(it) } } ?: Color(0xFF1C1C1C) }
    val wash by animateColorAsState(tint, tween(if (Soiboi.reduceMotion) 0 else 700), label = "wash")
    var view by remember { mutableStateOf(PlayerView.Art) }
    var menuOpen by remember { mutableStateOf(false) }
    var eqOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }

    val platform = app.platform
    DisposableEffect(Unit) { platform.setSystemBarIcons(lightIcons = true); onDispose { platform.setSystemBarIcons(lightIcons = !app.themeIsDark) } }
    CompositionLocalProvider(LocalSoiboiColors provides playerColors()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(wash, wash.darker(0.45f), Color(0xFF0A0A0A))))) {
            if (maxWidth >= 860.dp) WidePlayer(app, song, cover, view, { view = it }, { menuOpen = true })
            else NarrowPlayer(app, song, cover, wash, maxWidth, maxHeight, view, { view = it }, { menuOpen = true })
        }
    }
    if (menuOpen) PlayerMenu(app, song, { menuOpen = false }, { eqOpen = true }, { sleepOpen = true })
    if (eqOpen) EqualizerDialog(app) { eqOpen = false }
    if (sleepOpen) SleepDialog(app) { sleepOpen = false }
}

@Composable
private fun NarrowPlayer(app: AppController, song: Song, cover: ImageBitmap?, wash: Color, width: Dp, height: Dp, view: PlayerView, setView: (PlayerView) -> Unit, openMenu: () -> Unit) {
    val state by app.engine.player.state.collectAsState()
    val queue by app.engine.player.queue.collectAsState()
    val settings by app.engine.settings.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        if (view == PlayerView.Art) {
            when (settings.artworkStyle) {
                ArtworkStyle.Vinyl -> {
                    PlayerTopBar(app, queue.songs.size, song.displayAlbum, openMenu, Modifier.statusBarsPadding())
                    Spacer(Modifier.height(Space.m))
                    VinylDisc(cover, state.playing, Modifier.fillMaxWidth().weight(1f))
                    Spacer(Modifier.height(Space.m))
                }
                ArtworkStyle.Fit -> {
                    val artHeight = min(width.value * 1.15f, height.value * 0.58f).dp
                    Box(Modifier.fillMaxWidth().height(artHeight)) {
                        if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alignment = Alignment.Center)
                        else Box(Modifier.fillMaxSize().background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Disc, size = 96.dp, tint = Color(0x66FFFFFF)) }
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.28f to Color.Transparent, 0.7f to Color.Transparent, 1f to wash)))
                        PlayerTopBar(app, queue.songs.size, song.displayAlbum, openMenu, Modifier.statusBarsPadding())
                    }
                    Spacer(Modifier.height(Space.m))
                }
            }
            TitleBlock(app, song, live = isLive(song), streamTitle = state.streamTitle, modifier = Modifier.padding(horizontal = Space.l))
        } else {
            PlayerTopBar(app, queue.songs.size, song.displayAlbum, openMenu, Modifier.statusBarsPadding())
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Cover(song, 56.dp, shape = RoundedCornerShape(8.dp))
                Column(Modifier.weight(1f)) {
                    Txt(song.displayTitle, style = Soiboi.type.bodyStrong, color = Color.White, maxLines = 1)
                    Txt(song.displayArtist, style = Soiboi.type.caption, color = Color(0xB3FFFFFF), maxLines = 1)
                }
                Box(Modifier.size(40.dp).press({ setView(PlayerView.Art) }, "Back to the cover", shape = CircleShape).background(Color(0x33FFFFFF), CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Close, size = 18.dp, tint = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s)) {
                if (view == PlayerView.Lyrics) LyricsView(app, song, state.positionMs) else QueuePanel(app, Modifier.fillMaxSize(), embedded = true)
            }
        }
        if (view == PlayerView.Art) Spacer(Modifier.weight(1f))
        Transport(app, song, Modifier.padding(horizontal = Space.l))
        Spacer(Modifier.height(Space.m))
        if (view == PlayerView.Art) {
            ActionRow(app, song, view, setView, Modifier.padding(horizontal = Space.l).navigationBarsPadding().padding(bottom = Space.m))
        } else {
            ActionRow(app, song, view, setView, Modifier.padding(horizontal = Space.l).navigationBarsPadding().padding(bottom = Space.m))
        }
    }
}

@Composable
private fun WidePlayer(app: AppController, song: Song, cover: ImageBitmap?, view: PlayerView, setView: (PlayerView) -> Unit, openMenu: () -> Unit) {
    val state by app.engine.player.state.collectAsState()
    val queue by app.engine.player.queue.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = Space.xl)) {
        PlayerTopBar(app, queue.songs.size, song.displayAlbum, openMenu, Modifier.padding(top = Space.m))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                BoxWithConstraints(Modifier.weight(1f, fill = true).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val side = min(maxWidth.value, maxHeight.value).dp
                    Box(Modifier.size(side).clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
                        if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else GlyphIcon(Glyph.Disc, size = 96.dp, tint = Color(0x66FFFFFF))
                    }
                }
                Spacer(Modifier.height(Space.m))
                TitleBlock(app, song, live = isLive(song), streamTitle = state.streamTitle, modifier = Modifier.widthIn(max = 560.dp))
                Spacer(Modifier.height(Space.s))
                Transport(app, song, Modifier.widthIn(max = 560.dp))
                Spacer(Modifier.height(Space.s))
                ActionRow(app, song, view, setView, Modifier.widthIn(max = 560.dp).padding(bottom = Space.m), includeQueue = false)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = Space.l)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(bottom = Space.m)) {
                    SideTab("Lyrics", Glyph.Lyrics, view != PlayerView.Queue) { setView(PlayerView.Lyrics) }
                    SideTab("Up next", Glyph.Queue, view == PlayerView.Queue) { setView(PlayerView.Queue) }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { if (view == PlayerView.Queue) QueuePanel(app, Modifier.fillMaxSize(), embedded = true) else LyricsView(app, song, state.positionMs) }
            }
        }
    }
}

@Composable
private fun SideTab(label: String, glyph: Glyph, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.press(onClick, label, role = Role.Tab, shape = CircleShape).background(if (selected) Color.White else Color(0x26FFFFFF), CircleShape).padding(horizontal = 16.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(glyph, size = 18.dp, tint = if (selected) Color.Black else Color.White); Txt(label, style = Soiboi.type.label, color = if (selected) Color.Black else Color.White)
    }
}

private fun isLive(song: Song) = song.origin == SongOrigin.Addon && song.album == "Live radio"

@Composable
private fun PlayerTopBar(app: AppController, count: Int, album: String, openMenu: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).press({ app.nowPlayingOpen = false }, "Close the player", shape = CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.ChevronDown, size = 26.dp, tint = Color.White) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("PLAYING FROM", style = Soiboi.type.micro.copy(letterSpacing = 1.6.sp), color = Color(0xB3FFFFFF), maxLines = 1)
            Txt(album, style = Soiboi.type.label, color = Color.White, maxLines = 1)
        }
        Box(Modifier.size(44.dp).press(openMenu, "More options", shape = CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.More, size = 24.dp, tint = Color.White) }
    }
}

@Composable
private fun TitleBlock(app: AppController, song: Song, live: Boolean, streamTitle: String?, modifier: Modifier = Modifier) {
    val favs by app.engine.playlists.playlists.collectAsState()
    val isFav = song.id in app.engine.playlists.favoriteIds
    val quality = QualityInfo.of(song)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(song.displayTitle, Modifier.fillMaxWidth(), style = Soiboi.type.headline.copy(fontSize = 26.sp, lineHeight = 30.sp, textAlign = TextAlign.Center), color = Color.White, maxLines = 2)
        Txt(if (live) (streamTitle ?: song.displayArtist) else song.displayArtist, Modifier.fillMaxWidth(), style = Soiboi.type.title.copy(fontWeight = FontWeight.W600, textAlign = TextAlign.Center), color = Color(0xCCFFFFFF), maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Txt(quality.label, Modifier.background(Color(0x33FFFFFF), CircleShape).padding(horizontal = 12.dp, vertical = 3.dp), style = Soiboi.type.micro, color = Color(0xE6FFFFFF), maxLines = 1)
            quality.detail?.let { Txt(it, style = Soiboi.type.caption, color = Color(0xB3FFFFFF), maxLines = 1) }
        }
        // `favs` is read so the heart repaints when the library of favourites changes.
        if (favs.isEmpty() && !isFav) Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun PlayerSlider(value: Float, onChange: (Float) -> Unit, onFinished: () -> Unit, label: String, valueText: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Soiboi.colors
    val v = value.coerceIn(0f, 1f)
    BoxWithConstraints(modifier.height(28.dp).semantics { contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(v, 0f..1f); stateDescription = valueText; if (enabled) setProgress { onChange(it.coerceIn(0f, 1f)); onFinished(); true } }, contentAlignment = Alignment.CenterStart) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        Box(Modifier.fillMaxSize().then(if (enabled) Modifier.pointerInput(Unit) { detectTapGestures { o -> onChange((o.x / wPx).coerceIn(0f, 1f)); onFinished() } }.pointerInput(Unit) { detectDragGestures(onDragEnd = onFinished) { ch, _ -> ch.consume(); onChange((ch.position.x / wPx).coerceIn(0f, 1f)) } } else Modifier), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x40FFFFFF), CircleShape))
            Box(Modifier.fillMaxWidth(v).height(4.dp).background(c.accent, CircleShape))
        }
    }
}

@Composable
private fun Transport(app: AppController, song: Song, modifier: Modifier = Modifier) {
    val player = app.engine.player
    val state by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    val live = isLive(song)
    var drag by remember { mutableStateOf<Float?>(null) }
    val fraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PlayerSlider(drag ?: fraction, { drag = it }, { drag?.let { f -> if (!live) scope.launch { player.seek((f * state.durationMs).toLong()) } }; drag = null }, "Seek", if (live) "Live radio" else "${formatTime(state.positionMs)} of ${formatTime(state.durationMs)}", enabled = !live)
        Row(Modifier.fillMaxWidth()) {
            Txt(formatTime(((drag ?: fraction) * state.durationMs).toLong().takeIf { drag != null } ?: state.positionMs), Modifier.weight(1f), style = Soiboi.type.caption.copy(fontFamily = Mono), color = Color(0xB3FFFFFF), maxLines = 1)
            Txt(if (live) "LIVE" else formatTime(state.durationMs), style = Soiboi.type.caption.copy(fontFamily = Mono), color = Color(0xB3FFFFFF), maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().padding(top = Space.s), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).press({ scope.launch { player.previous() } }, "Previous", shape = CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Previous, size = 34.dp, tint = Color.White) }
            Box(Modifier.size(80.dp).press({ player.togglePlay() }, if (state.playing) "Pause" else "Play", shape = CircleShape).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                GlyphIcon(if (state.playing) Glyph.Pause else Glyph.Play, size = 34.dp, tint = Color(0xFF111111))
            }
            Box(Modifier.size(64.dp).press({ scope.launch { player.next() } }, "Next", shape = CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Next, size = 34.dp, tint = Color.White) }
        }
    }
}

/** The five quiet controls along the bottom: lyrics, shuffle, like, repeat and the queue. Equalizer and the sleep timer live under the three dots. */
@Composable
private fun ActionRow(app: AppController, song: Song, view: PlayerView, setView: (PlayerView) -> Unit, modifier: Modifier = Modifier, includeQueue: Boolean = true) {
    val player = app.engine.player
    val mode by player.mode.collectAsState()
    val scope = rememberCoroutineScope()
    val isFav = song.id in app.engine.playlists.favoriteIds
    val c = Soiboi.colors
    @Composable fun Action(glyph: Glyph, label: String, active: Boolean = false, onClick: () -> Unit) =
        Box(Modifier.size(48.dp).press(onClick, label, shape = CircleShape), contentAlignment = Alignment.Center) { GlyphIcon(glyph, size = 24.dp, tint = if (active) c.accent else Color.White) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Action(Glyph.Lyrics, "Lyrics", view == PlayerView.Lyrics) { setView(if (view == PlayerView.Lyrics) PlayerView.Art else PlayerView.Lyrics) }
        Action(Glyph.Shuffle, "Shuffle", mode == PlayMode.Shuffle) { scope.launch { player.setMode(if (mode == PlayMode.Shuffle) PlayMode.Order else PlayMode.Shuffle) } }
        Action(if (isFav) Glyph.HeartFilled else Glyph.Heart, if (isFav) "Remove from favorites" else "Add to favorites", isFav) { app.toggleFavorite(song) }
        Action(if (mode == PlayMode.Repeat) Glyph.RepeatOne else Glyph.Repeat, "Repeat this song", mode == PlayMode.Repeat) { player.toggleRepeat() }
        if (includeQueue) Action(Glyph.Queue, "Up next", view == PlayerView.Queue) { setView(if (view == PlayerView.Queue) PlayerView.Art else PlayerView.Queue) }
    }
}

/** Synced lines centred and large, the current one bright and the rest fading with distance. Tapping a synced line seeks to it. */
@Composable
private fun LyricsView(app: AppController, song: Song, positionMs: Long) {
    val lyrics by produceState<app.soiboi.engine.lyrics.ParsedLyrics?>(null, song.id) { value = null; value = app.engine.lyrics.parsedFor(song) }
    val scope = rememberCoroutineScope()
    val parsed = lyrics
    if (parsed == null) { Loading("Looking for lyrics"); return }
    if (parsed.lines.size == 1 && parsed.lines[0].tokens.isEmpty() && parsed.lines[0].startMs == 0L && !parsed.isSynced && parsed.lines[0].text.startsWith("No lyrics")) {
        EmptyState("No lyrics for this song", "Soiboi checks the file, a .lrc beside it, then LRCLIB. Turn LRCLIB on in Settings to fetch synced lyrics.", glyph = Glyph.Lyrics)
        return
    }
    val current = parsed.lineAt(positionMs)
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (parsed.isSynced && current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = Space.xl), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexed(parsed.lines) { i, line ->
            val distance = if (parsed.isSynced) abs(i - current) else 0
            val alpha = if (!parsed.isSynced) 0.92f else when (distance) { 0 -> 1f; 1 -> 0.55f; 2 -> 0.4f; else -> 0.28f }
            Column(Modifier.fillMaxWidth().then(if (parsed.isSynced) Modifier.press({ scope.launch { app.engine.player.seek(line.startMs) } }, "Jump to this line", shape = RoundedCornerShape(8.dp)) else Modifier).alpha(alpha), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(line.text, Modifier.fillMaxWidth(), style = Soiboi.type.headline.copy(fontSize = 26.sp, lineHeight = 33.sp, textAlign = TextAlign.Center, fontWeight = FontWeight.W700), color = Color.White)
                line.translates.forEach { Txt(it, Modifier.fillMaxWidth(), style = Soiboi.type.caption.copy(textAlign = TextAlign.Center), color = Color(0xB3FFFFFF)) }
            }
        }
    }
}

@Composable
private fun PlayerMenu(app: AppController, song: Song, onDismiss: () -> Unit, openEq: () -> Unit, openSleep: () -> Unit) {
    val sleepLeft by app.engine.player.sleepRemaining.collectAsState()
    SDialog("Options", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SButton("Equalizer", { onDismiss(); openEq() }, Modifier.fillMaxWidth(), glyph = Glyph.Sliders)
            SButton(if (sleepLeft != null) "Sleep timer, ${formatTime(sleepLeft!! * 1000L)} left" else "Sleep timer", { onDismiss(); openSleep() }, Modifier.fillMaxWidth(), glyph = Glyph.Moon)
            SButton("Song options", { onDismiss(); app.songMenu = SongMenu(song) }, Modifier.fillMaxWidth(), glyph = Glyph.More)
        }
    }
}

@Composable
private fun SleepDialog(app: AppController, onDismiss: () -> Unit) {
    val player = app.engine.player
    val sleepLeft by player.sleepRemaining.collectAsState()
    SDialog("Sleep timer", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(15, 30, 45, 60, 90).forEach { m -> SButton("Stop after $m minutes", { player.sleepTimer(m); onDismiss() }, Modifier.fillMaxWidth()) }
            SButton("Stop after this song", { player.setPauseAfterTrack(true); onDismiss() }, Modifier.fillMaxWidth())
            if (sleepLeft != null) SButton("Turn the timer off", { player.sleepTimer(null); onDismiss() }, Modifier.fillMaxWidth(), tone = Tone.Ghost)
        }
    }
}
