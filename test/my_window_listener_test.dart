import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/my_window_listener.dart';
import 'package:soiboi/mini_view/mini_view.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;
  final windowManagerCalls = <String>[];

  setUpAll(() async {
    tempDir = await Directory.systemTemp.createTemp('window_listener_test_');
    appSupportDir = tempDir;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('window_manager'),
      (MethodCall call) async {
        windowManagerCalls.add(call.method);
        switch (call.method) {
          case 'isMinimized':
            return false;
          case 'getBounds':
            return {'x': 150.0, 'y': 250.0, 'width': 1200.0, 'height': 800.0};
          case 'getPosition':
            return {'x': 150.0, 'y': 250.0};
          case 'getSize':
            return {'width': 1200.0, 'height': 800.0};
          case 'hide':
          case 'setSize':
          case 'show':
          case 'focus':
            return null;
          default:
            return null;
        }
      },
    );
  });

  tearDownAll(() async {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  setUp(() {
    windowManagerCalls.clear();
    exitOnCloseNotifier.value = false;
  });

  group('MyWindowListener', () {
    test('serializes and deserializes window config', () {
      final listener = MyWindowListener();

      mainPosition = const Offset(120, 240);
      mainSize = const Size(1000, 700);
      miniPosition = const Offset(50, 60);
      miniSize = const Size(320, 320);
      mainMaximized = true;
      miniViewMainHeight = 350.0;
      miniViewDisplayBottom = true;
      miniViewDisplayLyricsNotifier.value = false;

      final json = listener.toJson();
      expect(json['mainMaximized'], isTrue);
      expect(json['mainPosition']['dx'], 120.0);
      expect(json['mainPosition']['dy'], 240.0);
      expect(json['mainSize']['width'], 1000.0);
      expect(json['miniViewMainHeight'], 350.0);
      expect(json['miniViewDisplayLyrics'], isFalse);

      listener.fromJson({
        'mainMaximized': false,
        'mainPosition': {'dx': 300.0, 'dy': 400.0},
        'miniPosition': {'dx': 80.0, 'dy': 90.0},
        'mainSize': {'width': 900.0, 'height': 600.0},
        'miniSize': {'width': 300.0, 'height': 300.0},
        'miniViewMainHeight': 300.0,
        'miniViewDisplayBottom': false,
        'miniViewDisplayLyrics': true,
      });

      expect(mainMaximized, isFalse);
      expect(mainPosition, const Offset(300, 400));
      expect(miniPosition, const Offset(80, 90));
      expect(mainSize, const Size(900, 600));
      expect(miniSize, const Size(300, 300));
      expect(miniViewMainHeight, 300.0);
      expect(miniViewDisplayLyricsNotifier.value, isTrue);
    });

    test('persists config via saveConfig with debounce', () async {
      final listener = MyWindowListener();
      mainPosition = const Offset(200, 300);
      mainSize = const Size(800, 600);

      listener.saveConfig();
      // Debounce is 250ms
      await Future<void>.delayed(const Duration(milliseconds: 300));

      final file = File('${tempDir.path}/window_config.json');
      expect(file.existsSync(), isTrue);
      expect(file.readAsStringSync(), contains('"mainMaximized"'));
    });

    test('handles onWindowMaximize and onWindowUnmaximize', () {
      final listener = MyWindowListener();

      listener.onWindowMaximize();
      expect(isMaximizedNotifier.value, isTrue);
      expect(mainMaximized, isTrue);

      listener.onWindowUnmaximize();
      expect(isMaximizedNotifier.value, isFalse);
      expect(mainMaximized, isFalse);
    });

    test('onWindowClose hides window when exitOnClose is false', () {
      final listener = MyWindowListener();
      exitOnCloseNotifier.value = false;

      listener.onWindowClose();

      expect(windowIsClosed, isTrue);
      expect(windowManagerCalls, contains('hide'));
    });

    test('onWindowMoved updates position from windowManager', () async {
      final listener = MyWindowListener();
      viewModeNotifier.value = ViewMode.normal;

      listener.onWindowMoved();
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(windowManagerCalls, contains('getBounds'));
      expect(mainPosition, const Offset(150.0, 250.0));
    });

    test('onWindowResized updates size from windowManager', () async {
      final listener = MyWindowListener();
      viewModeNotifier.value = ViewMode.normal;

      listener.onWindowResized();
      await Future<void>.delayed(const Duration(milliseconds: 50));

      expect(windowManagerCalls, contains('getBounds'));
      expect(mainSize, const Size(1200.0, 800.0));
    });
  });
}
