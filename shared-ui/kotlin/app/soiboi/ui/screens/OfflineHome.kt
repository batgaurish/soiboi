package app.soiboi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.ai.AiAlbumPick
import app.soiboi.engine.ai.AiConfig
import app.soiboi.engine.library.Album
import app.soiboi.engine.library.QualityInfo
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.recentlyAddedAlbums
import app.soiboi.engine.library.recentlyPlayed
import app.soiboi.engine.library.topArtists
import app.soiboi.engine.smart.MoodCardData
import app.soiboi.engine.smart.autoMoodPlaylists
import app.soiboi.engine.smart.moodsNeedAnalysis
import app.soiboi.engine.sources.ExternalPlaylist
import app.soiboi.ui.AppController
import app.soiboi.ui.LibraryTab
import app.soiboi.ui.Route
import app.soiboi.ui.SongMenu
import app.soiboi.ui.components.AlbumTile
import app.soiboi.ui.components.ArtistTile
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.Cover
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.Loading
import app.soiboi.ui.components.PlayButton
import app.soiboi.ui.components.QualitySticker
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SectionTitle
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.leadShadow
import app.soiboi.ui.components.plural
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private fun greeting(): String = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Still up" }

@Composable
internal fun OfflineHomeScreen(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val songs by engine.library.songs.collectAsState()
    val albums by engine.library.albums.collectAsState()
    val artists by engine.library.artists.collectAsState()
    val queue by engine.player.queue.collectAsState()
    val tagVersion by engine.tags.version.collectAsState()
    val aiConfig by engine.aiConfig.collectAsState()
    val settings by engine.settings.state.collectAsState()
    val scanning by engine.scanning.collectAsState()
    val scanned by engine.scanProgress.collectAsState()

    val moods = remember(songs, tagVersion) { autoMoodPlaylists(songs, engine.tags::of) }
    val needsAnalysis = remember(songs, tagVersion) { moodsNeedAnalysis(songs, engine.tags::of) }
    val recentAlbums = remember(albums) { recentlyAddedAlbums(albums) }
    val topArtistsList = remember(artists) { topArtists(artists) }
    val recent = remember(songs) { recentlyPlayed(songs).take(6) }
    val discoveries by produceState<List<ExternalPlaylist>?>(null, settings.listenBrainzUser) {
        value = if (settings.listenBrainzUser.isBlank()) emptyList() else runCatching { engine.playlistSources.byId("listenbrainz")?.playlists().orEmpty() }.getOrDefault(emptyList())
    }
    val picks = remember { PicksState() }
    val scope = rememberCoroutineScope()
    val leadSong: Song? = queue.current ?: recent.firstOrNull() ?: songs.maxByOrNull { it.modified ?: Instant.EPOCH }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.xxl)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt(greeting(), style = Soiboi.type.display, maxLines = 2)
                Txt("${plural(songs.size, "song")} · ${plural(albums.size, "album")} · ${plural(artists.size, "artist")}", style = Soiboi.type.body, color = c.soft)
            }
        }
        if (scanning) item { Loading(if (scanned > 0) "Reading your music, $scanned files so far" else "Looking through your music folders") }
        if (leadSong != null) item { LeadCard(app, leadSong, resuming = queue.current != null) }
        if (songs.isEmpty()) item { EmptyLibrary(app) }
        if (moods.isNotEmpty()) item { MoodShelf(app, moods) }
        else if (needsAnalysis) item {
            Banner("Mood playlists need a first listen", "Soiboi measures energy and tempo from the audio, or reads moods an AI wrote once. Run the analysis in Settings to fill this shelf.", Glyph.Wave, modifier = Modifier.press({ app.go(Route.Settings) }, "Open settings", shape = Soiboi.look.shape(12)))
        }
        if (recentAlbums.isNotEmpty()) item { NewestAlbums(app, recentAlbums) }
        if (!discoveries.isNullOrEmpty()) item { WeeklyDiscoveries(app, settings.listenBrainzUser, discoveries!!) }
        if (topArtistsList.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Most played artists")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) { items(topArtistsList.size) { i -> ArtistTile(topArtistsList[i], { app.go(Route.Artist(topArtistsList[i].name)) }) } }
            }
        }
        aiConfig?.let { config -> item { AlbumPicks(app, config, picks, scope) } }
        if (recent.size > 1) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                SectionTitle("Played lately")
                recent.forEachIndexed { i, s ->
                    SongRow(s, null, queue.current?.id == s.id, s.id in engine.playlists.favoriteIds, { app.play(recent, i) }, { app.songMenu = SongMenu(s) })
                }
            }
        }
        item { Spacer(Modifier.height(Space.l)) }
    }
}

