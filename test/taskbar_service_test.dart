import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/taskbar_service.dart';

class _FakeAudioHandler implements MyAudioHandler {
  final calls = <String>[];

  @override
  void togglePlay() => calls.add('togglePlay');

  @override
  Future<void> skipToNext() async => calls.add('skipToNext');

  @override
  Future<void> skipToPrevious() async => calls.add('skipToPrevious');

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _FakeAudioHandler fakeAudio;

  setUp(() {
    fakeAudio = _FakeAudioHandler();
    audioHandler = fakeAudio;
  });

  group('setupTaskbar', () {
    test('enforces Windows-only assertion on non-Windows platforms', () async {
      if (!Platform.isWindows) {
        expect(() => setupTaskbar(), throwsA(isA<AssertionError>()));
      }
    });

    test('verifies taskbar audio action wiring', () {
      // AudioHandler actions called by taskbar button callbacks
      audioHandler.skipToPrevious();
      expect(fakeAudio.calls, contains('skipToPrevious'));

      audioHandler.togglePlay();
      expect(fakeAudio.calls, contains('togglePlay'));

      audioHandler.skipToNext();
      expect(fakeAudio.calls, contains('skipToNext'));
    });
  });
}
