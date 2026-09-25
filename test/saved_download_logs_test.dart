import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/layer/saved_download_logs.dart';

void main() {
  late Directory dir;

  setUp(() => dir = Directory.systemTemp.createTempSync('soiboi_logs'));
  tearDown(() => dir.deleteSync(recursive: true));

  void write(String name, String text) =>
      File('${dir.path}/$name').writeAsStringSync(text);

  test('newest first, named for the song, else the link', () {
    write(
      '1790324893578-0.log',
      '[INFO     13:58:30] Processing "https://music.apple.com/in/album/x/1"\n'
          '[INFO     13:58:37] [Track   1/1  ] Downloading "Iktara"\n',
    );
    write(
      '1790335011001-7.log',
      '[INFO     16:46:54] [URL   1/1  ] Processing '
          '"https://music.apple.com/us/album/cradles/1869065785"\n'
          '[ERROR    16:46:56] [Track   1/1  ] Error downloading '
          '"Unknown Title"\n',
    );
    write('notes.txt', 'not a log');

    final logs = listSavedLogs(dir.path);
    expect(logs.map((l) => l.title), [
      'https://music.apple.com/us/album/cradles/1869065785',
      'Iktara',
    ]);
    expect(
      logs.last.started,
      DateTime.fromMillisecondsSinceEpoch(1790324893578),
    );
  });

  test('no folder yet is no logs, not an error', () {
    expect(listSavedLogs('${dir.path}/missing'), isEmpty);
  });
}
