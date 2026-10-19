package app.soiboi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import app.soiboi.engine.discovery.AppleHomeItem
import app.soiboi.engine.discovery.AppleHomeShelf
import app.soiboi.engine.discovery.ApplePlaylist
import app.soiboi.engine.discovery.catalogAlbumUrl
import app.soiboi.engine.discovery.catalogPlaylistUrl
import app.soiboi.engine.discovery.catalogSongUrl
import app.soiboi.engine.download.DownloadRequest
import app.soiboi.engine.library.Song
import app.soiboi.ui.AppController
import app.soiboi.ui.Route
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.SButton
import app.soiboi.ui.components.SectionTitle
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.plural
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Shown when the signed-in home comes back with no shelves, so the page is never blank. */
private val catalogFallbackShelves = listOf("New Music" to "new music", "Mood Mixes" to "mood", "Discover: Bollywood" to "bollywood", "Discover: Punjabi" to "punjabi")

@Composable
internal fun AppleStreamingHome(app: AppController) {
    val c = Soiboi.colors
    val engine = app.engine
    val scope = rememberCoroutineScope()
    val signed by engine.cookieStore.signedIn.collectAsState()
    val region = engine.settings.value.storefront
    val home by produceState<Result<List<AppleHomeShelf>>?>(null, signed, region) {
        value = if (!signed) Result.success(emptyList()) else runCatching { engine.appleLibrary.fetchHome() }
    }
    val playlists by produceState<Result<List<ApplePlaylist>>?>(null, signed, region) {
        value = if (!signed) Result.success(emptyList()) else runCatching { engine.appleLibrary.fetchPlaylists().playlists }
    }
    val catalogFallback by produceState<List<Pair<String, List<Song>>>?>(null, region) {
        value = runCatching {
            catalogFallbackShelves.map { (title, term) -> title to engine.apple.searchSongs(term, region).orEmpty().toAppleSongs().take(12) }
        }.getOrDefault(emptyList())
    }
    val homeShelves = home?.getOrNull().orEmpty().filter { it.items.isNotEmpty() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Txt("Apple Music", style = Soiboi.type.display, maxLines = 2)
                if (!signed) Banner("Sign in to Apple Music", "The Apple Music source is selected, but there is no active Apple Music session.", Glyph.Key, action = { SButton("Open settings", { app.go(Route.Settings) }, compact = true) })
                else Txt("Recommendations, mixes, new releases, and your library.", style = Soiboi.type.body, color = c.soft)
            }
        }
        if (!signed) return@LazyColumn
        if (home == null) item { SectionTitle("Loading your recommendations") }
        if (home != null && homeShelves.isEmpty()) {
            catalogFallback.orEmpty().filter { it.second.isNotEmpty() }.forEach { (title, songs) ->
                item { CardShelf(title, songs) { song -> CatalogSongCard(app, song, region) } }
            }
        }
        home?.exceptionOrNull()?.let { error ->
            item { Banner("Apple Music home failed", error.message ?: error::class.simpleName ?: "Unknown error", Glyph.Warning, tint = c.danger, action = { SButton("Open logs", { app.go(Route.Settings) }, compact = true) }) }
        }
        homeShelves.forEach { shelf ->
            item { CardShelf(shelf.title, shelf.items) { HomeItemCard(app, scope, shelf, it, region) } }
        }
        playlists?.let { result ->
            item {
                result.exceptionOrNull()?.let { error ->
                    Banner("Apple Music playlists failed", error.message ?: error::class.simpleName ?: "Unknown error", Glyph.Warning, tint = c.danger)
                }
                CardShelf("Your Playlists", result.getOrNull().orEmpty()) { LibraryPlaylistCard(app, scope, it, region) }
            }
        }
    }
}

@Composable
private fun CatalogSongCard(app: AppController, song: Song, region: String) {
    val trackId = song.id.removePrefix("addon:apple:")
    StreamingCard(
        title = song.displayTitle,
        subtitle = song.displayArtist,
        coverUrl = song.coverId,
        badge = "Song",
        badgeGlyph = Glyph.Play,
        onClick = { app.go(Route.StreamingSong("apple", trackId, song.displayTitle, song.displayArtist, song.album, song.coverId)) },
        onDownload = { app.download(DownloadRequest(catalogSongUrl(trackId, region), song.displayTitle, song.displayArtist, song.album), "Downloading ${song.displayTitle}") },
    )
}

