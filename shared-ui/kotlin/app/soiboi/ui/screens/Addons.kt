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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.soiboi.engine.addons.AddonDetail
import app.soiboi.engine.addons.AddonException
import app.soiboi.engine.addons.AddonHit
import app.soiboi.engine.addons.AddonInstance
import app.soiboi.engine.addons.AddonManifest
import app.soiboi.engine.addons.AddonSettingDef
import app.soiboi.engine.download.DownloadRequest
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.SongMenu
import java.net.URLEncoder
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.ConfirmDialog
import app.soiboi.ui.components.Cover
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.Listbox
import app.soiboi.ui.components.Loading
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.SwitchRow
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.plural
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch

/** An addon's cover is a plain URL, so a stand-in song lets it ride the same cover loader and cache as everything else. */
@Composable
internal fun UrlCover(url: String?, size: Dp, modifier: Modifier = Modifier, shape: Shape = Soiboi.look.shape(12), description: String? = null) {
    val stand = remember(url) { url?.let { Song(id = it, origin = SongOrigin.Addon, coverId = it, title = "") } }
    Cover(stand, size, modifier, shape, description)
}

/** A square or round picture over a title and a line of detail: the tile every addon result uses, whatever its kind. */
@Composable
internal fun AddonTile(
    title: String,
    subtitle: String?,
    cover: String?,
    onClick: () -> Unit,
    round: Boolean = false,
    size: Dp = 140.dp,
    badge: String? = null,
    onDownload: (() -> Unit)? = null,
) {
    val c = Soiboi.colors
    Column(Modifier.width(size).press(onClick, listOfNotNull(title, subtitle).joinToString(", "), shape = Soiboi.look.shape(14)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(size - 8.dp)) {
            UrlCover(cover, size - 8.dp, shape = if (round) CircleShape else Soiboi.look.shape(12), description = "Picture for $title")
            if (badge != null && !round) {
                val glyph = when (badge.lowercase()) {
                    "album" -> Glyph.Disc
                    "playlist" -> Glyph.Playlist
                    "radio" -> Glyph.Wave
                    "song", "track" -> Glyph.Play
                    else -> null
                }
                EntityBadge(badge, glyph, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            if (onDownload != null && !round) {
                SIconButton(
                    glyph = Glyph.Download,
                    label = "Download $title",
                    onClick = onDownload,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    tint = c.ink,
                    size = 32.dp,
                    iconSize = 16.dp,
                    background = c.paper.copy(alpha = 0.94f),
                )
            }
        }
        Column {
            Txt(title, style = Soiboi.type.bodyStrong, maxLines = 1)
            if (subtitle != null) Txt(subtitle, style = Soiboi.type.caption, color = Soiboi.colors.soft, maxLines = 1)
        }
    }
}

private fun songs(app: AppController, addon: AddonInstance, tracks: List<app.soiboi.engine.addons.AddonTrack>): List<Song> = tracks.map { app.engine.addons.songFor(addon, it) }

// --- Sources: install, enable, configure, remove ---

@Composable
fun AddonsSection(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val installed by engine.addons.installed.collectAsState()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<String, AddonManifest>?>(null) }
    var configuring by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<AddonInstance?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().background(c.raised, Soiboi.look.shape(16)).border(1.5.dp, c.rule, Soiboi.look.shape(16)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Txt("Add an addon", style = Soiboi.type.title)
        Txt("An addon is a small server that adds search, streams and shelves. Paste its address, the part before /manifest.json. Soiboi uses the same protocol as Eclipse, so Eclipse addons work here.", style = Soiboi.type.caption, color = c.soft)
        SField(url, { url = it; error = null }, "Addon address", hint = "https://addon.example.org", leading = Glyph.Link, onSubmit = {
            if (url.isNotBlank() && !busy) { busy = true; scope.launch { try { preview = url.trim() to engine.addons.preview(url).first } catch (e: AddonException) { error = e.message } finally { busy = false } } }
        })
        error?.let { Banner("Could not read that addon", it, Glyph.Warning, tint = c.danger) }
        SButton("Look it up", {
            busy = true
            scope.launch { try { preview = url.trim() to engine.addons.preview(url).first } catch (e: AddonException) { error = e.message } finally { busy = false } }
        }, compact = true, glyph = Glyph.Search, loading = busy, enabled = url.isNotBlank())
    }
    installed.forEach { a ->
        Column(Modifier.fillMaxWidth().padding(top = Space.m).background(c.raised, Soiboi.look.shape(16)).border(1.5.dp, c.rule, Soiboi.look.shape(16)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                UrlCover(a.manifest.icon, 44.dp, shape = Soiboi.look.shape(10))
                Column(Modifier.weight(1f)) {
                    Txt(a.manifest.name, style = Soiboi.type.title, maxLines = 1)
                    Txt(if (a.builtin != null) "Built in" else "Version ${a.manifest.version}", style = Soiboi.type.caption, color = c.muted)
                }
            }
            if (a.manifest.description.isNotBlank()) Txt(a.manifest.description, style = Soiboi.type.caption, color = c.soft, maxLines = 3)
            Txt("Offers: ${abilities(a.manifest)}", style = Soiboi.type.caption, color = c.soft)
            SwitchRow(if (a.builtin != null) "Use this source" else "Use this addon", a.enabled, { v -> scope.launch { engine.addons.setEnabled(a.id, v) } }, detail = if (a.builtin != null) null else a.url)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (a.manifest.settings.isNotEmpty()) SButton("Settings", { configuring = a.id }, compact = true, glyph = Glyph.Sliders)
                if (a.builtin == null) SButton("Remove", { removing = a }, tone = Tone.Ghost, compact = true)
            }
        }
    }
    preview?.let { (address, m) ->
        SDialog("Install ${m.name}?", { preview = null }, width = 520.dp, actions = {
            SButton("Cancel", { preview = null }, tone = Tone.Ghost)
            SButton("Install", { scope.launch { try { engine.addons.install(address); app.toast("Installed ${m.name}"); url = "" } catch (e: AddonException) { app.toast(e.message ?: "Could not install") }; preview = null } }, tone = Tone.Primary)
        }) {
            Txt("Version ${m.version}${if (m.contentType != "music") ", ${m.contentType}" else ""}", style = Soiboi.type.caption, color = c.muted)
            if (m.description.isNotBlank()) Txt(m.description, style = Soiboi.type.body)
            Txt("Offers: ${abilities(m)}", style = Soiboi.type.body, color = c.soft)
            Banner("Only add addons you trust", "An addon sees what you search for, and the stream addresses it returns are played as they come.", Glyph.Info)
        }
    }
    configuring?.let { id -> engine.addons.instance(id)?.let { AddonSettingsDialog(app, it) { configuring = null } } }
    removing?.let { a -> ConfirmDialog("Remove ${a.manifest.name}?", "Songs you saved from it stay in your library but may stop playing.", "Remove", { scope.launch { engine.addons.remove(a.id) } }, { removing = null }) }
}

