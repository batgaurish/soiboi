package app.soiboi.ui.screens.detail

import app.soiboi.ui.SongMenu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soiboi.engine.library.Song
import app.soiboi.engine.smart.SmartContext
import app.soiboi.engine.smart.autoMoodPlaylists
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.components.AlbumTile
import app.soiboi.ui.components.ConfirmDialog
import app.soiboi.ui.components.Cover
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SDialog
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.leadShadow
import app.soiboi.ui.components.plural
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch

/** The head of every list page: back, a lead cover, a name, facts, and the play actions. */
@Composable
private fun DetailHeader(app: AppController, title: String, subtitle: String, cover: Song?, songs: List<Song>, extra: @Composable () -> Unit = {}) {
    val c = Soiboi.colors
    Column(Modifier.padding(horizontal = Space.l, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        SIconButton(Glyph.Back, "Back", { app.back() })
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.leadShadow(Soiboi.look.shape(20))) { Cover(cover, 168.dp, shape = Soiboi.look.shape(20), contentDescription = "Cover of $title") }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt(title, style = Soiboi.type.display.copy(fontSize = 36.sp, lineHeight = 40.sp), maxLines = 3)
                Txt(subtitle, style = Soiboi.type.body, color = c.soft, maxLines = 2)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            SButton("Play", { app.play(songs) }, tone = Tone.Primary, glyph = Glyph.Play, enabled = songs.isNotEmpty())
            SButton("Shuffle", { app.play(songs, shuffle = true) }, glyph = Glyph.Shuffle, enabled = songs.isNotEmpty())
            extra()
        }
    }
}


@Composable
private fun SongList(app: AppController, songs: List<Song>, header: @Composable () -> Unit, numbered: Boolean = false, inPlaylist: String? = null, empty: @Composable () -> Unit) {
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val playlists by engine.playlists.playlists.collectAsState()
    val favs = remember(playlists) { engine.playlists.favoriteIds }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
        item { header() }
        if (songs.isEmpty()) item { empty() }
        itemsIndexed(songs, key = { i, s -> "$i:${s.origin}${s.id}" }) { i, s ->
            Box(Modifier.padding(horizontal = Space.s)) { SongRow(s, if (numbered) i + 1 else null, queue.current?.id == s.id, s.id in favs, { app.play(songs, i) }, { app.songMenu = SongMenu(s, inPlaylist) }) }
        }
    }
}

@Composable
fun AlbumScreen(app: AppController, name: String) {
    val albums by app.engine.library.albums.collectAsState()
    val album = albums.firstOrNull { it.name == name }
    if (album == null) { Column(Modifier.padding(Space.l)) { SIconButton(Glyph.Back, "Back", { app.back() }); EmptyState("That album is gone", "It was removed from the library.") }; return }
    val artist = album.songs.firstOrNull()?.displayAlbumArtist ?: ""
    val totalMs = album.songs.sumOf { it.durationMs ?: 0L }
    val total = if (totalMs < 60_000) "${totalMs / 1000} sec" else "${totalMs / 60_000} min"
    SongList(app, album.songs, header = {
        DetailHeader(app, album.name, "$artist · ${album.year ?: "Year unknown"} · ${plural(album.songs.size, "song")}, $total", album.artworkSong, album.songs) {
            SButton(artist, { app.go(Route.Artist(album.songs.first().artists.first())) }, tone = Tone.Ghost, glyph = Glyph.Person)
        }
    }, numbered = true) { }
}

