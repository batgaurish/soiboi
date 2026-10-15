package app.soiboi.ui.screens

import app.soiboi.ui.SongMenu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.library.QualityTier
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongFilter
import app.soiboi.engine.library.SongSort
import app.soiboi.engine.library.filterOptions
import app.soiboi.engine.library.filterSongs
import app.soiboi.engine.library.FAVORITE
import app.soiboi.engine.smart.SmartPlaylist
import app.soiboi.engine.smart.SmartPlaylistTemplates
import app.soiboi.ui.AppController
import app.soiboi.ui.LibraryTab
import app.soiboi.ui.Route
import app.soiboi.ui.components.AlbumTile
import app.soiboi.ui.components.ArtistTile
import app.soiboi.ui.components.Chip
import app.soiboi.ui.components.ConfirmDialog
import app.soiboi.ui.components.Cover
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.Listbox
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.plural
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch

/** Songs, albums, artists and playlists, with the search, sort and quality filters that belong to each. */
@Composable
fun LibraryScreen(app: AppController, tab: LibraryTab) {
    val c = Soiboi.colors
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = Space.l, end = Space.l, top = Space.xl, bottom = Space.s), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Library", Modifier.weight(1f), style = Soiboi.type.display)
                SIconButton(Glyph.Search, "Search everything", { app.go(Route.Search) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                LibraryTab.entries.forEach { t -> Chip(t.label, t == tab, { app.go(Route.Library(t)) }) }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                LibraryTab.Songs -> SongsTab(app)
                LibraryTab.Albums -> AlbumsTab(app)
                LibraryTab.Artists -> ArtistsTab(app)
                LibraryTab.Playlists -> PlaylistsTab(app)
            }
        }
    }
}

@Composable
private fun SongsTab(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val songs by engine.library.songs.collectAsState()
    val queue by engine.player.queue.collectAsState()
    val playlists by engine.playlists.playlists.collectAsState()
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SongSort.NewestAdded) }
    var filter by remember { mutableStateOf(SongFilter()) }
    var filterOpen by remember { mutableStateOf(false) }
    val favs = remember(playlists) { engine.playlists.favoriteIds }
    val shown = remember(songs, query, sort, filter, favs) { sort.sort(filter.apply(filterSongs(songs, query), favs)) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.Bottom) {
            SField(query, { query = it }, "Filter songs", Modifier.weight(1f), hint = "Title, artist or album", leading = Glyph.Search)
            Listbox("Sort", SongSort.entries.map { it to it.label }, sort, { sort = it }, Modifier.width(190.dp))
            Chip(if (filter.activeCount == 0) "Filter" else "Filter, ${filter.activeCount}", filter.activeCount > 0, { filterOpen = true }, glyph = Glyph.Sliders)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Txt(plural(shown.size, "song"), Modifier.weight(1f), style = Soiboi.type.caption, color = c.soft)
            SButton("Play all", { app.play(shown) }, tone = Tone.Primary, glyph = Glyph.Play, compact = true, enabled = shown.isNotEmpty())
            SButton("Shuffle", { app.play(shown, shuffle = true) }, glyph = Glyph.Shuffle, compact = true, enabled = shown.isNotEmpty())
        }
        if (songs.isEmpty()) EmptyState("No songs yet", "Add a music folder in Settings, connect a server in Sources, or download from a playlist link.", glyph = Glyph.Library) { SButton("Add a music folder", { app.go(Route.Settings) }, tone = Tone.Primary, glyph = Glyph.Folder) }
        else if (shown.isEmpty()) EmptyState("Nothing matches", "No song fits \"$query\" with the current filters.", glyph = Glyph.Search) { SButton("Clear the filters", { query = ""; filter = SongFilter() }) }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.s, vertical = Space.s)) {
            itemsIndexed(shown, key = { _, s -> s.origin.name + s.id }) { i, s -> SongRow(s, null, queue.current?.id == s.id, s.id in favs, { app.play(shown, i) }, { app.songMenu = SongMenu(s) }) }
        }
    }
    if (filterOpen) FilterDialog(songs, filter, { filter = it }) { filterOpen = false }
}

@Composable
private fun FilterDialog(songs: List<Song>, filter: SongFilter, onChange: (SongFilter) -> Unit, onDismiss: () -> Unit) {
    val (codecs, genres) = remember(songs) { filterOptions(songs) }
    SDialog("Filter songs", onDismiss, actions = { SButton("Clear all", { onChange(SongFilter()) }, tone = Tone.Ghost); SButton("Done", onDismiss, tone = Tone.Primary) }) {
        Txt("Quality", style = Soiboi.type.label, color = Soiboi.colors.soft)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Chip("Any", filter.quality == null, { onChange(filter.copy(quality = null)) })
            Chip("Lossless", filter.quality == QualityTier.Lossless, { onChange(filter.copy(quality = QualityTier.Lossless)) })
            Chip("Lossy", filter.quality == QualityTier.Lossy, { onChange(filter.copy(quality = QualityTier.Lossy)) })
        }
        Chip("Favorites only", filter.favoritesOnly, { onChange(filter.copy(favoritesOnly = !filter.favoritesOnly)) }, glyph = Glyph.Heart)
        if (codecs.isNotEmpty()) {
            Txt("Codec", style = Soiboi.type.label, color = Soiboi.colors.soft)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                codecs.forEach { (k, n) -> Chip("$k ($n)", k in filter.codecs, { onChange(filter.copy(codecs = if (k in filter.codecs) filter.codecs - k else filter.codecs + k)) }) }
            }
        }
        if (genres.isNotEmpty()) {
            Txt("Genre", style = Soiboi.type.label, color = Soiboi.colors.soft)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                genres.entries.take(24).forEach { (k, n) -> Chip("$k ($n)", k in filter.genres, { onChange(filter.copy(genres = if (k in filter.genres) filter.genres - k else filter.genres + k)) }) }
            }
        }
    }
}

