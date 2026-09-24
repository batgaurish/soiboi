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

/// The flavour's own colours, taken from its design mockup. They fill in
/// when no colour source is chosen, so "App colours" means the flavour's
/// colours. Matugen and prebuilt palettes still win over them.
Color? flavourColor(ColorToken? token, {required bool isDark}) {
  if (token == null || colorSourceNotifier.value != ColorSource.off) {
    return null;
  }
  final spec = flavourPalettes[flavourNotifier.value]!;
  return (isDark ? spec.dark : spec.light)[token];
}

/// A flavour palette spans its mockup's whole set of colours, not one base:
/// the sidebar, bottom bar, controls and volume bar each take a different
/// role. Text on every one of them is the palette's own text colour, so each
/// role colour is picked to keep that text readable.
PrebuiltPaletteSpec _flavourSpec({
  required PrebuiltPaletteSpec base,
  required Map<ColorToken, Color> light,
  required Map<ColorToken, Color> dark,
}) => (light: {...base.light, ...light}, dark: {...base.dark, ...dark});

Map<ColorToken, Color> _roles({
  required Color sidebar,
  required Color bottom,
  required Color control,
  required Color field,
  required Color selected,
  required Color second,
}) => {
  ColorToken.sidebar: sidebar,
  ColorToken.bottom: bottom,
  ColorToken.button: control,
  ColorToken.menu: field,
  ColorToken.searchField: field,
  ColorToken.selectedItem: selected,
  ColorToken.volumeBar: second,
  ColorToken.lyricsButton: control,
  ColorToken.lyricsSelectedItem: selected,
};

final Map<Flavour, PrebuiltPaletteSpec> flavourPalettes = {
  // Paper and ink: letterpress red, bottle green, mustard.
  Flavour.linerNotes: _flavourSpec(
    base: _spec(
      bgLight: const Color(0xFFF3EDE1),
      surfaceLight: const Color(0xFFFBF8F1),
      mantleLight: const Color(0xFFEBE3D3),
      textLight: const Color(0xFF1F1A14),
      subtextLight: const Color(0xFF5E5245),
      accentLight: const Color(0xFFB8431F),
      bgDark: const Color(0xFF1C1813),
      surfaceDark: const Color(0xFF26211B),
      mantleDark: const Color(0xFF15120E),
      textDark: const Color(0xFFF3EDE1),
      subtextDark: const Color(0xFFC4B8A4),
      accentDark: const Color(0xFFE0714A),
    ),
    light: _roles(
      sidebar: const Color(0xFFE2C35C),
      bottom: const Color(0xFFC5D2BD),
      control: const Color(0xFFF0DCC9),
      field: const Color(0xFFFBF8F1),
      selected: const Color(0xFFF3EDE1),
      second: const Color(0xFF2F4A3A),
    ),
    dark: _roles(
      sidebar: const Color(0xFF2F4A3A),
      bottom: const Color(0xFF4A3A14),
      control: const Color(0xFF5A2A1A),
      field: const Color(0xFF26211B),
      selected: const Color(0xFF1C1813),
      second: const Color(0xFFE2C35C),
    ),
  ),
  // Equipment black: green readout, amber warning, rack blue.
  Flavour.signal: _flavourSpec(
    base: _spec(
      bgLight: const Color(0xFFE9ECE9),
      surfaceLight: const Color(0xFFF5F7F5),
      mantleLight: const Color(0xFFDDE2DE),
      textLight: const Color(0xFF0D0F0E),
      subtextLight: const Color(0xFF3F4A43),
      accentLight: const Color(0xFF1A8A44),
      bgDark: const Color(0xFF0D0F0E),
      surfaceDark: const Color(0xFF171A18),
      mantleDark: const Color(0xFF111312),
      textDark: const Color(0xFFE6EDE8),
      subtextDark: const Color(0xFF9AA69F),
      accentDark: const Color(0xFF7CF29A),
    ),
    light: _roles(
      sidebar: const Color(0xFFBFE8CB),
      bottom: const Color(0xFFF2D9A0),
      control: const Color(0xFFCFD8E8),
      field: const Color(0xFFF5F7F5),
      selected: const Color(0xFFE9ECE9),
      second: const Color(0xFFB7791F),
    ),
    dark: _roles(
      sidebar: const Color(0xFF12281A),
      bottom: const Color(0xFF2A2210),
      control: const Color(0xFF1B2433),
      field: const Color(0xFF171A18),
      selected: const Color(0xFF0D0F0E),
      second: const Color(0xFFF2B84C),
    ),
  ),
  // Cream stock: marker blue, tangerine, mint, highlighter yellow.
  Flavour.zine: _flavourSpec(
    base: _spec(
      bgLight: const Color(0xFFFFF4D6),
      surfaceLight: const Color(0xFFFFFFFF),
      mantleLight: const Color(0xFFFFE9A8),
      textLight: const Color(0xFF161616),
      subtextLight: const Color(0xFF3D3D3D),
      accentLight: const Color(0xFF3B3BD9),
      bgDark: const Color(0xFF17161F),
      surfaceDark: const Color(0xFF22212D),
      mantleDark: const Color(0xFF111018),
      textDark: const Color(0xFFFFF4D6),
      subtextDark: const Color(0xFFD9D3C0),
      accentDark: const Color(0xFF8F8FFF),
    ),
    light: _roles(
      sidebar: const Color(0xFFFF8A5C),
      bottom: const Color(0xFF7FD8B8),
      control: const Color(0xFFFFD23F),
      field: const Color(0xFFFFFFFF),
      selected: const Color(0xFFFFF4D6),
      second: const Color(0xFF16A37F),
    ),
    dark: _roles(
      sidebar: const Color(0xFF2B2B8C),
      bottom: const Color(0xFF0F4A3A),
      control: const Color(0xFF5C3A00),
      field: const Color(0xFF22212D),
      selected: const Color(0xFF17161F),
      second: const Color(0xFFFF8A5C),
    ),
  ),
};

Color? prebuiltColor(ColorToken? token, {required bool isDark}) {
  if (token == null || colorSourceNotifier.value != ColorSource.prebuilt) {
    return null;
  }
  final spec = prebuiltPalettes[prebuiltPaletteNotifier.value]!;
  return (isDark ? spec.dark : spec.light)[token];
}

/// [c] with its hue turned by [degrees].
Color _turn(Color c, double degrees) {
  final hsl = HSLColor.fromColor(c);
  return hsl.withHue((hsl.hue + degrees) % 360).toColor();
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
    // Regions are tinted from the accent and two hue turns of it, so a
    // palette with one accent still spans more than one colour.
    ColorToken.sidebar: Color.lerp(bg, accent, 0.28)!,
    ColorToken.bottom: Color.lerp(bg, _turn(accent, 150), 0.24)!,
    ColorToken.menu: surface,
    ColorToken.button: Color.lerp(bg, _turn(accent, 60), 0.3)!,
    ColorToken.searchField: surface,
    ColorToken.selectedItem: bg,
    ColorToken.divider: subtext.withAlpha(70),
    ColorToken.text: subtext,
    ColorToken.highlightText: text,
    ColorToken.icon: subtext,
    ColorToken.seekBar: accent,
    ColorToken.volumeBar: _turn(accent, 60),
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
