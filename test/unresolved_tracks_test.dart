import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/apple_catalog_service.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/manual_resolution.dart';
import 'package:soiboi/layer/unresolved_tracks.dart';

final _track = UnresolvedTrack(
  artist: 'Måneskin',
  title: 'I Wanna Be Your Slave',
  playlist: 'Weekly Exploration',
  added: DateTime.utc(2026, 9, 25),
);

AppleCandidate _song(int i) => AppleCandidate(
  title: 'I Wanna Be Your Slave',
  artist: 'Cover Band $i',
  url: 'https://music.apple.com/in/album/x/1?i=$i',
  album: 'Covers, Vol. $i',
  year: '2021',
  duration: const Duration(minutes: 2, seconds: 53),
  previewUrl: 'https://audio.invalid/$i.m4a',
);

void main() {
  setUpAll(() async {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_unresolved');
    await logger.init();
    await setting.load();
    colorManager.updateMainPageColors();
  });

  Future<List<Object?>> pump(
    WidgetTester tester,
    Widget child, {
    double scale = 1,
  }) async {
    final popped = <Object?>[];
    tester.view.physicalSize = const Size(360, 780);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        // Fresh each time, so a second pump starts from the button.
        key: UniqueKey(),
        builder: (context, app) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: app!,
        ),
        home: Scaffold(
          backgroundColor: menuColor.value,
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () async => popped.add(
                await Navigator.push<Object?>(
                  context,
                  MaterialPageRoute<Object?>(
                    builder: (_) => Scaffold(
                      backgroundColor: menuColor.value,
                      body: SafeArea(child: child),
                    ),
                  ),
                ),
              ),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    return popped;
  }

  testWidgets('Find match searches for artist and title, and returns a pick', (
    tester,
  ) async {
    final handle = tester.ensureSemantics();
    final terms = <String>[];
    final popped = await pump(
      tester,
      FindMatchSheet(
        track: _track,
        search: (term) async {
          terms.add(term);
          return [for (var i = 1; i <= 3; i++) _song(i)];
        },
      ),
    );
    expect(terms, ['Måneskin I Wanna Be Your Slave']);
    expect(find.text('Cover Band 2 · Covers, Vol. 2 · 2021 · 2:53'), findsOne);
    expect(
      find.bySemanticsLabel('Preview I Wanna Be Your Slave'),
      findsWidgets,
    );
    await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
    await expectLater(tester, meetsGuideline(textContrastGuideline));

    await tester.enterText(find.byType(TextField), 'Slave cover');
    await tester.testTextInput.receiveAction(TextInputAction.search);
    await tester.pumpAndSettle();
    expect(terms.last, 'Slave cover');

    await tester.tap(find.bySemanticsLabel(RegExp('^Use .*Cover Band 2')));
    await tester.pumpAndSettle();
    expect((popped.single! as AppleCandidate).artist, 'Cover Band 2');
    handle.dispose();
  });

  testWidgets('the sheet and the card fit a phone at 200% text', (
    tester,
  ) async {
    await pump(
      tester,
      FindMatchSheet(
        track: _track,
        search: (_) async => [for (var i = 1; i <= 3; i++) _song(i)],
      ),
      scale: 2,
    );
    expect(tester.takeException(), isNull);

    manualResolution.addUnresolved([_track]);
    await pump(
      tester,
      const SingleChildScrollView(child: UnresolvedTracksList()),
      scale: 2,
    );
    expect(tester.takeException(), isNull);
    expect(
      find.bySemanticsLabel('Find match for I Wanna Be Your Slave'),
      findsOne,
    );
    await tester.tap(find.bySemanticsLabel('Skip I Wanna Be Your Slave'));
    await tester.pumpAndSettle();
    expect(manualResolution.unresolved.value, isEmpty);
    expect(
      manualResolution.overrideFor('Måneskin', 'I Wanna Be Your Slave'),
      skipOverride,
    );
  });
}
