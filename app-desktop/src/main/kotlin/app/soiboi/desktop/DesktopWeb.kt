package app.soiboi.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import app.soiboi.engine.core.EngineLog
import app.soiboi.engine.sources.BROWSER_USER_AGENT
import app.soiboi.ui.web.WebCookie
import app.soiboi.ui.web.WebDownload
import app.soiboi.ui.web.WebPage
import app.soiboi.ui.web.WebPaneFactory
import app.soiboi.ui.web.WebState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.EnumProgress
import me.friwi.jcefmaven.IProgressHandler
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefCookieVisitor
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDownloadHandler
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * An embedded Chromium (JCEF) for sign-ins and guided downloads. The browser itself is about 100 MB, so it
 * is fetched once, on first use, into [installDir] rather than shipped inside the app.
 */
class DesktopWeb(private val installDir: File) : WebPaneFactory {
    override val available = true

    private var app: CefApp? = null

    private suspend fun ensureApp(state: WebState): CefApp {
        app?.let { return it }
        return withContext(Dispatchers.IO) {
            val builder = CefAppBuilder()
            builder.setInstallDir(installDir)
            // The window is an X11 child of the Java window; on a Wayland session Chromium would pick Wayland and never attach.
            builder.addJcefArgs("--ozone-platform=x11", "--no-sandbox")
            builder.cefSettings.cache_path = File(installDir, "profile").path
            builder.cefSettings.windowless_rendering_enabled = false
            builder.setProgressHandler(IProgressHandler { stage, percent ->
                state.preparing = when (stage) {
                    EnumProgress.LOCATING -> "Looking for the browser"
                    EnumProgress.DOWNLOADING -> "Downloading the browser, once (${if (percent >= 0) "${percent.toInt()}%" else "starting"})"
                    EnumProgress.EXTRACTING -> "Unpacking the browser"
                    EnumProgress.INSTALL -> "Installing the browser"
                    EnumProgress.INITIALIZING -> "Starting the browser"
                    EnumProgress.INITIALIZED -> null
                    else -> state.preparing
                }
            })
            builder.build().also { app = it }
        }
    }

    @Composable
    override fun Pane(startUrl: String, state: WebState, onDownload: (WebDownload) -> Unit, modifier: Modifier) {
        val download by rememberUpdatedState(onDownload)
        var holder by remember { mutableStateOf<Holder?>(null) }

        LaunchedEffect(Unit) {
            try {
                state.preparing = "Starting the browser"
                val cef = ensureApp(state)
                holder = withContext(Dispatchers.Main) { Holder(cef, startUrl, state) { d -> download(d) } }
                state.preparing = null
            } catch (e: Throwable) {
                EngineLog.output("jcef: $e")
                state.preparing = null
                state.failure = e.message ?: e.toString()
            }
        }
        DisposableEffect(Unit) { onDispose { holder?.close(); state.page = null } }

        holder?.let { h -> SwingPanel(modifier = modifier, factory = { h.browser.uiComponent as java.awt.Component }) }
    }

    private class Holder(cef: CefApp, startUrl: String, state: WebState, onDownload: (WebDownload) -> Unit) {
        val client: CefClient = cef.createClient()
        private val router: CefMessageRouter = CefMessageRouter.create()
        private val pending = HashMap<String, CompletableDeferred<String>>()
        private val ids = AtomicInteger()

        init {
            router.addHandler(object : CefMessageRouterHandlerAdapter() {
                override fun onQuery(browser: CefBrowser, frame: CefFrame, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean {
                    val sep = request.indexOf(':')
                    if (sep < 0) return false
                    synchronized(pending) { pending.remove(request.substring(0, sep)) }?.complete(request.substring(sep + 1))
                    callback.success("")
                    return true
                }
            }, true)
            client.addMessageRouter(router)
            client.addDownloadHandler(object : CefDownloadHandler {
                // Not calling the callback cancels the browser's own download; Soiboi fetches the file itself.
                override fun onBeforeDownload(browser: CefBrowser, item: CefDownloadItem, suggestedName: String, callback: CefBeforeDownloadCallback): Boolean {
                    onDownload(WebDownload(item.url, suggestedName.ifBlank { item.suggestedFileName.orEmpty() }, BROWSER_USER_AGENT, cookieHeader(item.url)))
                    return true
                }
                override fun onDownloadUpdated(browser: CefBrowser, item: CefDownloadItem, callback: CefDownloadItemCallback) { callback.cancel() }
            })
        }

        val browser: CefBrowser = client.createBrowser(startUrl, false, false)

        init {
            state.page = object : WebPage {
                override val url: String get() = browser.url.orEmpty()
                override suspend fun cookies(): List<WebCookie> = allCookies()
                    .filter { cookie -> state.cookieDomains.isEmpty() || state.cookieDomains.any { domain -> cookie.domain.trimStart('.').endsWith(domain.trimStart('.')) } }
                override suspend fun text(): String {
                    val id = "q${ids.incrementAndGet()}"
                    val answer = CompletableDeferred<String>()
                    synchronized(pending) { pending[id] = answer }
                    browser.executeJavaScript(
                        "(function(){var o=[];document.querySelectorAll('input,textarea').forEach(function(e){o.push(e.value)});" +
                            "o.push(document.body?document.body.innerText:'');" +
                            "window.cefQuery({request:'$id:'+o.join('\\n').slice(0,200000),onSuccess:function(){},onFailure:function(){}})})()",
                        browser.url, 0,
                    )
                    return withTimeoutOrNull(2000) { answer.await() }.also { synchronized(pending) { pending.remove(id) } }.orEmpty()
                }
            }
        }

        /** The cookies a download needs, for the file's own host. Runs on the browser's thread, so it waits. */
        private fun cookieHeader(url: String): String {
            val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
            return kotlinx.coroutines.runBlocking { allCookies() }
                .filter { host.endsWith(it.domain.trimStart('.')) && it.domain.isNotEmpty() }
                .joinToString("; ") { "${it.name}=${it.value}" }
        }

        private suspend fun allCookies(): List<WebCookie> = withTimeoutOrNull(2500) {
            suspendCancellableCoroutine { cont ->
                val seen = ArrayList<WebCookie>()
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                val any = CefCookieManager.getGlobalManager().visitAllCookies(object : CefCookieVisitor {
                    override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                        seen += WebCookie(cookie.domain.orEmpty(), cookie.name, cookie.value, cookie.path.orEmpty(), cookie.secure,
                            if (cookie.hasExpires && cookie.expires != null) cookie.expires.time / 1000 else 0, cookie.httponly)
                        if (count == total - 1 && finished.compareAndSet(false, true)) cont.resume(seen.toList())
                        return true
                    }
                })
                if (!any && finished.compareAndSet(false, true)) cont.resume(emptyList())
            }
        } ?: emptyList()

        fun close() {
            try { browser.close(true); client.dispose() } catch (e: Exception) { EngineLog.output("jcef close: $e") }
        }
    }

}
