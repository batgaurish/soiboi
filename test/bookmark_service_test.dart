import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/bookmark_service.dart';
import 'package:soiboi/base/services/logger.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('com.batgaurish.soiboi.bookmark_manager');
  late Directory tempDir;

  setUpAll(() async {
    tempDir = Directory.systemTemp.createTempSync('soiboi_bookmark_test_');
    appSupportDir = tempDir;
    await logger.init();
  });

  setUp(() async {
    final file = File('${tempDir.path}/directory_inventory.txt');
    if (file.existsSync()) file.deleteSync();

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (MethodCall methodCall) async {
        if (methodCall.method == 'getBookmarkFromPath') {
          final path = methodCall.arguments['path'] as String;
          return 'bookmark_for_$path';
        } else if (methodCall.method == 'activateAndGetPath') {
          final bookmark = methodCall.arguments['bookmark'] as String;
          return '/active/path/$bookmark';
        }
        return null;
      },
    );

    await BookmarkService.init();
  });

  tearDownAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      null,
    );
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  test('init creates empty inventory when file is missing', () {
    expect(BookmarkService.file.existsSync(), isFalse);
  });

  test('saveDirectoryAndActive records bookmark to file and activates it', () async {
    final success = await BookmarkService.saveDirectoryAndActive('folder_1', '/user/music');
    expect(success, isTrue);

    expect(BookmarkService.file.existsSync(), isTrue);
    final map = jsonDecode(BookmarkService.file.readAsStringSync()) as Map<String, dynamic>;
    expect(map['folder_1'], 'bookmark_for_/user/music');

    // getUrlById retrieves the activated path
    final activeUrl = await BookmarkService.getUrlById('folder_1');
    expect(activeUrl, '/active/path/bookmark_for_/user/music');
  });

  test('active invokes channel to activate a path', () async {
    final success = await BookmarkService.active('/user/music/ambient');
    expect(success, isTrue);
  });

  test('getUrlById returns null when id is not in inventory', () async {
    final activeUrl = await BookmarkService.getUrlById('unknown_id');
    expect(activeUrl, isNull);
  });

  test('channel errors return false or null without throwing', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      (call) async => throw PlatformException(code: 'ERR', message: 'Failed'),
    );

    final saveSuccess = await BookmarkService.saveDirectoryAndActive('err_id', '/path');
    expect(saveSuccess, isFalse);

    final activeSuccess = await BookmarkService.active('/path');
    expect(activeSuccess, isFalse);

    final url = await BookmarkService.getUrlById('folder_1');
    expect(url, isNull);
  });
}
