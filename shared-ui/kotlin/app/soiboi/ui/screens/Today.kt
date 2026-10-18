package app.soiboi.ui.screens

import app.soiboi.ui.SongMenu
import app.soiboi.ui.LibraryTab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.ai.AiAlbumPick
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.engine.library.recentlyAddedAlbums
import app.soiboi.engine.library.recentlyPlayed
import app.soiboi.engine.library.topArtists
import app.soiboi.engine.smart.autoMoodPlaylists
import app.soiboi.engine.smart.moodsNeedAnalysis
import app.soiboi.engine.sources.ExternalPlaylist
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.components.AlbumTile
import app.soiboi.ui.components.ArtistTile
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.Cover
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.Loading
import app.soiboi.ui.components.PlayButton
import app.soiboi.ui.components.QualitySticker
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SectionTitle
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.leadShadow
import app.soiboi.ui.components.plural
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import app.soiboi.engine.library.QualityInfo
import java.time.LocalTime
import kotlinx.coroutines.launch

private fun greeting(): String = when (LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Still up" }

enum class HomeTab { Offline, Streaming }

@Composable
fun TodayScreen(app: AppController) {
    val settings by app.engine.settings.state.collectAsState()
    var tab by remember { mutableStateOf(HomeTab.Offline) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            SButton("Offline library", { tab = HomeTab.Offline }, compact = true, tone = if (tab == HomeTab.Offline) Tone.Primary else Tone.Ghost, glyph = Glyph.Library)
            SButton("Streaming home", { tab = HomeTab.Streaming }, compact = true, tone = if (tab == HomeTab.Streaming) Tone.Primary else Tone.Ghost, glyph = Glyph.Wave)
        }
        if (tab == HomeTab.Offline) OfflineHomeScreen(app) else StreamingHomeScreen(app, settings.homepageProvider)
    }
}

@Composable
private fun StreamingHomeScreen(app: AppController, providerId: String) {
    if (providerId == "apple") {
        AppleStreamingHome(app)
        return
    }
    val c = Soiboi.colors
    val addons by app.engine.addons.installed.collectAsState()
    val selected = addons.firstOrNull { it.id == providerId && it.enabled }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt("Streaming home", style = Soiboi.type.display, maxLines = 2)
                if (selected == null || providerId == "offline") {
                    Banner("Choose a streaming provider", "Pick YouTube Music, Spotify, SoundCloud, or another enabled provider in Settings.", Glyph.Wave, action = { SButton("Open settings", { app.go(Route.Settings) }, compact = true) })
                } else {
                    Txt("Home from ${selected.manifest.name}", style = Soiboi.type.body, color = c.soft)
                }
            }
        }
        if (selected != null) addonShelves(app, listOf(selected))
    }
}

