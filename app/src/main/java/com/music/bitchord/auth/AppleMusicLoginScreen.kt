package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val LOGIN_URL = "https://music.apple.com/us/browse"

private val APPLE_COOKIE_URLS = listOf(
    "https://music.apple.com",
    "https://apple.com",
    "https://idmsa.apple.com",
    "https://appleid.apple.com",
)

private val LOGIN_HOST_SUFFIXES = listOf(
    "apple.com", "appleid.apple.com", "idmsa.apple.com", "music.apple.com", "mzstatic.com",
)

private fun hostMatches(uri: Uri?, suffixes: List<String>): Boolean {
    if (uri?.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return suffixes.any { host == it || host.endsWith(".$it") }
}

private fun isLoginHost(uri: Uri?) = hostMatches(uri, LOGIN_HOST_SUFFIXES)

/**
 * Signs the login WebView out of Apple Music.
 */
fun clearAppleWebSession() {
    val cookies = CookieManager.getInstance()
    for (url in APPLE_COOKIE_URLS) {
        val names = cookies.getCookie(url)?.split(";")
            ?.map { it.substringBefore("=").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        for (name in names) {
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            cookies.setCookie(url, expired)
            cookies.setCookie(url, "$expired; Domain=.apple.com")
        }
    }
    cookies.flush()
}

/**
 * In-app Apple Music sign-in. Lets music.apple.com handle the login,
 * capturing the `media-user-token` once set.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppleMusicLoginScreen(
    onConnected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = browserUserAgent(settings.userAgentString)
                setBackgroundColor(0xFF121212.toInt())
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                var sent = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !isLoginHost(request.url)

                    override fun onPageFinished(view: WebView, url: String?) {
                        canGoBack = view.canGoBack()
                        if (sent) return
                        val raw = CookieManager.getInstance().getCookie("https://music.apple.com") ?: return
                        if (raw.contains("media-user-token=")) {
                            val token = raw.split(";")
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("media-user-token=") }
                                ?.substringAfter("=")
                                ?.takeIf { it.isNotBlank() }
                            if (token != null) {
                                sent = true
                                view.visibility = View.GONE
                                onConnected("media-user-token=$token")
                            }
                        }
                    }
                }
                webView = this
                loadUrl(LOGIN_URL)
            }
        },
        onRelease = { it.destroy() },
    )

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }
}