private fun abilities(m: AddonManifest): String = buildList {
    if (m.canSearch) add("search"); if (m.canStream) add("streams")
    if (m.hasCatalogs) add(plural(m.catalogs.size, "shelf", "shelves"))
    if ("catalog" in m.resources && !m.hasCatalogs) add("album and artist pages")
    if ("isrc" in m.resources || "resolve" in m.resources) add("exact matches")
}.ifEmpty { listOf("nothing yet") }.joinToString(", ")

@Composable
private fun AddonSettingsDialog(app: AppController, addon: AddonInstance, onDismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val live by app.engine.addons.installed.collectAsState()
    val current = live.firstOrNull { it.id == addon.id } ?: addon
    SDialog("${addon.manifest.name} settings", onDismiss, width = 480.dp, actions = { SButton("Done", onDismiss, tone = Tone.Primary) }) {
        current.manifest.settings.forEach { def -> AddonSetting(def, current.setting(def)) { v -> scope.launch { app.engine.addons.setSetting(addon.id, def.key, v) } } }
    }
}

@Composable
private fun AddonSetting(def: AddonSettingDef, value: String, onChange: (String) -> Unit) {
    when {
        def.type == "toggle" -> SwitchRow(def.label, value == "true", { onChange(it.toString()) })
        def.type == "select" && def.options.isNotEmpty() -> Listbox(def.label, def.options, value.takeIf { v -> def.options.any { it.first == v } }, onChange)
        else -> {
            var text by remember(def.key) { mutableStateOf(value) }
            SField(text, { text = it; onChange(it) }, def.label, keyboardType = if (def.type == "number") androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text)
        }
    }
}

