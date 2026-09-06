/// Dynamic colour — the palette follows the system rather than the flavour.
///
/// Two sources, one switch:
///
///  * **Linux** reads a matugen-generated JSON. matugen already derives a
///    Material 3 scheme from the wallpaper for the rest of the desktop, so
///    Soiboi reads the same file and matches everything else on screen.
///  * **Android** would use the platform's own Material You palette, which is
///    the same mechanism by a different route. Not wired yet; see [systemPalette].
///
/// This sits *above* the flavour: when a dynamic palette is loaded it wins,
/// and the flavour still supplies any token the palette does not cover. So
/// dynamic colour restyles a flavour rather than replacing it, and the
/// flavour's shape, density and motion all survive.
library;

import 'dart:convert';
import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/theme/flavour.dart';

/// Whether to follow system colours instead of the flavour palette.
final dynamicColorEnabledNotifier = ValueNotifier<bool>(false);

/// Where to read matugen's output. Empty means the default location.
final matugenPathNotifier = ValueNotifier<String>('');

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
    final decoded = jsonDecode(await file.readAsString());
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
  } catch (e) {
    logger.output('matugen: $e');
    return false;
  }
}

void clearDynamicPalette() {
  dynamicLightNotifier.value = null;
  dynamicDarkNotifier.value = null;
}

/// The dynamic colour for [token], or null to fall through to the flavour.
Color? dynamicColor(ColorToken? token, {required bool isDark}) {
  if (token == null || !dynamicColorEnabledNotifier.value) return null;
  final palette = isDark ? dynamicDarkNotifier.value : dynamicLightNotifier.value;
  return palette?[token];
}

/// Android's system Material You palette.
///
/// Deliberately unimplemented rather than faked: reading it needs a platform
/// channel into `DynamicColors`, and returning something plausible here would
/// paint the app in colours the system never chose.
Palette? systemPalette({required bool isDark}) => null;
