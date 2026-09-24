import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/theme/motion.dart';

void main() {
  group('reduced motion', () {
    tearDown(() {
      motionPreferenceNotifier.value = MotionPreference.system;
      systemReducesMotionNotifier.value = false;
    });

    test('follows the system unless overridden', () {
      for (final system in [false, true]) {
        expect(
          resolveReduceMotion(MotionPreference.system, systemReduces: system),
          system,
        );
        expect(
          resolveReduceMotion(MotionPreference.reduced, systemReduces: system),
          isTrue,
        );
        expect(
          resolveReduceMotion(MotionPreference.full, systemReduces: system),
          isFalse,
        );
      }
    });

    test('the shared flag tracks both the system and the choice', () {
      var changes = 0;
      void count() => changes++;
      reduceMotionNotifier.addListener(count);
      addTearDown(() => reduceMotionNotifier.removeListener(count));

      expect(reduceMotion, isFalse);
      systemReducesMotionNotifier.value = true;
      expect(reduceMotion, isTrue);
      motionPreferenceNotifier.value = MotionPreference.full;
      expect(reduceMotion, isFalse);
      motionPreferenceNotifier.value = MotionPreference.reduced;
      expect(reduceMotion, isTrue);
      expect(changes, 3);

      expect(motionDuration(const Duration(seconds: 1)), Duration.zero);
      motionPreferenceNotifier.value = MotionPreference.full;
      expect(
        motionDuration(const Duration(seconds: 1)),
        const Duration(seconds: 1),
      );
    });

    testWidgets('motionFor collapses durations when reduced', (tester) async {
      late MotionSpec spec;
      Widget probe(bool disableAnimations) => MediaQuery(
        data: MediaQueryData(disableAnimations: disableAnimations),
        child: Builder(
          builder: (context) {
            spec = motionFor(context);
            return const SizedBox();
          },
        ),
      );

      await tester.pumpWidget(probe(false));
      expect(spec.medium, activeMotion.medium);

      await tester.pumpWidget(probe(true));
      expect(spec.medium, Duration.zero);
      expect(spec.staggerStep, Duration.zero);
      expect(spec.useContainerTransform, isFalse);

      motionPreferenceNotifier.value = MotionPreference.full;
      await tester.pumpWidget(probe(true));
      expect(spec.medium, activeMotion.medium);
    });
  });

  group('contrast', () {
    test('ratios match WCAG', () {
      expect(contrastRatio(Colors.black, Colors.white), closeTo(21, 0.01));
      expect(contrastRatio(Colors.white, Colors.white), closeTo(1, 0.001));
      expect(
        contrastRatio(const Color(0xFF777777), Colors.white),
        closeTo(4.48, 0.01),
      );
      expect(
        contrastRatio(Colors.red, Colors.blue),
        contrastRatio(Colors.blue, Colors.red),
      );
    });

    test('a translucent foreground is judged as it lands', () {
      final faint = Colors.black.withAlpha(20);
      expect(contrastRatio(faint, Colors.white), lessThan(1.3));
    });

    test('a colour that already reads is left alone', () {
      expect(ensureContrast(Colors.black, Colors.white), Colors.black);
    });

    test('nudges lightness only, just far enough', () {
      const accent = Color(0xFFFFB74D); // orange, too light on white
      final fixed = ensureContrast(accent, Colors.white);
      final before = HSLColor.fromColor(accent);
      final after = HSLColor.fromColor(fixed);

      expect(contrastRatio(fixed, Colors.white), greaterThanOrEqualTo(4.5));
      expect(contrastRatio(fixed, Colors.white), lessThan(4.6));
      expect(after.hue, closeTo(before.hue, 1));
      expect(after.saturation, closeTo(before.saturation, 0.02));
      expect(after.lightness, lessThan(before.lightness));
    });

    test('goes lighter on a dark ground', () {
      const ground = Color(0xFF1B1B2F);
      final fixed = ensureContrast(const Color(0xFF3949AB), ground);
      expect(contrastRatio(fixed, ground), greaterThanOrEqualTo(4.5));
      expect(
        HSLColor.fromColor(fixed).lightness,
        greaterThan(HSLColor.fromColor(const Color(0xFF3949AB)).lightness),
      );
    });

    test('keeps the original alpha', () {
      final fixed = ensureContrast(
        const Color(0x80FFB74D),
        Colors.white,
        minRatio: kLargeContrast,
      );
      expect((fixed.a * 255).round(), 0x80);
    });

    test('every random pair reaches the minimum', () {
      final random = Random(7);
      Color any() => Color.fromARGB(
        255,
        random.nextInt(256),
        random.nextInt(256),
        random.nextInt(256),
      );
      for (var i = 0; i < 2000; i++) {
        final ground = any();
        final text = ensureContrast(any(), ground);
        final icon = ensureContrast(any(), ground, minRatio: kLargeContrast);
        expect(
          contrastRatio(text, ground),
          greaterThanOrEqualTo(kTextContrast - 1e-9),
          reason: 'text on $ground',
        );
        expect(
          contrastRatio(icon, ground),
          greaterThanOrEqualTo(kLargeContrast - 1e-9),
          reason: 'icon on $ground',
        );
      }
    });

    test('readableOr keeps the preferred colour only when it reads', () {
      expect(
        readableOr(Colors.black, Colors.white, Colors.red),
        Colors.black,
      );
      expect(
        readableOr(const Color(0xFFEEEEEE), Colors.white, Colors.black),
        Colors.black,
      );
    });
  });
}
