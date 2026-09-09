/// Where the app's colours come from — independent of [Flavour], which only
/// governs shape and motion (see `flavour.dart` for why they were split).
///
/// Three sources, mutually exclusive, plus "off" (the app's own hand-picked
/// light/dark colours, unchanged from upstream):
///
///  * **Matugen** — the system's own palette. Linux reads or generates one
///    via matugen; Android reads the platform's Material You palette. This is
///    what `dynamic_color.dart` already builds; this file just adds the
///    on/off switch that used to be a single boolean ("Follow system
///    colours") to a three-way choice.
///  * **Prebuilt** — a hand-picked, named palette (Dracula, Nord, ...), for
///    people who want a specific look rather than whatever their wallpaper
///    happens to produce.
///  * **Album art** — not handled here at all. It is `ThemeType.vivid`,
///    already implemented in `color_manager.dart` (`MyColor.vividModeValue`
///    / `getVividValue`) and already exposed per-page in the Theme setting.
///    Vivid bypasses this file's token/palette lookup entirely, so there is
///    nothing to add for it here — selecting "Album art" as a source just
///    means setting the page's `ThemeType` to `.vivid`.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/theme/flavour.dart';

enum ColorSource { off, matugen, prebuilt }

/// Which source is active. `off` means "the app's own colours" — not "no
/// colour", just none of the two overrides below.
final colorSourceNotifier = ValueNotifier(ColorSource.off);

/// Named, hand-picked palettes. Chosen for being widely recognisable rather
/// than for covering every popular scheme — a short, good list beats a long
/// mediocre one.
enum PrebuiltPalette { dracula, tokyoNight, catppuccin, nord, gruvbox, rosePine }

extension PrebuiltPaletteLabel on PrebuiltPalette {
  String get label => switch (this) {
    PrebuiltPalette.dracula => 'Dracula',
    PrebuiltPalette.tokyoNight => 'Tokyo Night',
    PrebuiltPalette.catppuccin => 'Catppuccin',
    PrebuiltPalette.nord => 'Nord',
    PrebuiltPalette.gruvbox => 'Gruvbox',
    PrebuiltPalette.rosePine => 'Rosé Pine',
  };

  /// The colour shown as a swatch/preview for this palette, in the current
  /// brightness — deliberately the accent, not the background, since two
  /// dark palettes can look identical at a glance but never share an accent.
  Color accent({required bool isDark}) =>
      (isDark ? prebuiltPalettes[this]!.dark : prebuiltPalettes[this]!.light)[ColorToken.seekBar]!;
}

/// Active prebuilt palette. Only consulted when [colorSourceNotifier] is
/// [ColorSource.prebuilt].
final prebuiltPaletteNotifier = ValueNotifier(PrebuiltPalette.dracula);

Color? prebuiltColor(ColorToken? token, {required bool isDark}) {
  if (token == null || colorSourceNotifier.value != ColorSource.prebuilt) {
    return null;
  }
  final spec = prebuiltPalettes[prebuiltPaletteNotifier.value]!;
  return (isDark ? spec.dark : spec.light)[token];
}

typedef PrebuiltPaletteSpec = ({Palette light, Palette dark});

/// Builds both brightnesses of a palette from a handful of named roles,
/// rather than writing out all 23 [ColorToken]s by hand per palette — the
/// roles are what actually distinguish one scheme from another; the mapping
/// from role to token is the same shape every time.
PrebuiltPaletteSpec _spec({
  required Color bgLight,
  required Color surfaceLight,
  required Color mantleLight,
  required Color textLight,
  required Color subtextLight,
  required Color accentLight,
  required Color bgDark,
  required Color surfaceDark,
  required Color mantleDark,
  required Color textDark,
  required Color subtextDark,
  required Color accentDark,
}) {
  Palette build({
    required Color bg,
    required Color surface,
    required Color mantle,
    required Color text,
    required Color subtext,
    required Color accent,
  }) => {
    ColorToken.pageBackground: bg,
    ColorToken.panel: bg,
    ColorToken.sidebar: mantle,
    ColorToken.bottom: mantle,
    ColorToken.menu: surface,
    ColorToken.button: surface,
    ColorToken.searchField: surface,
    ColorToken.selectedItem: accent.withAlpha(60),
    ColorToken.divider: subtext.withAlpha(70),
    ColorToken.text: subtext,
    ColorToken.highlightText: text,
    ColorToken.icon: subtext,
    ColorToken.seekBar: accent,
    ColorToken.volumeBar: accent,
    ColorToken.switchTrack: accent,
    ColorToken.glass: surface.withAlpha(210),
    ColorToken.lyricsBackground: bg,
    ColorToken.lyricsForeground: subtext,
    ColorToken.lyricsHighlightText: text,
    ColorToken.lyricsButton: surface,
    ColorToken.lyricsDivider: subtext.withAlpha(70),
    ColorToken.lyricsSelectedItem: accent.withAlpha(60),
    ColorToken.lyricsMenu: surface,
  };

  return (
    light: build(
      bg: bgLight,
      surface: surfaceLight,
      mantle: mantleLight,
      text: textLight,
      subtext: subtextLight,
      accent: accentLight,
    ),
    dark: build(
      bg: bgDark,
      surface: surfaceDark,
      mantle: mantleDark,
      text: textDark,
      subtext: subtextDark,
      accent: accentDark,
    ),
  );
}

