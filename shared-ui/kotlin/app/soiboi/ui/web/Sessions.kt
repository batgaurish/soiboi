package app.soiboi.ui.web

import app.soiboi.engine.ai.AiProvider
import app.soiboi.engine.ai.AiProviders
import app.soiboi.engine.download.APPLE_MUSIC_APK_LABEL
import app.soiboi.engine.download.APPLE_MUSIC_APK_URL
import app.soiboi.engine.download.Cookie
import app.soiboi.engine.download.domainForCookie
import app.soiboi.engine.sources.BROWSER_USER_AGENT
import app.soiboi.engine.sources.downloadFile
import app.soiboi.ui.AppController
import java.io.File
import java.time.Instant

/** The three guided tasks that need a real browser. Each says what to do, watches the page, and finishes by itself. */
object WebSessions {
    /**
     * Apple Music sign-in. Apple's own page takes the password, 2FA and passkeys, so Soiboi never sees
     * them. The session counts as signed in once the cookie Apple Music needs appears.
     */
    fun appleSignIn(app: AppController): WebSession = WebSession(
        title = "Sign in to Apple Music",
        steps = listOf("Choose Sign In on the page and use your Apple ID.", "Soiboi keeps only the Apple session and closes this window when it has it."),
        startUrl = "https://music.apple.com/us/browse",
        watch = { page, state ->
            val cookies = page.cookies()
            if (cookies.none { it.name == "media-user-token" && it.value.isNotEmpty() }) {
                state.status = "Waiting for you to sign in."
                null
            } else if (app.engine.cookieStore.save(cookies.mapNotNull(::toCookie))) "Signed in to Apple Music"
            else { state.status = "Signed in, but Apple has not given a music session yet. Open any album."; null }
        },
    )

    /** YouTube Music sign-in. Watches for Google auth cookies. */
    fun ytMusicSignIn(app: AppController): WebSession = WebSession(
        title = "Sign in to YouTube Music",
        steps = listOf("Sign in with your Google account.", "Soiboi grabs the session when you're signed in."),
        startUrl = "https://music.youtube.com/",
        watch = { page, state ->
            val cookies = page.cookies()
            val sapisid = cookies.find { it.name == "SAPISID" && it.value.isNotEmpty() }
            val sid = cookies.find { it.name == "SID" && it.value.isNotEmpty() }
            if (sapisid == null || sid == null) {
                state.status = "Waiting for you to sign in."
                null
            } else {
                val cookieStr = cookies.filter { it.domain.contains("youtube") || it.domain.contains("google") }
                    .joinToString("; ") { "${it.name}=${it.value}" }
                app.engine.sessionRegistry.get("ytmusic")?.let { session ->
                    (session as? app.soiboi.engine.accounts.YTMusicSession)?.setCredential(cookieStr)
                }
                "Signed in to YouTube Music"
            }
        },
    )

    /** SoundCloud sign-in. Watches for the oauth_token cookie. */
    fun soundCloudSignIn(app: AppController): WebSession = WebSession(
        title = "Sign in to SoundCloud",
        steps = listOf("Sign in with your SoundCloud account.", "Soiboi grabs the session when you're signed in."),
        startUrl = "https://soundcloud.com/signin",
        watch = { page, state ->
            val cookies = page.cookies()
            val token = cookies.find { it.name == "oauth_token" && it.value.isNotEmpty() }
            if (token == null) {
                state.status = "Waiting for you to sign in. SoundCloud public access works without signing in."
                null
            } else {
                // SoundCloud session already works with public access
                "Signed in to SoundCloud"
            }
        },
    )

    /** Fetches the one Apple Music build the lossless wrapper needs, then installs its libraries. */
    fun wrapperApk(app: AppController): WebSession = WebSession(
        title = "Get the lossless libraries",
        steps = listOf(
            "Find $APPLE_MUSIC_APK_LABEL on this page and choose its download button.",
            "Soiboi takes the file as soon as the download starts and installs it. You do not need to save anything.",
        ),
        startUrl = APPLE_MUSIC_APK_URL,
        onDownload = { d, state ->
            val name = d.fileName.lowercase()
            if (!(name.endsWith(".apk") || name.endsWith(".xapk") || name.endsWith(".apkm"))) {
                state.status = "Ignored $name. Soiboi wants the Apple Music app file."
                null
            } else {
                val dest = File(app.engine.platform.paths.cacheDir, "apple-music-${System.currentTimeMillis()}${name.substringAfterLast('.', "apk").let { ".$it" }}")
                val headers = buildMap { put("User-Agent", d.userAgent.ifBlank { BROWSER_USER_AGENT }); if (d.cookieHeader.isNotBlank()) put("Cookie", d.cookieHeader); put("Referer", APPLE_MUSIC_APK_URL) }
                state.status = "Downloading $name"
                val error = downloadFile(d.url, dest, headers) { done, total ->
                    state.status = if (total > 0) "Downloading $name, ${done * 100 / total}%" else "Downloading $name, ${done / 1_048_576} MB"
                }
                if (error != null) "The download failed: $error Try the button again."
                else {
                    state.status = "Installing the libraries"
                    val failure = app.engine.wrapper.installLibraries(dest)
                    dest.delete()
                    failure?.let { "That file did not work: $it" } ?: "Lossless libraries installed. Sign the wrapper in next."
                }
            }
        },
    )

    /** Opens a provider's key page and takes the key the moment the page shows it. */
    fun aiKey(provider: AiProvider, onKey: (String) -> Unit): WebSession {
        val info = AiProviders.getValue(provider)
        val pattern = keyPattern(provider)
        return WebSession(
            title = "Get a ${info.label} key",
            steps = listOf("Sign in, then create a new key.", "Soiboi picks the key up when the page shows it and fills it in for you."),
            startUrl = info.keyPage ?: "about:blank",
            watch = { page, state ->
                val found = pattern?.find(page.text())?.value
                if (found == null) { state.status = "Waiting for a new key to appear."; null }
                else { onKey(found); "Key added" }
            },
        )
    }

    private fun toCookie(c: WebCookie): Cookie? {
        if (c.name.isEmpty() || c.value.isEmpty()) return null
        // Android reports no domain, and a guess from the page URL is wrong for session cookies set on .apple.com.
        return Cookie(
            domain = domainForCookie(c.name), name = c.name, value = c.value, path = c.path.ifEmpty { "/" },
            secure = true, expires = if (c.expiresEpoch > 0) Instant.ofEpochSecond(c.expiresEpoch) else null, httpOnly = c.httpOnly,
        )
    }

    /** A shape each provider's keys have. Loose enough to survive a format change, tight enough to ignore page text. */
    fun keyPattern(provider: AiProvider): Regex? = when (provider) {
        AiProvider.Gemini -> Regex("AIza[0-9A-Za-z_\\-]{35}")
        AiProvider.OpenRouter -> Regex("sk-or-v1-[0-9a-f]{40,}")
        AiProvider.Groq -> Regex("gsk_[A-Za-z0-9]{40,}")
        AiProvider.Anthropic -> Regex("sk-ant-[A-Za-z0-9_\\-]{30,}")
        AiProvider.OpenAi -> Regex("sk-(?:proj-)?[A-Za-z0-9_\\-]{40,}")
        else -> null
    }
}
