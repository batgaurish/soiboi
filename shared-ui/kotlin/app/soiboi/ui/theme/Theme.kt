package app.soiboi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.soiboi.engine.core.Flavour
import app.soiboi.engine.core.ThemeMode

/**
 * Colour is a short list on purpose. Paper and ink are neutrals and carry the weight. Vermilion is the one
 * accent: the play control and the place you are now, nothing else. Mustard exists only as the lossless
 * sticker, because codec quality must be obvious in every theme and that is a real state, not decoration.
 */
@Immutable
class SoiboiColors(
    val paper: Color, val raised: Color, val sunk: Color,
    val ink: Color, val soft: Color, val muted: Color, val rule: Color,
    val accent: Color, val onAccent: Color,
    val lossless: Color, val onLossless: Color,
    val danger: Color, val onDanger: Color, val ok: Color,
    val scrim: Color, val isDark: Boolean,
)

private val LightPaper = SoiboiColors(
    paper = Color(0xFFF3EEE4), raised = Color(0xFFFBF8F2), sunk = Color(0xFFE7E0D1),
    ink = Color(0xFF1C1A16), soft = Color(0xFF47433B), muted = Color(0xFF666154), rule = Color(0xFFCBC2AF),
    accent = Color(0xFFC93418), onAccent = Color(0xFFFFFFFF),
    lossless = Color(0xFFF2B705), onLossless = Color(0xFF1C1A16),
    danger = Color(0xFFA3261A), onDanger = Color(0xFFFFFFFF), ok = Color(0xFF2F6B3A),
    scrim = Color(0x991C1A16), isDark = false,
)

internal val DarkInk = SoiboiColors(
    paper = Color(0xFF161411), raised = Color(0xFF211E19), sunk = Color(0xFF0F0D0B),
    ink = Color(0xFFF1ECE1), soft = Color(0xFFCBC5B8), muted = Color(0xFFA29C8E), rule = Color(0xFF3A352D),
    accent = Color(0xFFFF7457), onAccent = Color(0xFF1A0A06),
    lossless = Color(0xFFF2B705), onLossless = Color(0xFF1C1A16),
    danger = Color(0xFFFF8A7A), onDanger = Color(0xFF2A0A06), ok = Color(0xFF7BC68A),
    scrim = Color(0xB3000000), isDark = true,
)

internal val SpotifyDark = SoiboiColors(
    paper = Color(0xFF121212), raised = Color(0xFF1F1F1F), sunk = Color(0xFF000000),
    ink = Color(0xFFFFFFFF), soft = Color(0xFFB3B3B3), muted = Color(0xFF8A8A8A), rule = Color(0xFF2E2E2E),
    accent = Color(0xFF1ED760), onAccent = Color(0xFF000000),
    lossless = Color(0xFFF2B705), onLossless = Color(0xFF1C1A16),
    danger = Color(0xFFFF6B5E), onDanger = Color(0xFF2A0A06), ok = Color(0xFF1ED760),
    scrim = Color(0xB3000000), isDark = true,
)

private val SpotifyLight = SoiboiColors(
    paper = Color(0xFFFFFFFF), raised = Color(0xFFF2F2F2), sunk = Color(0xFFE6E6E6),
    ink = Color(0xFF121212), soft = Color(0xFF4D4D4D), muted = Color(0xFF6A6A6A), rule = Color(0xFFD9D9D9),
    accent = Color(0xFF15A84A), onAccent = Color(0xFFFFFFFF),
    lossless = Color(0xFFF2B705), onLossless = Color(0xFF1C1A16),
    danger = Color(0xFFB3261E), onDanger = Color(0xFFFFFFFF), ok = Color(0xFF15A84A),
    scrim = Color(0x99000000), isDark = false,
)

/**
 * The flavours differ in shape, voice and badge, never in colour: switching flavour always visibly changes
 * the app, and switching light or dark never resets the corners. Zine is chunky and sticker-flavoured; Liner
 * Notes is a record sleeve with a serif voice and small corners.
 */
@Immutable
class Look(
    val flavour: Flavour,
    val cornerScale: Float,
    /** Whether the one lead block per screen casts a hard offset shadow (Zine) or sits flat (Liner Notes). */
    val hardShadow: Boolean,
    /** Sticker badges tilt a few degrees (Zine) or print straight as labels (Liner Notes). */
    val tiltedStickers: Boolean,
    val density: Float,
) {
    fun r(base: Int): Dp = (base * cornerScale).dp
    fun shape(base: Int) = RoundedCornerShape(r(base))
}

@Immutable
class SoiboiType(
    val display: TextStyle, val headline: TextStyle, val title: TextStyle, val body: TextStyle,
    val bodyStrong: TextStyle, val label: TextStyle, val caption: TextStyle, val micro: TextStyle,
)

