/// Dynamic colour — the palette follows the system rather than the flavour.
///
/// Two sources, one switch:
///
///  * **Linux** uses matugen, which derives a Material 3 scheme from the
///    wallpaper. Two routes, tried in that order: read the JSON an existing
///    matugen setup already writes, so a desktop themed as a whole stays
///    consistent; failing that, run matugen directly against the detected
///    wallpaper, so this works with no template configuration at all.
///  * **Android** uses the platform's own Material You palette, which is the
///    same mechanism by a different route: Android 12+ derives the tonal
///    palettes from the wallpaper itself and exposes them, so there is nothing
///    to detect and nothing to run.
///
/// This sits *above* the flavour: when a dynamic palette is loaded it wins,
/// and the flavour still supplies any token the palette does not cover. So
/// dynamic colour restyles a flavour rather than replacing it, and the
/// flavour's shape, density and motion all survive.
library;

import 'dart:convert';
import 'dart:io';

import 'package:dynamic_color/dynamic_color.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/theme/color_source.dart';
import 'package:soiboi/base/theme/flavour.dart';

/// Where to read matugen's output. Empty means the default location.
final matugenPathNotifier = ValueNotifier<String>('');

/// Which Material 3 scheme matugen builds from the wallpaper.
///
/// Only used when Soiboi runs matugen itself; a scheme read from an existing
/// setup's JSON was already generated with whatever that setup chose.
final matugenSchemeNotifier = ValueNotifier<String>('scheme-tonal-spot');

/// matugen's scheme types, in its own order.
///
/// Named exactly as matugen's --type expects, so the setting is passed
/// straight through rather than translated.
const matugenSchemes = <String>[
  'scheme-tonal-spot',
  'scheme-expressive',
  'scheme-fruit-salad',
  'scheme-vibrant',
  'scheme-content',
  'scheme-fidelity',
  'scheme-rainbow',
  'scheme-neutral',
  'scheme-monochrome',
];

String schemeLabel(String scheme) {
  final words = scheme.replaceFirst('scheme-', '').split('-');
  return words
      .map((w) => w.isEmpty ? w : w[0].toUpperCase() + w.substring(1))
      .join(' ');
}

/// How the palette currently in use was obtained, for the settings screen.
String? dynamicColorSourceDescription;

/// Loaded palettes, null until a successful read.
final dynamicLightNotifier = ValueNotifier<Palette?>(null);
final dynamicDarkNotifier = ValueNotifier<Palette?>(null);

/// matugen's own default output path when configured with a soiboi template.
String get defaultMatugenPath {
  final home = Platform.environment['HOME'] ?? '';
  final xdg = Platform.environment['XDG_CONFIG_HOME'] ?? '$home/.config';
  return '$xdg/matugen/soiboi-colors.json';
}

String get effectiveMatugenPath {
  final custom = matugenPathNotifier.value.trim();
  return custom.isEmpty ? defaultMatugenPath : custom;
}

/// Material 3 role -> our token.
///
/// The mapping is deliberate rather than mechanical. Surfaces climb the
/// container ladder so raised things stay legible above the ones behind them:
/// the content pane sits on `surface`, cards and menus on
/// `surface_container_high`. Accents come from `primary`, which is the role
/// that actually carries the wallpaper's hue.
/// The tokens the matugen route fills, so a test can assert the Android route
/// covers the same ground — a device themed by only half the mapping would
/// come out half-flavour, half-system.
Iterable<ColorToken> get matugenMappedTokens => _roleForToken.keys;

