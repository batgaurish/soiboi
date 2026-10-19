package app.soiboi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.soiboi.engine.apple.AppleCatalog
import app.soiboi.engine.discovery.catalogAlbumUrl
import app.soiboi.engine.discovery.catalogPlaylistUrl
import app.soiboi.engine.discovery.catalogSongUrl
import app.soiboi.engine.download.DownloadRequest
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.SongMenu
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.plural
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi

private val headerPadding = Modifier.padding(horizontal = Space.l, vertical = Space.l)

/** Dedicated UI for opening an Album entity from Apple Music or YouTube Music / Addons. */
@Composable
fun StreamingAlbumScreen(app: AppController, r: Route.StreamingAlbum) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val region = engine.settings.value.storefront

    val albumData by produceState<Result<List<Song>>?>(null, r) {
        value = runCatching {
            if (r.service == "apple") {
                engine.apple.albumTracks(r.id, region).orEmpty().map { t ->
                    val songId = AppleCatalog.songIdFromUrl(t.url) ?: t.url
                    Song("addon:apple:$songId", SongOrigin.Addon, title = t.title, artist = r.artist ?: "Unknown Artist", album = r.title, durationMs = t.durationMs, coverId = r.cover)
                }
            } else {
                val addon = engine.addons.instance(r.service)
                addon?.client?.album(r.id)?.tracks.orEmpty().map { t -> engine.addons.songFor(addon!!, t) }
            }
        }
    }
    val songs = albumData?.getOrNull().orEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
        item {
            Column(headerPadding, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                BackBar(app, serviceLabel(engine, r.service))
                EntityHero("Album", Glyph.Disc, r.title, cover = { UrlCover(r.cover, 160.dp, shape = Soiboi.look.shape(18), description = "Album cover for ${r.title}") }) {
                    if (!r.artist.isNullOrBlank()) Txt(r.artist, style = Soiboi.type.body, color = c.soft, maxLines = 1)
                    if (songs.isNotEmpty()) {
                        val totalMs = songs.sumOf { it.durationMs ?: 0L }
                        val totalStr = if (totalMs > 0) " · ${totalMs / 60000} min" else ""
                        Txt("${plural(songs.size, "song")}$totalStr", style = Soiboi.type.caption, color = c.muted)
                    }
                }
                PlayShuffleDownload(app, songs, "Download Album") {
                    if (r.service == "apple") engine.downloads.enqueue(listOf(DownloadRequest(catalogAlbumUrl(r.id, region), r.title, r.artist)))
                    else if (songs.isNotEmpty()) engine.downloads.enqueue(songs.map { streamingDownload(r.service, it, region, r.title) })
                    app.toast("Downloading album: ${r.title}")
                }
            }
        }
        streamingTracks(
            app, albumData, queue.current?.id,
            TrackListText("Loading album tracks", "Could not load album", "Unknown error", "No tracks found", "This album has no tracks available to stream.", Glyph.Disc),
        ) { streamingDownload(r.service, it, region, r.title) }
    }
}