final Map<PrebuiltPalette, PrebuiltPaletteSpec> prebuiltPalettes = {
  // Dracula has no official light variant; this one keeps its purple/pink
  // accents on a paper-toned ground rather than inventing an unrelated look.
  PrebuiltPalette.dracula: _spec(
    bgLight: const Color(0xFFF8F8F2),
    surfaceLight: const Color(0xFFE9E9F0),
    mantleLight: const Color(0xFFDEDEE8),
    textLight: const Color(0xFF282A36),
    subtextLight: const Color(0xFF44475A),
    accentLight: const Color(0xFFBD5FD9),
    bgDark: const Color(0xFF282A36),
    surfaceDark: const Color(0xFF343746),
    mantleDark: const Color(0xFF21222C),
    textDark: const Color(0xFFF8F8F2),
    subtextDark: const Color(0xFFBFBFD9),
    accentDark: const Color(0xFFBD93F9),
  ),
  // "Tokyo Night Day" is the official light companion.
  PrebuiltPalette.tokyoNight: _spec(
    bgLight: const Color(0xFFE1E2E7),
    surfaceLight: const Color(0xFFD0D3DB),
    mantleLight: const Color(0xFFC4C8D1),
    textLight: const Color(0xFF3760BF),
    subtextLight: const Color(0xFF565A6E),
    accentLight: const Color(0xFF2E7DE1),
    bgDark: const Color(0xFF1A1B26),
    surfaceDark: const Color(0xFF292E42),
    mantleDark: const Color(0xFF16161E),
    textDark: const Color(0xFFC0CAF5),
    subtextDark: const Color(0xFF9AA5CE),
    accentDark: const Color(0xFF7AA2F7),
  ),
  // Catppuccin's own pairing: Latte (light) and Mocha (dark).
  PrebuiltPalette.catppuccin: _spec(
    bgLight: const Color(0xFFEFF1F5),
    surfaceLight: const Color(0xFFCCD0DA),
    mantleLight: const Color(0xFFE6E9EF),
    textLight: const Color(0xFF4C4F69),
    subtextLight: const Color(0xFF6C6F85),
    accentLight: const Color(0xFF8839EF),
    bgDark: const Color(0xFF1E1E2E),
    surfaceDark: const Color(0xFF313244),
    mantleDark: const Color(0xFF181825),
    textDark: const Color(0xFFCDD6F4),
    subtextDark: const Color(0xFFA6ADC8),
    accentDark: const Color(0xFFCBA6F7),
  ),
  // Nord has no official light mode; this swaps its Polar Night / Snow Storm
  // roles rather than inventing colours foreign to the palette.
  PrebuiltPalette.nord: _spec(
    bgLight: const Color(0xFFECEFF4),
    surfaceLight: const Color(0xFFE5E9F0),
    mantleLight: const Color(0xFFD8DEE9),
    textLight: const Color(0xFF2E3440),
    subtextLight: const Color(0xFF4C566A),
    accentLight: const Color(0xFF5E81AC),
    bgDark: const Color(0xFF2E3440),
    surfaceDark: const Color(0xFF3B4252),
    mantleDark: const Color(0xFF242933),
    textDark: const Color(0xFFECEFF4),
    subtextDark: const Color(0xFFD8DEE9),
    accentDark: const Color(0xFF88C0D0),
  ),
  // Gruvbox ships both variants officially.
  PrebuiltPalette.gruvbox: _spec(
    bgLight: const Color(0xFFFBF1C7),
    surfaceLight: const Color(0xFFEBDBB2),
    mantleLight: const Color(0xFFD5C4A1),
    textLight: const Color(0xFF3C3836),
    subtextLight: const Color(0xFF7C6F64),
    accentLight: const Color(0xFFD65D0E),
    bgDark: const Color(0xFF282828),
    surfaceDark: const Color(0xFF3C3836),
    mantleDark: const Color(0xFF1D2021),
    textDark: const Color(0xFFEBDBB2),
    subtextDark: const Color(0xFFA89984),
    accentDark: const Color(0xFFFE8019),
  ),
  // Rosé Pine ships both variants officially (Main and Dawn).
  PrebuiltPalette.rosePine: _spec(
    bgLight: const Color(0xFFFAF4ED),
    surfaceLight: const Color(0xFFFFFAF3),
    mantleLight: const Color(0xFFF2E9E1),
    textLight: const Color(0xFF575279),
    subtextLight: const Color(0xFF797593),
    accentLight: const Color(0xFF907AA9),
    bgDark: const Color(0xFF191724),
    surfaceDark: const Color(0xFF1F1D2E),
    mantleDark: const Color(0xFF26233A),
    textDark: const Color(0xFFE0DEF4),
    subtextDark: const Color(0xFF908CAA),
    accentDark: const Color(0xFFC4A7E7),
  ),
};
