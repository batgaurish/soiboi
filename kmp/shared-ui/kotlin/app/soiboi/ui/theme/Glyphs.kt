package app.soiboi.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The icon set, drawn in code on a 24-unit grid with a heavy 2.4 stroke and round ends. It is our own set
 * rather than an import, so the glyphs share the sticker voice of the type: chunky, a little blunt, and
 * legible at 16dp. Each glyph is relevant to one meaning; there is no sparkle, star or magic wand.
 */
enum class Glyph {
    Play, Pause, Next, Previous, Shuffle, Repeat, RepeatOne, Heart, HeartFilled, Search, Today, Library, Disc, Download, Plug, Gear,
    Queue, Lyrics, Volume, VolumeMute, Plus, Check, Close, ChevronLeft, ChevronRight, ChevronDown, ChevronUp, More, Folder,
    Link, Cloud, Clock, Trash, Edit, Refresh, Moon, Sliders, Person, Playlist, Pin, Warning, Info, Wave, Key, Compass, Back, Stop,
}

private const val G = 24f

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = Soiboi.colors.ink, weight: Float = 2.4f) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / G
        draw(glyph, u, tint, weight * u)
    }
}

private fun DrawScope.stroke(path: Path, color: Color, w: Float) =
    drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))

private fun DrawScope.fill(path: Path, color: Color) = drawPath(path, color, style = Fill)

private fun Path.m(u: Float, x: Float, y: Float) = moveTo(x * u, y * u)
private fun Path.l(u: Float, x: Float, y: Float) = lineTo(x * u, y * u)

private fun poly(u: Float, vararg pts: Float, close: Boolean = false): Path = Path().apply {
    m(u, pts[0], pts[1]); var i = 2
    while (i < pts.size) { l(u, pts[i], pts[i + 1]); i += 2 }
    if (close) close()
}

