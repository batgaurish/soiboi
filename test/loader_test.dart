import 'dart:convert';
import 'dart:io';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/loader.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_loader_test_');
    appSupportDir = tempDir;
  });

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  test('Loader.init records versionNumber and identifies firstLaunch on virgin run', () async {
    final vFile = File('${tempDir.path}/version.json');
    if (vFile.existsSync()) {
      vFile.deleteSync();
    }

    await Loader.init();

    expect(vFile.existsSync(), isTrue);
    expect(jsonDecode(vFile.readAsStringSync()), versionNumber);
    expect(firstLaunch, isTrue);
  });

  test('Loader stateNotifier and busy status transition during operations', () {
    expect(Loader.busy, isFalse);
    final initial = Loader.stateNotifier.value;
    expect(initial, isA<int>());
  });
}
