/// Flavour system.
///
/// Upstream Sylvakru resolves every colour through three fixed modes on
/// [MyColor]: vivid (derived from album art), light, and dark. That works, but
/// adding a new visual identity means editing all 29 colour declarations.
///
/// This file makes the identity *data* instead. A [Flavour] supplies a palette
/// keyed by [ColorToken]; `MyColor.updateColor()` consults the active flavour
/// first and falls back to the upstream light/dark values when a flavour leaves
/// a token undefined. Consequences worth knowing:
///
///  * Nothing changes for the ~54 files that read `MyColor.valueNotifier`.
///  * A flavour may define as few tokens as it likes; the rest inherit.
///  * `ThemeType.vivid` is deliberately untouched. It is dynamic colour derived
///    from the current artwork, which is orthogonal to flavour — a flavour says
///    *how the app looks*, vivid says *where its colours come from*.
library;

import 'package:material_ui/material_ui.dart';

/// The visual identity. Orthogonal to light/dark and to vivid.
enum Flavour {
  /// Material 3 Expressive: big shapes, one loud accent, springy motion.
  expressive,

  /// Frosted translucent panes, album art blooming behind every surface.
  glasshouse,

  /// Dense, typographic, near-motionless. Built for large libraries.
  console,
}

extension FlavourLabel on Flavour {
  String get label => switch (this) {
    Flavour.expressive => 'Expressive',
    Flavour.glasshouse => 'Glasshouse',
    Flavour.console => 'Console',
  };

  String get blurb => switch (this) {
    Flavour.expressive => 'Bold shapes and springy motion',
    Flavour.glasshouse => 'Frosted panes lit by album art',
    Flavour.console => 'Dense rows for large libraries',
  };
}

/// Semantic names for every themeable surface, one per upstream `MyColor`.
enum ColorToken {
  // main page
  pageBackground,
  icon,
  text,
  highlightText,
  switchTrack,
  glass,
  panel,
  sidebar,
  bottom,
  searchField,
  button,
  divider,
  selectedItem,
  menu,
  seekBar,
  volumeBar,
  // lyrics page
  lyricsBackground,
  lyricsForeground,
  lyricsHighlightText,
  lyricsButton,
  lyricsDivider,
  lyricsSelectedItem,
  lyricsMenu,
  // mini view
  miniForeground,
  miniHighlightText,
  miniDivider,
  miniButton,
  miniSelectedItem,
  miniMenu,
}

/// A flavour's colours for one brightness.
typedef Palette = Map<ColorToken, Color>;

class FlavourSpec {
  const FlavourSpec({
    required this.light,
    required this.dark,
    this.surfaceTreatment = SurfaceTreatment.flat,
    this.cornerScale = 1.0,
    this.density = Density.comfortable,
  });

  final Palette light;
  final Palette dark;

  /// How panels are painted. Glass surfaces need a `BackdropFilter`, which is
  /// a widget concern rather than a colour, so widgets branch on this.
  final SurfaceTreatment surfaceTreatment;

  /// Multiplier applied to corner radii. Expressive rounds hard, Console barely.
  final double cornerScale;

  /// Row height and padding scale.
  final Density density;
}

enum SurfaceTreatment { flat, glass, solid }

enum Density { compact, comfortable, spacious }

