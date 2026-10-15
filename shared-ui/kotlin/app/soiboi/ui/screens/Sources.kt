package app.soiboi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.ServerConfig
import app.soiboi.engine.debrid.DebridFile
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.servers.GoogleDriveAuth
import app.soiboi.engine.servers.PlexSignIn
import app.soiboi.ui.AppController
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.ConfirmDialog
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.Loading
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SectionTitle
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.SwitchRow
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.plural
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch

private class ServerKind(val origin: SongOrigin, val name: String, val blurb: String, val urlHint: String)

private val serverKinds = listOf(
    ServerKind(SongOrigin.Navidrome, "Navidrome", "Your own music server. Soiboi signs in with a salted token, so the password never travels.", "https://music.example.org"),
    ServerKind(SongOrigin.OpenSubsonic, "OpenSubsonic", "Any server that speaks the Subsonic API: Gonic, Airsonic, Funkwhale and more.", "https://subsonic.example.org"),
    ServerKind(SongOrigin.Jellyfin, "Jellyfin", "The music library on your Jellyfin server.", "https://jellyfin.example.org"),
    ServerKind(SongOrigin.Emby, "Emby", "The music library on your Emby server.", "https://emby.example.org"),
    ServerKind(SongOrigin.Plex, "Plex", "A music library on your Plex Media Server.", "http://192.168.1.10:32400"),
    ServerKind(SongOrigin.WebDav, "WebDAV", "A shared folder on Nextcloud, ownCloud or a NAS. Tags come from the folder layout.", "https://cloud.example.org/remote.php/dav/files/me/Music"),
    ServerKind(SongOrigin.GoogleDrive, "Google Drive", "Audio files in your Drive or one folder of it. Needs your own Google Cloud client, set up once.", ""),
)

/**
 * Where music lives besides this device: local folders, your own servers, Google Drive, and debrid accounts that turn a
 * magnet or a download in your account into a stream. Connected servers sit in the library beside local files.
 */