// --- Search: what the addons found ---

fun androidx.compose.foundation.lazy.LazyListScope.addonResults(app: AppController, hits: List<AddonHit>, favs: Set<String>, playingId: String?) {
    for (hit in hits) {
        val addon = hit.addon; val r = hit.result
        if (hit.error != null) { item { Txt("${addon.manifest.name}: ${hit.error}", style = Soiboi.type.caption, color = Soiboi.colors.muted) }; continue }
        if (r == null || r.isEmpty) continue
        item { Txt("From ${addon.manifest.name}", style = Soiboi.type.headline) }
        val list = songs(app, addon, r.tracks.take(8))
        items(list.size) { i -> val s = list[i]; SongRow(s, null, playingId == s.id, s.id in favs, { app.play(list, i) }, { app.songMenu = SongMenu(s) }, showAlbum = true) }
        if (r.artists.isNotEmpty()) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) { items(r.artists.take(12), key = { it.id }) { a -> AddonTile(a.name, "Artist", a.artworkUrl, { app.go(Route.AddonItem(addon.id, "artist", a.id, a.name, a.artworkUrl)) }, round = true, size = 120.dp) } } }
        if (r.albums.isNotEmpty()) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) { items(r.albums.take(12), key = { it.id }) { a -> AddonTile(a.title, a.artist, a.artworkUrl, { app.go(Route.StreamingAlbum(addon.id, a.id, a.title, a.artist, a.artworkUrl)) }, badge = "Album") } } }
        if (r.playlists.isNotEmpty()) item { LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) { items(r.playlists.take(12), key = { it.id }) { p -> AddonTile(p.title, "Playlist", p.artworkUrl, { app.go(Route.StreamingPlaylist(addon.id, p.id, p.title, "Playlist", p.artworkUrl)) }, badge = "Playlist") } } }
    }
}

// --- An album, artist or playlist from an addon ---

