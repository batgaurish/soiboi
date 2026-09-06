@Tags(['integration'])
library;

import 'dart:io';

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
}
