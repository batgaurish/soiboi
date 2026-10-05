import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/folder.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/services/acoustic_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_acoustic_');
    appSupportDir = tempDir;
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  group('AcousticSummary', () {
    test('default constructor sets initial zeros and false values', () {
      const summary = AcousticSummary();
      expect(summary.analysed, 0);
      expect(summary.skipped, 0);
      expect(summary.pending, 0);
      expect(summary.unavailable, isFalse);
      expect(summary.error, isNull);
    });

    test('operator + combines metrics and merges error/unavailable flags', () {
      const a = AcousticSummary(
        analysed: 5,
        skipped: 3,
        pending: 1,
        unavailable: false,
      );
      const b = AcousticSummary(
        analysed: 2,
        skipped: 4,
        pending: 0,
        unavailable: true,
        error: 'Failed to analyze',
      );

      final combined = a + b;
      expect(combined.analysed, 7);
      expect(combined.skipped, 7);
      expect(combined.pending, 1);
      expect(combined.unavailable, isTrue);
      expect(combined.error, 'Failed to analyze');
    });
  });

  group('AcousticProgress', () {
    test('instantiates with folder, index, count and status', () {
      const prog = AcousticProgress(
        folder: '/music/rock',
        folderIndex: 1,
        folderCount: 3,
        status: 'Scanning',
      );
      expect(prog.folder, '/music/rock');
      expect(prog.folderIndex, 1);
      expect(prog.folderCount, 3);
      expect(prog.status, 'Scanning');
    });
  });

  group('analyseLibrary', () {
    test('returns empty AcousticSummary immediately when no local folders exist', () async {
      library.folderList.clear();

      // Only webdav folder
      final webdavFolder = Folder('dav_id', 'https://dav.example.com', isWebdav: true);
      library.folderList.add(webdavFolder);

      final summary = await analyseLibrary();
      expect(summary.analysed, 0);
      expect(summary.skipped, 0);
      expect(summary.error, isNull);
    });
  });
}