@Composable
fun ArtistScreen(app: AppController, name: String) {
    val artists by app.engine.library.artists.collectAsState()
    val artist = artists.firstOrNull { it.name == name }
    if (artist == null) { Column(Modifier.padding(Space.l)) { SIconButton(Glyph.Back, "Back", { app.back() }); EmptyState("That artist is gone", "No songs by them are left in the library.") }; return }
    val songs = artist.songs
    SongList(app, songs, header = {
        Column {
            DetailHeader(app, artist.name, "${plural(artist.albums.size, "album")} · ${plural(songs.size, "song")}", artist.albums.firstOrNull()?.artworkSong, songs)
            if (artist.albums.isNotEmpty()) Column(Modifier.padding(bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt("Albums", Modifier.padding(horizontal = Space.l), style = Soiboi.type.headline)
                LazyRow(contentPadding = PaddingValues(horizontal = Space.m)) { items(artist.albums.size) { i -> AlbumTile(artist.albums[i], { app.go(Route.Album(artist.albums[i].name)) }) } }
                Txt("All songs", Modifier.padding(horizontal = Space.l, vertical = Space.s), style = Soiboi.type.headline)
            }
        }
    }) { }
}

@Composable
fun PlaylistScreen(app: AppController, name: String) {
    val engine = app.engine
    val playlists by engine.playlists.playlists.collectAsState()
    val songsAll by engine.library.songs.collectAsState()
    val playlist = playlists.firstOrNull { it.name == name }
    if (playlist == null) { Column(Modifier.padding(Space.l)) { SIconButton(Glyph.Back, "Back", { app.back() }); EmptyState("That playlist is gone", "It was deleted.") }; return }
    val songs = remember(playlist, songsAll) { playlist.songIds.mapNotNull { engine.library.song(it) } }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(name) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SongList(app, songs, header = {
        DetailHeader(app, playlist.name, plural(songs.size, "song") + if (playlist.songIds.size > songs.size) ", ${playlist.songIds.size - songs.size} not in the library yet" else "", songs.firstOrNull(), songs) {
            if (!playlist.isFavorite) {
                SIconButton(Glyph.Pin, if (playlist.pinned) "Unpin" else "Pin", { scope.launch { engine.playlists.setPinned(name, !playlist.pinned) } }, tint = if (playlist.pinned) Soiboi.colors.accent else Soiboi.colors.ink)
                SIconButton(Glyph.Edit, "Rename", { rename = true })
                SIconButton(Glyph.Trash, "Delete playlist", { delete = true })
            }
        }
    }, inPlaylist = name) { EmptyState("This playlist is empty", "Open the menu on any song and choose Add to playlist.", glyph = Glyph.Playlist) }
    if (rename) SDialog("Rename playlist", { rename = false }, actions = { SButton("Cancel", { rename = false }, tone = Tone.Ghost); SButton("Rename playlist", { scope.launch { if (engine.playlists.rename(name, newName.trim())) { app.stack[app.stack.lastIndex] = Route.Playlist(newName.trim()) } else app.toast("That name is taken") }; rename = false }, tone = Tone.Primary, enabled = newName.isNotBlank()) }) { SField(newName, { newName = it }, "Name", requestFocus = true) }
    if (delete) ConfirmDialog("Delete $name?", "The playlist is removed. The songs stay in your library.", "Delete $name", { scope.launch { engine.playlists.delete(name); app.back() } }, { delete = false })
}

@Composable
fun SmartScreen(app: AppController, name: String) {
    val engine = app.engine
    val smart by engine.smartPlaylists.collectAsState()
    val songsAll by engine.library.songs.collectAsState()
    val tagVersion by engine.tags.version.collectAsState()
    val spec = smart.firstOrNull { it.name == name }
    if (spec == null) { Column(Modifier.padding(Space.l)) { SIconButton(Glyph.Back, "Back", { app.back() }); EmptyState("That smart playlist is gone", "It was deleted.") }; return }
    val songs = remember(spec, songsAll, tagVersion) { engine.songsOf(spec) }
    var delete by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SongList(app, songs, header = {
        DetailHeader(app, spec.name, "Follows ${plural(spec.rules.size, "rule")} · ${plural(songs.size, "song")}", songs.firstOrNull(), songs) {
            SButton("Edit rules", { app.smartEditor = spec; app.smartEditorOriginal = spec.name }, glyph = Glyph.Edit)
            SButton("Save as playlist", { scope.launch { val n = engine.playlists.freeName(spec.name); engine.playlists.create(n); engine.playlists.setSongs(n, songs.map { it.id }); app.toast("Saved as $n") } }, tone = Tone.Ghost)
            SIconButton(Glyph.Trash, "Delete smart playlist", { delete = true })
        }
    }) { EmptyState("Nothing matches these rules", "Songs without analysed audio never match an energy or tempo rule. Run the analysis in Settings, or loosen a rule.", glyph = Glyph.Sliders) }
    if (delete) ConfirmDialog("Delete ${spec.name}?", "Only the rules are removed. No songs are touched.", "Delete ${spec.name}", { scope.launch { engine.removeSmart(name); app.back() } }, { delete = false })
}

@Composable
fun MoodScreen(app: AppController, name: String) {
    val engine = app.engine
    val songsAll by engine.library.songs.collectAsState()
    val moods = remember(songsAll) { autoMoodPlaylists(songsAll, engine.tags::of) }
    val mood = moods.firstOrNull { it.name == name }
    val songs = remember(mood, songsAll) { mood?.playlist?.evaluate(songsAll, SmartContext(vibeOf = engine.tags::of)).orEmpty() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SongList(app, songs, header = {
        DetailHeader(app, name, "Made from your library · ${plural(songs.size, "song")}", songs.firstOrNull(), songs) {
            SButton("Save as playlist", { scope.launch { val n = engine.playlists.freeName(name); engine.playlists.create(n); engine.playlists.setSongs(n, songs.map { it.id }); app.toast("Saved as $n") } }, tone = Tone.Ghost, enabled = songs.isNotEmpty())
        }
    }) { EmptyState("This mood has too few songs right now", "Mood playlists need at least five matching songs.", glyph = Glyph.Wave) }
}