// ---------------------------------------------------------------------------
// Expressive — the default.
// Warm paper, deep violet structure, amber reserved for the single loudest
// control on screen.
// ---------------------------------------------------------------------------
const _expressive = FlavourSpec(
  surfaceTreatment: SurfaceTreatment.solid,
  cornerScale: 1.6,
  density: Density.spacious,
  light: {
    ColorToken.pageBackground: Color(0xFFFDF7F2),
    ColorToken.panel: Color(0xFFFFFFFF),
    ColorToken.sidebar: Color(0xFFF5E9DF),
    ColorToken.bottom: Color(0xFFF0E4DA),
    ColorToken.text: Color(0xFF1D1B18),
    ColorToken.highlightText: Color(0xFF0F0D0B),
    ColorToken.icon: Color(0xFF514840),
    ColorToken.divider: Color(0xFFE5D8CC),
    ColorToken.button: Color(0xFFEADFD4),
    ColorToken.selectedItem: Color(0xFFE3DEF2),
    ColorToken.searchField: Color(0xFFF0E4DA),
    ColorToken.menu: Color(0xFFFFFFFF),
    ColorToken.seekBar: Color(0xFFE2713C),
    ColorToken.volumeBar: Color(0xFF4C3D8F),
    ColorToken.switchTrack: Color(0xFF4C3D8F),
    ColorToken.glass: Color(0x99FFFFFF),
    ColorToken.lyricsBackground: Color(0xFFFDF7F2),
    ColorToken.lyricsForeground: Color(0xFF6B5F54),
    ColorToken.lyricsHighlightText: Color(0xFF1D1B18),
    ColorToken.lyricsButton: Color(0xFFEADFD4),
    ColorToken.lyricsDivider: Color(0xFFE5D8CC),
    ColorToken.lyricsSelectedItem: Color(0xFFE3DEF2),
    ColorToken.lyricsMenu: Color(0xFFFFFFFF),
  },
  dark: {
    ColorToken.pageBackground: Color(0xFF17130F),
    ColorToken.panel: Color(0xFF221C17),
    ColorToken.sidebar: Color(0xFF1D1813),
    ColorToken.bottom: Color(0xFF221C17),
    ColorToken.text: Color(0xFFE8DDD2),
    ColorToken.highlightText: Color(0xFFFDF7F2),
    ColorToken.icon: Color(0xFFC4B5A6),
    ColorToken.divider: Color(0xFF33291F),
    ColorToken.button: Color(0xFF2E2620),
    ColorToken.selectedItem: Color(0xFF322A4A),
    ColorToken.searchField: Color(0xFF2A231C),
    ColorToken.menu: Color(0xFF262019),
    ColorToken.seekBar: Color(0xFFF5B944),
    ColorToken.volumeBar: Color(0xFFA79BD6),
    ColorToken.switchTrack: Color(0xFFA79BD6),
    ColorToken.glass: Color(0x99282019),
    ColorToken.lyricsBackground: Color(0xFF17130F),
    ColorToken.lyricsForeground: Color(0xFF8C7D6E),
    ColorToken.lyricsHighlightText: Color(0xFFFDF7F2),
    ColorToken.lyricsButton: Color(0xFF2E2620),
    ColorToken.lyricsDivider: Color(0xFF33291F),
    ColorToken.lyricsSelectedItem: Color(0xFF322A4A),
    ColorToken.lyricsMenu: Color(0xFF262019),
  },
);

// ---------------------------------------------------------------------------
// Glasshouse — translucent surfaces over an artwork-lit ground.
// Alpha values matter here: these colours sit on top of a blurred backdrop.
// ---------------------------------------------------------------------------
const _glasshouse = FlavourSpec(
  surfaceTreatment: SurfaceTreatment.glass,
  cornerScale: 1.3,
  density: Density.comfortable,
  light: {
    ColorToken.pageBackground: Color(0xFFEDE6DE),
    ColorToken.panel: Color(0x94FFFFFF),
    ColorToken.sidebar: Color(0x5CFFFFFF),
    ColorToken.bottom: Color(0x9EFFFFFF),
    ColorToken.text: Color(0xFF20232A),
    ColorToken.highlightText: Color(0xFF14161B),
    ColorToken.icon: Color(0xFF3E434C),
    ColorToken.divider: Color(0x1A000000),
    ColorToken.button: Color(0x9EFFFFFF),
    ColorToken.selectedItem: Color(0xD9FFFFFF),
    ColorToken.searchField: Color(0x80FFFFFF),
    ColorToken.menu: Color(0xCCFFFFFF),
    ColorToken.seekBar: Color(0xFFA9633F),
    ColorToken.volumeBar: Color(0xFFA9633F),
    ColorToken.switchTrack: Color(0xFF8C5347),
    ColorToken.glass: Color(0x8CFFFFFF),
  },
  dark: {
    ColorToken.pageBackground: Color(0xFF1A1D24),
    ColorToken.panel: Color(0x99282D37),
    ColorToken.sidebar: Color(0x801E222A),
    ColorToken.bottom: Color(0x99282D37),
    ColorToken.text: Color(0xFFDDE1E8),
    ColorToken.highlightText: Color(0xFFF2F4F8),
    ColorToken.icon: Color(0xFFB4BCC8),
    ColorToken.divider: Color(0x1AFFFFFF),
    ColorToken.button: Color(0x99323845),
    ColorToken.selectedItem: Color(0xB33C4352),
    ColorToken.searchField: Color(0x80262B34),
    ColorToken.menu: Color(0xCC262B34),
    ColorToken.seekBar: Color(0xFFD69A6E),
    ColorToken.volumeBar: Color(0xFFD69A6E),
    ColorToken.switchTrack: Color(0xFFD69A6E),
    ColorToken.glass: Color(0x8C282D37),
  },
);

