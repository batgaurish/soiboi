package app.soiboi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.soiboi.engine.download.DownloadJobState
import app.soiboi.ui.components.Chip
import app.soiboi.ui.components.Hairline
import app.soiboi.ui.components.ProgressBar
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.press
import app.soiboi.ui.screens.DiscoverScreen
import app.soiboi.ui.screens.DownloadsScreen
import app.soiboi.ui.screens.ImportScreen
import app.soiboi.ui.screens.LibraryScreen
import app.soiboi.ui.screens.NowPlayingScreen
import app.soiboi.ui.screens.PlayerBar
import app.soiboi.ui.screens.QueuePanel
import app.soiboi.ui.screens.SearchScreen
import app.soiboi.ui.screens.SettingsScreen
import app.soiboi.ui.screens.SongMenuDialog
import app.soiboi.ui.screens.SourcesScreen
import app.soiboi.ui.screens.TodayScreen
import app.soiboi.ui.screens.detail.AlbumScreen
import app.soiboi.ui.screens.detail.ArtistScreen
import app.soiboi.ui.screens.detail.MoodScreen
import app.soiboi.ui.screens.detail.PlaylistScreen
import app.soiboi.ui.screens.detail.SmartScreen
import app.soiboi.ui.screens.AskAiDialog
import app.soiboi.ui.screens.SmartEditorDialog
import app.soiboi.ui.screens.FirstRun
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.delay

private val WIDE = 900.dp

/**
 * The frame around every screen. Wide windows get a left rail, the content, an optional queue column and a full-width
 * player bar; phones get the content, a mini player and a bottom tab bar. The route decides what the content is.
 */
@Composable
fun SoiboiShell(app: AppController) {
    val c = Soiboi.colors
    val songs by app.engine.library.songs.collectAsState()
    val toast by app.message.collectAsState()
    LaunchedEffect(toast) { if (toast != null) { delay(3200); app.clearToast() } }

    Box(
        Modifier.fillMaxSize().background(c.paper).onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) false else when {
                e.key == Key.Escape && app.back() -> true
                e.isCtrlPressed && e.key == Key.K -> { app.go(Route.Search); true }
                else -> false
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            val wide = maxWidth >= WIDE
            if (wide) WideLayout(app) else NarrowLayout(app)
        }
        AnimatedVisibility(app.nowPlayingOpen, enter = slideInVertically(tween(if (Soiboi.reduceMotion) 0 else 260)) { it } + fadeIn(), exit = slideOutVertically(tween(if (Soiboi.reduceMotion) 0 else 200)) { it } + fadeOut()) {
            Box(Modifier.fillMaxSize().background(c.paper).windowInsetsPadding(WindowInsets.systemBars)) { NowPlayingScreen(app) }
        }
        ToastHost(toast, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 110.dp))
        app.songMenu?.let { SongMenuDialog(app, it) }
        if (app.askAiOpen) AskAiDialog(app)
        app.smartEditor?.let { SmartEditorDialog(app, it) }
    }
}

@Composable
private fun ToastHost(text: String?, modifier: Modifier) {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(12)
    AnimatedVisibility(text != null, modifier, enter = fadeIn(tween(120)) + slideInVertically { it / 2 }, exit = fadeOut(tween(120))) {
        Row(Modifier.background(c.ink, shape).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(text ?: "", style = Soiboi.type.bodyStrong, color = c.paper)
        }
    }
}

@Composable
private fun WideLayout(app: AppController) {
    val c = Soiboi.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Rail(app, Modifier.width(224.dp).fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.rule))
            Box(Modifier.weight(1f).fillMaxHeight()) { Content(app) }
            if (app.queueOpen) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.rule))
                QueuePanel(app, Modifier.width(320.dp).fillMaxHeight())
            }
        }
        Hairline()
        PlayerBar(app, wide = true)
    }
}

@Composable
private fun NarrowLayout(app: AppController) {
    val c = Soiboi.colors
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { Content(app) }
        PlayerBar(app, wide = false)
        Hairline()
        TabBar(app)
    }
}

