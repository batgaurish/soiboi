import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/theme/color_source.dart';
import 'package:soiboi/base/theme/dynamic_color.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/utils/contrast_color_generator.dart';

/// Average cover colours from real music libraries, made by
/// `tools/cover_colors.py`. Colours only: no titles leave the library.
final _coverFixture = File('test/fixtures/cover_colors.json');

/// Every colour on an even grid through RGB, [steps] values per channel.
Iterable<Color> _grid(int steps) sync* {
  for (var r = 0; r < steps; r++) {
    for (var g = 0; g < steps; g++) {
      for (var b = 0; b < steps; b++) {
        int at(int i) => (i * 255 / (steps - 1)).round();
        yield Color.fromARGB(255, at(r), at(g), at(b));
      }
    }
  }
}

/// What the main page's text sits on: the same surfaces the colour manager
/// checks, written out again so the test does not just trust it.
List<(String, Color)> _mainGrounds({required bool vivid}) {
  Color on(MyColor surface) => Color.alphaBlend(
    surface.value,
    vivid ? backgroundCoverArtColor : pageBackgroundColor.value,
  );
  return [
    if (!vivid) ('page', pageBackgroundColor.value.withAlpha(255)),
    ('panel', on(panelColor)),
    ('sidebar', on(sidebarColor)),
    ('bottom bar', on(bottomColor)),
    ('menu', on(menuColor)),
    (
      'selected row',
      Color.alphaBlend(selectedItemColor.value, on(sidebarColor)),
    ),
  ];
}

/// Checks the resolved main and lyrics colours, returning each failure.
List<String> _failures(String name) {
  final failures = <String>[];
  void check(
    String what,
    Color color,
    List<(String, Color)> grounds,
    double min,
  ) {
    for (final (where, ground) in grounds) {
      final ratio = contrastRatio(color, ground);
      if (ratio < min - 1e-9) {
        failures.add('$name: $what on $where is ${ratio.toStringAsFixed(2)}');
      }
    }
  }

  final vivid = mainPageThemeNotifier.value == ThemeType.vivid;
  final grounds = _mainGrounds(vivid: vivid);
  check('text', textColor.value, grounds, kTextContrast);
  check('highlighted text', highlightTextColor.value, grounds, kTextContrast);
  check('icons', iconColor.value, grounds, kLargeContrast);

  if (lyricsPageThemeNotifier.value != ThemeType.vivid) {
    final background = lyricsPageBackgroundColor.value.withAlpha(255);
    final lyricsGrounds = [('lyrics page', background)];
    check(
      'lyrics',
      lyricsPageForegroundColor.value,
      lyricsGrounds,
      kTextContrast,
    );
    check(
      'current lyric',
      lyricsPageHighlightTextColor.value,
      lyricsGrounds,
      kTextContrast,
    );
  }
  return failures;
}

void _resolve(ThemeType theme) {
  mainPageThemeNotifier.value = theme;
  lyricsPageThemeNotifier.value = theme;
  colorManager.updateMainPageColors();
  colorManager.updateLyricsPageColors();
}

