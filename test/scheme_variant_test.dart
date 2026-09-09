/// The scheme picker was hidden on Android, and Android's own
/// `toColorScheme()` only ever builds Tonal Spot — so every wallpaper
/// produced the same palette and the setting could not have had an effect
/// there. These pin down the part that makes it mean something: the same
/// seed through different variants must give genuinely different colours.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/theme/dynamic_color.dart';

void main() {
  const seed = Color(0xFF6750A4);

  test('every scheme the picker offers maps to a Flutter variant', () {
    // A name with no mapping silently falls back to Tonal Spot, which is
    // exactly the failure this is meant to prevent.
    for (final scheme in matugenSchemes) {
      expect(
        schemeVariantFor(scheme),
        isNotNull,
        reason: '$scheme has no variant, so it would do nothing on Android',
      );
    }
  });

  test('the variants produce different palettes from one seed', () {
    final backgrounds = <int>{};
    final primaries = <int>{};
    for (final scheme in matugenSchemes) {
      final scheme0 = ColorScheme.fromSeed(
        seedColor: seed,
        brightness: Brightness.dark,
        dynamicSchemeVariant: schemeVariantFor(scheme)!,
      );
      backgrounds.add(scheme0.surface.toARGB32());
      primaries.add(scheme0.primary.toARGB32());
    }
    // Not all nine differ on every role — neutral and monochrome share a
    // grey ground by design — but the picker must not be nine of one thing.
    expect(primaries.length, greaterThan(4));
    expect(backgrounds.length, greaterThan(2));
  });

  test('monochrome really is colourless', () {
    final mono = ColorScheme.fromSeed(
      seedColor: seed,
      dynamicSchemeVariant: schemeVariantFor('scheme-monochrome')!,
    );
    final p = mono.primary;
    expect(
      (p.r - p.g).abs() < 0.02 && (p.g - p.b).abs() < 0.02,
      isTrue,
      reason: 'monochrome primary should be grey, got $p',
    );
  });
}
