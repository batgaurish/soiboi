import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/exit.dart';
import 'package:soiboi/base/services/logger.dart';

void main() {
  test('calls exitApp', () async {
    final temp = Directory.systemTemp.createTempSync('exit_test_');
    appSupportDir = temp;
    await logger.init();
    exitApp();
  });
}
