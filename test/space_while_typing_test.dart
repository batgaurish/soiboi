import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/keyboard.dart';
import 'package:audio_tags_lofty/audio_tags_lofty.dart';

class _FakeHandler implements MyAudioHandler {
  int toggles = 0;

  @override
  void togglePlay() => toggles++;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_space');
  });

  tearDown(() => playQueue = []);

  testWidgets('Space types in a plain text field instead of pausing', (
    tester,
  ) async {
    // The test binding drops keyboard handlers after each test.
    keyboardInit();
    final handler = _FakeHandler();
    audioHandler = handler;
    playQueue = [
      MyAudioMetadata(
        AudioMetadata(title: 'One More Time'),
        id: 'one-more-time',
        path: '/tmp/one-more-time.m4a',
      ),
    ];
    final field = FocusNode();
    addTearDown(field.dispose);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(body: TextField(focusNode: field)),
      ),
    );

    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    expect(handler.toggles, 1);

    field.requestFocus();
    await tester.pump();
    await tester.sendKeyEvent(LogicalKeyboardKey.space);
    expect(handler.toggles, 1);
  });
}
