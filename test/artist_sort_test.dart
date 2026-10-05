import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';

MyAudioMetadata track(String id, String album, String albumId) =>
    MyAudioMetadata(
      AudioMetadata(title: id, artist: 'Same Artist', album: album),
      id: id,
      path: '/tmp/$id.m4a',
      albumId: albumId,
    );

void main() {
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_sort_test');
  });

  tearDown(() {
    sourceType = .local;
    artistAlbumManager.albumMap.clear();
  });

  // Sort type 3 is Artist Ascending: one artist's songs go album by album,
  // oldest first. When streaming, albums are keyed by the server's id.
  test('a streaming artist sorts by album year, not album name', () {
    sourceType = .navidrome;
    artistAlbumManager.albumMap
      ..['id-new'] = Album('A Newer Album', id: 'id-new', year: 2020)
      ..['id-old'] = Album('Z Older Album', id: 'id-old', year: 1999);
    final songs = [
      track('n', 'A Newer Album', 'id-new'),
      track('o', 'Z Older Album', 'id-old'),
    ];

    sortSongList(3, songs);

    expect(songs.map((s) => s.id), ['o', 'n']);
  });

  test('a local artist sorts by album year, not album name', () {
    artistAlbumManager.albumMap
      ..['A Newer Album'] = Album('A Newer Album', year: 2020)
      ..['Z Older Album'] = Album('Z Older Album', year: 1999);
    final songs = [
      track('n', 'A Newer Album', ''),
      track('o', 'Z Older Album', ''),
    ];

    sortSongList(3, songs);

    expect(songs.map((s) => s.id), ['o', 'n']);
  });
}
