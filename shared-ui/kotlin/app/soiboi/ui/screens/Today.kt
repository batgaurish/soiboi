package app.soiboi.ui.screens


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.soiboi.ui.AppController
import app.soiboi.ui.HomeTab
import app.soiboi.ui.Route
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import java.time.LocalTime

private fun greeting(): String = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Still up" }

@Composable
fun TodayScreen(app: AppController) {
    val settings by app.engine.settings.state.collectAsState()
    val tab = app.homeTab
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            SButton("Offline library", { app.homeTab = HomeTab.Offline }, compact = true, tone = if (tab == HomeTab.Offline) Tone.Primary else Tone.Ghost, glyph = Glyph.Library)
            SButton("Streaming home", { app.homeTab = HomeTab.Streaming }, compact = true, tone = if (tab == HomeTab.Streaming) Tone.Primary else Tone.Ghost, glyph = Glyph.Wave)
        }
        if (tab == HomeTab.Offline) OfflineHomeScreen(app) else StreamingHomeScreen(app, settings.homepageProvider)
    }
}

@Composable
private fun StreamingHomeScreen(app: AppController, providerId: String) {
    if (providerId == "apple") {
        AppleStreamingHome(app)
        return
    }
    val c = Soiboi.colors
    val addons by app.engine.addons.installed.collectAsState()
    val selected = addons.firstOrNull { it.id == providerId && it.enabled }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt("Streaming home", style = Soiboi.type.display, maxLines = 2)
                if (selected == null || providerId == "offline") {
                    Banner("Choose a streaming provider", "Pick YouTube Music, Spotify, SoundCloud, or another enabled provider in Settings.", Glyph.Wave, action = { SButton("Open settings", { app.go(Route.Settings) }, compact = true) })
                } else {
                    Txt("Home from ${selected.manifest.name}", style = Soiboi.type.body, color = c.soft)
                }
            }
        }
        if (selected != null) addonShelves(app, listOf(selected))
    }
}
