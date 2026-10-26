package com.music.bitchord.desktop

import java.net.CookieHandler
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.util.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Both credential representations produced by a successful Apple Music sign-in.
 *
 * [netscapeJar] is for archive adapters — written verbatim to a temp file and passed to
 * apple_auth.open_api(cookies_path). It contains media-user-token plus Apple store cookies
 * (dsid, mz_at0, itspod, …) in Netscape/Mozilla format so MozillaCookieJar.load() succeeds.
 *
 * [cookieHeader] is for catalog API callers — the same cookies as a `Cookie: name=value; …`
 * header string, for use in HTTP requests to Apple's catalog and personalized endpoints.
 */
internal data class AppleSession(val netscapeJar: String, val cookieHeader: String)

/**
 * In-app sign-in, as Android does it: the provider's own web login in a WebView, and the session read
 * from that WebView's cookie jar. The WebView is JavaFX's, the one BotGuard already runs in, so this
 * needs no browser installed, decrypts nothing, and is the same on Windows, macOS and Linux.
 *
 * YouTube Music waits for "Use this profile", as the phone does, so a multi-channel account can pick
 * its identity on the page first. Spotify is taken the moment `sp_dc` is set: JavaFX's WebKit has no
 * DRM, so Spotify's web player fails to start, and closing before it shows is what the phone does
 * too.
 */
internal object DesktopWebSignIn {

    enum class Service(val title: String, val startUrl: String, val cookieUrl: String) {
        YOUTUBE_MUSIC(
            "Sign in to YouTube Music",
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
                "&continue=https%3A%2F%2Fmusic.youtube.com%2F",
            "https://music.youtube.com/",
        ),
        SPOTIFY(
            "Sign in to Spotify",
            "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F",
            "https://open.spotify.com/",
        ),
        APPLE_MUSIC(
            "Sign in to Apple Music",
            "https://music.apple.com/us/browse",
            "https://music.apple.com/",
        ),
    }

    /** Whether a sign-in window can be shown at all: it needs the JavaFX toolkit. */
    val available: Boolean get() = DesktopPoTokenWebView.available

    /** The music.youtube.com `Cookie` header, once the listener confirms the profile. */
    suspend fun captureYouTube(): String = capture(Service.YOUTUBE_MUSIC)

    /** The `sp_dc` cookie's value, taken as soon as Spotify sets it. */
    suspend fun captureSpotify(): String = capture(Service.SPOTIFY)

    /**
     * Both Apple Music session representations, taken once media-user-token appears.
     *
     * Collects cookies from all Apple domains (music.apple.com, buy.itunes.apple.com,
     * apple.com, idmsa.apple.com) and returns them as both a Netscape jar (for archive
     * adapters) and a Cookie header (for catalog API callers). The WebView's cookie jar
     * is reset after capture via [DesktopWebCookies.reset] regardless of success.
     */
    suspend fun captureApple(): AppleSession {
        check(available) { "This desktop cannot show a sign-in window (JavaFX did not start)." }
        return suspendCancellableCoroutine { continuation ->
            Platform.runLater {
                val window = AppleSignInWindow { result ->
                    if (continuation.isActive) continuation.resumeWith(result)
                }
                continuation.invokeOnCancellation { Platform.runLater(window::cancel) }
                window.show()
            }
        }
    }

    private suspend fun capture(service: Service): String {
        check(available) { "This desktop cannot show a sign-in window (JavaFX did not start)." }
        return suspendCancellableCoroutine { continuation ->
            Platform.runLater {
                val window = SignInWindow(service) { result ->
                    if (continuation.isActive) continuation.resumeWith(result)
                }
                continuation.invokeOnCancellation { Platform.runLater(window::cancel) }
                window.show()
            }
        }
    }

    private const val SPOTIFY_COOKIE = "sp_dc"
    private const val APPLE_COOKIE = "media-user-token"

    /** The cookies [service] would send, as a `Cookie` header value. */
    private fun cookies(service: Service): String = runCatching {
        CookieHandler.getDefault().get(URI(service.cookieUrl), emptyMap())["Cookie"].orEmpty().joinToString("; ")
    }.getOrDefault("")

    private fun youtubeSession(): String? =
        cookies(Service.YOUTUBE_MUSIC).takeIf(DesktopBrowserCookies::hasSigningSecret)

    private fun spotifySession(): String? = cookies(Service.SPOTIFY).split(';')
        .map(String::trim)
        .firstOrNull { it.substringBefore('=') == SPOTIFY_COOKIE }
        ?.substringAfter('=')
        ?.takeIf(String::isNotBlank)

    private fun appleSession(): String? = cookies(Service.APPLE_MUSIC).split(';')
        .map(String::trim)
        .firstOrNull { it.substringBefore('=') == APPLE_COOKIE }
        ?.substringAfter('=')
        ?.takeIf(String::isNotBlank)

