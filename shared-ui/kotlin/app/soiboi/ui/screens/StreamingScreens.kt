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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.Loading
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Tone
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.formatTime
import app.soiboi.ui.components.leadShadow
import app.soiboi.ui.components.plural
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.launch
import java.net.URLEncoder

@Composable
fun EntityBadge(label: String, glyph: Glyph? = null, modifier: Modifier = Modifier) {
    val c = Soiboi.colors
    val shape = Soiboi.look.shape(6)
    Row(
        modifier = modifier
            .background(c.paper.copy(alpha = 0.94f), shape)
            .border(1.dp, c.rule, shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (glyph != null) GlyphIcon(glyph, size = 12.dp, tint = c.ink)
        Txt(label.uppercase(), style = Soiboi.type.micro, color = c.ink)
    }
}

private fun urlEnc(s: String): String = runCatching { URLEncoder.encode(s, "UTF-8") }.getOrDefault(s)

/** Dedicated UI for opening an Album entity from Apple Music or YouTube Music / Addons. */
@Composable
fun StreamingAlbumScreen(app: AppController, r: Route.StreamingAlbum) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val scope = rememberCoroutineScope()
    val region = engine.settings.value.storefront

    val albumData by produceState<Result<List<Song>>?>(null, r) {
        value = runCatching {
            if (r.service == "apple") {
                val tracks = engine.apple.albumTracks(r.id, region).orEmpty()
                tracks.map { t ->
                    val songId = AppleCatalog.songIdFromUrl(t.url) ?: t.url
                    Song(
                        id = "addon:apple:$songId",
                        origin = SongOrigin.Addon,
                        title = t.title,
                        artist = r.artist ?: "Unknown Artist",
                        album = r.title,
                        durationMs = t.durationMs,
                        coverId = r.cover,
                    )
                }
            } else {
                val addon = engine.addons.instance(r.service)
                val detail = addon?.client?.album(r.id)
                detail?.tracks.orEmpty().map { t ->
                    engine.addons.songFor(addon!!, t)
                }
            }
        }
    }

    val songs = albumData?.getOrNull().orEmpty()
    val serviceLabel = if (r.service == "apple") "Apple Music" else engine.addons.instance(r.service)?.manifest?.name ?: "Streaming"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
        item {
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SIconButton(Glyph.Back, "Back", { app.back() })
                    Txt(serviceLabel, Modifier.padding(start = Space.s), style = Soiboi.type.caption, color = c.muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.leadShadow(Soiboi.look.shape(18))) {
                        UrlCover(r.cover, 160.dp, shape = Soiboi.look.shape(18), description = "Album cover for ${r.title}")
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        EntityBadge("Album", Glyph.Disc)
                        Txt(r.title, style = Soiboi.type.display.copy(fontSize = 32.sp, lineHeight = 36.sp), maxLines = 3)
                        if (!r.artist.isNullOrBlank()) {
                            Txt(r.artist, style = Soiboi.type.body, color = c.soft, maxLines = 1)
                        }
                        if (songs.isNotEmpty()) {
                            val totalMs = songs.sumOf { it.durationMs ?: 0L }
                            val totalStr = if (totalMs > 0) " · ${totalMs / 60000} min" else ""
                            Txt("${plural(songs.size, "song")}$totalStr", style = Soiboi.type.caption, color = c.muted)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    SButton("Play", { app.play(songs) }, tone = Tone.Primary, glyph = Glyph.Play, enabled = songs.isNotEmpty())
                    SButton("Shuffle", { app.play(songs, shuffle = true) }, glyph = Glyph.Shuffle, enabled = songs.isNotEmpty())
                    SButton("Download Album", {
                        scope.launch {
                            if (r.service == "apple") {
                                val url = catalogAlbumUrl(r.id, region)
                                engine.downloads.enqueue(listOf(DownloadRequest(url, r.title, r.artist)))
                            } else {
                                val reqs = songs.map { s ->
                                    val trackId = s.id.removePrefix("addon:${r.service}:")
                                    DownloadRequest(
                                        url = "session://${r.service}/$trackId?title=${urlEnc(s.displayTitle)}&artist=${urlEnc(s.displayArtist)}",
                                        label = s.displayTitle,
                                        subtitle = s.displayArtist,
                                        group = r.title,
                                    )
                                }
                                if (reqs.isNotEmpty()) engine.downloads.enqueue(reqs)
                            }
                            app.toast("Downloading album: ${r.title}")
                        }
                    }, glyph = Glyph.Download)
                }
            }
        }

        when {
            albumData == null -> item { Loading("Loading album tracks") }
            albumData!!.isFailure -> item {
                Box(Modifier.padding(horizontal = Space.l)) {
                    Banner("Could not load album", albumData!!.exceptionOrNull()?.message ?: "Unknown error", Glyph.Warning, tint = c.danger)
                }
            }
            songs.isEmpty() -> item {
                Box(Modifier.padding(horizontal = Space.l)) {
                    EmptyState("No tracks found", "This album has no tracks available to stream.", glyph = Glyph.Disc)
                }
            }
            else -> {
                itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { index, s ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            SongRow(
                                song = s,
                                index = index + 1,
                                playing = queue.current?.id == s.id,
                                favorite = s.id in engine.playlists.favoriteIds,
                                onPlay = { app.play(songs, index) },
                                onMore = { app.songMenu = SongMenu(s) },
                            )
                        }
                        SIconButton(
                            glyph = Glyph.Download,
                            label = "Download ${s.displayTitle}",
                            onClick = {
                                val req = if (r.service == "apple") {
                                    val tid = s.id.removePrefix("addon:apple:")
                                    DownloadRequest(catalogSongUrl(tid, region), s.displayTitle, s.displayArtist, r.title)
                                } else {
                                    val tid = s.id.removePrefix("addon:${r.service}:")
                                    DownloadRequest("session://${r.service}/$tid?title=${urlEnc(s.displayTitle)}&artist=${urlEnc(s.displayArtist)}", s.displayTitle, s.displayArtist, r.title)
                                }
                                engine.downloads.enqueue(listOf(req))
                                app.toast("Downloading ${s.displayTitle}")
                            },
                            size = 36.dp,
                            iconSize = 16.dp,
                            tint = c.soft,
                        )
                    }
                }
            }
        }
    }
}