const _roleForToken = <ColorToken, String>{
  ColorToken.pageBackground: 'surface',
  ColorToken.panel: 'surface_container_low',
  ColorToken.sidebar: 'surface_container_lowest',
  ColorToken.bottom: 'surface_container',
  ColorToken.menu: 'surface_container_high',
  ColorToken.button: 'surface_container_high',
  ColorToken.searchField: 'surface_container_high',
  ColorToken.selectedItem: 'secondary_container',
  ColorToken.divider: 'outline_variant',
  ColorToken.text: 'on_surface_variant',
  ColorToken.highlightText: 'on_surface',
  ColorToken.icon: 'on_surface_variant',
  ColorToken.seekBar: 'primary',
  ColorToken.volumeBar: 'primary',
  ColorToken.switchTrack: 'primary',
  ColorToken.glass: 'surface_container',
  // Lyrics reuse the same ladder so the lyrics page doesn't fall back to a
  // flavour palette while the rest of the app follows the wallpaper.
  ColorToken.lyricsBackground: 'surface',
  ColorToken.lyricsForeground: 'on_surface_variant',
  ColorToken.lyricsHighlightText: 'on_surface',
  ColorToken.lyricsButton: 'surface_container_high',
  ColorToken.lyricsDivider: 'outline_variant',
  ColorToken.lyricsSelectedItem: 'secondary_container',
  ColorToken.lyricsMenu: 'surface_container_high',
};

Color? _parseHex(String? raw) {
  if (raw == null) return null;
  var hex = raw.trim().replaceFirst('#', '');
  if (hex.length == 6) hex = 'ff$hex';
  if (hex.length != 8) return null;
  final value = int.tryParse(hex, radix: 16);
  return value == null ? null : Color(value);
}

/// Reads matugen's JSON and builds a light and a dark palette.
///
/// Returns false and leaves the previous palettes alone when the file is
/// missing or malformed — a broken colour file should never leave the app
/// unpainted.
Future<bool> loadMatugenPalette() async {
  final path = effectiveMatugenPath;
  try {
    final file = File(path);
    if (!await file.exists()) {
      logger.output('matugen: no file at $path');
      return false;
    }
    return _applyMatugenJson(await file.readAsString());
  } catch (e) {
    logger.output('matugen: $e');
    return false;
  }
}

/// Parses matugen's JSON — from a file or from its stdout — and applies it.
bool _applyMatugenJson(String source) {
  final decoded = jsonDecode(source);
  if (decoded is! Map) return false;
  final colors = decoded['colors'];
  if (colors is! Map) return false;

  Palette build(String mode) {
    final palette = <ColorToken, Color>{};
    for (final entry in _roleForToken.entries) {
      final role = colors[entry.value];
      if (role is! Map) continue;
      // matugen nests as colors.<role>.<mode>.color
      final slot = role[mode];
      final hex = slot is Map ? slot['color'] as String? : null;
      final color = _parseHex(hex);
      if (color != null) palette[entry.key] = color;
    }
    return palette;
  }

  final light = build('light');
  final dark = build('dark');
  if (light.isEmpty && dark.isEmpty) return false;

  dynamicLightNotifier.value = light;
  dynamicDarkNotifier.value = dark;
  return true;
}

/// The matugen binary, or null if it is not installed.
///
/// Looked up rather than assumed: matugen is not a dependency, and asking the
/// user to type a path to a program already on their PATH is the kind of setup
/// step that makes a feature look broken.
Future<String?> findMatugen() async {
  if (!Platform.isLinux) return null;
  for (final candidate in const [
    '/usr/bin/matugen',
    '/usr/local/bin/matugen',
  ]) {
    if (File(candidate).existsSync()) return candidate;
  }
  try {
    final which = await Process.run('which', ['matugen']);
    if (which.exitCode == 0) {
      final path = (which.stdout as String).trim();
      if (path.isNotEmpty) return path;
    }
  } on ProcessException {
    // No `which`; the fixed paths above were the only chance.
  }
  return null;
}