@Composable
fun AddonItemScreen(app: AppController, r: Route.AddonItem) {
    val c = Soiboi.colors
    val addon = app.engine.addons.instance(r.addonId)
    val queue by app.engine.player.queue.collectAsState()
    val result by produceState<Result<AddonDetail>?>(null, r) {
        value = if (addon == null) Result.failure(AddonException("That addon was removed.")) else runCatching {
            when (r.kind) { "album" -> addon.client.album(r.id); "artist" -> addon.client.artist(r.id); else -> addon.client.playlist(r.id) }
        }
    }
    val favs = app.engine.playlists.favoriteIds
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { SIconButton(Glyph.Back, "Back", { app.back() }); Txt(addon?.manifest?.name ?: "Addon", Modifier.padding(start = Space.s), style = Soiboi.type.caption, color = c.muted) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Bottom) {
                UrlCover(result?.getOrNull()?.artworkUrl ?: r.cover, 160.dp, shape = if (r.kind == "artist") CircleShape else Soiboi.look.shape(18), description = "Picture for ${r.title}")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Txt(r.kind.replaceFirstChar { it.uppercase() }, style = Soiboi.type.label, color = c.soft)
                    Txt(result?.getOrNull()?.title ?: r.title, style = Soiboi.type.display, maxLines = 3)
                }
            }
        }
        val d = result?.getOrNull()
        when {
            result == null -> item { Loading("Asking ${addon?.manifest?.name ?: "the addon"}") }
            result?.isFailure == true -> item { Banner("Could not load this", result?.exceptionOrNull()?.message ?: "The addon did not answer.", Glyph.Warning, tint = c.danger) }
            d != null && addon != null -> {
                val list = songs(app, addon, d.tracks)
                if (list.isNotEmpty()) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        SButton("Play", { app.play(list) }, tone = Tone.Primary, glyph = Glyph.Play)
                        SButton("Shuffle", { app.play(list, shuffle = true) }, glyph = Glyph.Shuffle)
                        SButton("Download ${r.kind.replaceFirstChar { it.uppercase() }}", {
                            val reqs = list.map { s ->
                                val tid = s.id.removePrefix("addon:${addon.id}:")
                                DownloadRequest(
                                    url = "session://${addon.id}/$tid?title=${URLEncoder.encode(s.displayTitle, "UTF-8")}&artist=${URLEncoder.encode(s.displayArtist, "UTF-8")}",
                                    label = s.displayTitle,
                                    subtitle = s.displayArtist,
                                    group = r.title,
                                )
                            }
                            app.engine.downloads.enqueue(reqs)
                            app.toast("Downloading ${r.kind}: ${r.title}")
                        }, glyph = Glyph.Download)
                    }
                }
                if (list.isEmpty() && d.albums.isEmpty()) item { EmptyState("Nothing here", "The addon has no tracks for this ${r.kind}.", glyph = Glyph.Disc) }
                items(list.size) { i ->
                    val s = list[i]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            SongRow(s, i + 1, queue.current?.id == s.id, s.id in favs, { app.play(list, i) }, { app.songMenu = SongMenu(s) })
                        }
                        SIconButton(
                            glyph = Glyph.Download,
                            label = "Download ${s.displayTitle}",
                            onClick = {
                                val tid = s.id.removePrefix("addon:${addon.id}:")
                                val req = DownloadRequest(
                                    url = "session://${addon.id}/$tid?title=${URLEncoder.encode(s.displayTitle, "UTF-8")}&artist=${URLEncoder.encode(s.displayArtist, "UTF-8")}",
                                    label = s.displayTitle,
                                    subtitle = s.displayArtist,
                                    group = r.title,
                                )
                                app.engine.downloads.enqueue(listOf(req))
                                app.toast("Downloading ${s.displayTitle}")
                            },
                            size = 36.dp,
                            iconSize = 16.dp,
                            tint = c.soft,
                        )
                    }
                }
                if (d.albums.isNotEmpty()) {
                    item { Txt("Albums", style = Soiboi.type.headline) }
                    item { LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) { items(d.albums, key = { it.id }) { a -> AddonTile(a.title, a.artist, a.artworkUrl, { app.go(Route.StreamingAlbum(addon.id, a.id, a.title, a.artist, a.artworkUrl)) }, badge = "Album") } } }
                }
            }
        }
    }
}

// --- Discover: the shelves an addon declares ---

fun androidx.compose.foundation.lazy.LazyListScope.addonShelves(app: AppController, addons: List<AddonInstance>) {
    for (addon in addons) {
        if (!addon.enabled) continue
        for (catalog in addon.manifest.catalogs.take(4)) item(key = "shelf:${addon.id}:${catalog.id}") { AddonShelf(app, addon, catalog.id, catalog.name) }
    }
}

