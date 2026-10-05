import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/exit.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('exitApp', () {
    test('can be referenced without errors', () {
      expect(exitApp, isNotNull);
    });

    test('terminates process cleanly when invoked in subprocess', () async {
      final result = await Process.run(
        'flutter',
        ['test', 'test/fixtures/exit_test_runner.dart'],
      );
      final combined = '${result.stdout}\n${result.stderr}';
      expect(
        combined,
        contains('Shell subprocess ended cleanly. Did main() call exit()?'),
      );
    });
  });
}