/** Dedicated UI for opening a Playlist entity from Apple Music or YouTube Music / Addons. */
@Composable
fun StreamingPlaylistScreen(app: AppController, r: Route.StreamingPlaylist) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val scope = rememberCoroutineScope()
    val region = engine.settings.value.storefront

    val playlistData by produceState<Result<List<Song>>?>(null, r) {
        value = runCatching {
            if (r.service == "apple") {
                val libTracks = runCatching { engine.appleLibrary.fetchPlaylistTracks(r.id) }.getOrNull()
                if (!libTracks.isNullOrEmpty()) {
                    libTracks.map { t ->
                        val id = t.catalogId ?: t.title
                        Song("addon:apple:$id", SongOrigin.Addon, title = t.title, artist = t.artist ?: "Unknown Artist", album = t.album ?: r.title, coverId = r.cover)
                    }
                } else {
                    // Fallback to searching playlist title for tracks
                    val songs = engine.apple.searchSongs(r.title, region).orEmpty().take(20)
                    songs.mapNotNull { cand ->
                        cand.trackId?.let { id -> Song("addon:apple:$id", SongOrigin.Addon, title = cand.title, artist = cand.artist, album = cand.album, coverId = cand.artwork) }
                    }
                }
            } else {
                val addon = engine.addons.instance(r.service)
                val detail = addon?.client?.playlist(r.id)
                detail?.tracks.orEmpty().map { t ->
                    engine.addons.songFor(addon!!, t)
                }
            }
        }
    }

    val songs = playlistData?.getOrNull().orEmpty()
    val serviceLabel = if (r.service == "apple") "Apple Music" else engine.addons.instance(r.service)?.manifest?.name ?: "Streaming"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
        item {
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SIconButton(Glyph.Back, "Back", { app.back() })
                    Txt(serviceLabel, Modifier.padding(start = Space.s), style = Soiboi.type.caption, color = c.muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.leadShadow(Soiboi.look.shape(18))) {
                        UrlCover(r.cover, 160.dp, shape = Soiboi.look.shape(18), description = "Playlist cover for ${r.title}")
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        EntityBadge("Playlist", Glyph.Playlist)
                        Txt(r.title, style = Soiboi.type.display.copy(fontSize = 32.sp, lineHeight = 36.sp), maxLines = 3)
                        if (!r.curator.isNullOrBlank()) {
                            Txt(r.curator, style = Soiboi.type.body, color = c.soft, maxLines = 1)
                        }
                        if (songs.isNotEmpty()) {
                            Txt(plural(songs.size, "song"), style = Soiboi.type.caption, color = c.muted)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    SButton("Play", { app.play(songs) }, tone = Tone.Primary, glyph = Glyph.Play, enabled = songs.isNotEmpty())
                    SButton("Shuffle", { app.play(songs, shuffle = true) }, glyph = Glyph.Shuffle, enabled = songs.isNotEmpty())
                    SButton("Download Playlist", {
                        scope.launch {
                            if (r.service == "apple" && (r.id.startsWith("pl.") || r.id.startsWith("p."))) {
                                val url = catalogPlaylistUrl(r.id, region)
                                engine.downloads.enqueue(listOf(DownloadRequest(url, r.title, r.curator)))
                            } else {
                                val reqs = songs.map { s ->
                                    val tid = s.id.removePrefix("addon:${r.service}:").removePrefix("addon:apple:")
                                    val url = if (r.service == "apple") catalogSongUrl(tid, region) else "session://${r.service}/$tid?title=${urlEnc(s.displayTitle)}&artist=${urlEnc(s.displayArtist)}"
                                    DownloadRequest(url, s.displayTitle, s.displayArtist, r.title)
                                }
                                if (reqs.isNotEmpty()) engine.downloads.enqueue(reqs)
                            }
                            app.toast("Downloading playlist: ${r.title}")
                        }
                    }, glyph = Glyph.Download)
                }
            }
        }

        when {
            playlistData == null -> item { Loading("Loading playlist tracks") }
            playlistData!!.isFailure -> item {
                Box(Modifier.padding(horizontal = Space.l)) {
                    Banner("Could not load playlist", playlistData!!.exceptionOrNull()?.message ?: "Unknown error", Glyph.Warning, tint = c.danger)
                }
            }
            songs.isEmpty() -> item {
                Box(Modifier.padding(horizontal = Space.l)) {
                    EmptyState("No tracks found", "This playlist has no tracks available.", glyph = Glyph.Playlist)
                }
            }
            else -> {
                itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { index, s ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            SongRow(
                                song = s,
                                index = index + 1,
                                playing = queue.current?.id == s.id,
                                favorite = s.id in engine.playlists.favoriteIds,
                                onPlay = { app.play(songs, index) },
                                onMore = { app.songMenu = SongMenu(s) },
                            )
                        }
                        SIconButton(
                            glyph = Glyph.Download,
                            label = "Download ${s.displayTitle}",
                            onClick = {
                                val tid = s.id.removePrefix("addon:${r.service}:").removePrefix("addon:apple:")
                                val url = if (r.service == "apple") catalogSongUrl(tid, region) else "session://${r.service}/$tid?title=${urlEnc(s.displayTitle)}&artist=${urlEnc(s.displayArtist)}"
                                engine.downloads.enqueue(listOf(DownloadRequest(url, s.displayTitle, s.displayArtist, r.title)))
                                app.toast("Downloading ${s.displayTitle}")
                            },
                            size = 36.dp,
                            iconSize = 16.dp,
                            tint = c.soft,
                        )
                    }
                }
            }
        }
    }
}