@Composable
private fun AppleStreamingHome(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val signed by engine.cookieStore.signedIn.collectAsState()
    val home by produceState<List<app.soiboi.engine.discovery.AppleHomeShelf>?>(null, signed) {
        value = if (!signed) emptyList() else runCatching { engine.appleLibrary.fetchHome() }.getOrDefault(emptyList())
    }
    val playlists by produceState<List<app.soiboi.engine.discovery.ApplePlaylist>?>(null, signed) {
        value = if (!signed) emptyList() else runCatching { engine.appleLibrary.fetchPlaylists().playlists }.getOrDefault(emptyList())
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt("Apple Music", style = Soiboi.type.display, maxLines = 2)
                if (!signed) Banner("Sign in to Apple Music", "The Apple Music source is selected, but there is no active Apple Music session.", Glyph.Key, action = { SButton("Open settings", { app.go(Route.Settings) }, compact = true) })
                else Txt("Recommendations, mixes, new releases, and your library.", style = Soiboi.type.body, color = c.soft)
            }
        }
        if (signed && home != null) {
            home!!.filter { it.items.isNotEmpty() }.forEach { shelf ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        SectionTitle(shelf.title)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            items(shelf.items.size) { index ->
                                val item = shelf.items[index]
                                val song = Song("addon:apple:${item.id}", SongOrigin.Addon, title = item.title, artist = item.subtitle, album = shelf.title, coverId = item.artworkUrl)
                                Column(Modifier.width(148.dp).press({ app.play(listOf(song)) }, item.title, shape = Soiboi.look.shape(12))) {
                                    Cover(song, 148.dp, shape = Soiboi.look.shape(12), contentDescription = item.title)
                                    Txt(item.title, style = Soiboi.type.bodyStrong, maxLines = 2, modifier = Modifier.padding(top = Space.xs))
                                    Txt(item.subtitle, style = Soiboi.type.caption, color = c.soft, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (signed && playlists != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Your Playlists")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(playlists!!.size) { index ->
                        val playlist = playlists!![index]
                        Column(Modifier.width(148.dp).press({ app.go(Route.Discover) }, playlist.name, shape = Soiboi.look.shape(12))) {
                            Box(Modifier.size(148.dp).background(c.raised, Soiboi.look.shape(12)), contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Playlist, size = 44.dp, tint = c.soft) }
                            Txt(playlist.name, style = Soiboi.type.bodyStrong, maxLines = 2, modifier = Modifier.padding(top = Space.xs))
                            playlist.trackCount?.let { Txt(plural(it, "song"), style = Soiboi.type.caption, color = c.soft) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineHomeScreen(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val songs by engine.library.songs.collectAsState()
    val albums by engine.library.albums.collectAsState()
    val artists by engine.library.artists.collectAsState()
    val queue by engine.player.queue.collectAsState()
    val state by engine.player.state.collectAsState()
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
    var picks by remember { mutableStateOf<List<AiAlbumPick>?>(null) }
    var picksError by remember { mutableStateOf<String?>(null) }
    var picksBusy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val leadSong: Song? = queue.current ?: recent.firstOrNull() ?: songs.maxByOrNull { it.modified ?: java.time.Instant.EPOCH }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.l, vertical = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.xxl)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt(greeting(), style = Soiboi.type.display, maxLines = 2)
                Txt("${plural(songs.size, "song")} · ${plural(albums.size, "album")} · ${plural(artists.size, "artist")}", style = Soiboi.type.body, color = c.soft)
            }
        }
        if (scanning) item { Loading(if (scanned > 0) "Reading your music, $scanned files so far" else "Looking through your music folders") }
        if (leadSong != null) item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 640.dp
                val shape = Soiboi.look.shape(26)
                val lead: @Composable () -> Unit = {
                    Row(Modifier.fillMaxWidth().leadShadow(shape).background(c.raised, shape).padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
                        Cover(leadSong, if (stacked) 112.dp else 168.dp, shape = Soiboi.look.shape(18), contentDescription = "Cover of ${leadSong.displayAlbum}")
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Txt(if (queue.current != null) "Pick up where you left off" else "Newest in your library", style = Soiboi.type.label, color = c.muted)
                            Txt(leadSong.displayTitle, style = Soiboi.type.headline, maxLines = 2)
                            Txt("${leadSong.displayArtist} · ${leadSong.displayAlbum}", style = Soiboi.type.body, color = c.soft, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                                PlayButton(state.playing && queue.current?.id == leadSong.id, {
                                    if (queue.current?.id == leadSong.id) engine.player.togglePlay() else app.play(listOf(leadSong))
                                }, size = 52.dp)
                                QualitySticker(QualityInfo.of(leadSong), large = true)
                            }
                        }
                    }
                }
                lead()
            }
        }
        if (songs.isEmpty()) item {
            EmptyState("Your library is empty", "Add a folder of music, connect a server, or paste a playlist link and Soiboi downloads it.", glyph = Glyph.Folder) {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    SButton("Add a music folder", { app.go(Route.Sources) }, tone = Tone.Primary, glyph = Glyph.Folder)
                    SButton("Paste a playlist link", { app.go(Route.Discover) }, glyph = Glyph.Link)
                }
            }
        }
        if (moods.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Made from your library")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    moods.forEachIndexed { i, m ->
                        val shape = Soiboi.look.shape(20)
                        Column(Modifier.weight(if (i == 0) 1.35f else 1f).press({ app.go(Route.Mood(m.name)) }, "${m.name}, ${plural(m.trackCount, "song")}", shape = shape).background(if (i == 0) c.ink else c.sunk, shape).padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Txt(m.name, style = Soiboi.type.title, color = if (i == 0) c.paper else c.ink, maxLines = 2)
                            Txt(plural(m.trackCount, "song"), style = Soiboi.type.caption, color = if (i == 0) c.paper.copy(alpha = 0.8f) else c.soft)
                        }
                    }
                }
            }
        } else if (needsAnalysis) item {
            Banner("Mood playlists need a first listen", "Soiboi measures energy and tempo from the audio, or reads moods an AI wrote once. Run the analysis in Settings to fill this shelf.", Glyph.Wave, modifier = Modifier.press({ app.go(Route.Settings) }, "Open settings", shape = Soiboi.look.shape(12)))
        }
        if (recentAlbums.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Newest albums") { SButton("All albums", { app.go(Route.Library(LibraryTab.Albums)) }, tone = Tone.Ghost, compact = true) }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(recentAlbums.size) { i -> AlbumTile(recentAlbums[i], { app.go(Route.Album(recentAlbums[i].name)) }, size = if (i == 0) 200.dp else 150.dp, modifier = Modifier.padding(top = if (i == 0) 0.dp else 26.dp)) }
                }
            }
        }
        if (!discoveries.isNullOrEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Your weekly discoveries")
                Txt("Playlists ListenBrainz made for ${settings.listenBrainzUser}. Open one to see which songs Apple Music has and archive the ones you want.", style = Soiboi.type.caption, color = c.soft)
                discoveries!!.forEach { p ->
                    val shape = Soiboi.look.shape(14)
                    Row(Modifier.fillMaxWidth().press({ app.go(Route.Import("lb:${p.id}")) }, p.title, shape = shape).background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon(Glyph.Compass, size = 24.dp, tint = c.soft)
                        Column(Modifier.weight(1f)) { Txt(p.title, style = Soiboi.type.bodyStrong, maxLines = 1); p.lastModified?.let { Txt(it.take(10), style = Soiboi.type.caption, color = c.muted) } }
                        GlyphIcon(Glyph.ChevronRight, size = 18.dp, tint = c.muted)
                    }
                }
            }
        }
        if (topArtistsList.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Most played artists")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) { items(topArtistsList.size) { i -> ArtistTile(topArtistsList[i], { app.go(Route.Artist(topArtistsList[i].name)) }) } }
            }
        }
        if (aiConfig != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                SectionTitle("Albums to archive next") {
                    SButton(if (picks == null) "Get suggestions" else "Refresh", {
                        if (!picksBusy) {
                            picksBusy = true; picksError = null
                            scope.launch {
                                try { picks = engine.ai.recommendAlbums() } catch (e: Exception) { picksError = e.message ?: "The request failed" }
                                picksBusy = false
                            }
                        }
                    }, tone = Tone.Secondary, compact = true, loading = picksBusy)
                }
                if (picksBusy && picks == null) Loading("Asking ${aiConfig!!.info.label} for ideas")
                picksError?.let { Banner("Could not get suggestions", it, Glyph.Warning, tint = c.danger) }
                picks?.forEach { p ->
                    val shape = Soiboi.look.shape(14)
                    Row(Modifier.fillMaxWidth().background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Txt(p.album, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(p.artist, style = Soiboi.type.caption, color = c.soft, maxLines = 1); if (p.why.isNotEmpty()) Txt(p.why, style = Soiboi.type.caption, color = c.muted, maxLines = 2) }
                        SButton("Find it", { app.go(Route.Import("album:${p.artist}|${p.album}")) }, compact = true, glyph = Glyph.Search)
                    }
                }
            }
        }
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

