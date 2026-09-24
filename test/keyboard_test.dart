import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/keyboard.dart';
import 'package:soiboi/base/widgets/my_switch.dart';
import 'package:soiboi/base/widgets/song_semantics.dart';
import 'package:soiboi/landscape_view/sidebar.dart';
import 'package:soiboi/layer/global_search_layer.dart';

/// Records what the shortcuts ask of the player, which needs native audio.
class _FakeHandler implements MyAudioHandler {
  final calls = <String>[];
  Duration position = const Duration(seconds: 30);

  @override
  void togglePlay() => calls.add('toggle');

  @override
  Duration getPosition() => position;

  @override
  Future<void> seek(Duration position) async {
    calls.add('seek ${position.inSeconds}');
    this.position = position;
  }

  @override
  Future<void> skipToNext() async => calls.add('next');

  @override
  Future<void> skipToPrevious() async => calls.add('previous');

  @override
  void setVolume(double volume) {}

  @override
  void savePlayState() {}

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

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

Future<void> _app(WidgetTester tester, Widget body) => tester.pumpWidget(
  MaterialApp(
    navigatorKey: globalNavigatorKey,
    home: Scaffold(body: body),
  ),
);

Future<void> _withKey(
  WidgetTester tester,
  LogicalKeyboardKey modifier,
  LogicalKeyboardKey key,
) async {
  await tester.sendKeyDownEvent(modifier);
  await tester.sendKeyEvent(key);
  await tester.sendKeyUpEvent(modifier);
  await tester.pump();
}

void main() {
  late _FakeHandler handler;

  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_keyboard');
  });

  setUp(() {
    // The test binding drops keyboard handlers after each test.
    keyboardInit();
    handler = _FakeHandler();
    audioHandler = handler;
    playQueue = [_song()];
    currentSongNotifier.value = playQueue.first;
    volumeNotifier.value = 0.5;
  });

  tearDown(() {
    playQueue = [];
    currentSongNotifier.value = null;
  });

  testWidgets('Space plays or pauses, but not while typing or on a button', (
    tester,
  ) async {
    final field = FocusNode();
    final button = FocusNode();
    addTearDown(field.dispose);
    addTearDown(button.dispose);
    var pressed = 0;
    await _app(
      tester,
      Column(
        children: [
          TextField(focusNode: field),
          ElevatedButton(
            focusNode: button,
            onPressed: () => pressed++,
            child: const Text('Button'),
          ),
        ],
      ),
    );

    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    expect(handler.calls, ['toggle']);

    field.requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    expect(handler.calls, ['toggle']);

    button.requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    await tester.pumpAndSettle();
    expect(pressed, 1);
    expect(handler.calls, ['toggle']);
  });

  testWidgets('arrows seek 5 seconds; with Shift they change song', (
    tester,
  ) async {
    await _app(tester, const SizedBox());

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    expect(handler.calls, ['seek 35', 'seek 30']);

    handler.position = const Duration(seconds: 2);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    expect(handler.calls.last, 'seek 0');

    handler.calls.clear();
    await _withKey(
      tester,
      LogicalKeyboardKey.shiftLeft,
      LogicalKeyboardKey.arrowRight,
    );
    await _withKey(
      tester,
      LogicalKeyboardKey.shiftLeft,
      LogicalKeyboardKey.arrowLeft,
    );
    expect(handler.calls, ['next', 'previous']);
  });

  testWidgets('up and down change the volume, within its range', (
    tester,
  ) async {
    await _app(tester, const SizedBox());

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
    expect(volumeNotifier.value, closeTo(0.55, 1e-9));
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    expect(volumeNotifier.value, closeTo(0.45, 1e-9));

    volumeNotifier.value = 0.98;
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
    expect(volumeNotifier.value, 1.0);
  });

  testWidgets('a text field or a slider keeps its own arrows', (tester) async {
    final field = FocusNode();
    final slider = FocusNode();
    addTearDown(field.dispose);
    addTearDown(slider.dispose);
    var sliderValue = 0.5;
    await _app(
      tester,
      StatefulBuilder(
        builder: (context, setState) => Column(
          children: [
            TextField(focusNode: field),
            Slider(
              focusNode: slider,
              value: sliderValue,
              onChanged: (v) => setState(() => sliderValue = v),
            ),
          ],
        ),
      ),
    );

    field.requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);

    slider.requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowUp);
    await tester.pump();

    expect(handler.calls, isEmpty);
    expect(volumeNotifier.value, 0.5);
    expect(sliderValue, greaterThan(0.5));
  });

  testWidgets('? lists every shortcut, and the list keeps its keys', (
    tester,
  ) async {
    await _app(tester, const SizedBox());

    await tester.sendKeyDownEvent(LogicalKeyboardKey.shiftLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.slash, character: '?');
    await tester.sendKeyUpEvent(LogicalKeyboardKey.shiftLeft);
    await tester.pumpAndSettle();

    expect(find.text('Keyboard shortcuts'), findsOneWidget);
    for (final (keys, action) in shortcutList) {
      expect(find.text(keys), findsOneWidget);
      expect(find.text(action), findsOneWidget);
    }
    // Shift+/ is `?`, not search.
    expect(sidebarHighlighLabel.value, isNot('search'));

    // Space and the arrows belong to the dialog while it is open.
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowRight);
    expect(handler.calls, isEmpty);

    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(find.text('Keyboard shortcuts'), findsNothing);
  });

  testWidgets('arrows move through a context menu instead of seeking', (
    tester,
  ) async {
    await _app(tester, const SizedBox());
    var chosen = '';
    // The shape of showContextMenu's route: a page that a tap outside, or
    // Esc, dismisses.
    globalNavigatorKey.currentState!.push(
      PageRouteBuilder<void>(
        opaque: false,
        barrierDismissible: true,
        pageBuilder: (context, _, _) => Material(
          child: Column(
            children: [
              for (final item in ['Play next', 'Add to playlist'])
                InkWell(onTap: () => chosen = item, child: Text(item)),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyEvent(LogicalKeyboardKey.arrowDown);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pump();
    expect(chosen, 'Add to playlist');
    expect(handler.calls, isEmpty);
    expect(volumeNotifier.value, 0.5);

    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    expect(find.text('Play next'), findsNothing);
  });

  testWidgets('Ctrl+D opens Downloads; Ctrl+F and / open search', (
    tester,
  ) async {
    final field = FocusNode();
    addTearDown(field.dispose);
    await _app(tester, TextField(focusNode: field));

    await _withKey(
      tester,
      LogicalKeyboardKey.controlLeft,
      LogicalKeyboardKey.keyD,
    );
    expect(sidebarHighlighLabel.value, 'downloads');

    final asked = focusSearchNotifier.value;
    await _withKey(
      tester,
      LogicalKeyboardKey.controlLeft,
      LogicalKeyboardKey.keyF,
    );
    expect(sidebarHighlighLabel.value, 'search');
    expect(focusSearchNotifier.value, asked + 1);

    await _withKey(
      tester,
      LogicalKeyboardKey.controlLeft,
      LogicalKeyboardKey.keyD,
    );
    await tester.sendKeyEvent(LogicalKeyboardKey.slash);
    await tester.pump();
    expect(sidebarHighlighLabel.value, 'search');
    expect(focusSearchNotifier.value, asked + 2);

    // In a text field, Ctrl+F still searches, but Ctrl+D and / are the
    // field's.
    field.requestFocus();
    await tester.pump();
    await _withKey(
      tester,
      LogicalKeyboardKey.controlLeft,
      LogicalKeyboardKey.keyD,
    );
    await tester.sendKeyEvent(LogicalKeyboardKey.slash);
    expect(sidebarHighlighLabel.value, 'search');
    expect(focusSearchNotifier.value, asked + 2);
    await _withKey(
      tester,
      LogicalKeyboardKey.controlLeft,
      LogicalKeyboardKey.keyF,
    );
    expect(focusSearchNotifier.value, asked + 3);
  });

  testWidgets('a focused song row: Enter plays, Menu opens its options', (
    tester,
  ) async {
    final row = FocusNode();
    final star = FocusNode();
    addTearDown(row.dispose);
    addTearDown(star.dispose);
    var played = 0;
    var menus = 0;
    var selected = 0;
    var starred = 0;
    await _app(
      tester,
      Material(
        child: SongSemantics(
          song: playQueue.first,
          onTap: () => played++,
          onLongPress: () => menus++,
          child: InkWell(
            focusNode: row,
            excludeFromSemantics: true,
            onTap: () => selected++,
            child: Row(
              children: [
                const Text('One More Time'),
                IconButton(
                  focusNode: star,
                  tooltip: 'Add to favorites',
                  icon: const Icon(Icons.star_outline),
                  onPressed: () => starred++,
                ),
              ],
            ),
          ),
        ),
      ),
    );

    row.requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.sendKeyEvent(LogicalKeyboardKey.numpadEnter);
    expect(played, 2);
    await tester.sendKeyEvent(LogicalKeyboardKey.contextMenu);
    await _withKey(
      tester,
      LogicalKeyboardKey.shiftLeft,
      LogicalKeyboardKey.f10,
    );
    expect(menus, 2);

    // Space plays or pauses, as anywhere else, instead of selecting.
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    expect(handler.calls, ['toggle']);
    // With nothing queued, it plays the row.
    playQueue = [];
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    expect(played, 3);
    expect(selected, 0);

    // A button inside the row keeps Enter for itself.
    star.requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pumpAndSettle();
    expect(starred, 1);
    expect(played, 3);
  });

  testWidgets('Tab reaches a switch, Space flips it, and a ring shows', (
    tester,
  ) async {
    final value = ValueNotifier(false);
    final next = FocusNode();
    addTearDown(value.dispose);
    addTearDown(next.dispose);
    await _app(
      tester,
      Material(
        child: Column(
          children: [
            MySwitch(valueNotifier: value, semanticLabel: 'Shuffle'),
            TextButton(
              focusNode: next,
              onPressed: () {},
              child: const Text('Next'),
            ),
          ],
        ),
      ),
    );
    Finder ring() => find.descendant(
      of: find.byType(MySwitch),
      matching: find.byWidgetPredicate(
        (w) => w is CustomPaint && w.foregroundPainter != null,
      ),
    );
    expect(ring(), findsNothing);

    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    expect(ring(), findsOneWidget);
    // One stop, not two: the next Tab goes on to the button, and the one
    // after comes back round to the switch.
    final stop = FocusManager.instance.primaryFocus;
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    expect(next.hasPrimaryFocus, isTrue);
    await tester.sendKeyEvent(LogicalKeyboardKey.tab);
    await tester.pump();
    expect(FocusManager.instance.primaryFocus, stop);

    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    await tester.pump();
    expect(value.value, isTrue);
    await tester.sendKeyEvent(LogicalKeyboardKey.enter);
    await tester.pump();
    expect(value.value, isFalse);
    expect(handler.calls, isEmpty);
  });
}
