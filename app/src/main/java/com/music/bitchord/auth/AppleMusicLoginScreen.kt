package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.net.Uri
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val LOGIN_URL = "https://music.apple.com/us/browse"

/** Every Apple domain whose cookies are needed by the pipeline and catalog API. */
private val APPLE_CAPTURE_URLS = listOf(
    "https://music.apple.com",
    "https://buy.itunes.apple.com",
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
 * Cookie names that the pipeline's apple_auth.store_cookie_header() needs for the iTunes store
 * ledger. gamdl's webPlayback otherwise returns error 3077 ("item not found for fuse").
 */
private val STORE_COOKIE_NAMES = setOf("X-Dsid", "dsid", "mz_at0", "mz_at_ssl", "itspod", "itxt", "hsaccnt")

/**
 * Assign a Netscape domain for a cookie whose source domain metadata is unavailable.
 * gamdl requires media-user-token on exactly .music.apple.com; store cookies on .apple.com.
 */
private fun netscapeDomainFor(name: String, sourceUrl: String): String = when {
    name.equals("media-user-token", ignoreCase = true) -> ".music.apple.com"
    name in STORE_COOKIE_NAMES || name.startsWith("mz_at") || name.startsWith("des-") -> ".apple.com"
    sourceUrl.contains("music.apple.com") -> ".music.apple.com"
    else -> ".apple.com"
}

/**
 * Builds a Netscape/Mozilla cookie jar string from name-value pairs.
 *
 * android.webkit.CookieManager.getCookie() returns only "name=value" pairs — no domain, path,
 * expires, or httpOnly metadata. We assign domains from the cookie name and source URL using
 * the same rules as apple_auth.py's store_cookie_header(). Expires is 0 (session cookie);
 * gamdl loads the jar with ignore_discard=True and ignore_expires=True, so this is fine.
 */
private fun buildNetscapeJar(pairs: List<Triple<String, String, String>>): String {
    val header = "# Netscape HTTP Cookie File\n# Written by BitChord. Do not edit.\n"
    val lines = pairs.joinToString("\n") { (name, value, sourceUrl) ->
        val domain = netscapeDomainFor(name, sourceUrl)
        listOf(domain, "TRUE", "/", "TRUE", "0", name, value).joinToString("\t")
    }
    return header + lines + "\n"
}

/**
 * Clears all Apple WebView cookies from the shared process cookie jar.
 *
 * Called after the session is handed off whether saving to secure storage succeeded or failed.
 * Prevents the persistent WebView jar from being re-read as a credential fallback.
 */
fun clearAppleWebSession(onComplete: () -> Unit = {}) {
    val cookies = CookieManager.getInstance()
    val expirations = APPLE_CAPTURE_URLS.flatMap { url ->
        val names = cookies.getCookie(url)?.split(";")
            ?.map { it.substringBefore("=").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        names.flatMap { name ->
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            listOf(url to expired, url to "$expired; Domain=.apple.com")
        }
    }
    if (expirations.isEmpty()) {
        cookies.flush()
        onComplete()
        return
    }

    val pending = AtomicInteger(expirations.size)
    expirations.forEach { (url, expired) ->
        cookies.setCookie(url, expired) {
            if (pending.decrementAndGet() == 0) {
                cookies.flush()
                onComplete()
            }
        }
    }
}

/**
 * In-app Apple Music sign-in.
 *
 * Lets music.apple.com handle the sign-in (Apple ID, 2FA, passkeys) — BitChord never sees
 * credentials. Once media-user-token appears, ALL Apple cookies from every relevant domain are
 * collected and converted into:
 *
 *  - a Netscape/Mozilla cookie jar string (for archive adapters → apple_auth.py)
 *  - a Cookie header string (for catalog API callers → AppleHomeCatalog)
 *
 * [onConnected] is called with both. The WebView cookies are cleared immediately afterwards
 * regardless of whether saving to secure storage succeeds or fails, so the persistent WebView
 * jar cannot be re-read as a fallback credential.
 *
 * The composable does not render a header or guided steps — those are the caller's
 * responsibility so the layout stays consistent with the rest of the sign-in sheets.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppleMusicLoginScreen(
    /** Called once with (netscapeJar, cookieHeader) when the session is ready. */
    onConnected: (netscapeJar: String, cookieHeader: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    val sessionActive = remember { AtomicBoolean(false) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            sessionActive.set(true)
            var captureGate: AppleSessionCaptureGate? = null
            val loginView = WebView(context).apply {
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

                        // Wait until media-user-token is present before harvesting the session.
                        val musicCookies = CookieManager.getInstance().getCookie("https://music.apple.com")
                        val sessionToken = captureGate?.newSessionToken(musicCookies) ?: return

                        // Collect cookies from all Apple domains. CookieManager.getCookie() is
                        // host-scoped and returns only name=value pairs; we assign Netscape
                        // domain fields from name and source URL.
                        val manager = CookieManager.getInstance()
                        val pairs = APPLE_CAPTURE_URLS.flatMap { captureUrl ->
                            manager.getCookie(captureUrl).orEmpty().split(";").mapNotNull { part ->
                                val eq = part.indexOf('=')
                                if (eq <= 0) return@mapNotNull null
                                val name = part.substring(0, eq).trim()
                                val value = part.substring(eq + 1).trim()
                                if (name.isEmpty() || value.isEmpty()) null
                                else Triple(name, value, captureUrl)
                            }
                        }.distinctBy { it.first }  // keep first occurrence if a name appears in multiple jars

                        // Require media-user-token in the collected set before handing off.
                        if (pairs.none {
                                it.first.equals("media-user-token", ignoreCase = true) && it.second == sessionToken
                            }
                        ) return

                        sent = true
                        val netscapeJar = buildNetscapeJar(pairs)
                        val cookieHeader = pairs.joinToString("; ") { "${it.first}=${it.second}" }

                        // Hand off then clear, regardless of what the caller does with the session.
                        try {
                            onConnected(netscapeJar, cookieHeader)
                        } finally {
                            clearAppleWebSession()
                        }
                    }
                }
                webView = this
            }
            clearAppleWebSession {
                if (sessionActive.get()) {
                    // Record only cookies that remain after expiration callbacks have completed.
                    // A token from a previous WebView session must not close this sign-in window.
                    captureGate = AppleSessionCaptureGate(
                        CookieManager.getInstance().getCookie("https://music.apple.com"),
                    )
                    loginView.loadUrl(LOGIN_URL)
                }
            }
            loginView
        },
        onRelease = {
            sessionActive.set(false)
            it.destroy()
        },
    )

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }
}
