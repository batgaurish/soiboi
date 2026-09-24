import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:soiboi/base/utils/dynamic_lyrics_page_route.dart';
import 'package:soiboi/base/widgets/marquee_text.dart';
import 'package:soiboi/portrait_view/custom_page_transition_builder.dart';
import 'package:text_scroll/text_scroll.dart';

Future<void> _pushLyricsRoute(WidgetTester tester) async {
  final navigator = GlobalKey<NavigatorState>();
  await tester.pumpWidget(
    MaterialApp(
      navigatorKey: navigator,
      home: const SizedBox(width: 360, height: 640),
    ),
  );
  navigator.currentState!.push(
    DynamicLyricsPageRoute(pageBuilder: (_, _, _) => const Text('Lyrics')),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 50));
}

void main() {
  tearDown(() => motionPreferenceNotifier.value = MotionPreference.system);

  testWidgets('the lyrics page slides up with full motion', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    motionPreferenceNotifier.value = MotionPreference.full;

    await _pushLyricsRoute(tester);
    expect(
      find.ancestor(
        of: find.text('Lyrics'),
        matching: find.byType(SlideTransition),
      ),
      findsWidgets,
    );
    await tester.pumpAndSettle();
  });

  testWidgets('and fades in, quickly, with motion reduced', (tester) async {
    tester.view.physicalSize = const Size(360, 640);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    motionPreferenceNotifier.value = MotionPreference.reduced;

    await _pushLyricsRoute(tester);
    expect(
      find.ancestor(
        of: find.text('Lyrics'),
        matching: find.byType(SlideTransition),
      ),
      findsNothing,
    );
    expect(
      find.ancestor(
        of: find.text('Lyrics'),
        matching: find.byType(FadeTransition),
      ),
      findsWidgets,
    );
    // Done well inside the full-motion 500 ms.
    await tester.pump(reducedTransitionDuration);
    expect(tester.hasRunningAnimations, isFalse);
  });

  testWidgets('Android page changes fade too', (tester) async {
    late BuildContext context;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (c) {
            context = c;
            return const SizedBox();
          },
        ),
      ),
    );
    Widget transition() => const CustomPageTransitionBuilder().buildTransitions(
      MaterialPageRoute<void>(builder: (_) => const SizedBox()),
      context,
      kAlwaysCompleteAnimation,
      kAlwaysDismissedAnimation,
      const SizedBox(),
    );

    motionPreferenceNotifier.value = MotionPreference.full;
    expect(transition(), isA<SlideTransition>());
    motionPreferenceNotifier.value = MotionPreference.reduced;
    expect(transition(), isA<FadeTransition>());
  });

  testWidgets('long titles stop scrolling with motion reduced', (tester) async {
    Future<void> show() => tester.pumpWidget(
      const MaterialApp(
        home: SizedBox(
          width: 100,
          child: MarqueeText('A Very Long Title That Keeps Going'),
        ),
      ),
    );

    motionPreferenceNotifier.value = MotionPreference.full;
    await show();
    expect(find.byType(TextScroll), findsOneWidget);

    motionPreferenceNotifier.value = MotionPreference.reduced;
    await tester.pump();
    expect(find.byType(TextScroll), findsNothing);
    final text = tester.widget<Text>(
      find.text('A Very Long Title That Keeps Going'),
    );
    expect(text.overflow, TextOverflow.ellipsis);
    expect(text.maxLines, 1);
  });

  testWidgets('scrolling to a place jumps with motion reduced', (tester) async {
    final controller = ScrollController();
    await tester.pumpWidget(
      MaterialApp(
        home: ListView(
          controller: controller,
          children: [
            for (var i = 0; i < 100; i++)
              SizedBox(height: 50, child: Text('$i')),
          ],
        ),
      ),
    );

    motionPreferenceNotifier.value = MotionPreference.reduced;
    await controller.glideTo(
      1000,
      duration: const Duration(seconds: 1),
      curve: Curves.linear,
    );
    expect(controller.offset, 1000);
    expect(tester.hasRunningAnimations, isFalse);

    motionPreferenceNotifier.value = MotionPreference.full;
    final glide = controller.glideTo(
      0,
      duration: const Duration(milliseconds: 300),
      curve: Curves.linear,
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 150));
    expect(controller.offset, allOf(greaterThan(0), lessThan(1000)));
    await tester.pumpAndSettle();
    await glide;
    expect(controller.offset, 0);
  });
}
