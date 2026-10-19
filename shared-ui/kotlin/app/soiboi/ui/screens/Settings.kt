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
import app.soiboi.engine.core.ArtworkStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.core.DownloadCodecLabels
import app.soiboi.engine.core.Flavour
import app.soiboi.engine.core.ThemeMode
import app.soiboi.ui.AppController
import app.soiboi.ui.components.ConfirmDialog
import app.soiboi.ui.components.Listbox
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.SectionTitle
import app.soiboi.ui.components.Segmented
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.SwitchRow
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.CoroutineScope

/** Screen-level so a dialog flag or a running analysis survives its section scrolling out of the list. */
internal class SettingsUi {
    var aiOpen by mutableStateOf(false)
    var wrapperOpen by mutableStateOf(false)
    var logOpen by mutableStateOf(false)
    var confirmClearTags by mutableStateOf(false)
    var analysing by mutableStateOf(false)
}

@Composable
internal fun Group(title: String, content: @Composable () -> Unit) {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(16)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        SectionTitle(title)
        Column(Modifier.fillMaxWidth().background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) { content() }
    }
}

/** Looks, downloads, Apple Music sign-in, lossless wrapper, lyrics, ListenBrainz, AI and backup, in the order people reach for them. */
@Composable
fun SettingsScreen(app: AppController) {
    val engine = app.engine
    val ai by engine.aiConfig.collectAsState()
    val ui = remember { SettingsUi() }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        item { Txt("Settings", Modifier.padding(top = Space.l), style = Soiboi.type.display) }
        item { LookGroup(app) }
        item { DownloadsGroup(app, scope) }
        item { AppleMusicGroup(app, scope, ui) }
        item { AccountSourcesGroup(app, scope) }
        item { HomeGroup(app) }
        item { PlayerGroup(app) }
        item { MoodGroup(app, scope, ui) }
        item { LyricsGroup(app) }
        item { ListenBrainzGroup(app, scope) }
        item { AiGroup(app, scope, ui) }
        item { BackupGroup(app, scope) }
        item {
            Group("Updates") { UpdateControls(app) }
            Group("Spotify") { SpotifySettings(app, scope) }
            if (!app.platform.isAndroid) DesktopGroup(app)
            Group("Troubleshooting") {
                Txt("Everything Soiboi did and every error, kept on this device, including what happened just before a crash. Copy it when you report a problem.", style = Soiboi.type.caption, color = Soiboi.colors.soft)
                SButton("Show the app log", { ui.logOpen = true }, compact = true, tone = Tone.Secondary, glyph = Glyph.Info)
            }
            Group("About") { Txt("Soiboi ${engine.platform.appVersion}. Plays what you own. Downloads through Apple Music; it never streams from anyone.", style = Soiboi.type.caption, color = Soiboi.colors.soft) }
        }
    }
    if (ui.logOpen) LogDialog(app) { ui.logOpen = false }
    if (ui.aiOpen) AiSetupDialog(app, ai) { ui.aiOpen = false }
    if (ui.wrapperOpen) WrapperDialog(app) { ui.wrapperOpen = false }
    if (ui.confirmClearTags) ConfirmDialog("Forget all mood tags?", "Smart playlists on moods will match nothing until songs are tagged again.", "Forget mood tags", { scope.launch { engine.tags.clear() } }, { ui.confirmClearTags = false })
}

@Composable
private fun LookGroup(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val s by engine.settings.state.collectAsState()
    Group("Look") {
        Txt("Flavour", style = Soiboi.type.label, color = c.soft)
        Segmented(listOf(Flavour.Zine to "Zine", Flavour.LinerNotes to "Liner Notes", Flavour.Spotify to "Spotify"), s.flavour, { f -> engine.settings.update { copy(flavour = f) } })
        Txt(when (s.flavour) { Flavour.Zine -> "Chunky type, round corners and tilted codec stickers."; Flavour.Spotify -> "Near-black, green accent, flat tiles. Familiar if you came from Spotify."; Flavour.LinerNotes -> "Serif headings, small corners and a record sleeve's printed labels." }, style = Soiboi.type.caption, color = c.soft)
        Txt("Theme", style = Soiboi.type.label, color = c.soft)
        Segmented(listOf(ThemeMode.System to "Follow the system", ThemeMode.Light to "Paper", ThemeMode.Dark to "Ink"), s.theme, { m -> engine.settings.update { copy(theme = m) } })
        SwitchRow("Reduce motion", s.reduceMotion, { v -> engine.settings.update { copy(reduceMotion = v) } }, detail = "Presses, fades and slides become instant.")
    }
}

@Composable
private fun DownloadsGroup(app: AppController, scope: CoroutineScope) {
    val c = Soiboi.colors
    val engine = app.engine
    val s by engine.settings.state.collectAsState()
    Group("Downloads") {
        Listbox("Quality", DownloadCodecLabels.entries.map { it.key to it.value }, s.downloadCodec, { v -> engine.settings.update { copy(downloadCodec = v) } })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Column(Modifier.weight(1f)) { Txt("Download folder", style = Soiboi.type.bodyStrong); Txt(s.downloadFolder.ifEmpty { "The app's own folder, ${engine.paths.defaultDownloadDir.path}" }, style = Soiboi.type.caption, color = c.soft, maxLines = 2) }
            SButton("Choose", { scope.launch { app.platform.pickFolder()?.let { f -> engine.settings.update { copy(downloadFolder = f) } } } }, compact = true, glyph = Glyph.Folder)
        }
        if (s.downloadFolder.isNotEmpty()) SButton("Use the app's own folder", { engine.settings.update { copy(downloadFolder = "") } }, tone = Tone.Ghost, compact = true)
    }
}

