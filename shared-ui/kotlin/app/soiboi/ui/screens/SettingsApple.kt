package app.soiboi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.download.WrapperStage
import app.soiboi.engine.download.APPLE_MUSIC_APK_LABEL
import app.soiboi.engine.download.APPLE_MUSIC_APK_URL
import app.soiboi.ui.AppController
import app.soiboi.ui.web.WebSessions
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.Listbox
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.SwitchRow
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.Hairline
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.CoroutineScope

private val AppleStorefronts = listOf(
    "us" to "United States", "in" to "India", "gb" to "United Kingdom", "ca" to "Canada", "au" to "Australia",
    "nz" to "New Zealand", "ie" to "Ireland", "de" to "Germany", "fr" to "France", "it" to "Italy",
    "es" to "Spain", "pt" to "Portugal", "nl" to "Netherlands", "be" to "Belgium", "ch" to "Switzerland",
    "at" to "Austria", "se" to "Sweden", "no" to "Norway", "dk" to "Denmark", "fi" to "Finland",
    "is" to "Iceland", "pl" to "Poland", "cz" to "Czechia", "sk" to "Slovakia", "hu" to "Hungary",
    "ro" to "Romania", "gr" to "Greece", "tr" to "Türkiye", "za" to "South Africa", "ae" to "United Arab Emirates",
    "sa" to "Saudi Arabia", "il" to "Israel", "jp" to "Japan", "kr" to "South Korea", "cn" to "China",
    "hk" to "Hong Kong", "tw" to "Taiwan", "sg" to "Singapore", "my" to "Malaysia", "th" to "Thailand",
    "ph" to "Philippines", "id" to "Indonesia", "vn" to "Vietnam", "br" to "Brazil", "mx" to "Mexico",
    "ar" to "Argentina", "cl" to "Chile", "co" to "Colombia", "pe" to "Peru", "ua" to "Ukraine",
)

@Composable
internal fun AppleMusicGroup(app: AppController, scope: CoroutineScope, ui: SettingsUi) {
    val c = Soiboi.colors
    val engine = app.engine
    val s by engine.settings.state.collectAsState()
    val signed by engine.cookieStore.signedIn.collectAsState()
    val expiry by engine.cookieStore.sessionExpiry.collectAsState()
    val wrapper by engine.wrapper.state.collectAsState()
    val wrapperIn by engine.wrapper.signedIn.collectAsState()
    Group("Apple Music") {
        Banner(if (wrapperIn) "Signed in for lossless" else if (signed) "Signed in with a browser session" else "Not signed in", when {
            wrapperIn -> "Signed in as ${wrapper.account ?: "your Apple ID"}. ALAC downloads work."
            signed -> expiry?.let { "The session ends ${it.toString().take(10)}." } ?: "AAC downloads work. Lossless needs the wrapper below."
            else -> "Downloading needs an Apple Music subscription and a sign-in."
        }, if (wrapperIn || signed) Glyph.Check else Glyph.Key, tint = if (wrapperIn || signed) c.ok else c.soft)
        Listbox("Apple Music country", AppleStorefronts, s.storefront, { code -> engine.settings.update { copy(appleStorefront = code) } })
        Txt("This region controls Apple Music search, recommendations, charts, and catalog links. Two-letter code: ${s.storefront.uppercase()}.", style = Soiboi.type.caption, color = c.soft)
        Txt("Browser session", style = Soiboi.type.label, color = c.soft)
        Txt("Sign in on Apple's own page inside Soiboi. Apple takes the password and any 2FA; Soiboi keeps only the Apple session.", style = Soiboi.type.caption, color = c.soft)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (app.platform.web.available) SButton(if (signed) "Sign in again" else "Sign in to Apple Music", { app.web = WebSessions.appleSignIn(app) }, tone = Tone.Primary, compact = true, glyph = Glyph.Key)
            SButton("Import cookies.txt", { scope.launch { app.platform.pickFile(listOf("txt"))?.let { p -> app.toast(if (engine.cookieStore.importFile(File(p))) "Signed in" else "That file has no Apple Music session") } } }, compact = true, tone = if (app.platform.web.available) Tone.Ghost else Tone.Secondary, glyph = Glyph.Download)
            if (signed) SButton("Sign out", { engine.cookieStore.signOut() }, tone = Tone.Ghost, compact = true)
        }
        Hairline()
        Txt("Lossless wrapper", style = Soiboi.type.label, color = c.soft)
        Txt(when (wrapper.stage) { WrapperStage.Unsupported -> "Not part of this build. AAC downloads work without it."; WrapperStage.NeedsLibraries -> "Needs the Apple Music app file once."; WrapperStage.Ready -> "Running and signed in."; WrapperStage.NeedsCode -> "Apple sent a two-factor code."; WrapperStage.Failed -> wrapper.message ?: "It did not start."; else -> if (wrapperIn) "Signed in. It starts when a download needs it." else "Set up to download ALAC." }, style = Soiboi.type.caption, color = c.soft)
        if (wrapper.stage != WrapperStage.Unsupported) SButton("Set up lossless", { ui.wrapperOpen = true }, compact = true, glyph = Glyph.Key)
    }
}

