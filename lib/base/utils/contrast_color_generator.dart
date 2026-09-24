import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/utils/contrast.dart';

class ContrastColorTextTheme {
  final Color regular;
  final Color accent;

  ContrastColorTextTheme({required this.regular, required this.accent});
}

class ContrastColorGenerator {
  /// Regular: High-contrast complementary tint for best readability.
  /// Accent: Subtle neighboring hue for a gentle highlight.
  ///
  /// [backgroundColor] is the cover's average colour, which vivid mode lays
  /// over the blurred artwork at about 70%, so it is what the text sits on.
  /// Both colours are guaranteed [kTextContrast] against it: the tint is
  /// chosen first, then its lightness nudged just far enough to read, so a
  /// cover's character survives wherever it can.
  static ContrastColorTextTheme generate(Color backgroundColor) {
    final background = backgroundColor.withAlpha(255);
    final hsl = HSLColor.fromColor(background);

    // Light text wherever white reads better than black. The old cut-off
    // (luminance 0.45) put light text on mid-tone covers, where even white
    // falls short: a grey cover got pale grey lyrics at about 2:1.
    final bool isDark =
        contrastRatio(Colors.white, background) >=
        contrastRatio(Colors.black, background);

    // --- 1. Regular Text (Optimized for Readability) ---
    // We use the 180° hue shift but keep saturation very low.
    // This "cuts" through the background color so it doesn't look blurry.
    Color regularColor = HSLColor.fromAHSL(
      1.0,
      (hsl.hue + 180) % 360,
      0.10, // Very low saturation to keep it clean
      isDark ? 0.90 : 0.15, // High contrast for clarity
    ).toColor();

    // --- 2. Subtle Accent Color ---
    // Logic: Instead of rotating 180°, we only rotate 15-30°.
    // We increase saturation slightly to make it "pop" without clashing.
    Color accentColor = HSLColor.fromAHSL(
      1.0,
      (hsl.hue + 20) % 360, // Slight shift to a neighboring hue
      (hsl.saturation + 0.3).clamp(0.4, 0.6), // Moderate saturation boost
      isDark
          ? 0.95
          : 0.15, // Make it slightly closer to white/black than the regular text
    ).toColor();

    return ContrastColorTextTheme(
      regular: ensureContrast(regularColor, background),
      accent: ensureContrast(accentColor, background),
    );
  }
}
