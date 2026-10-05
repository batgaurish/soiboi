import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/single_instance.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;
  final windowManagerCalls = <String>[];

  setUpAll(() async {
    tempDir = await Directory.systemTemp.createTemp('single_instance_test_');
    appSupportDir = tempDir;
    await logger.init();

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
      const MethodChannel('window_manager'),
      (MethodCall call) async {
        windowManagerCalls.add(call.method);
        switch (call.method) {
          case 'isMinimized':
            return false;
          case 'getPosition':
            return {'x': 100.0, 'y': 100.0};
          case 'getSize':
            return {'width': 800.0, 'height': 600.0};
          default:
            return null;
        }
      },
    );
  });

  tearDownAll(() async {
    await SingleInstance.end();
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  group('SingleInstance', () {
    test('start binds port and writes lock and port files', () async {
      await SingleInstance.start();

      final lockFile = File('${tempDir.path}/soiboi.lock');
      final portFile = File('${tempDir.path}/soiboi.port');

      expect(lockFile.existsSync(), isTrue);
      expect(portFile.existsSync(), isTrue);

      final portContent = portFile.readAsStringSync().trim();
      final port = int.tryParse(portContent);
      expect(port, isNotNull);
      expect(port!, greaterThan(0));

      // Test socket connectivity to the bound server socket
      final socket = await Socket.connect(InternetAddress.loopbackIPv4, port);
      socket.write('particle_music_show_window');
      await socket.flush();
      await socket.close();
      await Future<void>.delayed(const Duration(milliseconds: 100));

      expect(windowManagerCalls, contains('show'));
      expect(windowManagerCalls, contains('focus'));

      await SingleInstance.end();
    });

    test('end completes gracefully when lock is released', () async {
      await expectLater(SingleInstance.end(), completes);
    });
  });
}
