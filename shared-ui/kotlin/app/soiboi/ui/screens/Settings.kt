package app.soiboi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import app.soiboi.engine.core.ArtworkStyle
import app.soiboi.engine.core.EngineLog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.ai.AiConfig
import app.soiboi.engine.spotify.SPOTIFY_REDIRECT
import app.soiboi.engine.spotify.SpotifyDevice
import app.soiboi.engine.spotify.SpotifyLogin
import app.soiboi.engine.spotify.awaitSpotifyCode
import app.soiboi.engine.ai.AiProvider
import app.soiboi.engine.ai.AiProviders
import app.soiboi.engine.ai.aiListModels
import app.soiboi.engine.ai.pickDefaultModel
import app.soiboi.engine.core.DownloadCodecLabels
import app.soiboi.engine.core.Flavour
import app.soiboi.engine.core.ThemeMode
import app.soiboi.engine.download.WrapperStage
import app.soiboi.engine.download.APPLE_MUSIC_APK_LABEL
import app.soiboi.engine.download.APPLE_MUSIC_APK_URL
import app.soiboi.ui.AppController
import app.soiboi.ui.web.WebSessions
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.Chip
import app.soiboi.ui.components.ConfirmDialog
import app.soiboi.ui.components.Listbox
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.SectionTitle
import app.soiboi.ui.components.Segmented
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.SwitchRow
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.Hairline
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch
import java.io.File

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(16)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        SectionTitle(title)
        Column(Modifier.fillMaxWidth().background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) { content() }
    }
}

