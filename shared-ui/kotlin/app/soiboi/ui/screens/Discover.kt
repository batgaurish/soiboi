package app.soiboi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soiboi.engine.discovery.ApplePlaylist
import app.soiboi.engine.discovery.DiscoveryTrack
import app.soiboi.engine.discovery.LinkedTrack
import app.soiboi.engine.discovery.UnresolvedTrack
import app.soiboi.engine.download.DownloadRequest
import app.soiboi.engine.sources.ExternalPlaylist
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.Loading
import app.soiboi.ui.components.ProgressBar
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SectionTitle
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.plural
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch

/**
 * Bringing music in. The lead is one field: paste a playlist link from any platform and Soiboi reads it, matches every
 * track to Apple Music, and archives the ones you pick. Under it, your own Apple Music playlists, and the AI.
 */
@Composable
fun DiscoverScreen(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val settings by engine.settings.state.collectAsState()
    val signedIn by engine.cookieStore.signedIn.collectAsState()
    val wrapperIn by engine.wrapper.signedIn.collectAsState()
    var link by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var apple by remember { mutableStateOf<List<ApplePlaylist>?>(null) }
    var appleError by remember { mutableStateOf<String?>(null) }
    var appleBusy by remember { mutableStateOf(false) }
    val aiConfig by engine.aiConfig.collectAsState()
    var aiPlaylists by remember { mutableStateOf<List<app.soiboi.engine.ai.AiPlaylist>>(emptyList()) }
    var aiPlaylistsBusy by remember { mutableStateOf(false) }
    val listenBrainzPlaylists by androidx.compose.runtime.produceState<List<app.soiboi.engine.discovery.LbPlaylist>?>(null, settings.listenBrainzUser) {
        value = if (settings.listenBrainzUser.isBlank()) emptyList() else runCatching { engine.listenBrainz.discoveryPlaylists() }.getOrDefault(emptyList())
    }
    val unresolved by engine.manualResolution.unresolved.collectAsState()
    val addons by engine.addons.installed.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun go() {
        val text = link.trim()
        if (text.isEmpty()) return
        if (engine.playlistSources.forUrl(text) == null) { linkError = "That is not a playlist link Soiboi knows. It reads Spotify, Deezer, YouTube Music, Tidal, SoundCloud, JioSaavn, Qobuz, Gaana and Bandcamp, plus a pasted tracklist."; return }
        linkError = null; app.go(Route.Import(text))
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
        item {
            Column(Modifier.padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Txt("Bring music in", style = Soiboi.type.display, maxLines = 2)
                Txt("Paste a playlist from another service. Soiboi matches each song to Apple Music and downloads the ones you choose to your own device.", style = Soiboi.type.body, color = c.soft)
                SField(link, { link = it; linkError = null }, "Playlist link", hint = "https://open.spotify.com/playlist/...", leading = Glyph.Link, error = linkError, onSubmit = ::go)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    SButton("Read this playlist", ::go, tone = Tone.Primary, glyph = Glyph.Search, enabled = link.isNotBlank())
                    SButton("Paste a tracklist instead", { app.go(Route.Import("")) }, glyph = Glyph.Playlist)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Your Apple Music playlists")
                if (!signedIn && !wrapperIn) Banner("Sign in to list them", "Soiboi lists your own playlists once it can talk to Apple Music as you. Add your sign-in in Settings.", Glyph.Key, action = { SButton("Open settings", { app.go(Route.Settings) }, compact = true) })
                else {
                    if (apple == null) SButton("Load my playlists", { appleBusy = true; appleError = null; scope.launch { val r = engine.appleLibrary.fetchPlaylists(); apple = r.playlists.takeIf { r.error == null }; appleError = r.error; appleBusy = false } }, glyph = Glyph.Download, loading = appleBusy)
                    appleError?.let { Banner("Could not read your library", it, Glyph.Warning, tint = c.danger) }
                    apple?.let { list ->
                        if (list.isEmpty()) Txt("No playlists in this Apple Music account.", style = Soiboi.type.body, color = c.soft)
                        list.forEach { p ->
                            Row(Modifier.fillMaxWidth().background(c.raised, Soiboi.look.shape(14)).border(1.5.dp, c.rule, Soiboi.look.shape(14)).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) { Txt(p.name, style = Soiboi.type.bodyStrong, maxLines = 1); Txt((p.trackCount?.let { plural(it, "song") } ?: "Song count unknown") + if (p.hasCatalogUrl) "" else " · only in your library", style = Soiboi.type.caption, color = c.soft) }
                                SButton("Download", { scope.launch { val sf = engine.appleLibrary.accountStorefront(); val o = engine.appleLibrary.archivePlaylist(p, sf); app.toast(o.error ?: "Queued ${plural(o.queued, "download")}${if (o.skipped > 0) ", ${o.skipped} not in the catalog" else ""}"); if (o.error == null) app.go(Route.Downloads) } }, tone = Tone.Primary, compact = true, glyph = Glyph.Download)
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("ListenBrainz discoveries")
                if (settings.listenBrainzUser.isBlank()) {
                    Banner("Add your ListenBrainz username", "Your public ListenBrainz playlists appear here when a username is saved in Settings.", Glyph.Compass, action = { SButton("Open settings", { app.go(Route.Settings) }, compact = true) })
                } else if (listenBrainzPlaylists.isNullOrEmpty()) {
                    Txt("No ListenBrainz discovery playlists are available yet.", style = Soiboi.type.body, color = c.soft)
                } else {
                    listenBrainzPlaylists!!.forEach { playlist ->
                        Row(Modifier.fillMaxWidth().press({ app.go(Route.Import("lb:${playlist.mbid}")) }, playlist.title, shape = Soiboi.look.shape(14)).background(c.raised, Soiboi.look.shape(14)).border(1.5.dp, c.rule, Soiboi.look.shape(14)).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                            GlyphIcon(Glyph.Compass, size = 24.dp, tint = c.soft)
                            Column(Modifier.weight(1f)) { Txt(playlist.title, style = Soiboi.type.bodyStrong, maxLines = 1); playlist.lastModified?.let { Txt(it.take(10), style = Soiboi.type.caption, color = c.muted) } }
                            GlyphIcon(Glyph.ChevronRight, size = 18.dp, tint = c.muted)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("AI recommended playlists")
                if (aiConfig == null) Banner("Add an AI provider", "Soiboi can build recommendation playlists from your local library and listening history.", Glyph.Wave, action = { SButton("Open settings", { app.go(Route.Settings) }, compact = true) })
                else {
                    Txt("Generate a few playlists from your own library. Nothing is sent until you ask for it.", style = Soiboi.type.caption, color = c.soft)
                    SButton(if (aiPlaylistsBusy) "Generating" else "Generate recommendations", {
                        if (!aiPlaylistsBusy) {
                            aiPlaylistsBusy = true
                            scope.launch {
                                aiPlaylists = listOf("A fresh set from my library", "Deep focus from my library", "High energy from my library").mapNotNull { request -> runCatching { engine.ai.makePlaylist(request) }.getOrNull() }
                                aiPlaylistsBusy = false
                            }
                        }
                    }, tone = Tone.Secondary, compact = true, loading = aiPlaylistsBusy, glyph = Glyph.Wave)
                    aiPlaylists.forEach { playlist ->
                        Row(Modifier.fillMaxWidth().press({ app.go(Route.Playlist(playlist.name)) }, playlist.name, shape = Soiboi.look.shape(14)).background(c.raised, Soiboi.look.shape(14)).border(1.5.dp, c.rule, Soiboi.look.shape(14)).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Txt(playlist.name, style = Soiboi.type.bodyStrong); Txt(plural(playlist.songs.size, "song"), style = Soiboi.type.caption, color = c.soft) }
                            GlyphIcon(Glyph.ChevronRight, size = 18.dp, tint = c.muted)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Ask an AI")
                if (aiConfig == null) Banner("Optional, and off until you add a key", "Make a playlist from a sentence, and get album ideas on Today. Your key stays on this device and goes only to the provider you pick.", Glyph.Key, action = { SButton("Add a key", { app.go(Route.Settings) }, compact = true) })
                else SButton("Make a playlist from a sentence", { app.askAiOpen = true }, glyph = Glyph.Wave, tone = Tone.Secondary)
            }
        }
        if (unresolved.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Songs that need your pick")
                Txt("Soiboi could not find these on Apple Music. Choose the right song once and it remembers.", style = Soiboi.type.caption, color = c.soft)
                unresolved.take(30).forEach { t -> UnresolvedRow(app, t) }
            }
        }
        addonShelves(app, addons.filter { it.enabled && it.id != settings.homepageProvider })
    }
}

@Composable
private fun UnresolvedRow(app: AppController, t: UnresolvedTrack) {
    val c = Soiboi.colors
    var open by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().background(c.raised, Soiboi.look.shape(14)).border(1.5.dp, c.rule, Soiboi.look.shape(14)).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Txt(t.title, style = Soiboi.type.bodyStrong, maxLines = 1); Txt("${t.artist} · from ${t.playlist}", style = Soiboi.type.caption, color = c.soft, maxLines = 1) }
        SButton("Pick the song", { open = true }, compact = true)
        SButton("Skip it", { scope.launch { app.engine.manualResolution.skip(t); app.engine.discovery.forgetMatches() } }, tone = Tone.Ghost, compact = true)
    }
    if (open) PickSongDialog(app, t) { open = false }
}

@Composable
private fun PickSongDialog(app: AppController, t: UnresolvedTrack, onDismiss: () -> Unit) {
    val c = Soiboi.colors
    val engine = app.engine
    var term by remember { mutableStateOf("${t.artist} ${t.title}") }
    var results by remember { mutableStateOf<List<app.soiboi.engine.apple.AppleCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun search() { busy = true; scope.launch { results = engine.apple.searchSongs(term, engine.settings.value.storefront); busy = false } }
    SDialog("Pick the right song", onDismiss, width = 560.dp) {
        SField(term, { term = it }, "Search Apple Music", leading = Glyph.Search, onSubmit = ::search, requestFocus = true)
        SButton("Search", ::search, glyph = Glyph.Search, loading = busy)
        results?.let { rs ->
            if (rs.isEmpty()) Txt("Apple Music has nothing for that. Try fewer words.", style = Soiboi.type.body, color = c.soft)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(rs) { r ->
                    Row(Modifier.fillMaxWidth().press({ scope.launch { engine.manualResolution.resolve(t, r.url, r.artist, r.title); engine.linked.replaceTrack(t.playlist, LinkedTrack(t.artist, t.title), LinkedTrack(r.artist, r.title)); engine.discovery.forgetMatches(); app.toast("Remembered your pick"); onDismiss() } }, "${r.title} by ${r.artist}", shape = Soiboi.look.shape(10)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Txt(r.title, style = Soiboi.type.bodyStrong, maxLines = 1); Txt("${r.artist} · ${r.album ?: ""} ${r.year?.let { "($it)" } ?: ""}", style = Soiboi.type.caption, color = c.soft, maxLines = 1) }
                    }
                }
            }
        }
    }
}


/**
 * Reads one playlist and shows what Apple Music has. Matching runs a few songs at a time and the list fills as it goes.
 * Matched songs are downloaded together; the rest wait in "Songs that need your pick" instead of failing during a download.
 */
@Composable
fun ImportScreen(app: AppController, initial: String) {
    val c = Soiboi.colors
    val engine = app.engine
    var title by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableStateOf(0) }
    val tracks = remember { mutableStateListOf<DiscoveryTrack>() }
    var phase by remember { mutableStateOf(if (initial.isBlank()) 0 else 1) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<app.soiboi.engine.sources.ExternalPlaylistSource?>(null) }
    var playlistId by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun load(ref: Pair<app.soiboi.engine.sources.ExternalPlaylistSource, String>) {
        source = ref.first; playlistId = ref.second; phase = 1; error = null; tracks.clear()
        scope.launch {
            title = ref.first.titleFor(ref.second)
            val ext = ExternalPlaylist(ref.first.id, ref.second, title ?: "Playlist")
            val result = engine.discovery.resolveTracks(ext, null) { list, n -> tracks.clear(); tracks.addAll(list); total = n }
            if (result == null) { error = ref.first.lastError ?: "Nothing came back. The playlist may be private, empty, or the platform changed how it shows playlists."; phase = 3 } else { tracks.clear(); tracks.addAll(result); phase = 2 }
        }
    }

    androidx.compose.runtime.LaunchedEffect(initial) {
        when {
            initial.startsWith("lb:") -> engine.playlistSources.byId("listenbrainz")?.let { load(it to initial.removePrefix("lb:")) }
            initial.startsWith("album:") -> { phase = 1
                val (artist, album) = initial.removePrefix("album:").split('|').let { it[0] to it.getOrElse(1) { "" } }
                title = album; val found = engine.apple.resolveAlbum(artist, album, engine.settings.value.storefront)
                val list = found?.let { engine.apple.albumTracks(it.id) }.orEmpty()
                tracks.clear(); tracks.addAll(list.map { DiscoveryTrack(it.title, artist, album, found?.artwork, it.previewUrl, it.url) }); phase = if (list.isEmpty()) 3 else 2
                if (list.isEmpty()) error = "Apple Music does not list \"$album\" by $artist."
            }
            initial.isNotBlank() -> engine.playlistSources.forUrl(initial)?.let { load(it.source to it.playlistId) } ?: run { error = "Soiboi does not recognise that link."; phase = 3 }
        }
    }

    val matched = tracks.filter { it.isResolved }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item { SIconButton(Glyph.Back, "Back", { app.back() }) }
        if (phase == 0) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Txt("Paste a tracklist", style = Soiboi.type.display, maxLines = 2)
                Txt("One song per line as Artist - Title, or a CSV with Artist and Title columns and an optional ISRC. Use it for any service Soiboi cannot read.", style = Soiboi.type.body, color = c.soft)
                SField(pasted, { pasted = it }, "Tracklist", hint = "Daft Punk - One More Time", minLines = 8, singleLine = false, requestFocus = true)
                SButton("Match these songs", { engine.playlistSources.forUrl(pasted)?.let { load(it.source to it.playlistId) } ?: run { error = "Soiboi needs at least two lines it can read."; } }, tone = Tone.Primary, enabled = pasted.lines().count { it.isNotBlank() } >= 2)
                error?.let { Banner("Could not read that", it, Glyph.Warning, tint = c.danger) }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Txt(title ?: "Reading the playlist", style = Soiboi.type.display.copy(fontSize = 34.sp, lineHeight = 38.sp), maxLines = 3)
                    source?.let { Txt("From ${it.displayName}", style = Soiboi.type.caption, color = c.muted) }
                    if (phase == 1) { Loading(if (total > 0) "Matching to Apple Music, ${tracks.size} of $total" else "Reading the playlist"); if (total > 0) ProgressBar(tracks.size.toFloat() / total) }
                    if (phase == 2) Txt("${plural(matched.size, "song")} found on Apple Music${if (tracks.size > matched.size) ", ${tracks.size - matched.size} not found" else ""}.", style = Soiboi.type.body, color = c.soft)
                    error?.let { Banner("Nothing to import", it, Glyph.Warning, tint = c.danger) }
                }
            }
            if (phase == 2 && matched.isNotEmpty()) item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    SButton("Download ${plural(matched.size, "song")}", {
                        scope.launch {
                            val name = title ?: "Imported playlist"
                            val batch = engine.downloads.enqueue(matched.map { DownloadRequest(it.appleUrl!!, it.title, it.artist, name) })
                            engine.linked.link(name, tracks.map { LinkedTrack(it.artist, it.title) })
                            engine.manualResolution.addUnresolved(tracks.filter { !it.isResolved && !it.userSkipped }.map { UnresolvedTrack(it.artist, it.title, name) })
                            app.toast("Queued ${plural(matched.size, "download")}"); app.go(Route.Downloads)
                        }
                    }, tone = Tone.Primary, glyph = Glyph.Download)
                    SButton("Keep the list only", { scope.launch { val name = title ?: "Imported playlist"; engine.linked.link(name, tracks.map { LinkedTrack(it.artist, it.title) }); app.toast("Saved $name; songs join it as they arrive") } }, glyph = Glyph.Playlist)
                }
            }
            items(tracks.size) { i ->
                val t = tracks[i]
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(c.sunk, Soiboi.look.shape(8)), contentAlignment = Alignment.Center) { GlyphIcon(if (t.isResolved) Glyph.Check else Glyph.Warning, size = 20.dp, tint = if (t.isResolved) c.ok else c.muted) }
                    Column(Modifier.weight(1f)) { Txt(t.title, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(t.artist + (t.album?.let { " · $it" } ?: ""), style = Soiboi.type.caption, color = c.soft, maxLines = 1) }
                    Txt(if (t.isResolved) "On Apple Music" else if (t.userSkipped) "Skipped" else "Not found", style = Soiboi.type.caption, color = if (t.isResolved) c.ok else c.muted, maxLines = 1)
                }
            }
        }
    }
}
