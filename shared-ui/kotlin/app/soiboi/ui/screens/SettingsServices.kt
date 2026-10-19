package app.soiboi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
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
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun ListenBrainzGroup(app: AppController, scope: CoroutineScope) {
    val c = Soiboi.colors
    val engine = app.engine
    val s by engine.settings.state.collectAsState()
    var lbCheck by remember { mutableStateOf<String?>(null) }
    var lbName by remember(s.listenBrainzUser) { mutableStateOf(s.listenBrainzUser) }
    Group("ListenBrainz") {
        Txt("Your public username shows weekly discoveries and real top artists on Today. No account link or token is needed.", style = Soiboi.type.caption, color = c.soft)
        SField(lbName, { lbName = it; lbCheck = null }, "Username", hint = "Your ListenBrainz name")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            SButton("Check and save", { scope.launch { if (lbName.isBlank() || engine.listenBrainz.verifyUser(lbName.trim())) { engine.settings.update { copy(listenBrainzUser = lbName.trim()) }; lbCheck = "Saved" } else lbCheck = "ListenBrainz does not know that name." } }, compact = true)
            lbCheck?.let { Txt(it, style = Soiboi.type.caption, color = if (it == "Saved") c.ok else c.danger) }
        }
    }
}

@Composable
internal fun AiGroup(app: AppController, scope: CoroutineScope, ui: SettingsUi) {
    val c = Soiboi.colors
    val engine = app.engine
    val ai by engine.aiConfig.collectAsState()
    val tagProgress by engine.tags.progress.collectAsState()
    Group("AI") {
        Txt(if (ai == null) "Off. Add your own key to make playlists from a sentence and get album ideas." else "On with ${ai!!.info.label}, model ${ai!!.effectiveModel.ifEmpty { "not chosen" }}.", style = Soiboi.type.caption, color = c.soft)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SButton(if (ai == null) "Add a key" else "Change the setup", { ui.aiOpen = true }, compact = true, glyph = Glyph.Key)
            if (ai != null) SButton("Remove the key", { engine.saveAiConfig(null) }, tone = Tone.Ghost, compact = true)
        }
        if (ai != null) {
            Hairline()
            Txt("Tag songs by mood", style = Soiboi.type.label, color = c.soft)
            Txt("The model tags each song once with moods, positivity, energy and language. Smart playlists and the Today shelf then read those tags offline. ${engine.tags.count} songs are tagged.", style = Soiboi.type.caption, color = c.soft)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SButton(tagProgress?.let { "Tagging ${it.done} of ${it.total}" } ?: "Tag my library", { scope.launch { try { val n = engine.tags.tagLibrary(ai, engine.library.songs.value); app.toast("Tagged $n songs") } catch (e: Exception) { app.toast(e.message ?: "Tagging stopped") } } }, compact = true, loading = tagProgress != null)
                if (tagProgress != null) SButton("Stop", { engine.tags.cancel() }, tone = Tone.Ghost, compact = true)
                else SButton("Forget tags", { ui.confirmClearTags = true }, tone = Tone.Ghost, compact = true)
            }
        }
    }
}

@Composable
internal fun AiSetupDialog(app: AppController, current: AiConfig?, onDismiss: () -> Unit) {
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
internal fun LogDialog(app: AppController, onDismiss: () -> Unit) {
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
internal fun SpotifySettings(app: AppController, scope: CoroutineScope) {
    val c = Soiboi.colors
    val engine = app.engine
    val api = engine.spotifyApi
    val s by engine.settings.state.collectAsState()
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
