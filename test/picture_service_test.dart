import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/picture_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_pic_test_');
    appSupportDir = tempDir;
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  group('MyPicture', () {
    test('empty id sets loaded true, exist false, and color grey', () {
      final pic = MyPicture('');
      expect(pic.isExist, isFalse);
      expect(pic.isLoaded, isTrue);
      expect(pic.color?.toARGB32(), Colors.grey.toARGB32());
    });

    test('non-empty id initializes path with pictures directory and existence state', () {
      final pic = MyPicture('test_picture_id');
      expect(pic.path, contains('pictures'));
      expect(pic.isExist, isFalse);
      expect(pic.isLoaded, isFalse);
    });

    test('from factory registers instance in globalPictureList', () {
      globalPictureList.clear();
      final pic = MyPicture.from('from_id');
      expect(globalPictureList.contains(pic), isTrue);
    });

    test('reset clears loaded, exist, color and lowerLuminance', () {
      final pic = MyPicture('reset_id');
      pic.isLoaded = true;
      pic.isExist = true;
      pic.color = Colors.blue;
      pic.lowerLuminance = Colors.black;

      pic.reset();

      expect(pic.isLoaded, isFalse);
      expect(pic.isExist, isFalse);
      expect(pic.color, isNull);
      expect(pic.lowerLuminance, isNull);
    });
  });

  group('computeColor', () {
    test('returns existing color immediately if already computed', () async {
      final pic = MyPicture('has_color')..color = Colors.red;
      final c = await computeColor(pic);
      expect(c.toARGB32(), Colors.red.toARGB32());
    });

    test('returns Colors.grey when picture is null or not found', () async {
      final c1 = await computeColor(null);
      expect(c1.toARGB32(), Colors.grey.toARGB32());

      final pic = MyPicture('');
      final c2 = await computeColor(pic);
      expect(c2.toARGB32(), Colors.grey.toARGB32());
    });
  });
}