@Composable
internal fun WrapperDialog(app: AppController, onDismiss: () -> Unit) {
    val c = Soiboi.colors
    val engine = app.engine
    val state by engine.wrapper.state.collectAsState()
    var appleId by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (app.web == null) SDialog("Set up lossless", onDismiss, width = 520.dp) {
        Txt("Lossless downloads decrypt with Apple Music's own Android libraries. Soiboi ships none of them, so you supply the one app file it is pinned to.", style = Soiboi.type.caption, color = c.soft)
        when (state.stage) {
            WrapperStage.NeedsLibraries -> {
                if (app.platform.web.available) {
                    Txt("Soiboi opens the download page, takes the file when the download starts, and installs it.", style = Soiboi.type.bodyStrong)
                    SButton("Get it for me", { app.web = WebSessions.wrapperApk(app) }, tone = Tone.Primary, compact = true, glyph = Glyph.Download)
                    Txt("Or do it by hand: download $APPLE_MUSIC_APK_LABEL yourself, then choose the file.", style = Soiboi.type.caption, color = c.soft)
                } else {
                    Txt("Step 1. Download $APPLE_MUSIC_APK_LABEL.", style = Soiboi.type.bodyStrong)
                    SButton("Open the download page", { app.platform.openUrl(APPLE_MUSIC_APK_URL) }, compact = true, glyph = Glyph.Link)
                    Txt("Step 2. Choose the file you downloaded.", style = Soiboi.type.bodyStrong)
                }
                SButton("Choose the APK", { busy = true; scope.launch { app.platform.pickFile(listOf("apk", "xapk"))?.let { p -> message = engine.wrapper.installLibraries(File(p)) ?: "Installed" }; busy = false } }, tone = if (app.platform.web.available) Tone.Secondary else Tone.Primary, compact = true, loading = busy, glyph = Glyph.Folder)
            }
            WrapperStage.NeedsCode -> {
                Txt("Apple sent a code to your devices.", style = Soiboi.type.bodyStrong)
                SField(code, { code = it }, "Six-digit code", requestFocus = true)
                SButton("Submit the code", { busy = true; scope.launch { engine.wrapper.submitCode(code); busy = false } }, tone = Tone.Primary, loading = busy, compact = true)
            }
            WrapperStage.Unsupported -> Banner("Not part of this build", "The wrapper binaries are missing, so lossless is unavailable here. AAC downloads work.", Glyph.Info)
            else -> {
                if (wrapperSigned(engine)) { Banner("Signed in", state.account ?: "Your Apple ID", Glyph.Check, tint = c.ok); SButton("Sign out of the wrapper", { scope.launch { engine.wrapper.signOut() } }, tone = Tone.Ghost, compact = true) }
                else {
                    SField(appleId, { appleId = it }, "Apple ID", hint = "name@example.com"); SField(password, { password = it }, "Password", secret = true)
                    Txt("The password goes only to the wrapper on this device, which hands it to Apple. Soiboi never stores it.", style = Soiboi.type.caption, color = c.muted)
                    SButton("Sign in", { busy = true; scope.launch { engine.wrapper.start(); engine.wrapper.signIn(appleId.trim(), password); password = ""; busy = false } }, tone = Tone.Primary, loading = busy, enabled = appleId.isNotBlank() && password.isNotBlank(), compact = true)
                }
            }
        }
        state.message?.let { Banner("Wrapper", it, Glyph.Info) }
        message?.let { Txt(it, style = Soiboi.type.caption, color = c.soft) }
    }
}

