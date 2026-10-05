import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/taskbar_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('setupTaskbar is Windows-only', () {
    if (Platform.isWindows) return;
    expect(
      () => setupTaskbar(
        isPlaying: false,
        onPrevious: () {},
        onTogglePlay: () {},
        onNext: () {},
      ),
      throwsA(isA<AssertionError>()),
    );
  });
}
