import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/system_ui_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final platformCalls = <MethodCall>[];

  setUp(() {
    platformCalls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (MethodCall call) async {
      platformCalls.add(call);
      return null;
    });
  });

  group('applySystemUiMode', () {
    test('applies mode and caches it to prevent duplicate calls', () {
      applySystemUiMode(mode: SystemUiMode.edgeToEdge);
      expect(platformCalls, isNotEmpty);
      final initialCount = platformCalls.length;

      // Same mode without forceApply does not call platform again
      applySystemUiMode(mode: SystemUiMode.edgeToEdge);
      expect(platformCalls.length, initialCount);

      // forceApply calls platform again even if mode is unchanged
      applySystemUiMode(mode: SystemUiMode.edgeToEdge, forceApply: true);
      expect(platformCalls.length, greaterThan(initialCount));
    });

    test('manual mode applies top overlay', () {
      platformCalls.clear();
      applySystemUiMode(mode: SystemUiMode.manual);
      expect(platformCalls, isNotEmpty);

      final call = platformCalls.last;
      expect(call.method, 'SystemChrome.setEnabledSystemUIOverlays');
      final overlays = call.arguments as List?;
      expect(overlays, contains('SystemUiOverlay.top'));
    });

    test('null mode does not crash', () {
      platformCalls.clear();
      applySystemUiMode(mode: null);
      // Mode null with no prior mode shouldn't invoke setEnabledSystemUIMode
      expect(platformCalls, isEmpty);
    });
  });
}