@Composable
fun SourcesScreen(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val status by engine.serverStatus.collectAsState()
    val configs by engine.serverConfigs.collectAsState()
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    val scanning by engine.scanning.collectAsState()
    val progress by engine.scanProgress.collectAsState()
    var connecting by remember { mutableStateOf<ServerKind?>(null) }
    var disconnecting by remember { mutableStateOf<ServerKind?>(null) }
    var debridOpen by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(scanning) { folders = engine.scanner.folders() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item { Txt("Sources", Modifier.padding(top = Space.l), style = Soiboi.type.display) }
        item { Txt("Everything you connect lands in one library. A song from a server plays and shows up in search just like a file on this device.", style = Soiboi.type.body, color = c.soft) }
        item { SectionTitle("On this device") }
        if (folders.isEmpty()) item { EmptyState("No music folders yet", "Choose the folders Soiboi should read. It scans them for songs and keeps watching for new ones.", glyph = Glyph.Folder) }
        items2(folders) { f ->
            Row(Modifier.fillMaxWidth().background(c.raised, Soiboi.look.shape(14)).border(1.5.dp, c.rule, Soiboi.look.shape(14)).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Folder, size = 22.dp, tint = c.soft)
                Txt(f, Modifier.weight(1f), style = Soiboi.type.body, maxLines = 2)
                SIconButton(Glyph.Close, "Stop reading $f", { scope.launch { engine.scanner.setFolders(folders - f); folders = engine.scanner.folders(); engine.rescan() } }, size = 36.dp, iconSize = 16.dp)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                SButton("Add a folder", { scope.launch { app.platform.pickFolder()?.let { engine.scanner.addFolder(it); folders = engine.scanner.folders(); engine.rescan() } } }, tone = Tone.Primary, glyph = Glyph.Plus)
                SButton(if (scanning) "Scanning, $progress files" else "Scan now", { scope.launch { engine.rescan() } }, glyph = Glyph.Refresh, loading = scanning)
            }
        }
        item { SectionTitle("Addons") }
        item { AddonsSection(app) }
        item { SectionTitle("Servers and cloud") }
        items2(serverKinds) { k ->
            val st = status[k.origin]
            val connected = st?.connected == true
            Column(Modifier.fillMaxWidth().background(c.raised, Soiboi.look.shape(16)).border(1.5.dp, c.rule, Soiboi.look.shape(16)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(if (k.origin == SongOrigin.GoogleDrive) Glyph.Cloud else Glyph.Plug, size = 22.dp, tint = if (connected) c.ok else c.soft)
                    Column(Modifier.weight(1f)) {
                        Txt(k.name, style = Soiboi.type.title)
                        Txt(if (connected) "${plural(st?.songs ?: 0, "song")} in your library" else "Not connected", style = Soiboi.type.caption, color = if (connected) c.ok else c.muted)
                    }
                }
                Txt(k.blurb, style = Soiboi.type.caption, color = c.soft)
                st?.error?.let { Banner("Last sync failed", it, Glyph.Warning, tint = c.danger) }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (!connected) SButton("Connect ${k.name}", { connecting = k }, tone = Tone.Primary, compact = true, glyph = Glyph.Plug)
                    else {
                        SButton(if (st?.syncing == true) "Syncing, ${st.songs} songs" else "Sync songs", { scope.launch { engine.syncServer(k.origin) } }, compact = true, glyph = Glyph.Refresh, loading = st?.syncing == true, tone = Tone.Primary)
                        SButton("Disconnect", { disconnecting = k }, tone = Tone.Ghost, compact = true)
                    }
                }
            }
        }
        item { SectionTitle("Debrid accounts") }
        item {
            Column(Modifier.fillMaxWidth().background(c.raised, Soiboi.look.shape(16)).border(1.5.dp, c.rule, Soiboi.look.shape(16)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt("TorBox and Real-Debrid", style = Soiboi.type.title)
                Txt("When a song is not on this device, Soiboi looks through the downloads in your debrid account for it and streams the match. Keys stay in this device's encrypted store.", style = Soiboi.type.caption, color = c.soft)
                Txt("TorBox: ${if (engine.hasTorbox) "key saved" else "no key"} · Real-Debrid: ${if (engine.hasRealDebrid) "token saved" else "no token"}", style = Soiboi.type.caption, color = c.muted)
                SButton("Manage debrid accounts", { debridOpen = true }, compact = true, glyph = Glyph.Key)
            }
        }
    }
    connecting?.let { k -> ConnectDialog(app, k) { connecting = null } }
    disconnecting?.let { k -> ConfirmDialog("Disconnect ${k.name}?", "Its songs leave your library. Nothing is deleted on the server.", "Disconnect ${k.name}", { scope.launch { engine.disconnectServer(k.origin) } }, { disconnecting = null }) }
    if (debridOpen) DebridDialog(app) { debridOpen = false }
}

private inline fun <T> androidx.compose.foundation.lazy.LazyListScope.items2(list: List<T>, crossinline content: @Composable (T) -> Unit) = items(list.size) { content(list[it]) }