    /**
     * Collects ALL Apple cookies from every relevant domain via the JavaFX CookieHandler and
     * returns both session representations, or null if media-user-token is not present yet.
     *
     * JavaFX's CookieHandler.get() is URI-scoped, so we query each Apple domain separately.
     * The handler returns only name=value pairs without domain/path/expires metadata, so
     * domains are assigned using the same rules as apple_auth.py's store_cookie_header().
     */
    private fun appleSessionFull(): AppleSession? {
        val appleDomains = listOf(
            "https://music.apple.com",
            "https://buy.itunes.apple.com",
            "https://apple.com",
            "https://idmsa.apple.com",
            "https://appleid.apple.com",
        )
        val storeNames = setOf("X-Dsid", "dsid", "mz_at0", "mz_at_ssl", "itspod", "itxt", "hsaccnt")
        // Collect name/value/sourceUrl triples; keep first occurrence when a name appears in multiple jars.
        val pairs = appleDomains.flatMap { domainUrl ->
            runCatching {
                CookieHandler.getDefault()
                    .get(URI(domainUrl), emptyMap())["Cookie"].orEmpty()
                    .joinToString("; ")
            }.getOrDefault("").split(';').mapNotNull { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                if (name.isEmpty() || value.isEmpty()) null else Triple(name, value, domainUrl)
            }
        }.distinctBy { it.first }

        // media-user-token must be present before we hand off
        if (pairs.none { it.first.equals("media-user-token", ignoreCase = true) && it.second.isNotBlank() }) {
            return null
        }

        val jarLines = pairs.joinToString("\n") { (name, value, sourceUrl) ->
            val domain = when {
                name.equals("media-user-token", ignoreCase = true) -> ".music.apple.com"
                name in storeNames || name.startsWith("mz_at") || name.startsWith("des-") -> ".apple.com"
                sourceUrl.contains("music.apple.com") -> ".music.apple.com"
                else -> ".apple.com"
            }
            listOf(domain, "TRUE", "/", "TRUE", "0", name, value).joinToString("\t")
        }
        val netscapeJar = "# Netscape HTTP Cookie File\n# Written by BitChord. Do not edit.\n$jarLines\n"
        val cookieHeader = pairs.joinToString("; ") { "${it.first}=${it.second}" }
        return AppleSession(netscapeJar, cookieHeader)
    }