// ---------------------------------------------------------------------------
// Console — dense and typographic. Brass is the only chromatic note, reserved
// for state (playing position, lossless badges).
// ---------------------------------------------------------------------------
const _console = FlavourSpec(
  surfaceTreatment: SurfaceTreatment.flat,
  cornerScale: 0.35,
  density: Density.compact,
  light: {
    ColorToken.pageBackground: Color(0xFFFFFFFF),
    ColorToken.panel: Color(0xFFFFFFFF),
    ColorToken.sidebar: Color(0xFFF7F8F9),
    ColorToken.bottom: Color(0xFFF2F4F6),
    ColorToken.text: Color(0xFF3D434E),
    ColorToken.highlightText: Color(0xFF14171D),
    ColorToken.icon: Color(0xFF5A6472),
    ColorToken.divider: Color(0xFFE3E7EB),
    ColorToken.button: Color(0xFFEEF1F4),
    ColorToken.selectedItem: Color(0xFFEDF1F5),
    ColorToken.searchField: Color(0xFFF2F4F6),
    ColorToken.menu: Color(0xFFFFFFFF),
    ColorToken.seekBar: Color(0xFFA9762E),
    ColorToken.volumeBar: Color(0xFF77808C),
    ColorToken.switchTrack: Color(0xFFA9762E),
    ColorToken.glass: Color(0xF2FFFFFF),
  },
  dark: {
    ColorToken.pageBackground: Color(0xFF0C0E11),
    ColorToken.panel: Color(0xFF0C0E11),
    ColorToken.sidebar: Color(0xFF0C0E11),
    ColorToken.bottom: Color(0xFF101419),
    ColorToken.text: Color(0xFF9AA3AF),
    ColorToken.highlightText: Color(0xFFE8ECF1),
    ColorToken.icon: Color(0xFF9AA3AF),
    ColorToken.divider: Color(0xFF1A1F25),
    ColorToken.button: Color(0xFF171C22),
    ColorToken.selectedItem: Color(0xFF12171D),
    ColorToken.searchField: Color(0xFF14171B),
    ColorToken.menu: Color(0xFF14171B),
    ColorToken.seekBar: Color(0xFFC08A3E),
    ColorToken.volumeBar: Color(0xFF5A6472),
    ColorToken.switchTrack: Color(0xFFC08A3E),
    ColorToken.glass: Color(0xF214171B),
    ColorToken.lyricsBackground: Color(0xFF0C0E11),
    ColorToken.lyricsForeground: Color(0xFF4E5763),
    ColorToken.lyricsHighlightText: Color(0xFFE8ECF1),
    ColorToken.lyricsButton: Color(0xFF171C22),
    ColorToken.lyricsDivider: Color(0xFF1A1F25),
    ColorToken.lyricsSelectedItem: Color(0xFF12171D),
    ColorToken.lyricsMenu: Color(0xFF14171B),
  },
);

const Map<Flavour, FlavourSpec> flavourSpecs = {
  Flavour.expressive: _expressive,
  Flavour.glasshouse: _glasshouse,
  Flavour.console: _console,
};

/// Active flavour. Persisted alongside the other theme settings.
final flavourNotifier = ValueNotifier(Flavour.expressive);

FlavourSpec get activeFlavour =>
    flavourSpecs[flavourNotifier.value] ?? _expressive;

/// The flavour's colour for [token], or null to fall back to upstream's value.
///
/// Returning null rather than a default is deliberate: it lets a flavour define
/// only the tokens it cares about and inherit the rest, so partial flavours are
/// valid and we can migrate tokens incrementally.
Color? flavourColor(ColorToken? token, {required bool isDark}) {
  if (token == null) return null;
  final spec = activeFlavour;
  return isDark ? spec.dark[token] : spec.light[token];
}
