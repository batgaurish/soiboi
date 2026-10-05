import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/font_manager.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_font_manager_test_');
    appSupportDir = tempDir;
  });

  setUp(() {
    final fontsDir = Directory('${tempDir.path}/fonts');
    if (fontsDir.existsSync()) {
      fontsDir.deleteSync(recursive: true);
    }
    importedFonts.clear();
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  test('constructor initializes font_map.json and empty map when no existing file', () {
    final manager = FontManager();
    final mapFile = File('${tempDir.path}/fonts/font_map.json');

    expect(manager, isNotNull);
    expect(mapFile.existsSync(), isTrue);
    final content = mapFile.readAsStringSync();
    expect(jsonDecode(content), isEmpty);
  });

  test('constructor loads pre-existing font_map.json', () {
    final fontsDir = Directory('${tempDir.path}/fonts');
    fontsDir.createSync(recursive: true);
    final mapFile = File('${fontsDir.path}/font_map.json');
    mapFile.writeAsStringSync(jsonEncode({
      'CustomFont': ['custom_regular.ttf', 'custom_bold.ttf'],
    }));

    final manager = FontManager();
    expect(manager, isNotNull);
    expect(mapFile.existsSync(), isTrue);
  });

  test('addFonts copies font files and updates font_map.json', () async {
    final manager = FontManager();

    // Create dummy font files
    final dummySrcDir = Directory('${tempDir.path}/source_fonts')..createSync();
    final font1 = File('${dummySrcDir.path}/font1.ttf')..writeAsStringSync('dummy font 1 bytes');
    final font2 = File('${dummySrcDir.path}/font2.otf')..writeAsStringSync('dummy font 2 bytes');

    await manager.addFonts('TestFont', [font1.path, font2.path]);

    final copied1 = File('${tempDir.path}/fonts/font1.ttf');
    final copied2 = File('${tempDir.path}/fonts/font2.otf');
    expect(copied1.existsSync(), isTrue);
    expect(copied2.existsSync(), isTrue);
    expect(copied1.readAsStringSync(), 'dummy font 1 bytes');
    expect(copied2.readAsStringSync(), 'dummy font 2 bytes');

    final mapFile = File('${tempDir.path}/fonts/font_map.json');
    final data = jsonDecode(mapFile.readAsStringSync()) as Map<String, dynamic>;
    expect(data['TestFont'], ['font1.ttf', 'font2.otf']);
  });

  test('deleteFonts removes files from disk, map, and importedFonts', () async {
    final manager = FontManager();

    final dummySrcDir = Directory('${tempDir.path}/source_fonts')..createSync(recursive: true);
    final fontFile = File('${dummySrcDir.path}/remove_me.ttf')..writeAsStringSync('font data');

    await manager.addFonts('RemoveMeFont', [fontFile.path]);
    importedFonts.add('RemoveMeFont');

    final copied = File('${tempDir.path}/fonts/remove_me.ttf');
    expect(copied.existsSync(), isTrue);
    expect(importedFonts.contains('RemoveMeFont'), isTrue);

    await manager.deleteFonts('RemoveMeFont');

    expect(copied.existsSync(), isFalse);
    expect(importedFonts.contains('RemoveMeFont'), isFalse);

    final mapFile = File('${tempDir.path}/fonts/font_map.json');
    final data = jsonDecode(mapFile.readAsStringSync()) as Map<String, dynamic>;
    expect(data.containsKey('RemoveMeFont'), isFalse);
  });

  test('deleteFonts on non-existent font returns safely', () async {
    final manager = FontManager();
    await manager.deleteFonts('DoesNotExist');

    final mapFile = File('${tempDir.path}/fonts/font_map.json');
    expect(mapFile.existsSync(), isTrue);
  });

  test('loadFonts skips missing font files without throwing', () async {
    final fontsDir = Directory('${tempDir.path}/fonts')..createSync(recursive: true);
    final mapFile = File('${fontsDir.path}/font_map.json');
    mapFile.writeAsStringSync(jsonEncode({
      'MissingFont': ['non_existent.ttf'],
    }));

    final manager = FontManager();
    await manager.loadFonts();
    expect(importedFonts.contains('MissingFont'), isTrue);
  });
}