/** Looks, downloads, Apple Music sign-in, lossless wrapper, lyrics, ListenBrainz, AI and backup, in the order people reach for them. */
@Composable
fun SettingsScreen(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val s by engine.settings.state.collectAsState()
    val signed by engine.cookieStore.signedIn.collectAsState()
    val expiry by engine.cookieStore.sessionExpiry.collectAsState()
    val wrapper by engine.wrapper.state.collectAsState()
    val wrapperIn by engine.wrapper.signedIn.collectAsState()
    val ai by engine.aiConfig.collectAsState()
    var aiOpen by remember { mutableStateOf(false) }
    var wrapperOpen by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var lbCheck by remember { mutableStateOf<String?>(null) }
    var lbName by remember(s.listenBrainzUser) { mutableStateOf(s.listenBrainzUser) }
    var confirmClearTags by remember { mutableStateOf(false) }
    val tagProgress by engine.tags.progress.collectAsState()
    var analysing by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        item { Txt("Settings", Modifier.padding(top = Space.l), style = Soiboi.type.display) }
        item {
            Group("Look") {
                Txt("Flavour", style = Soiboi.type.label, color = c.soft)
                Segmented(listOf(Flavour.Zine to "Zine", Flavour.LinerNotes to "Liner Notes", Flavour.Spotify to "Spotify"), s.flavour, { f -> engine.settings.update { copy(flavour = f) } })
                Txt(when (s.flavour) { Flavour.Zine -> "Chunky type, round corners and tilted codec stickers."; Flavour.Spotify -> "Near-black, green accent, flat tiles. Familiar if you came from Spotify."; Flavour.LinerNotes -> "Serif headings, small corners and a record sleeve's printed labels." }, style = Soiboi.type.caption, color = c.soft)
                Txt("Theme", style = Soiboi.type.label, color = c.soft)
                Segmented(listOf(ThemeMode.System to "Follow the system", ThemeMode.Light to "Paper", ThemeMode.Dark to "Ink"), s.theme, { m -> engine.settings.update { copy(theme = m) } })
                SwitchRow("Reduce motion", s.reduceMotion, { v -> engine.settings.update { copy(reduceMotion = v) } }, detail = "Presses, fades and slides become instant.")
            }
        }
        item {
            Group("Downloads") {
                Listbox("Quality", DownloadCodecLabels.entries.map { it.key to it.value }, s.downloadCodec, { v -> engine.settings.update { copy(downloadCodec = v) } })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Column(Modifier.weight(1f)) { Txt("Download folder", style = Soiboi.type.bodyStrong); Txt(s.downloadFolder.ifEmpty { "The app's own folder, ${engine.paths.defaultDownloadDir.path}" }, style = Soiboi.type.caption, color = c.soft, maxLines = 2) }
                    SButton("Choose", { scope.launch { app.platform.pickFolder()?.let { f -> engine.settings.update { copy(downloadFolder = f) } } } }, compact = true, glyph = Glyph.Folder)
                }
                if (s.downloadFolder.isNotEmpty()) SButton("Use the app's own folder", { engine.settings.update { copy(downloadFolder = "") } }, tone = Tone.Ghost, compact = true)
            }
        }
        item {
            Group("Apple Music") {
                Banner(if (wrapperIn) "Signed in for lossless" else if (signed) "Signed in with a browser session" else "Not signed in", when {
                    wrapperIn -> "Signed in as ${wrapper.account ?: "your Apple ID"}. ALAC downloads work."
                    signed -> expiry?.let { "The session ends ${it.toString().take(10)}." } ?: "AAC downloads work. Lossless needs the wrapper below."
                    else -> "Downloading needs an Apple Music subscription and a sign-in."
                }, if (wrapperIn || signed) Glyph.Check else Glyph.Key, tint = if (wrapperIn || signed) c.ok else c.soft)
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
                if (wrapper.stage != WrapperStage.Unsupported) SButton("Set up lossless", { wrapperOpen = true }, compact = true, glyph = Glyph.Key)
            }
        }
        item { AccountSourcesGroup(app) }
        item {
            Group("Player") {
                Txt("How the album art appears on the Now Playing screen.", style = Soiboi.type.caption, color = c.soft)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    val vinyl = s.artworkStyle == ArtworkStyle.Vinyl
                    SButton("Vinyl", { engine.settings.update { copy(artworkStyle = ArtworkStyle.Vinyl) } }, compact = true, tone = if (vinyl) Tone.Primary else Tone.Ghost, glyph = Glyph.Disc)
                    SButton("Fit", { engine.settings.update { copy(artworkStyle = ArtworkStyle.Fit) } }, compact = true, tone = if (!vinyl) Tone.Primary else Tone.Ghost)
                }
            }
        }
        item {
            Group("Mood and tempo") {
                Txt("Smart playlists and mood shelves read energy, tempo and danceability. Soiboi measures them on your device from the audio.", style = Soiboi.type.caption, color = c.soft)
                SButton(if (analysing) "Analysing" else "Analyse my library", { analysing = true; scope.launch { engine.analyse { }; analysing = false; app.toast("Analysis finished") } }, compact = true, glyph = Glyph.Wave, loading = analysing)
            }
        }
        item {
            Group("Lyrics") {
                SwitchRow("Fetch synced lyrics from LRCLIB", s.lrclibEnabled, { v -> engine.settings.update { copy(lrclibEnabled = v) } }, detail = "Used when a song has none, or only plain words. Found lyrics are saved beside the file.")
                SwitchRow("Also try Paxsenix", s.paxsenixLyrics, { v -> engine.settings.update { copy(paxsenixLyrics = v) } }, detail = "Apple Music lyrics by song match, when LRCLIB has no synced ones. The service is often busy.")
                SField(s.musixmatchToken, { v -> engine.settings.update { copy(musixmatchToken = v.trim()) } }, "Musixmatch user token (optional)", hint = "Paste your token to also search Musixmatch", secret = true)
            }
        }
        item {
            Group("ListenBrainz") {
                Txt("Your public username shows weekly discoveries and real top artists on Today. No account link or token is needed.", style = Soiboi.type.caption, color = c.soft)
                SField(lbName, { lbName = it; lbCheck = null }, "Username", hint = "Your ListenBrainz name")
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    SButton("Check and save", { scope.launch { if (lbName.isBlank() || engine.listenBrainz.verifyUser(lbName.trim())) { engine.settings.update { copy(listenBrainzUser = lbName.trim()) }; lbCheck = "Saved" } else lbCheck = "ListenBrainz does not know that name." } }, compact = true)
                    lbCheck?.let { Txt(it, style = Soiboi.type.caption, color = if (it == "Saved") c.ok else c.danger) }
                }
            }
        }
        item {
            Group("AI") {
                Txt(if (ai == null) "Off. Add your own key to make playlists from a sentence and get album ideas." else "On with ${ai!!.info.label}, model ${ai!!.effectiveModel.ifEmpty { "not chosen" }}.", style = Soiboi.type.caption, color = c.soft)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    SButton(if (ai == null) "Add a key" else "Change the setup", { aiOpen = true }, compact = true, glyph = Glyph.Key)
                    if (ai != null) SButton("Remove the key", { engine.saveAiConfig(null) }, tone = Tone.Ghost, compact = true)
                }
                if (ai != null) {
                    Hairline()
                    Txt("Tag songs by mood", style = Soiboi.type.label, color = c.soft)
                    Txt("The model tags each song once with moods, positivity, energy and language. Smart playlists and the Today shelf then read those tags offline. ${engine.tags.count} songs are tagged.", style = Soiboi.type.caption, color = c.soft)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        SButton(tagProgress?.let { "Tagging ${it.done} of ${it.total}" } ?: "Tag my library", { scope.launch { try { val n = engine.tags.tagLibrary(ai, engine.library.songs.value); app.toast("Tagged $n songs") } catch (e: Exception) { app.toast(e.message ?: "Tagging stopped") } } }, compact = true, loading = tagProgress != null)
                        if (tagProgress != null) SButton("Stop", { engine.tags.cancel() }, tone = Tone.Ghost, compact = true)
                        else SButton("Forget tags", { confirmClearTags = true }, tone = Tone.Ghost, compact = true)
                    }
                }
            }
        }
        item {
            Group("Backup") {
                Txt("Playlists, smart playlists, matches you chose and settings, as one file. Audio is never included.", style = Soiboi.type.caption, color = c.soft)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    SButton("Save a backup", { scope.launch { app.platform.pickSavePath("soiboi-backup-${java.time.LocalDate.now()}.json")?.let { p -> engine.exportBackup(File(p)); app.toast("Backup saved") } } }, compact = true, glyph = Glyph.Download)
                    SButton("Restore a backup", { scope.launch { app.platform.pickFile(listOf("json"))?.let { p -> app.toast(if (engine.importBackup(File(p))) "Backup restored" else "That is not a Soiboi backup") } } }, compact = true, tone = Tone.Secondary)
                }
            }
        }
        item {
            Group("Updates") { UpdateControls(app) }
            Group("Spotify") { SpotifySettings(app) }
            if (!app.platform.isAndroid) Group("Desktop") {
                SwitchRow("Keep playing in the tray", s.minimizeToTray, { v -> engine.settings.update { copy(minimizeToTray = v) } }, detail = "Closing the window hides Soiboi to the system tray instead of quitting. Needs a tray on your desktop.")
                SwitchRow("Show what is playing on Discord", s.discordPresence, { v -> engine.settings.update { copy(discordPresence = v) } }, detail = "Needs the Discord app running, and a Discord application id from discord.com/developers.")
                if (s.discordPresence) SField(s.discordClientId, { v -> engine.settings.update { copy(discordClientId = v.trim()) } }, "Discord application id", hint = "A long number from your Discord application")
            }
            Group("Troubleshooting") {
                Txt("Everything Soiboi did and every error, kept on this device, including what happened just before a crash. Copy it when you report a problem.", style = Soiboi.type.caption, color = c.soft)
                SButton("Show the app log", { logOpen = true }, compact = true, tone = Tone.Secondary, glyph = Glyph.Info)
            }
            Group("About") { Txt("Soiboi ${engine.platform.appVersion}. Plays what you own. Downloads through Apple Music; it never streams from anyone.", style = Soiboi.type.caption, color = c.soft) }
        }
    }
    if (logOpen) LogDialog(app) { logOpen = false }
    if (aiOpen) AiSetupDialog(app, ai) { aiOpen = false }
    if (wrapperOpen) WrapperDialog(app) { wrapperOpen = false }
    if (confirmClearTags) ConfirmDialog("Forget all mood tags?", "Smart playlists on moods will match nothing until songs are tagged again.", "Forget mood tags", { scope.launch { engine.tags.clear() } }, { confirmClearTags = false })
}

