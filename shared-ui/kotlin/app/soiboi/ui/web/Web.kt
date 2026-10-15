package app.soiboi.ui.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import app.soiboi.ui.AppController
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.theme.Soiboi
import app.soiboi.ui.components.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A cookie as the in-app browser reports it. Android reports only name and value; the rest stay default. */
class WebCookie(
    val domain: String, val name: String, val value: String,
    val path: String = "/", val secure: Boolean = true, val expiresEpoch: Long = 0, val httpOnly: Boolean = false,
)

/** A file the page tried to download. The pane cancels the browser's own download; we fetch it ourselves. */
class WebDownload(val url: String, val fileName: String, val userAgent: String, val cookieHeader: String)

/** What the code watching a page can ask of it. Implemented by each platform's pane. */
interface WebPage {
    val url: String
    suspend fun cookies(): List<WebCookie>
    /** The visible text and field values of the page, for spotting something the page shows once (a new API key). */
    suspend fun text(): String
}

/** What a pane tells the screen about itself. */
class WebState {
    var page by mutableStateOf<WebPage?>(null)
    /** Set while the browser itself is being prepared, so the screen can say why it is blank. */
    var preparing by mutableStateOf<String?>(null)
    var failure by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
}

/** One guided browsing task: where to go, what to say, and how to know it worked. */
class WebSession(
    val title: String,
    val steps: List<String>,
    val startUrl: String,
    /** Polled about every second once a page exists. A non-null answer finishes the session with that message. */
    val watch: suspend (WebPage, WebState) -> String? = { _, _ -> null },
    /** Offered each file the page tries to download. A non-null answer finishes the session. */
    val onDownload: suspend (WebDownload, WebState) -> String? = { _, _ -> null },
    val onFinished: (String) -> Unit = {},
)

/** The browser itself. Each platform draws its own: WebView on Android, an embedded Chromium on Linux. */
interface WebPaneFactory {
    val available: Boolean
    @Composable fun Pane(startUrl: String, state: WebState, onDownload: (WebDownload) -> Unit, modifier: Modifier)
}

@Composable
fun WebScreen(app: AppController, session: WebSession) {
    val c = Soiboi.colors
    val state = remember(session) { WebState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var done by remember(session) { mutableStateOf(false) }

    fun finish(message: String) {
        if (done) return
        done = true
        session.onFinished(message)
        app.toast(message)
        app.web = null
    }

    LaunchedEffect(state.page) {
        val page = state.page ?: return@LaunchedEffect
        while (!done) {
            val result = try { session.watch(page, state) } catch (e: Exception) { null }
            if (result != null) { finish(result); break }
            delay(1000)
        }
    }

    Column(Modifier.fillMaxSize().background(c.paper).windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            SButton("Close", { app.web = null }, tone = Tone.Secondary, compact = true, glyph = Glyph.Close)
            Txt(session.title, Modifier.weight(1f), style = Soiboi.type.bodyStrong, maxLines = 1)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.m).padding(bottom = Space.s), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            session.steps.forEachIndexed { i, s -> Txt("${i + 1}. $s", style = Soiboi.type.caption, color = c.soft) }
            (state.status)?.let { Txt(it, style = Soiboi.type.caption, color = c.ink) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            app.platform.web.Pane(
                session.startUrl, state,
                onDownload = { d -> scope.launch { session.onDownload(d, state)?.let { finish(it) } } },
                modifier = Modifier.fillMaxSize(),
            )
            val failure = state.failure
            val preparing = state.preparing
            if (failure != null || preparing != null) Column(Modifier.fillMaxSize().background(c.paper).padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                if (failure != null) {
                    Txt("The in-app browser did not start", style = Soiboi.type.bodyStrong)
                    Txt(failure, style = Soiboi.type.caption, color = c.soft)
                    SButton("Open in your browser instead", { app.platform.openUrl(session.startUrl); app.web = null }, compact = true, glyph = Glyph.Link)
                } else Txt(preparing ?: "", style = Soiboi.type.body, color = c.soft)
            }
        }
    }
}