    /** One Apple Music sign-in window that resolves an [AppleSession] when complete. */
    private class AppleSignInWindow(
        private val finish: (Result<AppleSession>) -> Unit,
    ) {
        private val stage = Stage()
        private val root = BorderPane()
        private val where = Label().apply { style = "-fx-text-fill: #888888;" }
        private var view: WebView? = null
        private var storage: Path? = null
        private var sessionPoller: Timeline? = null
        private var done = false

        init {
            stage.title = Service.APPLE_MUSIC.title
            val startOver = Button("Start over").apply { setOnAction { load() } }
            val steps = Label(
                "1. Use your Apple ID to sign in on the page.\n" +
                    "2. BitChord captures the session automatically.",
            ).apply {
                style = "-fx-text-fill: #888888; -fx-font-size: 12;"
                isWrapText = true
            }
            val bar = HBox(8.0, startOver, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, where)
            bar.padding = Insets(6.0, 8.0, 6.0, 8.0)
            val top = VBox(4.0, steps, bar).apply { style = "-fx-padding: 8 8 0 8;" }
            root.top = top
            stage.scene = Scene(root, 480.0, 760.0)
            stage.setOnHidden { cancel() }
            load()
        }

        fun show() = stage.show()

        fun cancel() = complete(Result.failure(CancellationException("Apple Music sign-in cancelled")))

        private fun load() {
            discard()
            DesktopWebCookies.reset()
            val folder = Files.createTempDirectory("bitchord-apple-sign-in-").also { storage = it }
            val next = WebView().also { view = it }
            val engine = next.engine
            engine.userDataDirectory = folder.toFile()
            engine.locationProperty().addListener { _, _, url -> where.text = hostOf(url) }
            engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED && view === next) checkPage(engine.location.orEmpty())
            }
            sessionPoller = Timeline(KeyFrame(Duration.seconds(1.0), javafx.event.EventHandler {
                if (view === next) checkPage(engine.location.orEmpty())
            })).apply {
                cycleCount = Timeline.INDEFINITE
                playFromStart()
            }
            root.center = next
            engine.load(Service.APPLE_MUSIC.startUrl)
        }

        private fun checkPage(url: String) {
            if (!url.startsWith("https://music.apple.com")) return
            appleSessionFull()?.let { complete(Result.success(it)) }
        }

        private fun complete(result: Result<AppleSession>) {
            if (done) return
            done = true
            if (result.isSuccess) DesktopTrackLog.log("sign-in: Apple Music session captured")
            stage.close()
            discard()
            // Clear the sign-in window's cookie jar regardless of success.
            DesktopWebCookies.reset()
            finish(result)
        }

        private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

        private fun discard() {
            sessionPoller?.stop()
            sessionPoller = null
            view?.engine?.load("about:blank")
            view = null
            storage?.toFile()?.let { folder ->
                if (!folder.deleteRecursively()) {
                    Runtime.getRuntime().addShutdownHook(Thread { folder.deleteRecursively() })
                }
            }
            storage = null
        }
    }

    /** One sign-in: a window that resolves [finish] once, with the session or a cancellation. */
    private class SignInWindow(
        private val service: Service,
        private val finish: (Result<String>) -> Unit,
    ) {
        private val stage = Stage()
        private val root = BorderPane()
        private val confirm = Button("Use this profile")
        // The page's host, since the window has no address bar to show it.
        private val where = Label().apply { style = "-fx-text-fill: #aaaaaa;" }
        private var view: WebView? = null
        private var storage: Path? = null
        private var done = false

        init {
            stage.title = service.title
            val startOver = Button("Start over").apply { setOnAction { load() } }
            val bar = HBox(8.0, startOver, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, where)
            bar.padding = Insets(8.0)
            if (service == Service.YOUTUBE_MUSIC) {
                confirm.isDefaultButton = true
                confirm.setOnAction { youtubeSession()?.let { complete(Result.success(it)) } }
                bar.children += confirm
            }
            root.bottom = bar
            stage.scene = Scene(root, 480.0, 760.0)
            // Closing the window is the listener cancelling.
            stage.setOnHidden { cancel() }
            load()
        }

        fun show() = stage.show()

        fun cancel() = complete(Result.failure(CancellationException("sign-in window closed")))

        /**
         * A clean start: an empty cookie jar and a new WebView with storage of its own. Clearing only
         * the cookies is not enough to change account — Spotify also remembers the login in web
         * storage — and starting over is how the listener picks another Google, Facebook or Apple
         * account.
         */
        private fun load() {
            discard()
            DesktopWebCookies.reset()
            val folder = Files.createTempDirectory("bitchord-sign-in-").also { storage = it }
            val next = WebView().also { view = it }
            val engine = next.engine
            engine.userDataDirectory = folder.toFile()
            engine.locationProperty().addListener { _, _, url -> where.text = hostOf(url) }
            engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED && view === next) onPage(engine.location.orEmpty())
            }
            confirm.isDisable = true
            root.center = next
            engine.load(service.startUrl)
        }

        private fun onPage(url: String) {
            when (service) {
                // Reaching YouTube Music only enables confirming: a multi-channel login may still be
                // choosing its identity on this page.
                Service.YOUTUBE_MUSIC ->
                    confirm.isDisable = !url.startsWith("https://music.youtube.com") || youtubeSession() == null
                Service.SPOTIFY -> if (url.startsWith("https://open.spotify.com")) {
                    spotifySession()?.let { complete(Result.success(it)) }
                }
                Service.APPLE_MUSIC -> if (url.startsWith("https://music.apple.com")) {
                    appleSession()?.let { complete(Result.success("media-user-token=$it")) }
                }
            }
        }

        private fun complete(result: Result<String>) {
            if (done) return
            done = true
            if (result.isSuccess) DesktopTrackLog.log("sign-in: ${service.name} session captured in the sign-in window")
            stage.close()
            discard()
            // BitChord keeps its own copy of the session; the window's goes with it.
            DesktopWebCookies.reset()
            finish(result)
        }

        private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

        /** Stops the current page and deletes its storage. */
        private fun discard() {
            view?.engine?.load("about:blank")
            view = null
            storage?.toFile()?.let { folder ->
                if (!folder.deleteRecursively()) {
                    // The page may still hold its files open for a moment. Retry as the app exits;
                    // a hard kill still leaves the folder behind.
                    DesktopTrackLog.log("sign-in: could not delete ${folder.name} yet; removing it at exit")
                    Runtime.getRuntime().addShutdownHook(Thread { folder.deleteRecursively() })
                }
            }
            storage = null
        }
    }
}

/**
 * The cookie jar every JavaFX WebView in the app uses, installed before the first one exists.
 *
 * JavaFX's HTTP2Loader builds one HttpClient with whatever [CookieHandler.getDefault] is at its first
 * request and keeps it, so replacing the default afterwards changes nothing for the WebView. This
 * wrapper stays the default and [reset] swaps what is inside it. The jar inside is JavaFX's own:
 * `java.net.CookieManager` drops cookies Spotify's login depends on.
 */
internal object DesktopWebCookies : CookieHandler() {
    @Volatile
    private var jar: CookieHandler = com.sun.webkit.network.CookieManager()

    fun install() = setDefault(this)

    fun reset() {
        jar = com.sun.webkit.network.CookieManager()
    }

    override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> =
        jar.get(uri, requestHeaders)

    override fun put(uri: URI, responseHeaders: Map<String, List<String>>) = jar.put(uri, responseHeaders)
}
