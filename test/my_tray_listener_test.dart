import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/my_tray_listener.dart';
import 'package:soiboi/base/services/my_window_listener.dart';
import 'package:tray_manager/tray_manager.dart';

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

  final windowManagerCalls = <String>[];
  final trayManagerCalls = <String>[];
  late _FakeAudioHandler fakeAudio;

  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('window_manager'),
      (MethodCall call) async {
        windowManagerCalls.add(call.method);
        if (call.method == 'isMinimized') {
          return false;
        }
        return null;
      },
    );

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('tray_manager'),
      (MethodCall call) async {
        trayManagerCalls.add(call.method);
        return null;
      },
    );
  });

  setUp(() {
    windowManagerCalls.clear();
    trayManagerCalls.clear();
    fakeAudio = _FakeAudioHandler();
    audioHandler = fakeAudio;
    windowIsClosed = true;
  });

  group('MyTrayListener', () {
    test('onTrayIconMouseDown shows window and resets windowIsClosed', () async {
      final listener = MyTrayListener();
      listener.onTrayIconMouseDown();
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(windowIsClosed, isFalse);
      expect(windowManagerCalls, contains('show'));
    });

    test('onTrayIconRightMouseDown triggers trayManager context menu', () {
      final listener = MyTrayListener();
      listener.onTrayIconRightMouseDown();

      expect(trayManagerCalls, contains('popUpContextMenu'));
    });

    test('onTrayMenuItemClick handles show, skipToPrevious, togglePlay, and skipToNext', () async {
      final listener = MyTrayListener();

      listener.onTrayMenuItemClick(MenuItem(key: 'show'));
      await Future<void>.delayed(const Duration(milliseconds: 50));
      expect(windowIsClosed, isFalse);
      expect(windowManagerCalls, contains('show'));

      listener.onTrayMenuItemClick(MenuItem(key: 'skipToPrevious'));
      expect(fakeAudio.calls, contains('skipToPrevious'));

      listener.onTrayMenuItemClick(MenuItem(key: 'togglePlay'));
      expect(fakeAudio.calls, contains('togglePlay'));

      listener.onTrayMenuItemClick(MenuItem(key: 'skipToNext'));
      expect(fakeAudio.calls, contains('skipToNext'));

      // Unknown key does not crash
      expect(() => listener.onTrayMenuItemClick(MenuItem(key: 'unknown')), returnsNormally);
    });
  });
}
