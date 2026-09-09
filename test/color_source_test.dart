/// Guards the colour/flavour split.
///
/// v4.2.2 shipped a fix for "flavours don't change anything" that did not
/// work: `MyColor` resolved `dynamicColor() ?? flavourColor() ?? upstream`,
/// and dynamic colour — on by default — covered nearly every token, so a
/// flavour's palette was never reached. The two settings were fighting over
/// one channel, and the symptom got reported twice ("follow system colours
/// only changes the sidebar", "flavours don't change anything") because it
/// had one cause.
///
/// v4.2.3 split them: colour comes from a [ColorSource], flavour carries
/// shape and motion only. These tests pin that split down, since the failure
/// mode is silent — nothing throws, the app just quietly ignores a setting.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/theme/color_source.dart';
import 'package:soiboi/base/theme/dynamic_color.dart';
import 'package:soiboi/base/theme/flavour.dart';

void main() {
  // The notifiers are global, so every test restores them rather than
  // leaking a source into the next one.
  final source = colorSourceNotifier.value;
  final palette = prebuiltPaletteNotifier.value;
  final flavour = flavourNotifier.value;
  final light = dynamicLightNotifier.value;
  final dark = dynamicDarkNotifier.value;

  tearDown(() {
    colorSourceNotifier.value = source;
    prebuiltPaletteNotifier.value = palette;
    flavourNotifier.value = flavour;
    dynamicLightNotifier.value = light;
    dynamicDarkNotifier.value = dark;
  });

  group('sources are mutually exclusive', () {
    test('only the selected source answers', () {
      // A loaded matugen palette that must stay silent unless selected —
      // the v4.2.2 bug was exactly a source answering when it should not.
      dynamicDarkNotifier.value = {ColorToken.pageBackground: Color(0xFF00FF00)};

      colorSourceNotifier.value = ColorSource.prebuilt;
      expect(dynamicColor(ColorToken.pageBackground, isDark: true), isNull);
      expect(prebuiltColor(ColorToken.pageBackground, isDark: true), isNotNull);

      colorSourceNotifier.value = ColorSource.matugen;
      expect(
        dynamicColor(ColorToken.pageBackground, isDark: true),
        const Color(0xFF00FF00),
      );
      expect(prebuiltColor(ColorToken.pageBackground, isDark: true), isNull);
    });

    test('off falls through to the app\'s own colours', () {
      dynamicDarkNotifier.value = {ColorToken.pageBackground: Color(0xFF00FF00)};
      colorSourceNotifier.value = ColorSource.off;

      // Both null means `MyColor` reaches its upstream light/dark value,
      // which is what "App colours" is.
      expect(dynamicColor(ColorToken.pageBackground, isDark: true), isNull);
      expect(prebuiltColor(ColorToken.pageBackground, isDark: true), isNull);
    });
  });

  group('flavour carries no colour', () {
    test('changing flavour leaves every resolved colour untouched', () {
      colorSourceNotifier.value = ColorSource.prebuilt;
      prebuiltPaletteNotifier.value = PrebuiltPalette.dracula;

      for (final isDark in [true, false]) {
        final before = {
          for (final token in ColorToken.values)
            token: prebuiltColor(token, isDark: isDark),
        };
        for (final f in Flavour.values) {
          flavourNotifier.value = f;
          for (final token in ColorToken.values) {
            expect(
              prebuiltColor(token, isDark: isDark),
              before[token],
              reason: '$f changed $token (isDark: $isDark)',
            );
          }
        }
      }
    });

    test('a flavour spec exposes shape and motion, never a palette', () {
      // Reflection is not available, so this asserts the shape positively:
      // the fields a flavour *does* have are the layout ones, and the three
      // flavours differ in them — a flavour that changed nothing at all
      // would be the other half of the original bug report.
      final specs = Flavour.values.map((f) => flavourSpecs[f]!).toList();
      expect(specs.map((s) => s.cornerScale).toSet().length, greaterThan(1));
      expect(specs.map((s) => s.density).toSet().length, greaterThan(1));
      expect(
        specs.map((s) => s.surfaceTreatment).toSet().length,
        greaterThan(1),
      );
    });
  });

  group('prebuilt palettes', () {
    test('every palette covers the same tokens', () {
      // A sparse palette is not a compile error, it is a half-themed screen:
      // the missing tokens silently fall back to upstream's colours and
      // clash with the rest of the scheme.
      final reference = prebuiltPalettes[PrebuiltPalette.dracula]!.dark.keys
          .toSet();
      expect(reference, isNotEmpty);

      for (final entry in prebuiltPalettes.entries) {
        expect(
          entry.value.dark.keys.toSet(),
          reference,
          reason: '${entry.key.label} dark covers a different token set',
        );
        expect(
          entry.value.light.keys.toSet(),
          reference,
          reason: '${entry.key.label} light covers a different token set',
        );
      }
    });

    test('every palette is offered and distinct', () {
      // Guards copy-paste: six entries that resolve to the same colours
      // would give a picker with six identical-looking choices.
      expect(prebuiltPalettes.keys.toSet(), PrebuiltPalette.values.toSet());

      final accents = <Color>{};
      for (final p in PrebuiltPalette.values) {
        expect(p.label, isNotEmpty);
        accents.add(p.accent(isDark: true));
        // Light and dark must genuinely differ, or one brightness is unusable.
        expect(
          prebuiltPalettes[p]!.light,
          isNot(equals(prebuiltPalettes[p]!.dark)),
          reason: '${p.label} has identical light and dark variants',
        );
      }
      expect(accents.length, PrebuiltPalette.values.length);
    });

    test('backgrounds and text contrast in both brightnesses', () {
      // The cheapest guard against an unreadable palette: text must not sit
      // at the same luminance as the surface behind it.
      for (final p in PrebuiltPalette.values) {
        for (final isDark in [true, false]) {
          final spec = isDark
              ? prebuiltPalettes[p]!.dark
              : prebuiltPalettes[p]!.light;
          final bg = spec[ColorToken.pageBackground]!;
          final text = spec[ColorToken.highlightText]!;
          final delta =
              (bg.computeLuminance() - text.computeLuminance()).abs();
          expect(
            delta,
            greaterThan(0.3),
            reason: '${p.label} ${isDark ? 'dark' : 'light'} is low contrast',
          );
        }
      }
    });
  });
}
