import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/library_match_service.dart';

MyAudioMetadata song({required String id, String? title, String? artist}) {
  return MyAudioMetadata(
    AudioMetadata(title: title ?? id, artist: artist),
    id: id,
    path: '/tmp/$id.m4a',
  );
}

Album albumWith(String name, String artist, int tracks) {
  final album = Album(name);
  for (var i = 0; i < tracks; i++) {
    album.songList.add(
      song(id: '$artist-$name-$i', title: 'Track $i', artist: artist),
    );
  }
  // sort() is what fills artist2SongList, which the scoped match reads.
  album.sort();
  artistAlbumManager.albumList.add(album);
  return album;
}

void main() {
  // MyAudioMetadata's constructor derives a cover-art path from this.
  appSupportDir = Directory.systemTemp.createTempSync('soiboi_match_test');

  setUp(() {
    artistAlbumManager.albumList = [];
    artistAlbumManager.artistList = [];
  });

  test('matchAlbum by name only keeps the old behaviour', () {
    final seger = albumWith('Greatest Hits', 'Bob Seger', 10);

    expect(matchAlbum('Greatest Hits'), seger);
    expect(matchAlbum('GREATEST HITS!'), seger);
    expect(matchAlbum('No Such Album'), isNull);
  });

  test('matchAlbum scoped by artist ignores same-titled other artists', () {
    final seger = albumWith('Greatest Hits', 'Bob Seger', 10);
    final queen = albumWith('Greatest Hits', 'Queen', 17);

    expect(matchAlbum('Greatest Hits', artist: 'Queen'), queen);
    expect(matchAlbum('Greatest Hits', artist: 'Bob Seger'), seger);
    expect(matchAlbum('Greatest Hits', artist: 'Eagles'), isNull);
    // Unscoped calls keep the old first-match behaviour.
    expect(matchAlbum('Greatest Hits'), seger);
  });

  test('matchAlbum artist scoping is case and punctuation insensitive', () {
    final daft = albumWith('Discovery', 'Daft Punk', 14);

    expect(matchAlbum('Discovery', artist: 'daft punk'), daft);
    expect(matchAlbum('Discovery', artist: 'Daft  Punk!'), daft);
    expect(matchAlbum('Discovery', artist: 'Daft Punk & Friends'), isNull);
  });

  test('matchAlbum returns null for unknown albums regardless of scope', () {
    albumWith('Discovery', 'Daft Punk', 14);

    expect(matchAlbum('No Such Album'), isNull);
    expect(matchAlbum('No Such Album', artist: 'Daft Punk'), isNull);
  });

  test('matchSong prefers the matched album and normalises punctuation', () {
    final album = Album('Discovery');
    final owned = song(
      id: 'hbfs',
      title: 'Harder, Better, Faster, Stronger',
      artist: 'Daft Punk',
    );
    album.songList.add(owned);

    expect(matchSong('harder better faster stronger', album: album), owned);
    expect(matchSong('One More Time', album: album), isNull);
  });
}