@Composable
private fun Content(app: AppController) {
    val songs by app.engine.library.songs.collectAsState()
    val folders by produceFolders(app)
    if (songs.isEmpty() && app.route is Route.Today && app.engine.settings.value.setupWizardDone.not() && !app.engine.scanning.collectAsState().value) {
        FirstRun(app); return
    }
    when (val r = app.route) {
        Route.Today -> TodayScreen(app)
        is Route.Library -> LibraryScreen(app, r.tab)
        is Route.Album -> AlbumScreen(app, r.name)
        is Route.Artist -> ArtistScreen(app, r.name)
        is Route.Playlist -> PlaylistScreen(app, r.name)
        is Route.Smart -> SmartScreen(app, r.name)
        is Route.Mood -> MoodScreen(app, r.name)
        Route.Search -> SearchScreen(app)
        Route.Discover -> DiscoverScreen(app)
        is Route.Import -> ImportScreen(app, r.link)
        Route.Downloads -> DownloadsScreen(app)
        Route.Sources -> SourcesScreen(app)
        Route.Settings -> SettingsScreen(app)
    }
}

@Composable
private fun produceFolders(app: AppController) = androidx.compose.runtime.produceState(emptyList<String>()) { value = app.engine.scanner.folders() }

private val navGlyphs = mapOf(
    Place.Today to Glyph.Today, Place.Library to Glyph.Library, Place.Discover to Glyph.Compass,
    Place.Downloads to Glyph.Download, Place.Sources to Glyph.Plug, Place.Settings to Glyph.Gear,
)

@Composable
private fun Rail(app: AppController, modifier: Modifier) {
    val c = Soiboi.colors
    val active = app.route.place()
    val job by app.engine.downloads.active.collectAsState()
    val tick by app.engine.downloads.tick.collectAsState()
    Column(modifier.background(c.paper).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Txt("Soiboi", Modifier.padding(start = 10.dp, top = 8.dp, bottom = Space.l), style = Soiboi.type.headline, maxLines = 1)
        Row(Modifier.fillMaxWidth().press({ app.go(Route.Search) }, "Search, Control K", shape = Soiboi.look.shape(10)).background(c.sunk, Soiboi.look.shape(10)).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Search, size = 18.dp, tint = c.soft); Txt("Search", Modifier.weight(1f), style = Soiboi.type.body, color = c.soft); Txt("Ctrl K", style = Soiboi.type.micro, color = c.muted)
        }
        Spacer(Modifier.height(Space.s))
        Place.entries.forEach { place ->
            val selected = place == active
            Row(
                Modifier.fillMaxWidth().press({ app.goPlace(place) }, place.label, Role.Tab, shape = Soiboi.look.shape(10)).background(if (selected) c.sunk else Color.Transparent, Soiboi.look.shape(10)).padding(horizontal = 12.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(navGlyphs.getValue(place), size = 20.dp, tint = if (selected) c.accent else c.soft)
                Txt(place.label, Modifier.weight(1f), style = if (selected) Soiboi.type.bodyStrong else Soiboi.type.body, color = if (selected) c.ink else c.soft)
            }
        }
        Spacer(Modifier.weight(1f))
        job?.let { j ->
            Column(Modifier.fillMaxWidth().press({ app.go(Route.Downloads) }, "Downloading ${j.label}, ${j.progress} percent", shape = Soiboi.look.shape(12)).background(c.raised, Soiboi.look.shape(12)).border(1.5.dp, c.rule, Soiboi.look.shape(12)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt("Downloading", style = Soiboi.type.micro, color = c.muted)
                Txt(j.label, style = Soiboi.type.bodyStrong, maxLines = 1)
                ProgressBar(j.progress / 100f + tick * 0f)
            }
        }
    }
}

@Composable
private fun TabBar(app: AppController) {
    val c = Soiboi.colors
    val active = app.route.place()
    Row(Modifier.fillMaxWidth().background(c.paper).windowInsetsPadding(WindowInsets.navigationBars).padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
        listOf(Place.Today, Place.Library, Place.Discover, Place.Downloads, Place.Settings).forEach { place ->
            val selected = place == active
            Column(Modifier.weight(1f).press({ app.goPlace(place) }, place.label, Role.Tab, shape = Soiboi.look.shape(10)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                GlyphIcon(navGlyphs.getValue(place), size = 22.dp, tint = if (selected) c.accent else c.soft)
                Txt(place.label, style = Soiboi.type.micro, color = if (selected) c.ink else c.soft, maxLines = 1)
            }
        }
    }
}