/** Dedicated UI for opening a Radio Station entity from Apple Music, YouTube Music or Radio directory. */
@Composable
fun StreamingRadioScreen(app: AppController, r: Route.StreamingRadio) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val scope = rememberCoroutineScope()
    val region = engine.settings.value.storefront

    val stationData by produceState<Result<List<Song>>?>(null, r) {
        value = runCatching {
            if (r.service == "apple") {
                // Stations in Apple have dynamic queues or term mixes; resolve related tracks
                val term = r.title.replace("Station", "").replace("Radio", "").trim().ifBlank { "Radio" }
                val candidates = engine.apple.searchSongs(term, region).orEmpty().take(15)
                candidates.mapNotNull { cand ->
                    cand.trackId?.let { id -> Song("addon:apple:$id", SongOrigin.Addon, title = cand.title, artist = cand.artist, album = cand.album, coverId = cand.artwork) }
                }
            } else if (r.service == "ytmusic") {
                val addon = engine.addons.instance("ytmusic")
                val mixId = if (r.id.startsWith("RDAMVM")) r.id else "RDAMVM${r.id}"
                val mix = addon?.client?.playlist(mixId)
                mix?.tracks.orEmpty().map { t -> engine.addons.songFor(addon!!, t) }
            } else {
                val addon = engine.addons.instance(r.service)
                val cat = addon?.client?.catalog(r.id)
                cat?.tracks.orEmpty().map { t -> engine.addons.songFor(addon!!, t) }
            }
        }
    }

    val songs = stationData?.getOrNull().orEmpty()
    val serviceLabel = if (r.service == "apple") "Apple Music Radio" else engine.addons.instance(r.service)?.manifest?.name ?: "Internet Radio"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xl)) {
        item {
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SIconButton(Glyph.Back, "Back", { app.back() })
                    Txt(serviceLabel, Modifier.padding(start = Space.s), style = Soiboi.type.caption, color = c.muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.leadShadow(Soiboi.look.shape(18))) {
                        if (!r.cover.isNullOrBlank()) {
                            UrlCover(r.cover, 160.dp, shape = Soiboi.look.shape(18), description = "Radio cover for ${r.title}")
                        } else {
                            Box(Modifier.size(160.dp).background(c.raised, Soiboi.look.shape(18)), contentAlignment = Alignment.Center) {
                                GlyphIcon(Glyph.Wave, size = 56.dp, tint = c.accent)
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        EntityBadge("Radio Station", Glyph.Wave)
                        Txt(r.title, style = Soiboi.type.display.copy(fontSize = 32.sp, lineHeight = 36.sp), maxLines = 3)
                        if (!r.subtitle.isNullOrBlank()) {
                            Txt(r.subtitle, style = Soiboi.type.body, color = c.soft, maxLines = 2)
                        }
                        if (songs.isNotEmpty()) {
                            Txt("${songs.size} tracks in lineup", style = Soiboi.type.caption, color = c.muted)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                    SButton("Start Radio", {
                        if (songs.isNotEmpty()) {
                            app.play(songs)
                        } else {
                            app.toast("Tuning in to ${r.title}")
                        }
                    }, tone = Tone.Primary, glyph = Glyph.Wave)
                    if (songs.isNotEmpty()) {
                        SButton("Download Station Tracks", {
                            scope.launch {
                                val reqs = songs.map { s ->
                                    val tid = s.id.removePrefix("addon:${r.service}:").removePrefix("addon:apple:")
                                    val url = if (r.service == "apple") catalogSongUrl(tid, region) else "session://${r.service}/$tid?title=${urlEnc(s.displayTitle)}&artist=${urlEnc(s.displayArtist)}"
                                    DownloadRequest(url, s.displayTitle, s.displayArtist, r.title)
                                }
                                engine.downloads.enqueue(reqs)
                                app.toast("Downloading ${songs.size} tracks from ${r.title}")
                            }
                        }, glyph = Glyph.Download)
                    }
                }
            }
        }

        when {
            stationData == null -> item { Loading("Connecting to station stream") }
            stationData!!.isFailure -> item {
                Box(Modifier.padding(horizontal = Space.l)) {
                    Banner("Station lineup unavailable", stationData!!.exceptionOrNull()?.message ?: "Could not fetch station tracks.", Glyph.Warning, tint = c.danger)
                }
            }
            songs.isEmpty() -> item {
                Box(Modifier.padding(horizontal = Space.l)) {
                    EmptyState("Live station stream", "This station streams audio live. Click Start Radio to listen.", glyph = Glyph.Wave)
                }
            }
            else -> {
                itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { index, s ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = Space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            SongRow(
                                song = s,
                                index = index + 1,
                                playing = queue.current?.id == s.id,
                                favorite = s.id in engine.playlists.favoriteIds,
                                onPlay = { app.play(songs, index) },
                                onMore = { app.songMenu = SongMenu(s) },
                            )
                        }
                        SIconButton(
                            glyph = Glyph.Download,
                            label = "Download ${s.displayTitle}",
                            onClick = {
                                val tid = s.id.removePrefix("addon:${r.service}:").removePrefix("addon:apple:")
                                val url = if (r.service == "apple") catalogSongUrl(tid, region) else "session://${r.service}/$tid?title=${urlEnc(s.displayTitle)}&artist=${urlEnc(s.displayArtist)}"
                                engine.downloads.enqueue(listOf(DownloadRequest(url, s.displayTitle, s.displayArtist, r.title)))
                                app.toast("Downloading ${s.displayTitle}")
                            },
                            size = 36.dp,
                            iconSize = 16.dp,
                            tint = c.soft,
                        )
                    }
                }
            }
        }
    }
}

