package app.soiboi.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.soiboi.engine.library.Album
import app.soiboi.engine.library.Artist
import app.soiboi.engine.library.QualityInfo
import app.soiboi.engine.library.Song
import app.soiboi.ui.LocalEngine
import app.soiboi.ui.LocalPlatform
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decoded covers kept in memory, least recently used out, so scrolling back is instant and memory stays bounded. */
private object CoverCache {
    private val map = object : LinkedHashMap<String, ImageBitmap?>(256, 0.75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, ImageBitmap?>) = size > 180 }
    @Synchronized fun get(k: String): ImageBitmap? = map[k]
    @Synchronized fun has(k: String) = map.containsKey(k)
    @Synchronized fun put(k: String, v: ImageBitmap?) { map[k] = v }
}

/** The song's cover as an image, loaded once and shared with [Cover]'s cache. Null while loading or when the song has none. */
@Composable
fun rememberCoverBitmap(song: Song?): ImageBitmap? {
    val engine = LocalEngine.current; val platform = LocalPlatform.current
    val key = song?.let { "${it.origin}:${it.albumId ?: it.path ?: it.id}" }
    var bitmap by remember(key) { mutableStateOf(key?.let { CoverCache.get(it) }) }
    LaunchedEffect(key) {
        if (song == null || key == null || CoverCache.has(key)) { if (key != null) bitmap = CoverCache.get(key); return@LaunchedEffect }
        val image = withContext(Dispatchers.IO) { engine.artwork.coverFor(song)?.let { f -> runCatching { platform.decodeImage(f.readBytes()) }.getOrNull() } }
        CoverCache.put(key, image); bitmap = image
    }
    return bitmap
}

/** The song's cover. With none, an honest placeholder: a sunk square with a disc mark, never an invented image. */
@Composable
fun Cover(song: Song?, size: Dp, modifier: Modifier = Modifier, shape: Shape = Soiboi.look.shape(10), contentDescription: String? = null) {
    val engine = LocalEngine.current; val platform = LocalPlatform.current
    val key = song?.let { "${it.origin}:${it.albumId ?: it.path ?: it.id}" }
    var bitmap by remember(key) { mutableStateOf(key?.let { CoverCache.get(it) }) }
    var tried by remember(key) { mutableStateOf(key != null && CoverCache.has(key)) }
    LaunchedEffect(key) {
        if (song == null || key == null || tried) return@LaunchedEffect
        val image = withContext(Dispatchers.IO) { engine.artwork.coverFor(song)?.let { f -> runCatching { platform.decodeImage(f.readBytes()) }.getOrNull() } }
        CoverCache.put(key, image); bitmap = image; tried = true
    }
    val c = Soiboi.colors
    Box(
        modifier.size(size).clip(shape).background(c.sunk).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(bitmap, animationSpec = tween(if (Soiboi.reduceMotion) 0 else 220), label = "cover") { img ->
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else GlyphIcon(Glyph.Disc, size = size * 0.4f, tint = c.muted)
        }
    }
}

/** A cover for a whole album or playlist: the first song with art. */
@Composable
fun AlbumCover(album: Album, size: Dp, modifier: Modifier = Modifier, shape: Shape = Soiboi.look.shape(12)) = Cover(album.artworkSong, size, modifier, shape, "Cover of ${album.name}")

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0); val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

/**
 * One song as a row: cover, title, artist and album, the quality sticker, length and a more button. The playing row
 * is marked with a wave glyph and bold type as well as colour, and rows hold their 56dp height so a long list scans.
 */
@Composable
fun SongRow(
    song: Song, index: Int?, playing: Boolean, favorite: Boolean, onPlay: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier,
    showAlbum: Boolean = true, showCover: Boolean = true, compact: Boolean = false,
) {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(10)
    Row(
        modifier.fillMaxWidth().press(onPlay, "${song.displayTitle} by ${song.displayArtist}", shape = shape, role = Role.Button).padding(horizontal = Space.s, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (index != null) Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            if (playing) GlyphIcon(Glyph.Wave, size = 18.dp, tint = c.accent) else Txt("${index}", style = Soiboi.type.caption, color = c.muted, maxLines = 1)
        }
        if (showCover) Cover(song, 44.dp, shape = Soiboi.look.shape(8))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Txt(song.displayTitle, style = if (playing) Soiboi.type.bodyStrong else Soiboi.type.body, color = if (playing) c.accent else c.ink, maxLines = 1)
            Txt(if (showAlbum) "${song.displayArtist} · ${song.displayAlbum}" else song.displayArtist, style = Soiboi.type.caption, color = c.soft, maxLines = 1)
        }
        if (favorite) GlyphIcon(Glyph.HeartFilled, size = 16.dp, tint = c.accent)
        if (!compact) QualitySticker(QualityInfo.of(song))
        if (!compact && song.durationMs != null) Txt(formatTime(song.durationMs!!), style = Soiboi.type.caption, color = c.muted, maxLines = 1)
        SIconButton(Glyph.More, "More for ${song.displayTitle}", onMore, size = 36.dp, iconSize = 18.dp)
    }
}

/** An album tile: cover over title and artist. Tiles come in two sizes so a shelf can lead with one large one. */
@Composable
fun AlbumTile(album: Album, onClick: () -> Unit, size: Dp = 148.dp, modifier: Modifier = Modifier) {
    Column(modifier.width(size).press(onClick, "${album.name}, album", shape = Soiboi.look.shape(14)).padding(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AlbumCover(album, size - 8.dp)
        Column { Txt(album.name, style = Soiboi.type.bodyStrong, maxLines = 1); Txt(album.songs.firstOrNull()?.displayAlbumArtist ?: "", style = Soiboi.type.caption, color = Soiboi.colors.soft, maxLines = 1) }
    }
}

/** Artists have no picture of their own, so they get their first album's cover cropped round. */
@Composable
fun ArtistTile(artist: Artist, onClick: () -> Unit, size: Dp = 120.dp, modifier: Modifier = Modifier) {
    Column(modifier.width(size).press(onClick, "${artist.name}, artist", shape = Soiboi.look.shape(14)).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Cover(artist.albums.firstOrNull()?.artworkSong, size - 8.dp, shape = CircleShape, contentDescription = "Picture for ${artist.name}")
        Txt(artist.name, style = Soiboi.type.bodyStrong, maxLines = 1)
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Soiboi.colors.accent) {
    Box(modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Soiboi.colors.rule)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(5.dp).background(color, CircleShape))
    }
}
