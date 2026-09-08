@Tags(['integration'])
library;

import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/theme/dynamic_color.dart';
import 'package:soiboi/base/theme/flavour.dart';

/// Runs against the host's real matugen.
///
/// Mocking the process would test the argument list and nothing else, and the
/// bug worth guarding against is precisely that matugen refuses the arguments:
/// version 4 will not choose between candidate source colours unless --prefer
/// is given and it cannot see a terminal, which is always the case here.
void main() {
  test('detection degrades quietly when matugen is absent', () async {
    // Never throws, on any platform: the settings screen calls this to decide
    // what to offer, long before the user has agreed to anything.
    final binary = await findMatugen();
    if (!Platform.isLinux) {
      expect(binary, isNull);
      return;
    }
    if (binary != null) expect(File(binary).existsSync(), isTrue);
  });

  test('generates a full palette for every scheme it offers', () async {
    final binary = await findMatugen();
    final wallpaper = await detectWallpaper();
    if (binary == null || wallpaper == null) {
      markTestSkipped('no matugen or no detectable wallpaper on this host');
      return;
    }

    final byScheme = <String, Palette>{};
    for (final scheme in matugenSchemes) {
      dynamicDarkNotifier.value = null;
      expect(
        await generateMatugenPalette(scheme: scheme),
        isTrue,
        reason: 'matugen rejected $scheme',
      );
      final dark = dynamicDarkNotifier.value;
      expect(dark, isNotNull);
      // Every token the mapping covers, or the theme falls back to the
      // flavour for some surfaces and the result is visibly mismatched.
      expect(dark!.length, greaterThan(20), reason: '$scheme was sparse');
      byScheme[scheme] = dark;
    }

    // The variants must actually differ; a --type that silently did nothing
    // would leave the picker offering nine identical choices.
    expect(
      byScheme['scheme-monochrome'],
      isNot(equals(byScheme['scheme-fruit-salad'])),
    );
  });

  test('labels are readable, not raw scheme ids', () {
    expect(schemeLabel('scheme-fruit-salad'), 'Fruit Salad');
    expect(schemeLabel('scheme-tonal-spot'), 'Tonal Spot');
  });

  group('Android system colours', () {
    test('no palette on a non-Android host, and no crash asking', () async {
      // The settings screen calls this to decide what to offer, so it has to
      // answer on every platform. False here is the honest answer, and the
      // caller already treats it as "fall back to the flavour".
      expect(await loadSystemPalette(), isFalse);
      expect(systemPalette(isDark: false), isNull);
      expect(systemPalette(isDark: true), isNull);
    });

    test('every token the flavour paints has a system role behind it', () {
      // The Android mapping and the matugen mapping have to cover the same
      // ground, or a device would come out half-themed: one route would leave
      // tokens to the flavour that the other fills.
      final scheme = ColorScheme.fromSeed(
        seedColor: const Color(0xff5b7fff),
        brightness: Brightness.dark,
      );
      final fromScheme = paletteFromColorScheme(scheme);
      for (final token in matugenMappedTokens) {
        expect(
          fromScheme.containsKey(token),
          isTrue,
          reason: '$token is mapped for matugen but not for Android',
        );
      }
    });

    test('the mapping produces a usable palette, not a blank one', () {
      final palette = paletteFromColorScheme(
        ColorScheme.fromSeed(seedColor: const Color(0xff5b7fff)),
      );
      expect(palette[ColorToken.pageBackground], isNotNull);
      expect(palette[ColorToken.seekBar], isNotNull);
      // Text has to differ from the surface it sits on, or the app is
      // unreadable in whatever the wallpaper happened to be.
      expect(
        palette[ColorToken.highlightText],
        isNot(palette[ColorToken.pageBackground]),
      );
    });
  });
}