/// The current wallpaper, or null if it cannot be determined.
///
/// Every desktop stores this somewhere different and none of them agree, so
/// this tries the mechanisms in turn and gives up quietly. A wallpaper that
/// cannot be found is a missing feature, not an error worth reporting.
Future<String?> detectWallpaper() async {
  if (!Platform.isLinux) return null;

  // GNOME and anything using its schema. picture-uri-dark first: a desktop in
  // dark mode shows that one, and deriving colours from the light wallpaper
  // the user is not looking at would be worse than deriving none.
  for (final key in const ['picture-uri-dark', 'picture-uri']) {
    final uri = await _gsettings('org.gnome.desktop.background', key);
    final path = _pathFromUri(uri);
    if (path != null) return path;
  }

  // Hyprland's paper daemons report the wallpaper per monitor as
  // "<monitor> = <path>"; any of them will do for a colour scheme.
  try {
    final result = await Process.run('hyprctl', ['hyprpaper', 'listloaded']);
    if (result.exitCode == 0) {
      for (final line in const LineSplitter().convert(result.stdout as String)) {
        final path = line.contains('=') ? line.split('=').last.trim() : line.trim();
        if (path.isNotEmpty && File(path).existsSync()) return path;
      }
    }
  } on ProcessException {
    // Not a Hyprland session.
  }

  return null;
}

Future<String?> _gsettings(String schema, String key) async {
  try {
    final result = await Process.run('gsettings', ['get', schema, key]);
    if (result.exitCode != 0) return null;
    return (result.stdout as String).trim().replaceAll("'", '');
  } on ProcessException {
    return null;
  }
}

String? _pathFromUri(String? uri) {
  if (uri == null || uri.isEmpty) return null;
  final path = uri.startsWith('file://') ? Uri.parse(uri).toFilePath() : uri;
  return File(path).existsSync() ? path : null;
}

/// Runs matugen against [image] and applies the result.
///
/// Uses --dry-run so nothing on the user's system is written or reloaded: this
/// reads a colour scheme, it does not take over their theming. --prefer is not
/// optional -- matugen 4 refuses to choose between multiple candidate source
/// colours when it cannot see a terminal, which is always the case here.
Future<bool> generateMatugenPalette({String? image, String? scheme}) async {
  final binary = await findMatugen();
  if (binary == null) return false;
  final source = image ?? await detectWallpaper();
  if (source == null) return false;

  try {
    final result = await Process.run(binary, [
      'image', source,
      '--json', 'hex',
      '--dry-run',
      '--quiet',
      '--prefer', 'saturation',
      '--type', scheme ?? matugenSchemeNotifier.value,
    ]);
    if (result.exitCode != 0) {
      logger.output('matugen: exited ${result.exitCode}');
      return false;
    }
    return _applyMatugenJson(result.stdout as String);
  } catch (e) {
    logger.output('matugen: $e');
    return false;
  }
}

/// Finds a dynamic palette without any configuration.
///
/// An existing matugen setup's JSON wins: a user who themes their whole
/// desktop from one scheme wants Soiboi to match it exactly, not to
/// re-derive something close. Generating is the fallback for everyone else.
Future<bool> autoLoadDynamicPalette() async {
  // Android needs no detection and nothing to run: the system already derived
  // the palette from the wallpaper, so asking it is both cheaper and more
  // faithful than re-deriving something close.
  if (Platform.isAndroid) {
    if (await loadSystemPalette()) {
      dynamicColorSourceDescription = 'Material You, from your wallpaper';
      return true;
    }
    dynamicColorSourceDescription = null;
    return false;
  }

  if (await loadMatugenPalette()) {
    dynamicColorSourceDescription = 'Read from $effectiveMatugenPath';
    return true;
  }
  final wallpaper = await detectWallpaper();
  if (await generateMatugenPalette(image: wallpaper)) {
    dynamicColorSourceDescription =
        '${schemeLabel(matugenSchemeNotifier.value)} from '
        '${wallpaper?.split('/').last}';
    return true;
  }
  dynamicColorSourceDescription = null;
  return false;
}

void clearDynamicPalette() {
  dynamicLightNotifier.value = null;
  dynamicDarkNotifier.value = null;
}