/** Dedicated UI for opening a Playlist entity from Apple Music or YouTube Music / Addons. */
@Composable
fun StreamingPlaylistScreen(app: AppController, r: Route.StreamingPlaylist) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val region = engine.settings.value.storefront

    val playlistData by produceState<Result<List<Song>>?>(null, r) {
        value = runCatching {
            if (r.service == "apple") {
                val isCatalogPlaylist = r.id.startsWith("pl.") || r.id.startsWith("p.")
                val libTracks = runCatching { engine.appleLibrary.fetchPlaylistTracks(r.id) }.getOrNull()
                if (!libTracks.isNullOrEmpty()) {
                    libTracks.map { t -> Song("addon:apple:${t.catalogId ?: t.title}", SongOrigin.Addon, title = t.title, artist = t.artist ?: "Unknown Artist", album = t.album ?: r.title, coverId = r.cover) }
                } else if (isCatalogPlaylist) {
                    // Catalog playlists can't be fetched for display, only downloaded. Show empty with download option.
                    emptyList()
                } else {
                    // Not in the user's library and not a catalog playlist: fall back to search.
                    engine.apple.searchSongs(r.title, region).orEmpty().take(20).toAppleSongs()
                }
            } else {
                val addon = engine.addons.instance(r.service)
                addon?.client?.playlist(r.id)?.tracks.orEmpty().map { t -> engine.addons.songFor(addon!!, t) }
            }
        }
    }
    val songs = playlistData?.getOrNull().orEmpty()
    val isCatalogPlaylist = r.service == "apple" && (r.id.startsWith("pl.") || r.id.startsWith("p."))

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
        item {
            Column(headerPadding, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                BackBar(app, serviceLabel(engine, r.service))
                EntityHero("Playlist", Glyph.Playlist, r.title, cover = { UrlCover(r.cover, 160.dp, shape = Soiboi.look.shape(18), description = "Playlist cover for ${r.title}") }) {
                    if (!r.curator.isNullOrBlank()) Txt(r.curator, style = Soiboi.type.body, color = c.soft, maxLines = 1)
                    if (songs.isNotEmpty()) Txt(plural(songs.size, "song"), style = Soiboi.type.caption, color = c.muted)
                }
                PlayShuffleDownload(app, songs, "Download Playlist") {
                    if (r.service == "apple" && isCatalogPlaylist) engine.downloads.enqueue(listOf(DownloadRequest(catalogPlaylistUrl(r.id, region), r.title, r.curator)))
                    else if (songs.isNotEmpty()) engine.downloads.enqueue(songs.map { streamingDownload(r.service, it, region, r.title) })
                    app.toast("Downloading playlist: ${r.title}")
                }
            }
        }
        if (isCatalogPlaylist && songs.isEmpty() && playlistData?.isSuccess == true) {
            item {
                Box(Modifier.padding(horizontal = Space.l)) {
                    EmptyState(
                        "Apple Music Playlist",
                        "Track listing isn't available for Apple Music catalog playlists. Tap Download Playlist to archive all tracks.",
                        glyph = Glyph.Playlist
                    )
                }
            }
        } else {
            streamingTracks(
                app, playlistData, queue.current?.id,
                TrackListText("Loading playlist tracks", "Could not load playlist", "Unknown error", "No tracks found", "This playlist has no tracks available.", Glyph.Playlist),
            ) { streamingDownload(r.service, it, region, r.title) }
        }
    }
}

/** Dedicated UI for opening a Radio Station entity from Apple Music, YouTube Music or Radio directory. */
@Composable
fun StreamingRadioScreen(app: AppController, r: Route.StreamingRadio) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val region = engine.settings.value.storefront

    val stationData by produceState<Result<List<Song>>?>(null, r) {
        value = runCatching {
            when (r.service) {
                "apple" -> engine.apple.searchSongs(stationSearchTerm(r.title), region).orEmpty().take(15).toAppleSongs()
                "ytmusic" -> {
                    val addon = engine.addons.instance("ytmusic")
                    val mixId = if (r.id.startsWith("RDAMVM")) r.id else "RDAMVM${r.id}"
                    addon?.client?.playlist(mixId)?.tracks.orEmpty().map { t -> engine.addons.songFor(addon!!, t) }
                }
                else -> {
                    val addon = engine.addons.instance(r.service)
                    addon?.client?.catalog(r.id)?.tracks.orEmpty().map { t -> engine.addons.songFor(addon!!, t) }
                }
            }
        }
    }
    val songs = stationData?.getOrNull().orEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
        item {
            Column(headerPadding, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                BackBar(app, serviceLabel(engine, r.service, apple = "Apple Music Radio", fallback = "Internet Radio"))
                EntityHero("Radio Station", Glyph.Wave, r.title, cover = {
                    if (!r.cover.isNullOrBlank()) {
                        UrlCover(r.cover, 160.dp, shape = Soiboi.look.shape(18), description = "Radio cover for ${r.title}")
                    } else {
                        Box(Modifier.size(160.dp).background(c.raised, Soiboi.look.shape(18)), contentAlignment = Alignment.Center) {
                            GlyphIcon(Glyph.Wave, size = 56.dp, tint = c.accent)
                        }
                    }
                }) {
                    if (!r.subtitle.isNullOrBlank()) Txt(r.subtitle, style = Soiboi.type.body, color = c.soft, maxLines = 2)
                    if (songs.isNotEmpty()) Txt("${songs.size} tracks in lineup", style = Soiboi.type.caption, color = c.muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    SButton("Start Radio", { if (songs.isNotEmpty()) app.play(songs) else app.toast("Tuning in to ${r.title}") }, tone = Tone.Primary, glyph = Glyph.Wave)
                    if (songs.isNotEmpty()) {
                        SButton("Download Station Tracks", {
                            engine.downloads.enqueue(songs.map { streamingDownload(r.service, it, region, r.title) })
                            app.toast("Downloading ${songs.size} tracks from ${r.title}")
                        }, glyph = Glyph.Download)
                    }
                }
            }
        }
        streamingTracks(
            app, stationData, queue.current?.id,
            TrackListText("Connecting to station stream", "Station lineup unavailable", "Could not fetch station tracks.", "Live station stream", "This station streams audio live. Click Start Radio to listen.", Glyph.Wave),
        ) { streamingDownload(r.service, it, region, r.title) }
    }
}