/** Dedicated UI for opening a Song entity from Apple Music or YouTube Music / Addons. */
@Composable
fun StreamingSongScreen(app: AppController, r: Route.StreamingSong) {
    val c = Soiboi.colors
    val engine = app.engine
    val queue by engine.player.queue.collectAsState()
    val scope = rememberCoroutineScope()
    val region = engine.settings.value.storefront

    val songId = if (r.service == "apple") "addon:apple:${r.id}" else "addon:${r.service}:${r.id}"
    val song = Song(
        id = songId,
        origin = SongOrigin.Addon,
        title = r.title,
        artist = r.artist ?: "Unknown Artist",
        album = r.album ?: "Single",
        coverId = r.cover,
    )
    val isPlaying = queue.current?.id == song.id
    val isFav = song.id in engine.playlists.favoriteIds
    val serviceLabel = if (r.service == "apple") "Apple Music" else engine.addons.instance(r.service)?.manifest?.name ?: "Streaming"

    Column(Modifier.fillMaxSize().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SIconButton(Glyph.Back, "Back", { app.back() })
            Txt(serviceLabel, Modifier.padding(start = Space.s), style = Soiboi.type.caption, color = c.muted)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.leadShadow(Soiboi.look.shape(20))) {
                UrlCover(r.cover, 180.dp, shape = Soiboi.look.shape(20), description = "Artwork for ${r.title}")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                EntityBadge("Song", Glyph.Play)
                Txt(r.title, style = Soiboi.type.display.copy(fontSize = 32.sp, lineHeight = 36.sp), maxLines = 3)
                if (!r.artist.isNullOrBlank()) {
                    Txt(r.artist, style = Soiboi.type.bodyStrong, color = c.ink, maxLines = 1)
                }
                if (!r.album.isNullOrBlank()) {
                    Txt(r.album, style = Soiboi.type.caption, color = c.soft, maxLines = 1)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            SButton(if (isPlaying) "Playing" else "Play Now", { app.play(listOf(song)) }, tone = Tone.Primary, glyph = Glyph.Play)
            SButton("Play Next", { app.playNext(song) }, glyph = Glyph.Next)
            SButton("Add to Queue", { app.addToQueue(song) }, glyph = Glyph.Queue)
            SButton("Download Song", {
                val url = if (r.service == "apple") {
                    catalogSongUrl(r.id, region)
                } else {
                    "session://${r.service}/${r.id}?title=${urlEnc(r.title)}&artist=${urlEnc(r.artist ?: "")}"
                }
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
            Txt("Service: $serviceLabel", style = Soiboi.type.caption, color = c.soft)
            Txt("Track ID: ${r.id}", style = Soiboi.type.caption, color = c.soft)
            Txt("Catalog match enabled for archive downloads", style = Soiboi.type.micro, color = c.muted)
        }
    }
}
