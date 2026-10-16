package app.soiboi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import app.soiboi.ui.web.WebScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.soiboi.engine.Engine
import app.soiboi.ui.theme.AppFonts
import app.soiboi.ui.theme.Soiboi
import app.soiboi.ui.theme.SoiboiTheme
import kotlinx.coroutines.CoroutineScope

/** The whole app: the theme from the saved settings, around the shell. Both platform apps call this and nothing else. */
@Composable
fun SoiboiApp(engine: Engine, platform: PlatformUi, fonts: AppFonts, scope: CoroutineScope, onReady: (AppController) -> Unit = {}) {
    val settings by engine.settings.state.collectAsState()
    val app = remember { AppController(engine, scope, platform) }
    LaunchedEffect(Unit) { engine.start() }
    LaunchedEffect(app) { onReady(app) }
    LaunchedEffect(Unit) {
        // Wait for the saved settings, then look once per launch.
        kotlinx.coroutines.delay(4000)
        val s = engine.settings.value
        if (s.checkForUpdates) engine.updater.check(quiet = true)?.let { if (it.version != s.skippedVersion) app.updateOpen = true }
    }
    CompositionLocalProvider(LocalEngine provides engine, LocalPlatform provides platform) {
        SoiboiTheme(settings.flavour, settings.theme, settings.reduceMotion, fonts) {
            val dark = Soiboi.colors.isDark
            LaunchedEffect(dark) { app.themeIsDark = dark; platform.setSystemBarIcons(lightIcons = dark) }
            Box {
                SoiboiShell(app)
                app.web?.let { session ->
                    // Swallows pointer events that reach the page behind; the browser and its own buttons sit above this.
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
                        WebScreen(app, session)
                    }
                }
            }
        }
    }
}