private fun DrawScope.draw(g: Glyph, u: Float, c: Color, w: Float) {
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = stroke(poly(u, x1, y1, x2, y2), c, w)
    fun circle(cx: Float, cy: Float, r: Float, filled: Boolean = false) =
        if (filled) drawCircle(c, r * u, Offset(cx * u, cy * u)) else drawCircle(c, r * u, Offset(cx * u, cy * u), style = Stroke(w))
    when (g) {
        Glyph.Play -> fill(poly(u, 7f, 4.5f, 19.5f, 12f, 7f, 19.5f, close = true), c)
        Glyph.Pause -> { fill(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(6f * u, 4.5f * u, 10.2f * u, 19.5f * u), 1.2f * u, 1.2f * u)) }, c); fill(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(13.8f * u, 4.5f * u, 18f * u, 19.5f * u), 1.2f * u, 1.2f * u)) }, c) }
        Glyph.Stop -> fill(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(6f * u, 6f * u, 18f * u, 18f * u), 2f * u, 2f * u)) }, c)
        Glyph.Next -> { fill(poly(u, 5f, 5f, 15f, 12f, 5f, 19f, close = true), c); line(18.5f, 5f, 18.5f, 19f) }
        Glyph.Previous -> { fill(poly(u, 19f, 5f, 9f, 12f, 19f, 19f, close = true), c); line(5.5f, 5f, 5.5f, 19f) }
        Glyph.Shuffle -> { stroke(Path().apply { m(u, 3f, 7f); l(u, 8f, 7f); cubicTo(13f * u, 7f * u, 11f * u, 17f * u, 16f * u, 17f * u); l(u, 20f, 17f) }, c, w); stroke(Path().apply { m(u, 3f, 17f); l(u, 8f, 17f); cubicTo(10f * u, 17f * u, 10.8f * u, 14.5f * u, 11.5f * u, 12.5f * u) }, c, w); stroke(poly(u, 17f, 14f, 20.5f, 17f, 17f, 20f), c, w); stroke(Path().apply { m(u, 13.5f, 9.5f); cubicTo(14.2f * u, 8f * u, 15f * u, 7f * u, 16f * u, 7f * u); l(u, 20f, 7f) }, c, w); stroke(poly(u, 17f, 4f, 20.5f, 7f, 17f, 10f), c, w) }
        Glyph.Repeat, Glyph.RepeatOne -> {
            stroke(Path().apply { m(u, 4f, 11f); l(u, 4f, 9.5f); cubicTo(4f * u, 7.5f * u, 5.5f * u, 6.5f * u, 7.5f * u, 6.5f * u); l(u, 19f, 6.5f) }, c, w); stroke(poly(u, 16f, 3.5f, 19.5f, 6.5f, 16f, 9.5f), c, w)
            stroke(Path().apply { m(u, 20f, 13f); l(u, 20f, 14.5f); cubicTo(20f * u, 16.5f * u, 18.5f * u, 17.5f * u, 16.5f * u, 17.5f * u); l(u, 5f, 17.5f) }, c, w); stroke(poly(u, 8f, 14.5f, 4.5f, 17.5f, 8f, 20.5f), c, w)
            if (g == Glyph.RepeatOne) line(12f, 10.5f, 12f, 13.5f)
        }
        Glyph.Heart, Glyph.HeartFilled -> {
            val p = Path().apply { m(u, 12f, 20f); cubicTo(5f * u, 15f * u, 3f * u, 11.5f * u, 3f * u, 8.5f * u); cubicTo(3f * u, 5.8f * u, 5f * u, 4f * u, 7.4f * u, 4f * u); cubicTo(9.4f * u, 4f * u, 11f * u, 5.2f * u, 12f * u, 7f * u); cubicTo(13f * u, 5.2f * u, 14.6f * u, 4f * u, 16.6f * u, 4f * u); cubicTo(19f * u, 4f * u, 21f * u, 5.8f * u, 21f * u, 8.5f * u); cubicTo(21f * u, 11.5f * u, 19f * u, 15f * u, 12f * u, 20f * u); close() }
            if (g == Glyph.HeartFilled) fill(p, c) else stroke(p, c, w)
        }
        Glyph.Search -> { circle(10.5f, 10.5f, 6f); line(15f, 15f, 20f, 20f) }
        Glyph.Today -> { stroke(poly(u, 3.5f, 11f, 12f, 4f, 20.5f, 11f), c, w); stroke(poly(u, 6f, 9.5f, 6f, 20f, 18f, 20f, 18f, 9.5f), c, w); line(10f, 20f, 10f, 14.5f); line(14f, 20f, 14f, 14.5f); line(10f, 14.5f, 14f, 14.5f) }
        Glyph.Library -> { line(5f, 4.5f, 5f, 19.5f); line(10f, 4.5f, 10f, 19.5f); stroke(poly(u, 14.5f, 6f, 19.5f, 4.5f, 20f, 18.5f, 15.5f, 19.5f, close = true), c, w) }
        Glyph.Disc -> { circle(12f, 12f, 8.5f); circle(12f, 12f, 2f, filled = true) }
        Glyph.Download -> { line(12f, 4f, 12f, 15f); stroke(poly(u, 7.5f, 10.8f, 12f, 15.2f, 16.5f, 10.8f), c, w); line(5f, 20f, 19f, 20f) }
        Glyph.Plug -> { line(9f, 3.5f, 9f, 8f); line(15f, 3.5f, 15f, 8f); stroke(Path().apply { m(u, 6f, 8f); l(u, 18f, 8f); l(u, 18f, 11f); cubicTo(18f * u, 14.5f * u, 15.5f * u, 16f * u, 12f * u, 16f * u); cubicTo(8.5f * u, 16f * u, 6f * u, 14.5f * u, 6f * u, 11f * u); close() }, c, w); line(12f, 16f, 12f, 20.5f) }
        Glyph.Gear -> { circle(12f, 12f, 3.2f); for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); val x1 = 12f + 6.3f * Math.cos(a).toFloat(); val y1 = 12f + 6.3f * Math.sin(a).toFloat(); val x2 = 12f + 9f * Math.cos(a).toFloat(); val y2 = 12f + 9f * Math.sin(a).toFloat(); line(x1, y1, x2, y2) }; circle(12f, 12f, 6.3f) }
        Glyph.Queue -> { line(4f, 6f, 15f, 6f); line(4f, 11f, 15f, 11f); line(4f, 16f, 10f, 16f); fill(poly(u, 14f, 14f, 20.5f, 17.5f, 14f, 21f, close = true), c) }
        Glyph.Lyrics -> { stroke(Path().apply { m(u, 4f, 5f); l(u, 20f, 5f); l(u, 20f, 16f); l(u, 11f, 16f); l(u, 6.5f, 20f); l(u, 6.5f, 16f); l(u, 4f, 16f); close() }, c, w); line(8f, 9f, 16f, 9f); line(8f, 12.2f, 13f, 12.2f) }
        Glyph.Volume -> { fill(poly(u, 4f, 9.5f, 8f, 9.5f, 12.5f, 5.5f, 12.5f, 18.5f, 8f, 14.5f, 4f, 14.5f, close = true), c); stroke(Path().apply { m(u, 15.5f, 9f); cubicTo(17.3f * u, 10.6f * u, 17.3f * u, 13.4f * u, 15.5f * u, 15f * u) }, c, w); stroke(Path().apply { m(u, 18.2f, 6.3f); cubicTo(21.7f * u, 9.5f * u, 21.7f * u, 14.5f * u, 18.2f * u, 17.7f * u) }, c, w) }
        Glyph.VolumeMute -> { fill(poly(u, 4f, 9.5f, 8f, 9.5f, 12.5f, 5.5f, 12.5f, 18.5f, 8f, 14.5f, 4f, 14.5f, close = true), c); line(16f, 9.5f, 21f, 14.5f); line(21f, 9.5f, 16f, 14.5f) }
        Glyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        Glyph.Check -> stroke(poly(u, 5f, 12.5f, 10f, 17.5f, 19.5f, 7f), c, w)
        Glyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        Glyph.ChevronLeft -> stroke(poly(u, 14.5f, 5f, 7.5f, 12f, 14.5f, 19f), c, w)
        Glyph.ChevronRight -> stroke(poly(u, 9.5f, 5f, 16.5f, 12f, 9.5f, 19f), c, w)
        Glyph.ChevronDown -> stroke(poly(u, 5f, 9.5f, 12f, 16.5f, 19f, 9.5f), c, w)
        Glyph.ChevronUp -> stroke(poly(u, 5f, 14.5f, 12f, 7.5f, 19f, 14.5f), c, w)
        Glyph.Back -> { line(19f, 12f, 5f, 12f); stroke(poly(u, 11f, 6f, 5f, 12f, 11f, 18f), c, w) }
        Glyph.More -> { circle(5.5f, 12f, 1.7f, true); circle(12f, 12f, 1.7f, true); circle(18.5f, 12f, 1.7f, true) }
        Glyph.Folder -> stroke(Path().apply { m(u, 3.5f, 7f); l(u, 3.5f, 18.5f); l(u, 20.5f, 18.5f); l(u, 20.5f, 8.5f); l(u, 11f, 8.5f); l(u, 9f, 5.5f); l(u, 4.5f, 5.5f); close() }, c, w)
        Glyph.Link -> { stroke(Path().apply { m(u, 10f, 14f); cubicTo(8f * u, 12f * u, 8f * u, 9.5f * u, 10f * u, 7.5f * u); l(u, 12.5f, 5f); cubicTo(14.5f * u, 3f * u, 17.5f * u, 3f * u, 19f * u, 4.5f * u); cubicTo(20.5f * u, 6.2f * u, 20.5f * u, 9f * u, 18.5f * u, 11f * u); l(u, 17f, 12.5f) }, c, w); stroke(Path().apply { m(u, 14f, 10f); cubicTo(16f * u, 12f * u, 16f * u, 14.5f * u, 14f * u, 16.5f * u); l(u, 11.5f, 19f); cubicTo(9.5f * u, 21f * u, 6.5f * u, 21f * u, 5f * u, 19.5f * u); cubicTo(3.5f * u, 17.8f * u, 3.5f * u, 15f * u, 5.5f * u, 13f * u); l(u, 7f, 11.5f) }, c, w) }
        Glyph.Cloud -> stroke(Path().apply { m(u, 7f, 18f); cubicTo(3.5f * u, 18f * u, 2.5f * u, 13.5f * u, 6f * u, 12.5f * u); cubicTo(6f * u, 8f * u, 11f * u, 6f * u, 14f * u, 9f * u); cubicTo(18f * u, 8f * u, 21f * u, 11.5f * u, 19.5f * u, 15f * u); cubicTo(19f * u, 17f * u, 17.5f * u, 18f * u, 16f * u, 18f * u); close() }, c, w)
        Glyph.Clock -> { circle(12f, 12f, 8.5f); stroke(poly(u, 12f, 7f, 12f, 12f, 15.5f, 14f), c, w) }
        Glyph.Trash -> { line(4.5f, 7f, 19.5f, 7f); stroke(poly(u, 9f, 7f, 9f, 4.5f, 15f, 4.5f, 15f, 7f), c, w); stroke(poly(u, 6.5f, 7f, 7.5f, 20f, 16.5f, 20f, 17.5f, 7f), c, w); line(10.5f, 11f, 10.5f, 16f); line(13.5f, 11f, 13.5f, 16f) }
        Glyph.Edit -> { stroke(poly(u, 4f, 20f, 4.5f, 15.5f, 16f, 4f, 20f, 8f, 8.5f, 19.5f, close = true), c, w); line(13.5f, 6.5f, 17.5f, 10.5f) }
        Glyph.Refresh -> { stroke(Path().apply { m(u, 19.5f, 12f); cubicTo(19.5f * u, 16.2f * u, 16.2f * u, 19.5f * u, 12f * u, 19.5f * u); cubicTo(7.8f * u, 19.5f * u, 4.5f * u, 16.2f * u, 4.5f * u, 12f * u); cubicTo(4.5f * u, 7.8f * u, 7.8f * u, 4.5f * u, 12f * u, 4.5f * u); cubicTo(14.5f * u, 4.5f * u, 16.7f * u, 5.7f * u, 18f * u, 7.5f * u) }, c, w); stroke(poly(u, 18.5f, 3.5f, 18.5f, 8f, 14f, 8f), c, w) }
        Glyph.Moon -> stroke(Path().apply { m(u, 19f, 14.5f); cubicTo(11f * u, 19f * u, 4.5f * u, 12f * u, 9.5f * u, 4.5f * u); cubicTo(4.5f * u, 5f * u, 2f * u, 10f * u, 4.5f * u, 15f * u); cubicTo(7f * u, 20f * u, 15f * u, 21f * u, 19f * u, 14.5f * u) }, c, w)
        Glyph.Sliders -> { line(4f, 8f, 20f, 8f); line(4f, 16f, 20f, 16f); circle(9f, 8f, 2.6f, true); circle(15f, 16f, 2.6f, true) }
        Glyph.Person -> { circle(12f, 8.5f, 3.8f); stroke(Path().apply { m(u, 4.5f, 20f); cubicTo(4.5f * u, 15f * u, 8f * u, 13.5f * u, 12f * u, 13.5f * u); cubicTo(16f * u, 13.5f * u, 19.5f * u, 15f * u, 19.5f * u, 20f * u) }, c, w) }
        Glyph.Playlist -> { line(4f, 7f, 14f, 7f); line(4f, 12f, 14f, 12f); line(4f, 17f, 10f, 17f); stroke(poly(u, 18f, 17f, 18f, 8f, 21f, 9.5f), c, w); circle(16f, 17.5f, 2f, true) }
        Glyph.Pin -> { stroke(poly(u, 9f, 4f, 15f, 4f, 14.5f, 10f, 18f, 13.5f, 6f, 13.5f, 9.5f, 10f, close = true), c, w); line(12f, 13.5f, 12f, 20.5f) }
        Glyph.Warning -> { stroke(poly(u, 12f, 4f, 21f, 19.5f, 3f, 19.5f, close = true), c, w); line(12f, 10f, 12f, 14.5f); circle(12f, 17f, 1f, true) }
        Glyph.Info -> { circle(12f, 12f, 8.5f); line(12f, 11f, 12f, 16f); circle(12f, 7.8f, 1f, true) }
        Glyph.Wave -> { line(4f, 10f, 4f, 14f); line(8f, 6f, 8f, 18f); line(12f, 3f, 12f, 21f); line(16f, 7.5f, 16f, 16.5f); line(20f, 10f, 20f, 14f) }
        Glyph.Key -> { circle(8f, 12f, 4f); line(12f, 12f, 21f, 12f); line(17.5f, 12f, 17.5f, 15.5f); line(20.5f, 12f, 20.5f, 14.5f) }
        Glyph.Compass -> { circle(12f, 12f, 8.5f); fill(poly(u, 15.5f, 8.5f, 13.3f, 13.3f, 8.5f, 15.5f, 10.7f, 10.7f, close = true), c) }
    }
}
