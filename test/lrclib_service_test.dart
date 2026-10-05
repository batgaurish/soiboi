import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/lrclib_service.dart';

void main() {
  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_lrclib_test_');
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  group('LrclibResult', () {
    test('isEmpty is true when both synced and plain are null or blank', () {
      expect(const LrclibResult().isEmpty, isTrue);
      expect(const LrclibResult(synced: '', plain: '  ').isEmpty, isTrue);
      expect(const LrclibResult(synced: '[00:01.00] hello').isEmpty, isFalse);
      expect(const LrclibResult(plain: 'hello').isEmpty, isFalse);
    });

    test('best prefers synced lyrics over plain fallback', () {
      const both = LrclibResult(
        synced: '[00:01.00] Synced line',
        plain: 'Plain line',
      );
      expect(both.best, '[00:01.00] Synced line');

      const plainOnly = LrclibResult(synced: '  ', plain: 'Plain line');
      expect(plainOnly.best, 'Plain line');

      const none = LrclibResult(synced: '', plain: null);
      expect(none.best, isNull);
    });
  });

  group('fetchFromLrclib', () {
    test('returns null immediately when title or artist is empty', () async {
      expect(await fetchFromLrclib(title: null, artist: 'Artist'), isNull);
      expect(await fetchFromLrclib(title: '  ', artist: 'Artist'), isNull);
      expect(await fetchFromLrclib(title: 'Song', artist: null), isNull);
      expect(await fetchFromLrclib(title: 'Song', artist: ''), isNull);
    });
  });

  group('cacheSidecar', () {
    test('writes .lrc file beside audio file', () async {
      final audioFile = File('${tempDir.path}/track1.mp3')..writeAsStringSync('audio');
      final expectedLrc = File('${tempDir.path}/track1.lrc');

      await cacheSidecar(audioFile.path, '[00:05.00] Lyrics');

      expect(expectedLrc.existsSync(), isTrue);
      expect(expectedLrc.readAsStringSync(), '[00:05.00] Lyrics');
    });

    test('does not overwrite existing .lrc file', () async {
      final audioFile = File('${tempDir.path}/track2.flac')..writeAsStringSync('audio');
      final existingLrc = File('${tempDir.path}/track2.lrc')..writeAsStringSync('original lyrics');

      await cacheSidecar(audioFile.path, 'new lyrics');

      expect(existingLrc.readAsStringSync(), 'original lyrics');
    });

    test('handles path without extension safely', () async {
      await cacheSidecar('${tempDir.path}/no_ext', 'lyrics');
      expect(File('${tempDir.path}/no_ext.lrc').existsSync(), isFalse);
    });
  });
}