/** Dedicated UI for opening a Song entity from Apple Music or YouTube Music / Addons. */
@Composable
fun StreamingSongScreen(app: AppController, r: Route.StreamingSong) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val region = engine.settings.value.storefront

    val song = Song("addon:${r.service}:${r.id}", SongOrigin.Addon, title = r.title, artist = r.artist ?: "Unknown Artist", album = r.album ?: "Single", coverId = r.cover)
    val isPlaying = queue.current?.id == song.id
    val isFav = song.id in engine.playlists.favoriteIds
    val label = serviceLabel(engine, r.service)

    Column(Modifier.fillMaxSize().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        BackBar(app, label)
        EntityHero("Song", Glyph.Play, r.title, coverCorner = 20, cover = { UrlCover(r.cover, 180.dp, shape = Soiboi.look.shape(20), description = "Artwork for ${r.title}") }) {
            if (!r.artist.isNullOrBlank()) Txt(r.artist, style = Soiboi.type.bodyStrong, color = c.ink, maxLines = 1)
            if (!r.album.isNullOrBlank()) Txt(r.album, style = Soiboi.type.caption, color = c.soft, maxLines = 1)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            SButton(if (isPlaying) "Playing" else "Play Now", { app.play(listOf(song)) }, tone = Tone.Primary, glyph = Glyph.Play)
            SButton("Play Next", { app.playNext(song) }, glyph = Glyph.Next)
            SButton("Add to Queue", { app.addToQueue(song) }, glyph = Glyph.Queue)
            SButton("Download Song", {
                val url = if (r.service == "apple") catalogSongUrl(r.id, region) else sessionUrl(r.service, r.id, r.title, r.artist ?: "")
                engine.downloads.enqueue(listOf(DownloadRequest(url, r.title, r.artist, r.album)))
                app.toast("Downloading ${r.title}")
            }, glyph = Glyph.Download)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            SButton(if (isFav) "Favorited" else "Favorite", { app.toggleFavorite(song) }, tone = Tone.Ghost, glyph = if (isFav) Glyph.HeartFilled else Glyph.Heart)
            SButton("Add to Playlist", { app.songMenu = SongMenu(song) }, tone = Tone.Ghost, glyph = Glyph.Playlist)
        }

        Column(Modifier.fillMaxWidth().padding(top = Space.m).background(c.raised, Soiboi.look.shape(12)).padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Txt("Track Information", style = Soiboi.type.headline)
            Txt("Service: $label", style = Soiboi.type.caption, color = c.soft)
            Txt("Track ID: ${r.id}", style = Soiboi.type.caption, color = c.soft)
            Txt("Catalog match enabled for archive downloads", style = Soiboi.type.micro, color = c.muted)
        }
    }
}

@Composable
private fun PlayShuffleDownload(app: AppController, songs: List<Song>, downloadLabel: String, download: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        SButton("Play", { app.play(songs) }, tone = Tone.Primary, glyph = Glyph.Play, enabled = songs.isNotEmpty())
        SButton("Shuffle", { app.play(songs, shuffle = true) }, glyph = Glyph.Shuffle, enabled = songs.isNotEmpty())
        SButton(downloadLabel, download, glyph = Glyph.Download)
    }
}
