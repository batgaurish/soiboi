package app.soiboi.android

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.soiboi.ui.web.WebCookie
import app.soiboi.ui.web.WebDownload
import app.soiboi.ui.web.WebPage
import app.soiboi.ui.web.WebPaneFactory
import app.soiboi.ui.web.WebState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The system WebView. Cookies come back as name=value pairs only, which is all the Apple session needs. */
object AndroidWeb : WebPaneFactory {
    override val available = true

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Pane(startUrl: String, state: WebState, onDownload: (WebDownload) -> Unit, modifier: Modifier) {
        val download by rememberUpdatedState(onDownload)
        var view: WebView? = null
        DisposableEffect(Unit) { onDispose { view?.let { it.stopLoading(); it.destroy() }; state.page = null } }
        AndroidView(modifier = modifier, factory = { context ->
            WebView(context).also { web ->
                view = web
                web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                // Sites serve a "not supported" page to a WebView that says so; present as the phone's browser.
                web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                web.webChromeClient = WebChromeClient()
                web.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { state.status = null }
                }
                web.setDownloadListener { url, userAgent, contentDisposition, mime, _ ->
                    download(WebDownload(url, URLUtil.guessFileName(url, contentDisposition, mime), userAgent.orEmpty(), CookieManager.getInstance().getCookie(url).orEmpty()))
                }
                state.page = object : WebPage {
                    override val url: String get() = web.url.orEmpty()
                    override suspend fun cookies(): List<WebCookie> {
                        val manager = CookieManager.getInstance()
                        return listOf("https://music.apple.com", "https://apple.com", "https://idmsa.apple.com", "https://buy.itunes.apple.com")
                            .flatMap { host -> manager.getCookie(host).orEmpty().split(';').mapNotNull { part ->
                                val i = part.indexOf('='); if (i <= 0) null else WebCookie("", part.substring(0, i).trim(), part.substring(i + 1).trim())
                            } }
                            .distinctBy { it.name }
                    }
                    override suspend fun text(): String = suspendCancellableCoroutine { cont ->
                        web.post {
                            web.evaluateJavascript(TEXT_SCRIPT) { cont.resume(it.orEmpty().trim('"').replace("\\n", "\n").replace("\\\"", "\"")) }
                        }
                    }
                }
                web.loadUrl(startUrl)
            }
        })
    }

    private const val TEXT_SCRIPT = "(function(){var o=[];document.querySelectorAll('input,textarea').forEach(function(e){o.push(e.value)});o.push(document.body?document.body.innerText:'');return o.join('\\\\n').slice(0,200000)})()"
}
