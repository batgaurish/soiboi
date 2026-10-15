package app.soiboi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.soiboi.engine.library.Song
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.components.Chip
import app.soiboi.ui.components.Cover
import app.soiboi.ui.components.Hairline
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.plural
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi

private enum class RailKind(val label: String) { Playlists("Playlists"), Albums("Albums"), Artists("Artists") }

private class RailItem(val kind: RailKind, val title: String, val subtitle: String, val cover: Song?, val route: Route, val glyph: Glyph?, val pinned: Boolean = false)

/**
 * "Your library" as a list beside the content: playlists first (Liked songs on top, then pinned), then
 * smart playlists, albums and artists, narrowed by a kind chip and a filter. One click opens the page; the
 * row for what is playing says so in words.
 */
@Composable
fun LibraryRail(app: AppController, modifier: Modifier = Modifier) {
    val c = Soiboi.colors
    val engine = app.engine
    val playlists by engine.playlists.playlists.collectAsState()
    val smart by engine.smartPlaylists.collectAsState()
    val albums by engine.library.albums.collectAsState()
    val artists by engine.library.artists.collectAsState()
    val songs by engine.library.songs.collectAsState()
    val queue by engine.player.queue.collectAsState()
    var kind by remember { mutableStateOf<RailKind?>(null) }
    var filter by remember { mutableStateOf("") }

    val items = remember(playlists, smart, albums, artists, songs, kind, filter) {
        val byId = songs.associateBy { it.id }
        val out = ArrayList<RailItem>()
        val ordered = playlists.sortedWith(compareByDescending<app.soiboi.engine.library.Playlist> { it.isFavorite }.thenByDescending { it.pinned }.thenBy { it.name.lowercase() })
        ordered.forEach { p ->
            out += RailItem(
                RailKind.Playlists, if (p.isFavorite) "Liked songs" else p.name, "Playlist, ${plural(p.songIds.size, "song")}",
                p.songIds.firstNotNullOfOrNull { byId[it] }, Route.Playlist(p.name), if (p.isFavorite) Glyph.HeartFilled else null, p.pinned || p.isFavorite,
            )
        }
        smart.forEach { out += RailItem(RailKind.Playlists, it.name, "Smart playlist", null, Route.Smart(it.name), Glyph.Wave) }
        albums.forEach { a -> out += RailItem(RailKind.Albums, a.name, "Album, ${a.byArtist.keys.firstOrNull() ?: ""}".trimEnd(',', ' '), a.artworkSong, Route.Album(a.name), null) }
        artists.forEach { a -> out += RailItem(RailKind.Artists, a.name, "Artist", a.albums.firstOrNull()?.artworkSong, Route.Artist(a.name), null) }
        val q = filter.trim().lowercase()
        out.filter { (kind == null || it.kind == kind) && (q.isEmpty() || it.title.lowercase().contains(q)) }
    }

    Column(modifier.background(c.raised, Soiboi.look.shape(14)).border(1.dp, c.rule, Soiboi.look.shape(14))) {
        Row(Modifier.fillMaxWidth().padding(start = Space.m, end = Space.xs, top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Library, size = 20.dp, tint = c.soft)
            Txt("Your library", Modifier.weight(1f).padding(start = 10.dp), style = Soiboi.type.bodyStrong, maxLines = 1)
            SIconButton(Glyph.Plus, "New playlist", { app.newPlaylistOpen = true }, size = 40.dp, iconSize = 18.dp)
            SIconButton(Glyph.ChevronRight, "Open the full library", { app.go(Route.Library()) }, size = 40.dp, iconSize = 18.dp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RailKind.entries.forEach { k -> Chip(k.label, kind == k, { kind = if (kind == k) null else k }) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.m).background(c.sunk, Soiboi.look.shape(10)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlyphIcon(Glyph.Search, size = 16.dp, tint = c.soft)
            androidx.compose.foundation.text.BasicTextField(
                filter, { filter = it }, singleLine = true, modifier = Modifier.weight(1f),
                textStyle = Soiboi.type.body.copy(color = c.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                decorationBox = { inner -> Box { if (filter.isEmpty()) Txt("Filter your library", style = Soiboi.type.body, color = c.muted, maxLines = 1); inner() } },
            )
        }
        Box(Modifier.padding(top = Space.xs)) { Hairline() }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(Space.m)) {
                Txt(if (filter.isNotBlank()) "Nothing matches that filter." else "Nothing here yet.", style = Soiboi.type.body, color = c.soft)
                if (filter.isBlank() && kind == RailKind.Playlists) Txt("Make a playlist with the plus button.", style = Soiboi.type.caption, color = c.muted)
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.xs), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = Space.xs)) {
            items(items, key = { "${it.kind}:${it.title}" }) { item ->
                val here = app.route == item.route
                val nowPlaying = queue.current?.let { s ->
                    when (val r = item.route) { is Route.Album -> s.displayAlbum == r.name; is Route.Artist -> r.name in s.artists; else -> false }
                } == true
                Row(
                    Modifier.fillMaxWidth().press({ app.go(item.route) }, "${item.title}, ${item.subtitle}", shape = Soiboi.look.shape(10))
                        .background(if (here) c.sunk else androidx.compose.ui.graphics.Color.Transparent, Soiboi.look.shape(10)).padding(horizontal = Space.s, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val shape = if (item.kind == RailKind.Artists) CircleShape else Soiboi.look.shape(8)
                    if (item.cover != null) Cover(item.cover, 44.dp, shape = shape)
                    else Box(Modifier.size(44.dp).clip(shape).background(c.sunk), contentAlignment = Alignment.Center) { GlyphIcon(item.glyph ?: Glyph.Playlist, size = 20.dp, tint = if (item.glyph == Glyph.HeartFilled) c.accent else c.soft) }
                    Column(Modifier.weight(1f)) {
                        Txt(item.title, style = Soiboi.type.bodyStrong, color = if (nowPlaying) c.accent else c.ink, maxLines = 1)
                        Txt(if (nowPlaying) "Playing now" else item.subtitle, style = Soiboi.type.caption, color = c.soft, maxLines = 1)
                    }
                    if (item.pinned && !item.glyph.let { it == Glyph.HeartFilled }) GlyphIcon(Glyph.Pin, size = 14.dp, tint = c.muted)
                }
            }
        }
    }
}