@Composable
private fun ConnectDialog(app: AppController, kind: ServerKind, onDismiss: () -> Unit) {
    val c = Soiboi.colors
    val engine = app.engine
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pinCode by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val usesToken = kind.origin == SongOrigin.Plex
    val isDrive = kind.origin == SongOrigin.GoogleDrive

    fun connect() {
        busy = true; error = null
        scope.launch {
            val ok = engine.connectServer(ServerConfig(kind.origin, url.trim(), user.trim(), extra.trim()), secret)
            busy = false
            if (ok) { app.toast("Connected to ${kind.name}"); onDismiss(); engine.syncServer(kind.origin) } else error = "Could not sign in. Check the address, and that the server is reachable from this device."
        }
    }

    SDialog("Connect ${kind.name}", onDismiss, actions = {
        SButton("Cancel", onDismiss, tone = Tone.Ghost)
        if (!isDrive) SButton("Connect and load songs", ::connect, tone = Tone.Primary, loading = busy, enabled = url.isNotBlank() && secret.isNotBlank() && (usesToken || user.isNotBlank()))
    }) {
        if (isDrive) {
            Txt("Create an OAuth client of type TVs and Limited Input devices in Google Cloud, with the Drive API turned on, then paste its two values.", style = Soiboi.type.caption, color = c.soft)
            SField(clientId, { clientId = it }, "Client ID"); SField(clientSecret, { clientSecret = it }, "Client secret", secret = true)
            SField(extra, { extra = it }, "Folder ID (optional)", hint = "Leave empty to read the whole Drive")
            pinCode?.let { Banner("Enter this code", it, Glyph.Key) }
            SButton(if (pinCode == null) "Get a sign-in code" else "Waiting for you to approve", {
                busy = true; error = null
                scope.launch {
                    engine.platform.secrets.put("drive.client", "${clientId.trim()}\n${clientSecret.trim()}")
                    val auth = GoogleDriveAuth(clientId.trim(), clientSecret.trim())
                    val code = auth.start()
                    if (code == null) { error = "Google did not answer. Check the client ID and secret."; busy = false; return@launch }
                    pinCode = "${code.userCode} at ${code.verificationUrl}"; app.platform.openUrl(code.verificationUrl)
                    val tokens = auth.await(code)
                    if (tokens == null) error = "No approval came in time. Try again."
                    else {
                        val ok = engine.connectServer(ServerConfig(SongOrigin.GoogleDrive, "", "", extra.trim()), "${tokens.accessToken}\n${tokens.refreshToken.orEmpty()}\n${tokens.expiresAtEpochMs}")
                        if (ok) { app.toast("Connected to Google Drive"); onDismiss(); engine.syncServer(SongOrigin.GoogleDrive) } else error = "Drive refused the connection."
                    }
                    busy = false
                }
            }, tone = Tone.Primary, loading = busy, enabled = clientId.isNotBlank() && clientSecret.isNotBlank())
        } else {
            SField(url, { url = it }, "Server address", hint = kind.urlHint)
            if (!usesToken) SField(user, { user = it }, "Username")
            SField(secret, { secret = it }, if (usesToken) "Plex token" else "Password", secret = true)
            if (usesToken) {
                Txt("Or sign in through plex.tv without typing a token.", style = Soiboi.type.caption, color = c.soft)
                SButton(if (pinCode == null) "Sign in with Plex" else "Waiting for approval in your browser", {
                    busy = true; error = null
                    scope.launch {
                        val signIn = PlexSignIn(); val pin = signIn.start()
                        if (pin == null) { error = "plex.tv did not answer."; busy = false; return@launch }
                        pinCode = pin.code; app.platform.openUrl(pin.authUrl)
                        signIn.awaitToken(pin)?.let { secret = it } ?: run { error = "No approval came in time." }
                        busy = false
                    }
                }, loading = busy, glyph = Glyph.Key)
            }
        }
        error?.let { Banner("It did not connect", it, Glyph.Warning, tint = c.danger) }
    }
}

@Composable
private fun DebridDialog(app: AppController, onDismiss: () -> Unit) {
    val c = Soiboi.colors
    val engine = app.engine
    var torbox by remember { mutableStateOf("") }
    var rd by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<DebridFile>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SDialog("Debrid accounts", onDismiss, width = 600.dp, actions = {
        SButton("Close", onDismiss, tone = Tone.Ghost)
        SButton("Save keys", { engine.setDebridKeys(torbox.ifBlank { null } ?: engine.platform.secrets.get("torbox.key"), rd.ifBlank { null } ?: engine.platform.secrets.get("realdebrid.token")); app.toast("Saved") }, tone = Tone.Primary)
    }) {
        SField(torbox, { torbox = it }, "TorBox API key", hint = if (engine.hasTorbox) "A key is saved. Type to replace it." else "From the TorBox settings page", secret = true)
        SField(rd, { rd = it }, "Real-Debrid API token", hint = if (engine.hasRealDebrid) "A token is saved. Type to replace it." else "From real-debrid.com/apitoken", secret = true)
        if (engine.hasTorbox) {
            SButton("Show my TorBox audio", { busy = true; scope.launch { files = runCatching { engine.torbox.files() }.getOrDefault(emptyList()).distinctBy { it.provider + it.downloadId }; busy = false } }, glyph = Glyph.Cloud, loading = busy, compact = true)
            Txt("AirLock keeps a download from being deleted after 30 days idle. It only works on files already in TorBox; TorBox has no way to upload files from this device.", style = Soiboi.type.caption, color = c.muted)
            files?.let { fs ->
                if (fs.isEmpty()) Txt("No audio files found in this TorBox account.", style = Soiboi.type.body, color = c.soft)
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items2(fs.take(60)) { f ->
                        var locked by remember(f) { mutableStateOf(f.airlocked) }
                        SwitchRow(f.name.substringAfterLast('/'), locked, { on -> scope.launch { if (engine.torbox.setAirlocked(f, on)) locked = on else app.toast("TorBox refused that change") } }, detail = if (locked) "AirLocked" else "Deleted after 30 idle days")
                    }
                }
            }
        }
    }
}