@Composable
private fun AiSetupDialog(app: AppController, current: AiConfig?, onDismiss: () -> Unit) {
    val c = Soiboi.colors
    var provider by remember { mutableStateOf(current?.provider ?: AiProvider.Gemini) }
    var key by remember { mutableStateOf(current?.key ?: "") }
    var model by remember { mutableStateOf(current?.model ?: "") }
    var baseUrl by remember { mutableStateOf(current?.baseUrl ?: "") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val info = AiProviders.getValue(provider)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (app.web == null) SDialog("Set up AI", onDismiss, width = 520.dp, actions = {
        SButton("Cancel", onDismiss, tone = Tone.Ghost)
        SButton("Save", { app.engine.saveAiConfig(AiConfig(provider, key.trim(), model.trim(), baseUrl.trim())); app.toast("AI is on"); onDismiss() }, tone = Tone.Primary, enabled = AiConfig(provider, key, model, baseUrl).isUsable)
    }) {
        Listbox("Provider", AiProviders.entries.map { it.key to it.value.label }, provider, { provider = it; models = emptyList(); model = "" })
        Txt(info.blurb, style = Soiboi.type.caption, color = c.soft)
        info.keyPage?.let { page ->
            if (app.platform.web.available && WebSessions.keyPattern(provider) != null) SButton("Get a key for me", { app.web = WebSessions.aiKey(provider) { key = it } }, tone = Tone.Secondary, compact = true, glyph = Glyph.Link)
            else SButton("Get a key", { app.platform.openUrl(page) }, tone = Tone.Ghost, compact = true, glyph = Glyph.Link)
        }
        SField(key, { key = it }, if (info.needsKey) "API key" else "API key (optional)", secret = true)
        if (provider == AiProvider.Ollama || provider == AiProvider.Custom) SField(baseUrl, { baseUrl = it }, "Address", hint = info.baseUrl.ifEmpty { "https://host/v1" })
        SField(model, { model = it }, "Model", hint = info.defaultModel ?: "Load the list below, or type a name")
        SButton("List this provider's models", { busy = true; scope.launch { models = aiListModels(AiConfig(provider, key.trim(), "", baseUrl.trim())); if (model.isBlank()) pickDefaultModel(provider, models)?.let { model = it }; busy = false } }, compact = true, loading = busy)
        if (models.isNotEmpty()) Listbox("Pick a model", models.take(200).map { it to it }, model.takeIf { it in models }, { model = it })
        Txt("Your key stays in this device's encrypted store and goes only to the provider you chose.", style = Soiboi.type.caption, color = c.muted)
    }
}

@Composable
private fun WrapperDialog(app: AppController, onDismiss: () -> Unit) {
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
private fun AccountSourcesGroup(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val syncEnabled by engine.accountSync.enabled.collectAsState()
    val sessions = engine.sessionRegistry.all()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var syncing by remember { mutableStateOf(false) }

    Group("Account sync") {
        Txt("Sign into streaming services to auto-download new playlist tracks.", style = Soiboi.type.caption, color = c.soft)

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
                if (app.platform.web.available && !signedIn) {
                    when (session.id) {
                        "ytmusic" -> SButton("Sign in", { app.web = WebSessions.ytMusicSignIn(app) }, compact = true, tone = Tone.Secondary, glyph = Glyph.Key)
                        "soundcloud" -> SButton("Sign in", { app.web = WebSessions.soundCloudSignIn(app) }, compact = true, tone = Tone.Secondary, glyph = Glyph.Key)
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

@Composable
private fun LogDialog(app: AppController, onDismiss: () -> Unit) {
    val c = Soiboi.colors
    var text by remember { mutableStateOf(EngineLog.text()) }
    val lines = remember(text) { text.lines().takeLast(400).reversed() }
    SDialog("App log", onDismiss, width = 640.dp, actions = {
        SButton("Close", onDismiss, tone = Tone.Ghost)
        SButton("Clear", { EngineLog.clear(); text = "" }, tone = Tone.Ghost)
        SButton("Refresh", { text = EngineLog.text() }, tone = Tone.Secondary)
        SButton("Copy all", { app.platform.copyToClipboard(text); app.toast("Log copied") }, tone = Tone.Primary)
    }) {
        Txt(if (text.isBlank()) "Nothing logged yet." else "Newest first. A line starting CRASH is where the app stopped.", style = Soiboi.type.caption, color = c.soft)
        LazyColumn(Modifier.fillMaxWidth().height(360.dp)) { items(lines) { Txt(it, style = Soiboi.type.caption, color = if (it.contains("CRASH") || it.contains("rror")) c.danger else c.ink) } }
    }
}

@Composable
private fun SpotifySettings(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val api = engine.spotifyApi
    val s by engine.settings.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var clientId by remember { mutableStateOf(api.clientId.orEmpty()) }
    var connected by remember { mutableStateOf(api.connected) }
    var busy by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<SpotifyDevice>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    Txt("Search Spotify and your library, and play through a Spotify Connect device you own (the Spotify app on a phone or computer, or a speaker). Soiboi controls the device; the sound comes from it. Needs Premium.", style = Soiboi.type.caption, color = c.soft)
    if (!connected) {
        Txt("Make a free app at developer.spotify.com, add $SPOTIFY_REDIRECT as its redirect address, then paste its Client ID here. No secret is needed.", style = Soiboi.type.caption, color = c.soft)
        SField(clientId, { clientId = it }, "Client ID", hint = "From your Spotify app's settings")
        SButton("Connect Spotify", {
            busy = true; problem = null
            scope.launch {
                api.saveClientId(clientId)
                val login = SpotifyLogin(clientId.trim())
                val code = awaitSpotifyCode(login, { app.platform.openUrl(it) })
                if (code == null) problem = "No sign-in came back. Check the redirect address in your Spotify app, and that nothing else is using port 8898."
                else problem = api.finishLogin(login, code)
                connected = api.connected
                if (connected) { engine.addons.setEnabled("spotify", true); app.toast("Spotify connected") }
                busy = false
            }
        }, tone = Tone.Primary, compact = true, loading = busy, enabled = clientId.isNotBlank(), glyph = Glyph.Link)
    } else {
        Banner("Spotify is connected", "Search and the shelves now include it.", Glyph.Check, tint = c.ok)
        SwitchRow("Use Spotify", engine.addons.instance("spotify")?.enabled ?: false, { v -> scope.launch { engine.addons.setEnabled("spotify", v) } })
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SButton("Find my devices", { busy = true; scope.launch { devices = runCatching { api.devices() }.getOrElse { problem = it.message; emptyList() }; busy = false } }, compact = true, loading = busy, glyph = Glyph.Refresh)
            SButton("Disconnect", { api.signOut(); connected = false; scope.launch { engine.addons.setEnabled("spotify", false) } }, tone = Tone.Ghost, compact = true)
        }
        if (devices.isNotEmpty()) Listbox("Play on", listOf("" to "Whichever is active") + devices.map { it.id to "${it.name} (${it.type})" }, s.spotifyDeviceId, { id -> engine.settings.update { copy(spotifyDeviceId = id) } })
        else if (s.spotifyDeviceId.isNotEmpty()) Txt("A device is chosen. Tap Find my devices to change it.", style = Soiboi.type.caption, color = c.muted)
    }
    problem?.let { Banner("Spotify", it, Glyph.Warning, tint = c.danger) }
}
