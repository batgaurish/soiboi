import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';

void main() {
  test('the version the app reports is the one it is built as', () {
    // The update check compares versionNumber with the latest release: left
    // behind, it offers people the release they are already running.
    final pubspec = File('pubspec.yaml').readAsStringSync();
    final built = RegExp(
      r'^version:\s*([0-9.]+)',
      multiLine: true,
    ).firstMatch(pubspec)!.group(1);
    expect(versionNumber, built);
  });
}
