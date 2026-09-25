import 'dart:async';
import 'dart:io';
import 'dart:ui' show Tristate;

import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/archive_service.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/layer/download_queue_sheet.dart';

void main() {
  final gates = <String, Completer<DownloadFailure?>>{};
  final reports = <String, List<TrackStatus>>{};

  setUpAll(() async {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_queue_view');
    await setting.load();
    colorManager.updateMainPageColors();
    downloadQueue
      ..sync = (() async {})
      ..stopActive = (() async {})
      ..archive =
          (
            url, {
            bool redownload = false,
            void Function(int, String)? onProgress,
            void Function(String)? onLog,
            void Function(TrackStatus)? onTrack,
            String? logPath,
          }) async {
            onProgress?.call(50, 'Downloading');
            for (final track in reports[url] ?? const <TrackStatus>[]) {
              onTrack?.call(track);
            }
            return (gates[url] ??= Completer()).future;
          };
  });

  /// Ends every download, inside the test's clock, so none runs on into
  /// the next test.
  Future<void> drain(WidgetTester tester) async {
    downloadQueue.stopAll();
    for (final gate in gates.values) {
      if (!gate.isCompleted) gate.complete(null);
    }
    await tester.pumpAndSettle();
    downloadQueue.clearFinished();
    gates.clear();
    reports.clear();
  }

  Future<void> pump(WidgetTester tester, {double scale = 1}) async {
    tester.view.physicalSize = const Size(360, 780);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(
            context,
          ).copyWith(textScaler: TextScaler.linear(scale)),
          child: child!,
        ),
        home: Scaffold(
          backgroundColor: panelColor.value,
          body: const Padding(
            padding: EdgeInsets.all(16),
            child: DownloadQueueView(showTitle: true),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('a playlist queued as tracks sits under one header', (
    tester,
  ) async {
    final handle = tester.ensureSemantics();
    downloadQueue.enqueue([
      for (final title in ['One', 'Two', 'Three'])
        DownloadRequest(url: title, label: title, group: 'Weekly Jams'),
      const DownloadRequest(url: 'solo', label: 'Solo'),
    ]);
    (gates['One'] ??= Completer()).complete(null);
    await tester.pump();
    (gates['Two'] ??= Completer()).complete(
      const DownloadFailure('Sign in first', code: 'no_cookies'),
    );
    await pump(tester);

    expect(find.text('Weekly Jams'), findsOneWidget);
    expect(find.textContaining('1 of 3 done · 1 failed'), findsOneWidget);
    // Closed: the tracks are under the header, the lone job is not.
    expect(find.text('Three'), findsNothing);
    expect(find.text('Solo'), findsOneWidget);
    expect(
      tester.getSemantics(find.bySemanticsLabel('Show tracks: Weekly Jams')),
      matchesSemantics(
        label: 'Show tracks: Weekly Jams',
        value: '1 of 3 done · 1 failed · now Three',
        isButton: true,
        hasExpandedState: true,
        isExpanded: false,
        hasTapAction: true,
        isFocusable: true,
        hasFocusAction: true,
      ),
    );

    await tester.tap(find.bySemanticsLabel('Show tracks: Weekly Jams'));
    await tester.pumpAndSettle();
    expect(find.text('Three'), findsOneWidget);
    // The failed track keeps its own fix and log buttons.
    expect(find.byTooltip('Log'), findsWidgets);
    expect(
      tester
          .getSemantics(find.bySemanticsLabel('Show tracks: Weekly Jams'))
          .flagsCollection
          .isExpanded,
      Tristate.isTrue,
    );
    await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
    await expectLater(tester, meetsGuideline(textContrastGuideline));
    await drain(tester);
    handle.dispose();
  });

  testWidgets('a playlist link lists the tracks the pipeline reports', (
    tester,
  ) async {
    final handle = tester.ensureSemantics();
    reports['playlist'] = const [
      TrackStatus(index: 1, title: 'GO TO HELL', state: TrackState.done),
      TrackStatus(
        index: 2,
        title: 'change ur mind',
        state: TrackState.skipped,
        detail: 'Media file already exists (already in your library)',
        owned: true,
      ),
      TrackStatus(
        index: 3,
        title: 'Calling',
        state: TrackState.failed,
        detail: 'Error downloading "Calling" (ConnectTimeout)',
      ),
      TrackStatus(index: 4, title: 'Goodbye', state: TrackState.downloading),
    ];
    downloadQueue.enqueue([
      const DownloadRequest(url: 'playlist', label: 'Weekly Exploration'),
    ]);
    await pump(tester);

    final chevron = find.bySemanticsLabel('Show tracks: Weekly Exploration');
    expect(
      tester.getSemantics(chevron).value,
      '1 done · 1 already there · 1 failed',
    );
    await tester.tap(chevron);
    await tester.pumpAndSettle();
    expect(find.text('4. Goodbye'), findsOneWidget);
    expect(find.text('Already in your library'), findsOneWidget);
    expect(
      find.bySemanticsLabel(RegExp(r'^Track 3, Calling, failed, .+')),
      findsOneWidget,
    );
    await expectLater(tester, meetsGuideline(textContrastGuideline));
    await drain(tester);
    handle.dispose();
  });

  testWidgets('an open group fits a phone at 200% text', (tester) async {
    reports['p'] = [
      for (var i = 1; i <= 5; i++)
        TrackStatus(
          index: i,
          total: 50,
          title: 'A rather long song title number $i (From "A Film")',
          state: TrackState.done,
        ),
    ];
    downloadQueue.enqueue([
      const DownloadRequest(url: 'p', label: 'A playlist with a long name'),
      for (final t in ['x', 'y'])
        DownloadRequest(url: t, label: t, group: 'Another long playlist name'),
    ]);
    await pump(tester, scale: 2);
    // The lower one first: opening the upper pushes it out of view.
    await tester.tap(find.bySemanticsLabel(RegExp('^Show tracks: Another')));
    await tester.pumpAndSettle();
    await tester.tap(find.bySemanticsLabel(RegExp('^Show tracks: A playlist')));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await drain(tester);
  });
}