/// The dynamic (matugen/system) colour for [token], or null to fall through
/// to whatever the next source in the chain supplies.
Color? dynamicColor(ColorToken? token, {required bool isDark}) {
  if (token == null || colorSourceNotifier.value != ColorSource.matugen) {
    return null;
  }
  final palette = isDark ? dynamicDarkNotifier.value : dynamicLightNotifier.value;
  return palette?[token];
}

/// Material 3 role → [ColorToken], as a [ColorScheme] exposes them.
///
/// The same assignment [_roleForToken] already encodes for matugen's JSON,
/// expressed against the typed fields instead of string keys — a second small
/// mapping, not a second schema. Keeping them side by side is what makes a
/// Linux and an Android device with the same wallpaper look like the same app.
Palette paletteFromColorScheme(ColorScheme scheme) => {
  ColorToken.pageBackground: scheme.surface,
  ColorToken.panel: scheme.surfaceContainerLow,
  ColorToken.sidebar: scheme.surfaceContainerLowest,
  ColorToken.bottom: scheme.surfaceContainer,
  ColorToken.menu: scheme.surfaceContainerHigh,
  ColorToken.button: scheme.surfaceContainerHigh,
  ColorToken.searchField: scheme.surfaceContainerHigh,
  ColorToken.selectedItem: scheme.secondaryContainer,
  ColorToken.divider: scheme.outlineVariant,
  ColorToken.text: scheme.onSurfaceVariant,
  ColorToken.highlightText: scheme.onSurface,
  ColorToken.icon: scheme.onSurfaceVariant,
  ColorToken.seekBar: scheme.primary,
  ColorToken.volumeBar: scheme.primary,
  ColorToken.switchTrack: scheme.primary,
  ColorToken.glass: scheme.surfaceContainer,
  ColorToken.lyricsBackground: scheme.surface,
  ColorToken.lyricsForeground: scheme.onSurfaceVariant,
  ColorToken.lyricsHighlightText: scheme.onSurface,
  ColorToken.lyricsButton: scheme.surfaceContainerHigh,
  ColorToken.lyricsDivider: scheme.outlineVariant,
  ColorToken.lyricsSelectedItem: scheme.secondaryContainer,
  ColorToken.lyricsMenu: scheme.surfaceContainerHigh,
};

/// The schemes built from the last palette Android handed over.
///
/// Cached as [ColorScheme]s rather than as the core palette itself: the type
/// `getCorePalette` returns is deprecated upstream in favour of a replacement
/// this package version does not expose, and holding the converted result
/// means never naming it.
ColorScheme? _systemLight;
ColorScheme? _systemDark;

/// Android's system Material You palette, or null where there is none.
///
/// Null is the honest answer in three real cases and the caller already treats
/// it as "fall back to the flavour": not Android at all, Android 11 or older
/// (no `DynamicColors`, so `getCorePalette` returns null), and an OEM build
/// that does not implement it. Nothing is faked — painting the app in colours
/// the system never chose would be worse than not following it.
Palette? systemPalette({required bool isDark}) {
  final scheme = isDark ? _systemDark : _systemLight;
  return scheme == null ? null : paletteFromColorScheme(scheme);
}

/// Reads Android's palette and applies it. Returns false when there is none.
Future<bool> loadSystemPalette() async {
  if (!Platform.isAndroid) return false;
  try {
    final core = await DynamicColorPlugin.getCorePalette();
    if (core == null) return false;
    _systemLight = core.toColorScheme();
    _systemDark = core.toColorScheme(brightness: Brightness.dark);
  } catch (e) {
    // A platform channel failure is a missing feature, not a crash: the app
    // must still paint.
    logger.output('dynamic color: $e');
    _systemLight = null;
    _systemDark = null;
    return false;
  }

  dynamicLightNotifier.value = systemPalette(isDark: false);
  dynamicDarkNotifier.value = systemPalette(isDark: true);
  return true;
}