/** The three font families the shell loads from the bundled files. */
class AppFonts(val zine: FontFamily, val linerDisplay: FontFamily, val linerBody: FontFamily)

val LocalSoiboiColors = compositionLocalOf { LightPaper }
val LocalLook = compositionLocalOf { Look(Flavour.Zine, 1.6f, true, true, 1.0f) }
val LocalType = compositionLocalOf<SoiboiType> { error("SoiboiTheme missing") }
val LocalReduceMotion = compositionLocalOf { false }

object Soiboi {
    val colors: SoiboiColors @Composable @ReadOnlyComposable get() = LocalSoiboiColors.current
    val look: Look @Composable @ReadOnlyComposable get() = LocalLook.current
    val type: SoiboiType @Composable @ReadOnlyComposable get() = LocalType.current
    val reduceMotion: Boolean @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

/**
 * Display type is at least 2.5x the body size on every screen that has a lead: body is 15sp and the display
 * step is 44sp (2.9x). Zine sets headings in Bricolage 800; Liner Notes sets them in Fraunces and the body in
 * IBM Plex Sans, as a sleeve's credits read.
 */
private fun typeFor(flavour: Flavour, f: AppFonts): SoiboiType {
    val zine = flavour == Flavour.Zine
    val spotify = flavour == Flavour.Spotify
    val head = if (zine || spotify) f.zine else f.linerDisplay
    val body = if (zine || spotify) f.zine else f.linerBody
    val hw = if (zine) FontWeight.W800 else if (spotify) FontWeight.W800 else FontWeight.W700
    return SoiboiType(
        display = TextStyle(fontFamily = head, fontWeight = hw, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = if (zine || spotify) (-1).sp else (-0.5).sp),
        headline = TextStyle(fontFamily = head, fontWeight = hw, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = if (zine) (-0.5).sp else 0.sp),
        title = TextStyle(fontFamily = head, fontWeight = if (zine) FontWeight.W800 else FontWeight.W600, fontSize = 18.sp, lineHeight = 22.sp),
        body = TextStyle(fontFamily = body, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 22.sp),
        bodyStrong = TextStyle(fontFamily = body, fontWeight = FontWeight.W600, fontSize = 15.sp, lineHeight = 22.sp),
        label = TextStyle(fontFamily = body, fontWeight = FontWeight.W600, fontSize = 13.sp, lineHeight = 16.sp),
        caption = TextStyle(fontFamily = body, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp),
        micro = TextStyle(fontFamily = body, fontWeight = FontWeight.W600, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.3.sp),
    )
}

@Composable
fun SoiboiTheme(flavour: Flavour, mode: ThemeMode, reduceMotion: Boolean, fonts: AppFonts, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.Dark -> true; ThemeMode.Light -> false; ThemeMode.System -> isSystemInDarkTheme() }
    val c = when (flavour) { Flavour.Spotify -> if (dark) SpotifyDark else SpotifyLight; else -> if (dark) DarkInk else LightPaper }
    val look = remember(flavour) {
        when (flavour) {
            Flavour.Zine -> Look(flavour, 1.6f, hardShadow = true, tiltedStickers = true, density = 1.0f)
            Flavour.Spotify -> Look(flavour, 0.7f, hardShadow = false, tiltedStickers = false, density = 1.0f)
            Flavour.LinerNotes -> Look(flavour, 0.5f, hardShadow = false, tiltedStickers = false, density = 0.92f)
        }
    }
    val type = remember(flavour, fonts) { typeFor(flavour, fonts) }
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.paper, onBackground = c.ink, surface = c.raised, onSurface = c.ink,
        surfaceVariant = c.sunk, onSurfaceVariant = c.soft, outline = c.rule, error = c.danger, onError = c.onDanger,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = c.onAccent, background = c.paper, onBackground = c.ink, surface = c.raised, onSurface = c.ink,
        surfaceVariant = c.sunk, onSurfaceVariant = c.soft, outline = c.rule, error = c.danger, onError = c.onDanger,
    )
    CompositionLocalProvider(
        LocalSoiboiColors provides c, LocalLook provides look, LocalType provides type, LocalReduceMotion provides reduceMotion,
        LocalContentColor provides c.ink,
    ) {
        MaterialTheme(colorScheme = scheme) { content() }
    }
}

/** The colours for surfaces that are always dark, such as the full-screen player, whatever the app theme. */
@Composable
@ReadOnlyComposable
fun playerColors(): SoiboiColors = if (Soiboi.look.flavour == Flavour.Spotify) SpotifyDark else DarkInk
