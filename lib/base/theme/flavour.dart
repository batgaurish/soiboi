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
  /// Warm and editorial: serif headings, printed-label badges, gentle motion.
  linerNotes,

  /// Hi-fi equipment: mono type, uppercase headings, square corners, no
  /// stagger.
  signal,

  /// Bold and playful: chunky rounded type, sticker badges, springy motion.
  zine,
}

/// Names written by builds up to v1.0.3, mapped to the flavour that took
/// their place, so an upgrade keeps roughly the same feel.
const Map<String, Flavour> legacyFlavourNames = {
  'expressive': Flavour.zine,
  'glasshouse': Flavour.linerNotes,
  'console': Flavour.signal,
};

Flavour flavourFromName(String? name) =>
    Flavour.values.where((e) => e.name == name).firstOrNull ??
    legacyFlavourNames[name] ??
    Flavour.zine;

extension FlavourLabel on Flavour {
  String get label => switch (this) {
    Flavour.linerNotes => 'Liner Notes',
    Flavour.signal => 'Signal',
    Flavour.zine => 'Zine',
  };

  String get blurb => switch (this) {
    Flavour.linerNotes => 'Serif headings, like a record sleeve',
    Flavour.signal => 'Mono type and square corners, like hi-fi gear',
    Flavour.zine => 'Chunky type and sticker badges',
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
    required this.bodyFont,
    required this.displayFont,
    this.displayWeight = FontWeight.w700,
    this.uppercaseHeadings = false,
    this.badgeStyle = BadgeStyle.outline,
  });

  /// How panels are painted. Glass surfaces need a `BackdropFilter`, which is
  /// a widget concern rather than a colour, so widgets branch on this.
  final SurfaceTreatment surfaceTreatment;

  /// Multiplier applied to corner radii. Expressive rounds hard, Console barely.
  final double cornerScale;

  /// Row height and padding scale.
  final Density density;

  /// Bundled font for running text, used unless the user picked their own.
  final String bodyFont;

  /// Bundled font for headings and titles.
  final String displayFont;

  final FontWeight displayWeight;

  final bool uppercaseHeadings;

  /// How codec badges are drawn. Lossless always stands apart from lossy.
  final BadgeStyle badgeStyle;

  /// Heading text as this flavour prints it.
  String heading(String text) =>
      uppercaseHeadings ? text.toUpperCase() : text;

  /// [base] in this flavour's heading face. A font the user chose wins.
  TextStyle headingStyle(TextStyle base, {String? userFont}) => base.copyWith(
    fontFamily: userFont ?? displayFont,
    fontWeight: displayWeight,
    letterSpacing: uppercaseHeadings ? 0.6 : base.letterSpacing,
  );
}

/// Liner Notes prints solid labels, Signal lights LED readouts, Zine sticks
/// on tilted pills.
enum BadgeStyle { outline, label, led, sticker }

enum SurfaceTreatment { flat, glass, solid }

enum Density { compact, comfortable, spacious }

// ---------------------------------------------------------------------------
// Liner Notes: a record sleeve. Serif headings, small corners.
// ---------------------------------------------------------------------------
const _linerNotes = FlavourSpec(
  surfaceTreatment: SurfaceTreatment.flat,
  cornerScale: 0.5,
  density: Density.comfortable,
  bodyFont: 'IBM Plex Sans',
  displayFont: 'Fraunces',
  badgeStyle: BadgeStyle.label,
);

// ---------------------------------------------------------------------------
// Signal: hi-fi equipment. Mono body, uppercase headings, square corners.
// ---------------------------------------------------------------------------
const _signal = FlavourSpec(
  surfaceTreatment: SurfaceTreatment.solid,
  cornerScale: 0.25,
  density: Density.compact,
  bodyFont: 'JetBrains Mono',
  displayFont: 'Space Grotesk',
  uppercaseHeadings: true,
  badgeStyle: BadgeStyle.led,
);

// ---------------------------------------------------------------------------
// Zine: the default. Chunky type, hard rounding, sticker badges.
// ---------------------------------------------------------------------------
const _zine = FlavourSpec(
  surfaceTreatment: SurfaceTreatment.glass,
  cornerScale: 1.6,
  density: Density.spacious,
  bodyFont: 'Bricolage Grotesque',
  displayFont: 'Bricolage Grotesque',
  displayWeight: FontWeight.w800,
  badgeStyle: BadgeStyle.sticker,
);

const Map<Flavour, FlavourSpec> flavourSpecs = {
  Flavour.linerNotes: _linerNotes,
  Flavour.signal: _signal,
  Flavour.zine: _zine,
};

/// Active flavour. Persisted alongside the other theme settings.
final flavourNotifier = ValueNotifier(Flavour.zine);

FlavourSpec get activeFlavour => flavourSpecs[flavourNotifier.value] ?? _zine;