@Composable
private fun HomeGroup(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val s by engine.settings.state.collectAsState()
    Group("Home") {
        val providers by engine.addons.installed.collectAsState()
        Txt("Home always has Offline library and Streaming home tabs. Choose which provider fills Streaming home.", style = Soiboi.type.caption, color = c.soft)
        Listbox(
            "Streaming home provider",
            listOf("offline" to "No provider", "apple" to "Apple Music") + providers.filter { it.enabled && it.id != "apple" }.map { it.id to it.manifest.name },
            s.homepageProvider,
            { id -> engine.settings.update { copy(homepageProvider = id) } },
        )
    }
}

@Composable
private fun PlayerGroup(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val s by engine.settings.state.collectAsState()
    Group("Player") {
        Txt("How the album art appears on the Now Playing screen.", style = Soiboi.type.caption, color = c.soft)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val vinyl = s.artworkStyle == ArtworkStyle.Vinyl
            SButton("Vinyl", { engine.settings.update { copy(artworkStyle = ArtworkStyle.Vinyl) } }, compact = true, tone = if (vinyl) Tone.Primary else Tone.Ghost, glyph = Glyph.Disc)
            SButton("Fit", { engine.settings.update { copy(artworkStyle = ArtworkStyle.Fit) } }, compact = true, tone = if (!vinyl) Tone.Primary else Tone.Ghost)
        }
    }
}

@Composable
private fun MoodGroup(app: AppController, scope: CoroutineScope, ui: SettingsUi) {
    val c = Soiboi.colors
    val engine = app.engine
    Group("Mood and tempo") {
        Txt("Smart playlists and mood shelves read energy, tempo and danceability. Soiboi measures them on your device from the audio.", style = Soiboi.type.caption, color = c.soft)
        SButton(if (ui.analysing) "Analysing" else "Analyse my library", { ui.analysing = true; scope.launch { engine.analyse { }; ui.analysing = false; app.toast("Analysis finished") } }, compact = true, glyph = Glyph.Wave, loading = ui.analysing)
    }
}

@Composable
private fun LyricsGroup(app: AppController) {
    val engine = app.engine
    val s by engine.settings.state.collectAsState()
    Group("Lyrics") {
        SwitchRow("Fetch synced lyrics from LRCLIB", s.lrclibEnabled, { v -> engine.settings.update { copy(lrclibEnabled = v) } }, detail = "Used when a song has none, or only plain words. Found lyrics are saved beside the file.")
        SwitchRow("Also try Paxsenix", s.paxsenixLyrics, { v -> engine.settings.update { copy(paxsenixLyrics = v) } }, detail = "Apple Music lyrics by song match, when LRCLIB has no synced ones. The service is often busy.")
        SField(s.musixmatchToken, { v -> engine.settings.update { copy(musixmatchToken = v.trim()) } }, "Musixmatch user token (optional)", hint = "Paste your token to also search Musixmatch", secret = true)
    }
}

@Composable
private fun BackupGroup(app: AppController, scope: CoroutineScope) {
    val c = Soiboi.colors
    val engine = app.engine
    Group("Backup") {
        Txt("Playlists, smart playlists, matches you chose and settings, as one file. Audio is never included.", style = Soiboi.type.caption, color = c.soft)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SButton("Save a backup", { scope.launch { app.platform.pickSavePath("soiboi-backup-${java.time.LocalDate.now()}.json")?.let { p -> engine.exportBackup(File(p)); app.toast("Backup saved") } } }, compact = true, glyph = Glyph.Download)
            SButton("Restore a backup", { scope.launch { app.platform.pickFile(listOf("json"))?.let { p -> app.toast(if (engine.importBackup(File(p))) "Backup restored" else "That is not a Soiboi backup") } } }, compact = true, tone = Tone.Secondary)
        }
    }
}

@Composable
private fun DesktopGroup(app: AppController) {
    val engine = app.engine
    val s by engine.settings.state.collectAsState()
    Group("Desktop") {
                SwitchRow("Keep playing in the tray", s.minimizeToTray, { v -> engine.settings.update { copy(minimizeToTray = v) } }, detail = "Closing the window hides Soiboi to the system tray instead of quitting. Needs a tray on your desktop.")
                SwitchRow("Show what is playing on Discord", s.discordPresence, { v -> engine.settings.update { copy(discordPresence = v) } }, detail = "Needs the Discord app running, and a Discord application id from discord.com/developers.")
                if (s.discordPresence) SField(s.discordClientId, { v -> engine.settings.update { copy(discordClientId = v.trim()) } }, "Discord application id", hint = "A long number from your Discord application")
    }
}
