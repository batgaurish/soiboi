package app.soiboi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch

/** The empty library's first screen: three honest ways in, with the one that needs nothing else first. */
@Composable
fun FirstRun(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val scanning by engine.scanning.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.xl), verticalArrangement = Arrangement.Center) {
        Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(Space.l)) {
            Txt("Soiboi", style = Soiboi.type.display.copy(fontSize = 64.sp, lineHeight = 64.sp))
            Txt("Plays the music you own, and downloads the music you do not yet. Nothing streams, and nothing leaves your device that you did not send.", style = Soiboi.type.title, color = c.soft)
            Spacer(Modifier.height(Space.s))
            SButton("Choose your music folder", { scope.launch { app.platform.pickFolder()?.let { engine.scanner.addFolder(it); engine.settings.update { copy(setupWizardDone = true) }; engine.rescan() } } }, tone = Tone.Primary, glyph = Glyph.Folder, loading = scanning, modifier = Modifier.fillMaxWidth())
            SButton("Connect a server", { engine.settings.update { copy(setupWizardDone = true) }; app.go(Route.Sources) }, glyph = Glyph.Plug, modifier = Modifier.fillMaxWidth())
            SButton("Paste a playlist link", { engine.settings.update { copy(setupWizardDone = true) }; app.go(Route.Discover) }, glyph = Glyph.Link, modifier = Modifier.fillMaxWidth())
            SButton("Look around first", { engine.settings.update { copy(setupWizardDone = true) } }, tone = Tone.Ghost, modifier = Modifier.fillMaxWidth())
        }
    }
}
