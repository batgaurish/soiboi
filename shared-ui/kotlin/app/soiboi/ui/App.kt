package app.soiboi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.soiboi.engine.Engine
import app.soiboi.ui.theme.AppFonts
import app.soiboi.ui.theme.SoiboiTheme
import kotlinx.coroutines.CoroutineScope

/** The whole app: the theme from the saved settings, around the shell. Both platform apps call this and nothing else. */
@Composable
fun SoiboiApp(engine: Engine, platform: PlatformUi, fonts: AppFonts, scope: CoroutineScope, onReady: (AppController) -> Unit = {}) {
    val settings by engine.settings.state.collectAsState()
    val app = remember { AppController(engine, scope, platform) }
    LaunchedEffect(Unit) { engine.start() }
    LaunchedEffect(app) { onReady(app) }
    CompositionLocalProvider(LocalEngine provides engine, LocalPlatform provides platform) {
        SoiboiTheme(settings.flavour, settings.theme, settings.reduceMotion, fonts) { SoiboiShell(app) }
    }
}
