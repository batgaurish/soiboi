/// Minimum contrast, per WCAG 2. Re-exported by `color_manager.dart`.
library;

import 'package:material_ui/material_ui.dart';

/// WCAG 2's minimum for body text.
const double kTextContrast = 4.5;

/// WCAG 2's minimum for large text, icons and other non-text marks.
const double kLargeContrast = 3.0;

/// The WCAG 2 contrast ratio of [foreground] drawn on [background], from 1
/// (identical) to 21 (black on white).
///
/// A translucent foreground is blended onto the background first, since that
/// is what reaches the eye. The background is taken as opaque: when it is
/// itself translucent, pass the colour it ends up as on screen.
double contrastRatio(Color foreground, Color background) {
  final ground = background.withAlpha(255);
  final a = Color.alphaBlend(foreground, ground).computeLuminance();
  final b = ground.computeLuminance();
  final hi = a > b ? a : b;
  final lo = a > b ? b : a;
  return (hi + 0.05) / (lo + 0.05);
}

/// [color], or the nearest colour of the same hue and saturation that reads
/// on [background] at [minRatio].
///
/// Only lightness moves, and only as far as it must, so a palette taken
/// from artwork keeps its character instead of collapsing to black and
/// white. It moves away from the background (darker on light grounds,
/// lighter on dark ones), and tries the other way only when that cannot
/// reach [minRatio]. Where neither can, black or white, whichever reads
/// better.
Color ensureContrast(
  Color color,
  Color background, {
  double minRatio = kTextContrast,
}) {
  final opaque = color.withAlpha(255);
  if (contrastRatio(opaque, background) >= minRatio) return color;

  final hsl = HSLColor.fromColor(opaque);
  final ground = background.withAlpha(255);
  final darkerFirst =
      contrastRatio(Colors.black, ground) >=
      contrastRatio(Colors.white, ground);

  Color? search(double target) {
    final extreme = hsl.withLightness(target).toColor();
    if (contrastRatio(extreme, ground) < minRatio) return null;
    // Luminance rises with HSL lightness at a fixed hue and saturation, so
    // the smallest move that passes can be bisected for.
    var passing = target;
    var failing = hsl.lightness;
    for (var i = 0; i < 24; i++) {
      final middle = (passing + failing) / 2;
      if (contrastRatio(hsl.withLightness(middle).toColor(), ground) >=
          minRatio) {
        passing = middle;
      } else {
        failing = middle;
      }
    }
    return hsl.withLightness(passing).toColor();
  }

  final found =
      search(darkerFirst ? 0 : 1) ??
      search(darkerFirst ? 1 : 0) ??
      (darkerFirst ? Colors.black : Colors.white);
  return found.withAlpha((color.a * 255).round());
}

/// [preferred] if it reads on [background] at [minRatio], else [fallback].
/// For when only a known-good alternative will do, rather than a nudged
/// version of the preferred colour.
Color readableOr(
  Color preferred,
  Color background,
  Color fallback, {
  double minRatio = kLargeContrast,
}) => contrastRatio(preferred, background) >= minRatio ? preferred : fallback;

/// [color], nudged until it reads on every one of [backgrounds].
///
/// The surfaces of one theme share a brightness, so moving away from one
/// moves away from all of them; a few passes settle it.
Color ensureContrastOnAll(
  Color color,
  Iterable<Color> backgrounds, {
  double minRatio = kTextContrast,
}) {
  final grounds = backgrounds.toList();
  var result = color;
  for (var pass = 0; pass < 3; pass++) {
    var changed = false;
    for (final ground in grounds) {
      final next = ensureContrast(result, ground, minRatio: minRatio);
      if (next != result) {
        result = next;
        changed = true;
      }
    }
    if (!changed) break;
  }
  return result;
}