private enum class HomeItemKind { Album, Playlist, Station, Song }

private fun AppleHomeItem.kind(): HomeItemKind = when {
    type.contains("album", ignoreCase = true) -> HomeItemKind.Album
    type.contains("playlist", ignoreCase = true) || id.startsWith("pl.") || id.startsWith("p.") -> HomeItemKind.Playlist
    type.contains("station", ignoreCase = true) || type.contains("radio", ignoreCase = true) || id.startsWith("ra.") -> HomeItemKind.Station
    else -> HomeItemKind.Song
}

@Composable
private fun HomeItemCard(app: AppController, scope: CoroutineScope, shelf: AppleHomeShelf, item: AppleHomeItem, region: String) {
    val card = @Composable { badge: String, glyph: Glyph, open: Route, download: () -> Unit ->
        StreamingCard(item.title, item.subtitle, item.artworkUrl, badge, glyph, onClick = { app.go(open) }, onDownload = download)
    }
    when (item.kind()) {
        HomeItemKind.Album -> card("Album", Glyph.Disc, Route.StreamingAlbum("apple", item.id, item.title, item.subtitle, item.artworkUrl)) {
            app.download(DownloadRequest(catalogAlbumUrl(item.id, region), item.title, item.subtitle), "Downloading album: ${item.title}")
        }
        HomeItemKind.Playlist -> card("Playlist", Glyph.Playlist, Route.StreamingPlaylist("apple", item.id, item.title, item.subtitle, item.artworkUrl)) {
            app.download(DownloadRequest(catalogPlaylistUrl(item.id, region), item.title, item.subtitle), "Downloading playlist: ${item.title}")
        }
        HomeItemKind.Station -> card("Radio", Glyph.Wave, Route.StreamingRadio("apple", item.id, item.title, item.subtitle, item.artworkUrl)) {
            scope.launch { downloadStation(app, item.title, region) }
        }
        HomeItemKind.Song -> card("Song", Glyph.Play, Route.StreamingSong("apple", item.id, item.title, item.subtitle, shelf.title, item.artworkUrl)) {
            app.download(DownloadRequest(catalogSongUrl(item.id, region), item.title, item.subtitle, shelf.title), "Downloading ${item.title}")
        }
    }
}

@Composable
private fun LibraryPlaylistCard(app: AppController, scope: CoroutineScope, playlist: ApplePlaylist, region: String) {
    StreamingCard(
        title = playlist.name,
        subtitle = playlist.trackCount?.let { plural(it, "song") } ?: "Apple Music",
        coverUrl = playlist.artworkUrl,
        badge = "Playlist",
        badgeGlyph = Glyph.Playlist,
        onClick = { app.go(Route.StreamingPlaylist("apple", playlist.catalogId ?: playlist.libraryId, playlist.name, "Apple Music", playlist.artworkUrl)) },
        onDownload = {
            scope.launch {
                app.engine.appleLibrary.archivePlaylist(playlist, region)
                app.toast("Downloading playlist: ${playlist.name}")
            }
        },
    )
}

/** A station has no track list to archive, so this downloads the songs its station screen would play. */
private suspend fun downloadStation(app: AppController, title: String, region: String) {
    val engine = app.engine
    val reqs = engine.apple.searchSongs(stationSearchTerm(title), region).orEmpty().take(15)
        .mapNotNull { cand -> cand.trackId?.let { DownloadRequest(catalogSongUrl(it, region), cand.title, cand.artist, title) } }
    if (reqs.isEmpty()) {
        app.toast("No downloadable tracks for this station")
    } else {
        engine.downloads.enqueue(reqs)
        app.toast("Downloading ${reqs.size} tracks from $title")
    }
}

private fun AppController.download(request: DownloadRequest, message: String) {
    engine.downloads.enqueue(listOf(request))
    toast(message)
}
