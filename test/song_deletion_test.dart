import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/song_deletion.dart';

late Directory dir;

MyAudioMetadata songAt(String path) => MyAudioMetadata(
  AudioMetadata(title: 'Song'),
  id: path,
  path: path,
);

File touch(String name) => File('${dir.path}/$name')..writeAsStringSync('x');

void main() {
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_delete_app');
  });

  setUp(() => dir = Directory.systemTemp.createTempSync('soiboi_delete'));
  tearDown(() => dir.deleteSync(recursive: true));

  test('deletes the file and its lyrics and mood sidecars', () async {
    final audio = touch('01 Song.m4a');
    final lrc = touch('01 Song.lrc');
    final mood = touch('01 Song.m4a.soiboi-acoustic.json');
    final neighbour = touch('02 Other.m4a');

    final result = await deleteSongFiles([songAt(audio.path)]);

    expect(result.deleted, hasLength(1));
    expect(result.failed, isEmpty);
    expect(audio.existsSync(), isFalse);
    expect(lrc.existsSync(), isFalse);
    expect(mood.existsSync(), isFalse);
    expect(neighbour.existsSync(), isTrue);
  });

  test('a file that is already gone counts as deleted', () async {
    final result = await deleteSongFiles([songAt('${dir.path}/gone.m4a')]);
    expect(result.deleted, hasLength(1));
  });

  test('songs that are not local files are refused, not deleted', () async {
    final remote = songAt('https://server/stream/1');
    expect(canDeleteFromDevice(remote), isFalse);
    final result = await deleteSongFiles([remote]);
    expect(result.deleted, isEmpty);
    expect(result.failed.keys.single, remote);
  });

  test(
    'a permission failure asks once to retry and reports the song',
    () async {
      // A read-only folder: deleting a file in it fails with EACCES.
      final locked = Directory('${dir.path}/locked')..createSync();
      final a = File('${locked.path}/a.m4a')..writeAsStringSync('x');
      final b = File('${locked.path}/b.m4a')..writeAsStringSync('x');
      await Process.run('chmod', ['555', locked.path]);
      addTearDown(() => Process.runSync('chmod', ['755', locked.path]));

      var asked = 0;
      final result = await deleteSongFiles(
        [songAt(a.path), songAt(b.path)],
        retry: () async {
          asked++;
          return false;
        },
      );

      expect(asked, 1);
      expect(result.deleted, isEmpty);
      expect(result.failed.values, everyElement('No permission to delete it'));
      expect(a.existsSync() && b.existsSync(), isTrue);
    },
    skip: Platform.isLinux || Platform.isMacOS ? false : 'needs chmod',
  );
}