@Composable
private fun AddonShelf(app: AppController, addon: AddonInstance, catalogId: String, name: String) {
    val c = Soiboi.colors
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val page by produceState<Result<app.soiboi.engine.addons.AddonCatalogPage>?>(null, addon.id, catalogId, addon.settings) { value = runCatching { addon.client.catalog(catalogId) } }
    val result = page
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Column { Txt(name, style = Soiboi.type.headline); Txt("From ${addon.manifest.name}", style = Soiboi.type.caption, color = c.muted) }
        when {
            result == null -> Loading("Loading $name")
            result.isFailure -> Txt(result.exceptionOrNull()?.message ?: "This shelf did not load.", style = Soiboi.type.caption, color = c.muted)
            else -> {
                val p = result.getOrThrow()
                val list = songs(app, addon, p.tracks)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(list.size) { i ->
                        val s = list[i]
                        val trackId = s.id.removePrefix("addon:${addon.id}:")
                        AddonTile(
                            title = s.displayTitle,
                            subtitle = s.displayArtist,
                            cover = s.coverId,
                            badge = "Song",
                            onClick = { app.go(Route.StreamingSong(addon.id, trackId, s.displayTitle, s.displayArtist, s.album, s.coverId)) },
                            onDownload = {
                                val req = DownloadRequest(
                                    url = "session://${addon.id}/$trackId?title=${URLEncoder.encode(s.displayTitle, "UTF-8")}&artist=${URLEncoder.encode(s.displayArtist, "UTF-8")}",
                                    label = s.displayTitle,
                                    subtitle = s.displayArtist,
                                    group = s.album,
                                )
                                app.engine.downloads.enqueue(listOf(req))
                                app.toast("Downloading ${s.displayTitle}")
                            },
                        )
                    }
                    items(p.albums.size) { i ->
                        val a = p.albums[i]
                        AddonTile(
                            title = a.title,
                            subtitle = a.artist,
                            cover = a.artworkUrl,
                            badge = "Album",
                            onClick = { app.go(Route.StreamingAlbum(addon.id, a.id, a.title, a.artist, a.artworkUrl)) },
                            onDownload = {
                                scope.launch {
                                    val detail = runCatching { addon.client.album(a.id) }.getOrNull()
                                    if (detail != null && detail.tracks.isNotEmpty()) {
                                        val reqs = detail.tracks.map { t ->
                                            DownloadRequest(
                                                url = "session://${addon.id}/${t.id}?title=${URLEncoder.encode(t.title, "UTF-8")}&artist=${URLEncoder.encode(t.artist ?: a.artist ?: "", "UTF-8")}",
                                                label = t.title,
                                                subtitle = t.artist ?: a.artist,
                                                group = a.title,
                                            )
                                        }
                                        app.engine.downloads.enqueue(reqs)
                                        app.toast("Downloading album: ${a.title}")
                                    } else {
                                        app.toast("Could not read album tracks")
                                    }
                                }
                            },
                        )
                    }
                    items(p.playlists.size) { i ->
                        val pl = p.playlists[i]
                        val isRadio = pl.id.startsWith("RDAMVM") || pl.title.contains("Radio", ignoreCase = true) || pl.title.contains("Mix", ignoreCase = true)
                        AddonTile(
                            title = pl.title,
                            subtitle = if (isRadio) "Radio mix" else "Playlist",
                            cover = pl.artworkUrl,
                            badge = if (isRadio) "Radio" else "Playlist",
                            onClick = {
                                if (isRadio) {
                                    app.go(Route.StreamingRadio(addon.id, pl.id, pl.title, "Radio mix", pl.artworkUrl))
                                } else {
                                    app.go(Route.StreamingPlaylist(addon.id, pl.id, pl.title, "Playlist", pl.artworkUrl))
                                }
                            },
                            onDownload = {
                                scope.launch {
                                    val detail = runCatching { addon.client.playlist(pl.id) }.getOrNull()
                                    if (detail != null && detail.tracks.isNotEmpty()) {
                                        val reqs = detail.tracks.map { t ->
                                            DownloadRequest(
                                                url = "session://${addon.id}/${t.id}?title=${URLEncoder.encode(t.title, "UTF-8")}&artist=${URLEncoder.encode(t.artist ?: "", "UTF-8")}",
                                                label = t.title,
                                                subtitle = t.artist,
                                                group = pl.title,
                                            )
                                        }
                                        app.engine.downloads.enqueue(reqs)
                                        app.toast("Downloading playlist: ${pl.title}")
                                    } else {
                                        app.toast("Could not read playlist tracks")
                                    }
                                }
                            },
                        )
                    }
                    items(p.artists.size) { i ->
                        val a = p.artists[i]
                        AddonTile(
                            title = a.name,
                            subtitle = "Artist",
                            cover = a.artworkUrl,
                            onClick = { app.go(Route.AddonItem(addon.id, "artist", a.id, a.name, a.artworkUrl)) },
                            round = true,
                        )
                    }
                }
                if (list.isEmpty() && p.albums.isEmpty() && p.playlists.isEmpty() && p.artists.isEmpty()) Txt("Nothing on this shelf right now.", style = Soiboi.type.caption, color = c.muted)
            }
        }
    }
}