@Composable
private fun AlbumsTab(app: AppController) {
    val albums by app.engine.library.albums.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(albums, query) { albums.filter { query.isBlank() || it.name.contains(query, true) || it.songs.any { s -> s.displayAlbumArtist.contains(query, true) } } }
    Column(Modifier.fillMaxSize()) {
        SField(query, { query = it }, "Filter albums", Modifier.padding(horizontal = Space.l), hint = "Album or artist", leading = Glyph.Search)
        if (albums.isEmpty()) EmptyState("No albums yet", "Albums appear once songs are in the library.", glyph = Glyph.Disc)
        else LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(shown, key = { it.name }) { a -> AlbumTile(a, { app.go(Route.Album(a.name)) }, size = 156.dp) }
        }
    }
}

@Composable
private fun ArtistsTab(app: AppController) {
    val artists by app.engine.library.artists.collectAsState()
    var query by remember { mutableStateOf("") }
    val shown = remember(artists, query) { artists.filter { query.isBlank() || it.name.contains(query, true) } }
    Column(Modifier.fillMaxSize()) {
        SField(query, { query = it }, "Filter artists", Modifier.padding(horizontal = Space.l), hint = "Artist name", leading = Glyph.Search)
        if (artists.isEmpty()) EmptyState("No artists yet", "Artists come from the tags on your songs.", glyph = Glyph.Person)
        else LazyVerticalGrid(GridCells.Adaptive(136.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(shown, key = { it.name }) { a -> ArtistTile(a, { app.go(Route.Artist(a.name)) }, size = 132.dp) }
        }
    }
}

@Composable
private fun PlaylistsTab(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val playlists by engine.playlists.playlists.collectAsState()
    val smart by engine.smartPlaylists.collectAsState()
    val songs by engine.library.songs.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var templatesOpen by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SButton("New playlist", { creating = true }, glyph = Glyph.Plus, tone = Tone.Primary)
                SButton("New smart playlist", { templatesOpen = true }, glyph = Glyph.Sliders)
                SButton("Ask AI", { app.askAiOpen = true }, glyph = Glyph.Wave)
            }
        }
        item { Txt("Your playlists", Modifier.padding(top = Space.m), style = Soiboi.type.headline) }
        items(playlists.size) { i ->
            val p = playlists[i]
            val first = p.songIds.firstNotNullOfOrNull { engine.library.song(it) }
            Row(Modifier.fillMaxWidth().press({ app.go(Route.Playlist(p.name)) }, "${p.name}, ${plural(p.songIds.size, "song")}", shape = Soiboi.look.shape(14)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Cover(first, 56.dp, shape = Soiboi.look.shape(10))
                Column(Modifier.weight(1f)) { Txt(p.name, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(plural(p.songIds.size, "song"), style = Soiboi.type.caption, color = c.soft) }
                if (p.isFavorite) GlyphIcon(Glyph.HeartFilled, size = 18.dp, tint = c.accent)
            }
        }
        item { Txt("Smart playlists", Modifier.padding(top = Space.l), style = Soiboi.type.headline) }
        if (smart.isEmpty()) item { Txt("A smart playlist follows rules, like lossless songs you have not played this year, and updates as your library changes.", style = Soiboi.type.caption, color = c.soft) }
        items(smart.size) { i ->
            val s = smart[i]
            val count = remember(s, songs) { engine.songsOf(s).size }
            Row(Modifier.fillMaxWidth().press({ app.go(Route.Smart(s.name)) }, "${s.name}, ${plural(count, "song")}, smart playlist", shape = Soiboi.look.shape(14)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).background(c.sunk, Soiboi.look.shape(10)), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Sliders, size = 24.dp, tint = c.soft) }
                Column(Modifier.weight(1f)) { Txt(s.name, style = Soiboi.type.bodyStrong, maxLines = 1); Txt("${plural(count, "song")} · ${plural(s.rules.size, "rule")}", style = Soiboi.type.caption, color = c.soft) }
            }
        }
    }
    if (creating) SDialog("New playlist", { creating = false }, actions = { SButton("Cancel", { creating = false }, tone = Tone.Ghost); SButton("Create playlist", { if (newName.isNotBlank()) { app.newPlaylistWith(null, newName); newName = ""; creating = false } }, tone = Tone.Primary, enabled = newName.isNotBlank()) }) {
        SField(newName, { newName = it }, "Name", hint = "Late night drive", requestFocus = true, onSubmit = { if (newName.isNotBlank()) { app.newPlaylistWith(null, newName); newName = ""; creating = false } })
    }
    if (templatesOpen) SDialog("Start a smart playlist", { templatesOpen = false }, width = 520.dp) {
        Txt("Pick a starting point. You can change every rule after.", style = Soiboi.type.caption, color = c.soft)
        SmartPlaylistTemplates.forEach { t ->
            Row(Modifier.fillMaxWidth().press({ templatesOpen = false; app.smartEditor = t.playlist; app.smartEditorOriginal = null }, "${t.name}, ${t.description}", shape = Soiboi.look.shape(12)).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Txt(t.name, style = Soiboi.type.bodyStrong); Txt(t.description, style = Soiboi.type.caption, color = c.soft) }
                GlyphIcon(Glyph.ChevronRight, size = 18.dp, tint = c.muted)
            }
        }
        SButton("Start from a blank playlist", { templatesOpen = false; app.smartEditor = SmartPlaylist("New smart playlist"); app.smartEditorOriginal = null }, Modifier.fillMaxWidth(), tone = Tone.Secondary)
    }
}