@Composable
private fun LeadCard(app: AppController, song: Song, resuming: Boolean) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val state by engine.player.state.collectAsState()
    val isCurrent = queue.current?.id == song.id
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 640.dp
        val shape = Soiboi.look.shape(26)
        Row(Modifier.fillMaxWidth().leadShadow(shape).background(c.raised, shape).padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Cover(song, if (stacked) 112.dp else 168.dp, shape = Soiboi.look.shape(18), contentDescription = "Cover of ${song.displayAlbum}")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt(if (resuming) "Pick up where you left off" else "Newest in your library", style = Soiboi.type.label, color = c.muted)
                Txt(song.displayTitle, style = Soiboi.type.headline, maxLines = 2)
                Txt("${song.displayArtist} · ${song.displayAlbum}", style = Soiboi.type.body, color = c.soft, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    PlayButton(state.playing && isCurrent, { if (isCurrent) engine.player.togglePlay() else app.play(listOf(song)) }, size = 52.dp)
                    QualitySticker(QualityInfo.of(song), large = true)
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(app: AppController) {
    EmptyState("Your library is empty", "Add a folder of music, connect a server, or paste a playlist link and Soiboi downloads it.", glyph = Glyph.Folder) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SButton("Add a music folder", { app.go(Route.Sources) }, tone = Tone.Primary, glyph = Glyph.Folder)
            SButton("Paste a playlist link", { app.go(Route.Discover) }, glyph = Glyph.Link)
        }
    }
}

@Composable
private fun MoodShelf(app: AppController, moods: List<MoodCardData>) {
    val c = Soiboi.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        SectionTitle("Made from your library")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            moods.forEachIndexed { i, m ->
                val shape = Soiboi.look.shape(20)
                val lead = i == 0
                Column(Modifier.weight(if (lead) 1.35f else 1f).press({ app.go(Route.Mood(m.name)) }, "${m.name}, ${plural(m.trackCount, "song")}", shape = shape).background(if (lead) c.ink else c.sunk, shape).padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Txt(m.name, style = Soiboi.type.title, color = if (lead) c.paper else c.ink, maxLines = 2)
                    Txt(plural(m.trackCount, "song"), style = Soiboi.type.caption, color = if (lead) c.paper.copy(alpha = 0.8f) else c.soft)
                }
            }
        }
    }
}

@Composable
private fun NewestAlbums(app: AppController, albums: List<Album>) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        SectionTitle("Newest albums") { SButton("All albums", { app.go(Route.Library(LibraryTab.Albums)) }, tone = Tone.Ghost, compact = true) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(albums.size) { i -> AlbumTile(albums[i], { app.go(Route.Album(albums[i].name)) }, size = if (i == 0) 200.dp else 150.dp, modifier = Modifier.padding(top = if (i == 0) 0.dp else 26.dp)) }
        }
    }
}

@Composable
private fun WeeklyDiscoveries(app: AppController, user: String, playlists: List<ExternalPlaylist>) {
    val c = Soiboi.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        SectionTitle("Your weekly discoveries")
        Txt("Playlists ListenBrainz made for $user. Open one to see which songs Apple Music has and archive the ones you want.", style = Soiboi.type.caption, color = c.soft)
        playlists.forEach { p ->
            val shape = Soiboi.look.shape(14)
            Row(Modifier.fillMaxWidth().press({ app.go(Route.Import("lb:${p.id}")) }, p.title, shape = shape).background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.Compass, size = 24.dp, tint = c.soft)
                Column(Modifier.weight(1f)) { Txt(p.title, style = Soiboi.type.bodyStrong, maxLines = 1); p.lastModified?.let { Txt(it.take(10), style = Soiboi.type.caption, color = c.muted) } }
                GlyphIcon(Glyph.ChevronRight, size = 18.dp, tint = c.muted)
            }
        }
    }
}

/** Held by the screen, not the list item, so suggestions (and a request in flight) survive the shelf scrolling out of view. */
private class PicksState {
    var picks by mutableStateOf<List<AiAlbumPick>?>(null)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
}

@Composable
private fun AlbumPicks(app: AppController, config: AiConfig, state: PicksState, scope: CoroutineScope) {
    val c = Soiboi.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        SectionTitle("Albums to archive next") {
            SButton(if (state.picks == null) "Get suggestions" else "Refresh", {
                if (!state.busy) {
                    state.busy = true; state.error = null
                    scope.launch {
                        try { state.picks = app.engine.ai.recommendAlbums() } catch (e: Exception) { state.error = e.message ?: "The request failed" }
                        state.busy = false
                    }
                }
            }, tone = Tone.Secondary, compact = true, loading = state.busy)
        }
        if (state.busy && state.picks == null) Loading("Asking ${config.info.label} for ideas")
        state.error?.let { Banner("Could not get suggestions", it, Glyph.Warning, tint = c.danger) }
        state.picks?.forEach { p ->
            val shape = Soiboi.look.shape(14)
            Row(Modifier.fillMaxWidth().background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Txt(p.album, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(p.artist, style = Soiboi.type.caption, color = c.soft, maxLines = 1); if (p.why.isNotEmpty()) Txt(p.why, style = Soiboi.type.caption, color = c.muted, maxLines = 2) }
                SButton("Find it", { app.go(Route.Import("album:${p.artist}|${p.album}")) }, compact = true, glyph = Glyph.Search)
            }
        }
    }
}
