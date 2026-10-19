package app.soiboi.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soiboi.engine.Engine
import app.soiboi.engine.apple.AppleCandidate
import app.soiboi.engine.discovery.catalogSongUrl
import app.soiboi.engine.download.DownloadRequest
import app.soiboi.engine.library.Song
import app.soiboi.engine.library.SongOrigin
import app.soiboi.ui.AppController
import app.soiboi.ui.SongMenu
import app.soiboi.ui.components.Banner
import app.soiboi.ui.components.EmptyState
import app.soiboi.ui.components.Loading
import app.soiboi.ui.components.SIconButton
import app.soiboi.ui.components.SectionTitle
import app.soiboi.ui.components.SongRow
import app.soiboi.ui.components.Space
import app.soiboi.ui.components.Txt
import app.soiboi.ui.components.leadShadow
import app.soiboi.ui.components.press
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import java.net.URLEncoder

private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** The `session://` link the archiver resolves through an addon's own download session. */
fun sessionUrl(service: String, trackId: String, title: String, artist: String): String =
    "session://$service/$trackId?title=${urlEnc(title)}&artist=${urlEnc(artist)}"

/** Apple songs download from their catalog link; every other service goes through its addon session. */
fun streamingDownload(service: String, song: Song, region: String, group: String?): DownloadRequest {
    val trackId = song.id.removePrefix("addon:$service:").removePrefix("addon:apple:")
    val url = if (service == "apple") catalogSongUrl(trackId, region) else sessionUrl(service, trackId, song.displayTitle, song.displayArtist)
    return DownloadRequest(url, song.displayTitle, song.displayArtist, group)
}

fun serviceLabel(engine: Engine, service: String, apple: String = "Apple Music", fallback: String = "Streaming"): String =
    if (service == "apple") apple else engine.addons.instance(service)?.manifest?.name ?: fallback

/** Apple has no station endpoint we can read, so a station plays songs matching its name. */
fun stationSearchTerm(title: String): String = title.replace("Station", "").replace("Radio", "").trim().ifBlank { "Radio" }

fun List<AppleCandidate>.toAppleSongs(): List<Song> = mapNotNull { cand ->
    cand.trackId?.let { id -> Song("addon:apple:$id", SongOrigin.Addon, title = cand.title, artist = cand.artist, album = cand.album, coverId = cand.artwork) }
}

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

@Composable
fun StreamingCard(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    badge: String,
    badgeGlyph: Glyph?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 148.dp,
) {
    val c = Soiboi.colors
    Column(
        modifier
            .width(size)
            .press(onClick, listOfNotNull(title, subtitle).joinToString(", "), shape = Soiboi.look.shape(12))
    ) {
        Box(Modifier.size(size)) {
            UrlCover(coverUrl, size, shape = Soiboi.look.shape(12), description = title)
            EntityBadge(badge, badgeGlyph, Modifier.align(Alignment.TopStart).padding(6.dp))
            SIconButton(
                glyph = Glyph.Download,
                label = "Download $title",
                onClick = onDownload,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                tint = c.ink,
                size = 34.dp,
                iconSize = 18.dp,
                background = c.paper.copy(alpha = 0.94f),
            )
        }
        Txt(title, style = Soiboi.type.bodyStrong, maxLines = 2, modifier = Modifier.padding(top = Space.xs))
        if (!subtitle.isNullOrBlank()) {
            Txt(subtitle, style = Soiboi.type.caption, color = c.soft, maxLines = 1)
        }
    }
}

/** A titled, horizontally scrolling shelf of cards. */
@Composable
fun <T> CardShelf(title: String, entries: List<T>, card: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        SectionTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(entries.size) { card(entries[it]) }
        }
    }
}

@Composable
fun BackBar(app: AppController, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SIconButton(Glyph.Back, "Back", { app.back() })
        Txt(label, Modifier.padding(start = Space.s), style = Soiboi.type.caption, color = Soiboi.colors.muted)
    }
}

/** Cover on the left, badge, title and whatever details the entity has on the right. */
@Composable
fun EntityHero(
    badge: String,
    badgeGlyph: Glyph,
    title: String,
    cover: @Composable () -> Unit,
    coverCorner: Int = 18,
    details: @Composable ColumnScope.() -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.leadShadow(Soiboi.look.shape(coverCorner))) { cover() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            EntityBadge(badge, badgeGlyph)
            Txt(title, style = Soiboi.type.display.copy(fontSize = 32.sp, lineHeight = 36.sp), maxLines = 3)
            details()
        }
    }
}

/** What a track list shows before, instead of, or around its tracks. */
class TrackListText(val loading: String, val failedTitle: String, val failedFallback: String, val emptyTitle: String, val emptyBody: String, val emptyGlyph: Glyph)

/** The loading, failure, empty and loaded states of a streaming entity's tracks, each row with its own download button. */
fun LazyListScope.streamingTracks(
    app: AppController,
    tracks: Result<List<Song>>?,
    currentId: String?,
    text: TrackListText,
    download: (Song) -> DownloadRequest,
) {
    val songs = tracks?.getOrNull().orEmpty()
    when {
        tracks == null -> item { Loading(text.loading) }
        tracks.isFailure -> item {
            Box(Modifier.padding(horizontal = Space.l)) {
                Banner(text.failedTitle, tracks.exceptionOrNull()?.message ?: text.failedFallback, Glyph.Warning, tint = Soiboi.colors.danger)
            }
        }
        songs.isEmpty() -> item {
            Box(Modifier.padding(horizontal = Space.l)) { EmptyState(text.emptyTitle, text.emptyBody, glyph = text.emptyGlyph) }
        }
        else -> itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { index, s ->
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SongRow(
                        song = s,
                        index = index + 1,
                        playing = currentId == s.id,
                        favorite = s.id in app.engine.playlists.favoriteIds,
                        onPlay = { app.play(songs, index) },
                        onMore = { app.songMenu = SongMenu(s) },
                    )
                }
                SIconButton(
                    glyph = Glyph.Download,
                    label = "Download ${s.displayTitle}",
                    onClick = {
                        app.engine.downloads.enqueue(listOf(download(s)))
                        app.toast("Downloading ${s.displayTitle}")
                    },
                    size = 36.dp,
                    iconSize = 16.dp,
                    tint = Soiboi.colors.soft,
                )
            }
        }
    }
}
