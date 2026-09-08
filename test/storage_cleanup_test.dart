import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/storage_cleanup.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

MyAudioMetadata _song(
  String id, {
  int playCount = 0,
  DateTime? lastPlayed,
  String? path,
}) {
  return MyAudioMetadata(
    AudioMetadata(title: id, artist: 'Artist'),
    id: id,
    path: path ?? '/tmp/$id.m4a',
    playCount: playCount,
    lastPlayed: lastPlayed,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_storage_test');
  });

  /// Sizes keyed by filename stem, so a test can say "b is the big one"
  /// without touching the disk.
  int Function(String) sizes(Map<String, int> bytes) =>
      (path) => bytes[path.split('/').last.split('.').first] ?? 0;

  test('largest sorts by file size, descending', () {
    final entries = storageEntries(
      songs: [_song('a'), _song('b'), _song('c')],
      sizeOf: sizes({'a': 100, 'b': 900, 'c': 500}),
    );
    expect(entries.map((e) => e.song.id), ['b', 'c', 'a']);
    expect(entries.first.sizeBytes, 900);
  });

  test('least played sorts by play count, breaking ties on size', () {
    final entries = storageEntries(
      songs: [
        _song('played', playCount: 9),
        _song('smallUnplayed'),
        _song('bigUnplayed'),
      ],
      sort: CleanupSort.leastPlayed,
      sizeOf: sizes({'played': 100, 'smallUnplayed': 10, 'bigUnplayed': 800}),
    );
    expect(entries.map((e) => e.song.id), [
      'bigUnplayed',
      'smallUnplayed',
      'played',
    ]);
  });

  test('oldest puts never-played first, then least recent', () {
    final entries = storageEntries(
      songs: [
        _song('recent', lastPlayed: DateTime(2025, 6)),
        _song('never'),
        _song('old', lastPlayed: DateTime(2020, 1)),
      ],
      sort: CleanupSort.oldest,
      sizeOf: sizes({'recent': 1, 'never': 1, 'old': 1}),
    );
    expect(entries.map((e) => e.song.id), ['never', 'old', 'recent']);
  });

  test('totalBytes adds up what would be reclaimed', () {
    final entries = storageEntries(
      songs: [_song('a'), _song('b')],
      sizeOf: sizes({'a': 1000, 'b': 24}),
    );
    expect(totalBytes(entries), 1024);
  });

  test('an unreadable file counts as zero rather than throwing', () {
    final entries = storageEntries(
      songs: [_song('gone', path: '/definitely/not/here.m4a')],
    );
    expect(entries.single.sizeBytes, 0);
  });

  test('sizes read as KB, MB and GB', () {
    expect(formatBytes(512), '512 B');
    expect(formatBytes(2048), '2 KB');
    expect(formatBytes(5 * 1024 * 1024), '5.0 MB');
    expect(formatBytes(3 * 1024 * 1024 * 1024), '3.00 GB');
  });

  test('deleting a track takes its acoustic and lyric sidecars with it',
      () async {
    final dir = Directory.systemTemp.createTempSync('soiboi_delete_test');
    addTearDown(() => dir.deleteSync(recursive: true));

    final audio = File('${dir.path}/track.m4a')..writeAsStringSync('audio');
    final acoustic = File('${dir.path}/track.m4a.soiboi-acoustic.json')
      ..writeAsStringSync('{}');
    final lyrics = File('${dir.path}/track.lrc')..writeAsStringSync('[00:00]');
    // A neighbour that must survive: deleting by stem rather than by exact
    // name would take this with it.
    final other = File('${dir.path}/track2.m4a')..writeAsStringSync('audio');

    final entry = storageEntries(songs: [_song('t', path: audio.path)]).single;
    final reclaimed = await deleteEntry(entry);

    expect(audio.existsSync(), isFalse);
    expect(acoustic.existsSync(), isFalse);
    expect(lyrics.existsSync(), isFalse);
    expect(other.existsSync(), isTrue);
    expect(reclaimed, greaterThan(0));
  });

  test('deleting an already-missing file reclaims nothing and does not throw',
      () async {
    final entry = storageEntries(
      songs: [_song('gone', path: '/definitely/not/here.m4a')],
    ).single;
    expect(await deleteEntry(entry), 0);
  });
}