void main() {
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_palettes');
  });

  tearDown(() {
    colorSourceNotifier.value = ColorSource.off;
    clearDynamicPalette();
    flavourNotifier.value = Flavour.zine;
  });

  test('album-art text reads on any cover colour at all', () {
    // 32,768 backgrounds: every average colour a cover could have, to within
    // eight steps per channel.
    final failures = <String>[];
    for (final background in _grid(32)) {
      final theme = ContrastColorGenerator.generate(background);
      for (final (role, color) in [
        ('regular', theme.regular),
        ('accent', theme.accent),
      ]) {
        final ratio = contrastRatio(color, background);
        if (ratio < kTextContrast) {
          failures.add('$role on $background: ${ratio.toStringAsFixed(2)}');
        }
      }
    }
    expect(failures, isEmpty);
  });

  test('album-art text keeps the cover\'s hue where it can', () {
    // A mid-blue cover: the tint survives, only its lightness moves.
    const cover = Color(0xFF2E5C8A);
    final theme = ContrastColorGenerator.generate(cover);
    final accent = HSLColor.fromColor(theme.accent);
    expect(accent.saturation, greaterThan(0.3));
    expect(contrastRatio(theme.accent, cover), greaterThanOrEqualTo(4.5));
  });

  test('a grey cover no longer gets pale grey lyrics', () {
    final theme = ContrastColorGenerator.generate(Colors.grey);
    expect(
      contrastRatio(theme.regular, Colors.grey),
      greaterThanOrEqualTo(4.5),
    );
  });

  test('every flavour reads, light and dark', () {
    final failures = <String>[];
    for (final flavour in Flavour.values) {
      flavourNotifier.value = flavour;
      for (final theme in [ThemeType.light, ThemeType.dark]) {
        _resolve(theme);
        failures.addAll(_failures('${flavour.name} ${theme.name}'));
      }
    }
    expect(failures, isEmpty);
  });

  test('every prebuilt palette reads, light and dark', () {
    final failures = <String>[];
    colorSourceNotifier.value = ColorSource.prebuilt;
    for (final palette in PrebuiltPalette.values) {
      prebuiltPaletteNotifier.value = palette;
      for (final theme in [ThemeType.light, ThemeType.dark]) {
        _resolve(theme);
        failures.addAll(_failures('${palette.name} ${theme.name}'));
      }
    }
    expect(failures, isEmpty);
  });

  test('Material You palettes read, whatever the wallpaper', () {
    final failures = <String>[];
    colorSourceNotifier.value = ColorSource.matugen;
    final seeds = [
      for (var hue = 0; hue < 360; hue += 30)
        HSLColor.fromAHSL(1, hue.toDouble(), 0.7, 0.5).toColor(),
      Colors.grey,
      Colors.black,
      Colors.white,
      const Color(0xFF6B4E3D),
    ];
    for (final seed in seeds) {
      for (final variant in [
        DynamicSchemeVariant.tonalSpot,
        DynamicSchemeVariant.vibrant,
        DynamicSchemeVariant.fidelity,
      ]) {
        dynamicLightNotifier.value = paletteFromColorScheme(
          ColorScheme.fromSeed(seedColor: seed, dynamicSchemeVariant: variant),
        );
        dynamicDarkNotifier.value = paletteFromColorScheme(
          ColorScheme.fromSeed(
            seedColor: seed,
            brightness: Brightness.dark,
            dynamicSchemeVariant: variant,
          ),
        );
        for (final theme in [ThemeType.light, ThemeType.dark]) {
          _resolve(theme);
          failures.addAll(_failures('$seed ${variant.name} ${theme.name}'));
        }
      }
    }
    expect(failures, isEmpty);
  });

  test('vivid pages read on any cover colour', () {
    final failures = <String>[];
    for (final cover in _grid(12)) {
      backgroundCoverArtColor = cover;
      _resolve(ThemeType.vivid);
      failures.addAll(_failures('vivid on $cover'));
    }
    expect(failures, isEmpty);
    backgroundCoverArtColor = Colors.grey;
  });

  test(
    'real covers read, in vivid pages and album-art text',
    () {
      final json =
          jsonDecode(_coverFixture.readAsStringSync()) as Map<String, dynamic>;
      final covers = [
        for (final hex in (json['colors'] as List).cast<String>())
          Color(int.parse(hex.substring(1), radix: 16) | 0xFF000000),
      ];
      expect(covers.length, greaterThanOrEqualTo(50));

      final failures = <String>[];
      for (final cover in covers) {
        final theme = ContrastColorGenerator.generate(cover);
        for (final color in [theme.regular, theme.accent]) {
          if (contrastRatio(color, cover) < kTextContrast) {
            failures.add('album-art text on $cover');
          }
        }
        backgroundCoverArtColor = cover;
        _resolve(ThemeType.vivid);
        failures.addAll(_failures('vivid on $cover'));
      }
      expect(failures, isEmpty);
      backgroundCoverArtColor = Colors.grey;
    },
    skip: _coverFixture.existsSync()
        ? false
        : 'No test/fixtures/cover_colors.json: run tools/cover_colors.py on a '
              'music folder to make one',
  );
}
