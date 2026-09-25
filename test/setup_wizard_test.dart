import 'dart:io';

import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/listenbrainz_service.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/layer/setup_wizard.dart';

void main() {
  setUpAll(() async {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_wizard');
    // Choices are saved as they are made, into a throwaway settings file.
    await setting.load();
    // The app's own palette, so the contrast check sees real colours.
    colorManager.updateMainPageColors();
  });

  setUp(() {
    downloadCodecNotifier.value = 'aac';
    downloadFolderNotifier.value = '';
    listenBrainzUserNotifier.value = '';
  });

  Future<List<SetupResult>> pumpWizard(WidgetTester tester) async {
    final results = <SetupResult>[];
    tester.view.physicalSize = const Size(1200, 1600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: SetupWizard(onFinish: (result) async => results.add(result)),
      ),
    );
    await tester.pumpAndSettle();
    return results;
  }

  Future<void> tapText(WidgetTester tester, String text) async {
    await tester.tap(find.text(text));
    await tester.pumpAndSettle();
  }

  /// Every step: labelled tap targets and readable text.
  Future<void> checkAccessible(WidgetTester tester) async {
    await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
    await expectLater(tester, meetsGuideline(textContrastGuideline));
  }

  testWidgets('every step can be skipped through to a summary', (tester) async {
    final handle = tester.ensureSemantics();
    final results = await pumpWizard(tester);

    expect(find.text('Step 1 of 5'), findsOneWidget);
    expect(find.text('Your music'), findsOneWidget);
    await checkAccessible(tester);

    await tapText(tester, 'Skip this step');
    expect(find.text('Apple Music sign-in'), findsOneWidget);
    expect(find.text('Lossless sign-in'), findsOneWidget);
    expect(find.text('Browser sign-in'), findsOneWidget);
    await checkAccessible(tester);

    await tapText(tester, 'Skip this step');
    expect(find.text('Download options'), findsOneWidget);
    await checkAccessible(tester);

    // Defaults are fine here, so the button says Next, not Skip.
    await tapText(tester, 'Next');
    expect(find.text('Step 4 of 5'), findsOneWidget);
    await checkAccessible(tester);

    await tapText(tester, 'Skip this step');
    expect(find.text('All set'), findsOneWidget);
    expect(find.text('Not connected'), findsOneWidget);
    await checkAccessible(tester);

    await tapText(tester, 'Start listening');
    expect(results, hasLength(1));
    expect(results.single.openDownloads, isFalse);
    expect(results.single.foldersChanged, isFalse);
    handle.dispose();
  });

  testWidgets('"Skip setup" goes straight to the summary', (tester) async {
    await pumpWizard(tester);
    await tapText(tester, 'Skip setup');
    expect(find.text('Step 5 of 5'), findsOneWidget);
    // Hidden there, but kept in place so the title does not move.
    expect(find.text('Skip setup').hitTestable(), findsNothing);
  });

  testWidgets(
    'ALAC without the lossless sign-in is flagged, with a way there',
    (tester) async {
      await pumpWizard(tester);
      await tapText(tester, 'Skip setup');
      await tapText(tester, 'Back');
      await tapText(tester, 'Back');
      expect(find.text('Download options'), findsOneWidget);

      await tapText(tester, 'ALAC (Apple Lossless)');
      expect(downloadCodecNotifier.value, 'alac');
      expect(find.text('ALAC needs the lossless sign-in.'), findsOneWidget);

      await tapText(tester, 'Set it up');
      expect(find.text('Apple Music sign-in'), findsOneWidget);

      await tapText(tester, 'Skip setup');
      expect(
        find.text('ALAC (Apple Lossless): needs the lossless sign-in'),
        findsOneWidget,
      );
    },
  );

  testWidgets('each summary row changes its own step', (tester) async {
    final handle = tester.ensureSemantics();
    listenBrainzUserNotifier.value = 'someone';
    await pumpWizard(tester);
    await tapText(tester, 'Skip setup');
    expect(find.text('someone'), findsOneWidget);

    // Five "Change" buttons, each named for its row.
    for (final row in [
      'Music',
      'Apple Music',
      'Quality',
      'Downloads go to',
      'ListenBrainz',
    ]) {
      expect(find.bySemanticsLabel('Change $row'), findsOneWidget);
    }
    await tester.tap(find.bySemanticsLabel('Change ListenBrainz'));
    await tester.pumpAndSettle();
    expect(find.text('Step 4 of 5'), findsOneWidget);
    handle.dispose();
  });

  testWidgets('the step title is a heading, and each step is announced', (
    tester,
  ) async {
    final handle = tester.ensureSemantics();
    await pumpWizard(tester);
    final title = tester.getSemantics(find.text('Your music'));
    expect(title.flagsCollection.isHeader, isTrue);

    // One live region that stays put while its label follows the step.
    SemanticsNode live() =>
        tester.getSemantics(find.bySemanticsLabel(RegExp(r'^Step \d of 5: ')));
    expect(live().label, 'Step 1 of 5: Your music');
    expect(live().flagsCollection.isLiveRegion, isTrue);
    final id = live().id;
    await tapText(tester, 'Skip this step');
    expect(live().label, 'Step 2 of 5: Apple Music sign-in');
    expect(live().id, id);
    handle.dispose();
  });

  testWidgets('Back on a later step goes to the previous step', (tester) async {
    await pumpWizard(tester);
    await tapText(tester, 'Skip this step');
    await tapText(tester, 'Skip this step');
    expect(find.text('Download options'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('Apple Music sign-in'), findsOneWidget);
  });

  testWidgets('every step fits a phone at 200% text', (tester) async {
    tester.view.physicalSize = const Size(360, 780);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: const TextScaler.linear(2)),
          child: child!,
        ),
        home: SetupWizard(onFinish: (_) async {}),
      ),
    );
    await tester.pumpAndSettle();
    // Overflow is reported as an exception; each step is checked, then
    // the footer's main button moves on.
    for (var step = 1; step <= 5; step++) {
      expect(find.text('Step $step of 5'), findsOneWidget);
      expect(tester.takeException(), isNull, reason: 'step $step');
      if (step == 5) break;
      await tester.tap(find.byWidgetPredicate((w) => w is FilledButton).last);
      await tester.pumpAndSettle();
    }
  });
}
