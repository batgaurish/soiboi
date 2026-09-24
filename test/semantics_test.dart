import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/utils/semantics_labels.dart';
import 'package:soiboi/base/widgets/icon_label.dart';
import 'package:soiboi/base/widgets/my_switch.dart';
import 'package:soiboi/base/widgets/song_semantics.dart';

MyAudioMetadata _song() => MyAudioMetadata(
  AudioMetadata(
    title: 'One More Time',
    artist: 'Daft Punk',
    album: 'Discovery',
    duration: const Duration(minutes: 5, seconds: 20),
  ),
  id: 'one-more-time',
  path: '/tmp/one-more-time.m4a',
);

/// Every node a screen reader can activate, in tree order.
List<SemanticsNode> _tappable(WidgetTester tester) =>
    find.semantics.byAction(SemanticsAction.tap).evaluate().toList();

void main() {
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_semantics');
  });

  group('labels', () {
    test('durations read without a leading zero', () {
      expect(durationLabel(const Duration(minutes: 3, seconds: 21)), '3:21');
      expect(durationLabel(const Duration(seconds: 7)), '0:07');
      expect(
        durationLabel(const Duration(hours: 1, minutes: 2, seconds: 3)),
        '1:02:03',
      );
    });

    test('a song reads as one sentence, skipping what is missing', () {
      expect(
        songLabel(
          title: 'One More Time',
          artist: 'Daft Punk',
          duration: const Duration(minutes: 5, seconds: 20),
        ),
        'One More Time, Daft Punk, 5:20',
      );
      expect(songLabel(title: 'Intro', artist: ' '), 'Intro');
      expect(percentLabel(0.456), '46%');
    });
  });

  testWidgets('a song row is one item that plays, beside its own buttons', (
    tester,
  ) async {
    final handle = tester.ensureSemantics();
    final song = _song();
    var played = 0;
    var menus = 0;

    await tester.pumpWidget(
      MaterialApp(
        home: Material(
          child: ListView(
            children: [
              // A custom row: the wrapper owns the item and its actions.
              SongSemantics(
                song: song,
                onTap: () => played++,
                onLongPress: () => menus++,
                child: InkWell(
                  excludeFromSemantics: true,
                  onTap: () => played++,
                  child: Row(
                    children: [
                      const ExcludeSemantics(child: Text('One More Time')),
                      IconButton(
                        tooltip: 'Add to favorites',
                        icon: const Icon(Icons.star_outline),
                        onPressed: () {},
                      ),
                    ],
                  ),
                ),
              ),
              // A ListTile: the label goes in its title, onto its own item.
              ListTile(
                title: SongSemantics.title(
                  song: song,
                  extra: 'second row',
                  child: const Text('One More Time'),
                ),
                subtitle: const ExcludeSemantics(
                  child: Text('Daft Punk - Discovery'),
                ),
                onTap: () => played++,
                onLongPress: () => menus++,
                trailing: IconButton(
                  tooltip: 'More options for One More Time',
                  icon: const Icon(Icons.more_vert),
                  onPressed: () {},
                ),
              ),
            ],
          ),
        ),
      ),
    );

    await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));

    // Each row and each button is one labelled thing to activate; no
    // unlabelled leftover from an ink well or the ListTile's own item.
    final tappable = _tappable(tester);
    String spoken(SemanticsNode node) {
      // A tooltip is what a screen reader says for an icon button.
      final data = node.getSemanticsData();
      return data.label.isNotEmpty ? data.label : data.tooltip;
    }

    expect(tappable.map(spoken).toList(), [
      'One More Time, Daft Punk, 5:20, album Discovery',
      'Add to favorites',
      'One More Time, Daft Punk, 5:20, album Discovery, second row',
      'More options for One More Time',
    ]);

    for (final label in [
      'One More Time, Daft Punk, 5:20, album Discovery',
      'One More Time, Daft Punk, 5:20, album Discovery, second row',
    ]) {
      tester.semantics.tap(find.semantics.byLabel(label));
      tester.semantics.longPress(find.semantics.byLabel(label));
    }
    expect(played, 2);
    expect(menus, 2);

    // The row says when it is the song playing, and when it is a favorite.
    currentSongNotifier.value = song;
    isPlayingNotifier.value = true;
    song.isFavoriteNotifier.value = true;
    await tester.pump();
    expect(
      find.bySemanticsLabel(
        'One More Time, Daft Punk, 5:20, album Discovery, now playing, '
        'favorite',
      ),
      findsOneWidget,
    );
    expect(
      find.bySemanticsLabel(RegExp('now playing, favorite, second row')),
      findsOneWidget,
    );
    currentSongNotifier.value = null;
    isPlayingNotifier.value = false;
    handle.dispose();
  });

  testWidgets('switches say what they control and their state', (tester) async {
    final handle = tester.ensureSemantics();
    final on = ValueNotifier(true);
    final view = ValueNotifier(true);
    await tester.pumpWidget(
      MaterialApp(
        home: Material(
          child: Column(
            children: [
              MySwitch(semanticLabel: 'Notifications', valueNotifier: on),
              MySwitch(
                semanticLabel: 'View',
                trueText: 'List',
                falseText: 'Grid',
                valueNotifier: view,
              ),
            ],
          ),
        ),
      ),
    );

    final plain = tester.getSemantics(find.byType(MySwitch).first);
    expect(plain.label, 'Notifications');
    expect(plain.getSemanticsData().flagsCollection.isToggled.toBoolOrNull(), isTrue);

    final choice = tester.getSemantics(find.byType(MySwitch).last);
    expect(choice.label, 'View: List');
    expect(choice.hint, 'Switches to Grid');

    tester.semantics.tap(find.semantics.byLabel('Notifications'));
    tester.semantics.tap(find.semantics.byLabel('View: List'));
    await tester.pump();
    expect(on.value, isFalse);
    expect(view.value, isFalse);
    expect(find.bySemanticsLabel('View: Grid'), findsOneWidget);
    handle.dispose();
  });

  testWidgets('icon buttons get their tooltip as a name on Linux', (
    tester,
  ) async {
    final handle = tester.ensureSemantics();
    await tester.pumpWidget(
      MaterialApp(
        home: Material(
          child: IconButton(
            tooltip: 'Pause',
            icon: labelIcon('Pause', const Icon(Icons.pause)),
            onPressed: () {},
          ),
        ),
      ),
    );
    final data = tester.getSemantics(find.byType(IconButton));
    // Tests run on Linux, where Orca reads only the label.
    expect(data.label, Platform.isLinux ? 'Pause' : '');
    expect(data.tooltip, 'Pause');
    handle.dispose();
  });
}
