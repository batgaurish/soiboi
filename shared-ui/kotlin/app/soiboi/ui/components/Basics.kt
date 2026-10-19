package app.soiboi.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.soiboi.engine.library.QualityInfo
import app.soiboi.engine.library.QualityTier
import app.soiboi.ui.theme.Glyph
import app.soiboi.ui.theme.GlyphIcon
import app.soiboi.ui.theme.Soiboi

/** Spacing steps on a 4dp rhythm. Sections use the larger steps and rows the smaller, so the page is not one flat strip. */
object Space { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 40.dp; val huge = 64.dp }

@Composable
fun Modifier.ring(focused: Boolean, shape: Shape): Modifier {
    val c = Soiboi.colors
    return if (focused) this.border(2.5.dp, c.accent, shape) else this
}

/**
 * Everything clickable goes through here: a spring press, a hover wash, a focus ring, a role and a label for
 * screen readers. With reduce-motion on the spring becomes an instant state change.
 */
@Composable
fun Modifier.press(
    onClick: () -> Unit, label: String? = null, role: Role = Role.Button, enabled: Boolean = true, shape: Shape = Soiboi.look.shape(10),
    hoverWash: Boolean = true, onLongClick: (() -> Unit)? = null,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    val reduce = Soiboi.reduceMotion
    val scale by animateFloatAsState(if (pressed && enabled) 0.965f else 1f, if (reduce) tween(0) else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")
    val c = Soiboi.colors
    return this
        .semantics { this.role = role; if (label != null) contentDescription = label }
        .scale(scale)
        .clip(shape)
        .drawBehind { if (hoverWash && hovered && enabled) drawRect(c.ink.copy(alpha = 0.06f)) }
        .ring(focused, shape)
        .hoverable(src)
        .pointerHoverIcon(PointerIcon.Hand)
        .clickable(interactionSource = src, indication = null, enabled = enabled, role = role, onClick = onClick)
        .alpha(if (enabled) 1f else 0.45f)
}

enum class Tone { Primary, Secondary, Ghost, Danger }

/** The one place the accent fills a button: [Tone.Primary]. Danger is its own colour so a destructive action never looks like a safe one. */
@Composable
fun SButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tone: Tone = Tone.Secondary, glyph: Glyph? = null,
    enabled: Boolean = true, loading: Boolean = false, compact: Boolean = false,
) {
    val c = Soiboi.colors; val look = Soiboi.look
    // A modest radius, not a pill: radius is hierarchy, and only the round play control is a full circle.
    val shape = look.shape(7)
    val (bg, fg, borderColor) = when (tone) {
        Tone.Primary -> Triple(c.accent, c.onAccent, c.accent)
        Tone.Danger -> Triple(c.danger, c.onDanger, c.danger)
        Tone.Secondary -> Triple(c.raised, c.ink, c.ink)
        Tone.Ghost -> Triple(Color.Transparent, c.ink, Color.Transparent)
    }
    Row(
        modifier.press(onClick, label, enabled = enabled && !loading, shape = shape, hoverWash = tone != Tone.Primary && tone != Tone.Danger)
            .background(bg, shape).border(1.5.dp, borderColor, shape)
            .padding(horizontal = if (compact) 12.dp else 18.dp, vertical = if (compact) 8.dp else 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = fg, strokeWidth = 2.dp)
        else if (glyph != null) GlyphIcon(glyph, size = 18.dp, tint = fg)
        if (loading || glyph != null) Spacer(Modifier.width(8.dp))
        BasicText(label, style = Soiboi.type.bodyStrong.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SIconButton(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Soiboi.colors.ink, size: Dp = 44.dp, iconSize: Dp = 22.dp, enabled: Boolean = true, background: Color = Color.Transparent) {
    Box(modifier.size(size).press(onClick, label, enabled = enabled, shape = CircleShape).background(background, CircleShape), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, size = iconSize, tint = tint)
    }
}

/** The play control: the single large accent shape in the app. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val c = Soiboi.colors
    Box(modifier.size(size).press(onClick, if (playing) "Pause" else "Play", shape = CircleShape).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
        GlyphIcon(if (playing) Glyph.Pause else Glyph.Play, size = size * 0.42f, tint = c.onAccent)
    }
}

/**
 * The lead block of a screen casts a hard offset shadow in Zine, as a sticker on paper would. Everything else
 * sits flat: elevation marks the one thing the eye should land on, nothing more.
 */
@Composable
fun Modifier.leadShadow(shape: Shape = Soiboi.look.shape(20), offset: Dp = 6.dp): Modifier {
    val c = Soiboi.colors; val look = Soiboi.look
    return if (!look.hardShadow) this.border(1.5.dp, c.ink, shape) else this.drawBehind {
        val o = offset.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        translate(o, o) { drawPath(Path().apply { addOutline(outline) }, c.ink) }
    }.border(1.5.dp, c.ink, shape)
}

/**
 * The quality sticker. Lossless is mustard with ink text and is the only filled badge in the app, so a glance down a
 * list separates lossless from lossy. Zine tilts it a few degrees as a stuck-on label; Liner Notes prints it straight.
 */
@Composable
fun QualitySticker(info: QualityInfo, modifier: Modifier = Modifier, large: Boolean = false) {
    if (info.tier == QualityTier.Unknown) return
    val c = Soiboi.colors; val look = Soiboi.look
    val lossless = info.tier == QualityTier.Lossless
    val shape = look.shape(5)
    val tilt = if (look.tiltedStickers) (if (lossless) -3f else 2f) else 0f
    val description = if (lossless) "Lossless, ${info.label}" else "Lossy, ${info.label}"
    Box(
        modifier.rotate(tilt).semantics { contentDescription = description }
            .background(if (lossless) c.lossless else Color.Transparent, shape).border(1.5.dp, if (lossless) c.onLossless else c.muted, shape)
            .padding(horizontal = if (large) 9.dp else 6.dp, vertical = if (large) 4.dp else 2.dp),
    ) {
        BasicText(info.label, style = (if (large) Soiboi.type.label else Soiboi.type.micro).copy(color = if (lossless) c.onLossless else c.soft), maxLines = 1)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(text, Modifier.weight(1f), style = Soiboi.type.headline.copy(color = Soiboi.colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) { Box(modifier.fillMaxWidth().height(1.dp).background(Soiboi.colors.rule)) }

@Composable
fun Txt(text: String, modifier: Modifier = Modifier, style: TextStyle = Soiboi.type.body, color: Color = Soiboi.colors.ink, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Ellipsis) =
    BasicText(text, modifier, style = style.copy(color = color), maxLines = maxLines, overflow = overflow)

/** A status line: a full hairline border, a real glyph and words, never colour alone. */
@Composable
fun Banner(title: String, detail: String? = null, glyph: Glyph = Glyph.Info, tint: Color = Soiboi.colors.ink, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(12)
    Row(modifier.fillMaxWidth().background(c.raised, shape).border(1.5.dp, c.rule, shape).padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
        GlyphIcon(glyph, size = 20.dp, tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(title, style = Soiboi.type.bodyStrong)
            if (detail != null) Txt(detail, style = Soiboi.type.caption, color = c.soft)
        }
        action?.invoke()
    }
}

/** Says why it is empty and offers the one thing that fills it. */
@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier, glyph: Glyph = Glyph.Disc, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = Space.xxl, horizontal = Space.l), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        GlyphIcon(glyph, size = 36.dp, tint = Soiboi.colors.muted)
        Txt(title, style = Soiboi.type.headline)
        Txt(detail, style = Soiboi.type.body, color = Soiboi.colors.soft)
        action?.invoke()
    }
}

@Composable
fun Loading(label: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), color = Soiboi.colors.accent, strokeWidth = 2.5.dp)
        Txt(label, style = Soiboi.type.body, color = Soiboi.colors.soft)
    }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: Glyph? = null) {
    val c = Soiboi.colors; val shape = Soiboi.look.shape(10)
    Row(
        modifier.press(onClick, label, role = Role.Tab, shape = shape).background(if (selected) c.ink else Color.Transparent, shape).border(1.5.dp, if (selected) c.ink else c.rule, shape)
            .semantics { this.contentDescription = if (selected) "$label, selected" else label }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (glyph != null) GlyphIcon(glyph, size = 16.dp, tint = if (selected) c.paper else c.ink)
        Txt(label, style = Soiboi.type.label, color = if (selected) c.paper else c.ink, maxLines = 1)
    }
}
