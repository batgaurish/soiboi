package app.soiboi.ui.screens

import app.soiboi.ui.SongMenu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.soiboi.engine.library.PlaylistKind
import app.soiboi.engine.library.artistSubtitle
import app.soiboi.engine.library.globalSearch
import app.soiboi.engine.smart.autoMoodPlaylists
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.components.AlbumTile
import app.soiboi.ui.components.ArtistTile
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.SField
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.plural
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import androidx.compose.ui.unit.dp

/** One box over songs, albums, artists and playlists. Each section is capped, so a one-letter search is still readable. */
@Composable
fun SearchScreen(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val songs by engine.library.songs.collectAsState()
    val albums by engine.library.albums.collectAsState()
    val artists by engine.library.artists.collectAsState()
    val playlists by engine.playlists.playlists.collectAsState()
    val smart by engine.smartPlaylists.collectAsState()
    val queue by engine.player.queue.collectAsState()
    var query by remember { mutableStateOf("") }
    val results = remember(query, songs, albums, artists, playlists, smart) {
        globalSearch(query, songs, albums, artists, playlists, smart, autoMoodPlaylists(songs, engine.tags::of), engine.smartContext)
    }
    val favs = remember(playlists) { engine.playlists.favoriteIds }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        item {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SIconButton(Glyph.Back, "Back", { app.back() })
                SField(query, { query = it }, "Search your library", Modifier.weight(1f), hint = "A song, an artist, an album or a playlist", leading = Glyph.Search, requestFocus = true)
            }
        }
        if (query.isBlank()) item { EmptyState("Search everything at once", "Type a name. Songs, albums, artists and playlists all answer. Press Control and K anywhere to come back here.", glyph = Glyph.Search) }
        else if (results.isEmpty) item { EmptyState("Nothing for \"${results.query}\"", "Check the spelling, or paste a streaming link in Discover to bring new music in.", glyph = Glyph.Search) }
        if (results.songs.isNotEmpty()) {
            item { Txt("Songs", style = Soiboi.type.headline) }
            items(results.songs.size) { i -> val s = results.songs[i]; SongRow(s, null, queue.current?.id == s.id, s.id in favs, { app.play(results.songs, i) }, { app.songMenu = SongMenu(s) }) }
        }
        if (results.artists.isNotEmpty()) {
            item { Txt("Artists", style = Soiboi.type.headline) }
            item { LazyRow { items(results.artists.size) { i -> ArtistTile(results.artists[i], { app.go(Route.Artist(results.artists[i].name)) }) } } }
        }
        if (results.albums.isNotEmpty()) {
            item { Txt("Albums", style = Soiboi.type.headline) }
            item { LazyRow { items(results.albums.size) { i -> AlbumTile(results.albums[i], { app.go(Route.Album(results.albums[i].name)) }) } } }
        }
        if (results.playlists.isNotEmpty()) {
            item { Txt("Playlists", style = Soiboi.type.headline) }
            items(results.playlists.size) { i ->
                val h = results.playlists[i]
                Row(Modifier.fillMaxWidth().press({ app.go(when (h.kind) { PlaylistKind.Saved -> Route.Playlist(h.name); PlaylistKind.Smart -> Route.Smart(h.name); PlaylistKind.Mood -> Route.Mood(h.name) }) }, "${h.name}, playlist", shape = Soiboi.look.shape(12)).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(if (h.kind == PlaylistKind.Smart) Glyph.Sliders else Glyph.Playlist, size = 22.dp, tint = c.soft)
                    Column(Modifier.weight(1f)) { Txt(h.name, style = Soiboi.type.bodyStrong); Txt("${plural(h.trackCount, "song")} · ${when (h.kind) { PlaylistKind.Saved -> "Playlist"; PlaylistKind.Smart -> "Smart playlist"; PlaylistKind.Mood -> "Made from your library" }}", style = Soiboi.type.caption, color = c.soft) }
                }
            }
        }
    }
}