private fun wrapperSigned(engine: app.soiboi.engine.Engine) = engine.wrapper.signedIn.value

@Composable
internal fun AccountSourcesGroup(app: AppController, scope: CoroutineScope) {
    val c = Soiboi.colors
    val engine = app.engine
    val syncEnabled by engine.accountSync.enabled.collectAsState()
    val sessions by engine.sessionRegistry.sessionsFlow.collectAsState()
    var syncing by remember { mutableStateOf(false) }

    Group("Account sync") {
        Txt(
            if (sessions.isEmpty()) "Loading streaming accounts…"
            else {
                val connected = sessions.count { it.state.value is app.soiboi.engine.accounts.SessionState.SignedIn }
                if (connected == 0) "No streaming accounts connected yet. Sign in below to enable playlist sync."
                else "$connected streaming account${if (connected == 1) "" else "s"} connected. Turn on auto-sync to download new playlist tracks."
            },
            style = Soiboi.type.caption,
            color = c.soft,
        )

        SwitchRow("Auto-sync playlists", syncEnabled, { v -> engine.accountSync.setEnabled(v) }, detail = if (syncEnabled) "Checks every 30 minutes." else "Off.")

        Hairline()
        Txt("Streaming accounts", style = Soiboi.type.label, color = c.soft)
        
        for (session in sessions) {
            val state by session.state.collectAsState()
            val signedIn = state is app.soiboi.engine.accounts.SessionState.SignedIn
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Txt(session.label, style = Soiboi.type.bodyStrong, color = if (signedIn) c.ink else c.muted)
                        if (session.canStream) Txt("Stream", style = Soiboi.type.micro, color = c.ok)
                    }
                    when (state) {
                        is app.soiboi.engine.accounts.SessionState.SignedIn -> Txt((state as app.soiboi.engine.accounts.SessionState.SignedIn).account, style = Soiboi.type.caption, color = c.soft)
                        is app.soiboi.engine.accounts.SessionState.SignedOut -> Txt("Not signed in", style = Soiboi.type.caption, color = c.muted)
                        is app.soiboi.engine.accounts.SessionState.Error -> Txt((state as app.soiboi.engine.accounts.SessionState.Error).message, style = Soiboi.type.caption, color = c.danger)
                    }
                }
                if (!signedIn) {
                    when (session.id) {
                        "apple" -> if (app.platform.web.available) SButton("Sign in", { app.web = WebSessions.appleSignIn(app) }, compact = true, tone = Tone.Secondary, glyph = Glyph.Key)
                        "ytmusic" -> if (app.platform.web.available) SButton("Sign in", { app.web = WebSessions.ytMusicSignIn(app) }, compact = true, tone = Tone.Secondary, glyph = Glyph.Key)
                        "soundcloud" -> if (app.platform.web.available) SButton("Sign in", { app.web = WebSessions.soundCloudSignIn(app) }, compact = true, tone = Tone.Secondary, glyph = Glyph.Key)
                    }
                }
            }
        }

        Hairline()
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SButton(if (syncing) "Syncing" else "Sync now", {
                syncing = true
                scope.launch {
                    runCatching { engine.syncAccounts() }
                    syncing = false
                    app.toast("Sync complete")
                }
            }, compact = true, loading = syncing, glyph = Glyph.Refresh, enabled = !syncing)
            SButton("Scan library", {
                scope.launch {
                    app.toast("Scanning library...")
                    engine.rescan()
                    app.toast("Library updated")
                }
            }, compact = true, tone = Tone.Ghost, glyph = Glyph.Folder)
        }
    }
}
