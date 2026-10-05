import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/picture_service.dart';
import 'package:soiboi/base/widgets/collection_list.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('collection_item_test_');
    appSupportDir = tempDir;
  });

  tearDownAll(() {
    try {
      tempDir.deleteSync(recursive: true);
    } catch (_) {}
  });

  group('CollectionItem', () {
    test('instantiates with required and optional fields', () {
      bool tapped = false;
      final picture = MyPicture('pic1');
      final item = CollectionItem(
        picture: picture,
        text: 'Favorites',
        subCount: 42,
        onTap: () => tapped = true,
      );

      expect(item.picture, picture);
      expect(item.text, 'Favorites');
      expect(item.subCount, 42);
      expect(item.onMenu, isNull);

      item.onTap();
      expect(tapped, isTrue);
    });

    test('supports null picture and null subCount', () {
      final item = CollectionItem(
        picture: null,
        text: 'No Picture',
        onTap: () {},
      );

      expect(item.picture, isNull);
      expect(item.subCount, isNull);
      expect(item.text, 'No Picture');
    });

    test('copyWith updates specified fields and preserves existing fields', () {
      final original = CollectionItem(
        picture: MyPicture('pic1'),
        text: 'Original',
        subCount: 10,
        onTap: () {},
      );

      final updated = original.copyWith(text: 'Updated', subCount: 20);

      expect(updated.text, 'Updated');
      expect(updated.subCount, 20);
      expect(updated.picture, original.picture);
      expect(updated.onTap, original.onTap);
    });
  });
}
