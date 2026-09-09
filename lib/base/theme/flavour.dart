/// Flavour system.
///
/// A flavour is a *shape and motion* identity: corner rounding, row density,
/// how panels are painted (flat/glass/solid), and stagger timing. It has
/// nothing to say about colour — colour is a separate, orthogonal choice (see
/// `color_source.dart`), so switching flavour never fights with switching a
/// colour source, and switching colour source never resets corners back to
/// square.
///
/// This used to also carry a light/dark palette per flavour, resolved ahead of
/// dynamic colour's own palette. That meant a flavour's colours were only
/// ever visible when dynamic colour (on by default on Android, where it is
/// named "Follow system colours") was off — which in practice was almost
/// never, so switching flavour looked like it did nothing. Splitting colour
/// out fixes that: a flavour now always visibly changes shape and motion,
/// regardless of which colour source is active.
library;

import 'package:material_ui/material_ui.dart';

/// The visual identity. Orthogonal to colour.
enum Flavour {
  /// Material 3 Expressive: big shapes, springy motion.
  expressive,

  /// Frosted translucent panes over whatever is painted behind them.
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
    Flavour.glasshouse => 'Frosted, translucent panels',
    Flavour.console => 'Dense rows for large libraries',
  };
}

/// Semantic names for every themeable surface, one per upstream `MyColor`.
///
/// Colour sources (dynamic/matugen, prebuilt palettes) key their palettes by
/// this same enum — it lives here rather than in `color_source.dart` only
/// because `MyColor` (in `color_manager.dart`) already imported this file
/// before colour sources existed, and splitting it out is a bigger diff than
/// the duplication it would save.
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

/// A named colour palette for one brightness — a colour source's colours, or
/// (formerly) a flavour's. Still called `Palette` for the token-keyed shape,
/// not because it still belongs to `Flavour`.
typedef Palette = Map<ColorToken, Color>;

class FlavourSpec {
  const FlavourSpec({
    this.surfaceTreatment = SurfaceTreatment.flat,
    this.cornerScale = 1.0,
    this.density = Density.comfortable,
  });

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
// Expressive — the default. Big, hard-rounded, springy.
// ---------------------------------------------------------------------------
const _expressive = FlavourSpec(
  surfaceTreatment: SurfaceTreatment.solid,
  cornerScale: 1.6,
  density: Density.spacious,
);

// ---------------------------------------------------------------------------
// Glasshouse — translucent surfaces over whatever sits behind them.
// ---------------------------------------------------------------------------
const _glasshouse = FlavourSpec(
  surfaceTreatment: SurfaceTreatment.glass,
  cornerScale: 1.3,
  density: Density.comfortable,
);

// ---------------------------------------------------------------------------
// Console — dense and typographic, corners barely rounded, motion minimal.
// ---------------------------------------------------------------------------
const _console = FlavourSpec(
  surfaceTreatment: SurfaceTreatment.flat,
  cornerScale: 0.35,
  density: Density.compact,
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
