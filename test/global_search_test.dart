import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/smart_playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/global_search_service.dart';

MyAudioMetadata _song(String title, {String artist = 'Artist', String? album}) {
  return MyAudioMetadata(
    AudioMetadata(title: title, artist: artist, album: album ?? 'Album'),
    id: '$artist-$title',
    path: '/tmp/$title.m4a',
  );
}

/// Searching a fixture rather than the app's global library, which cannot be
/// constructed in a unit test.
GlobalSearchResults _search(
  String query, {
  List<MyAudioMetadata> songs = const [],
  List<SmartPlaylist> smart = const [],
  int limit = 12,
}) => globalSearch(
  query,
  limit: limit,
  songs: songs,
  albums: const [],
  artists: const [],
  savedPlaylists: const [],
  smartPlaylistList: smart,
  moodPlaylistList: const [],
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_search_test');
  });

  test('an empty query returns nothing rather than everything', () {
    // The screen shows a hint for an empty box; returning the whole library
    // here would make every caller remember to check first.
    final results = _search('   ', songs: [_song('Anything')]);
    expect(results.isEmpty, isTrue);
    expect(results.total, 0);
  });

  test('a song matches on title, artist or album', () {
    final songs = [
      _song('One More Time', artist: 'Daft Punk', album: 'Discovery'),
      _song('Bad', artist: 'Michael Jackson', album: 'Bad'),
    ];
    expect(_search('one more', songs: songs).songs, hasLength(1));
    expect(_search('daft', songs: songs).songs.single.title, 'One More Time');
    expect(_search('discovery', songs: songs).songs, hasLength(1));
  });

  test('finds a smart playlist by name, with its live track count', () {
    final songs = [for (var i = 0; i < 3; i++) _song('t$i')];
    const playlist = SmartPlaylist(name: 'Late Night Drive');
    final results = _search('late night', songs: songs, smart: [playlist]);

    expect(results.playlists, hasLength(1));
    expect(results.playlists.single.kind, PlaylistKind.smart);
    // Counted by evaluating the rules against the fixture, not stored: a
    // smart playlist's length is an answer, not a field.
    expect(results.playlists.single.trackCount, 3);
  });

  group('name matching', () {
    test('ignores case and punctuation', () {
      expect(matchesName('Sigur Rós', 'sigur'), isTrue);
      expect(matchesName('Do I Wanna Know?', 'do i wanna know'), isTrue);
      expect(matchesName('AC/DC', 'acdc'), isTrue);
    });

    test('treats & and "and" as the same word', () {
      // The same normalisation the catalog matcher uses, so a search agrees
      // with what the app calls "already owned".
      expect(matchesName('Simon & Garfunkel', 'simon and garfunkel'), isTrue);
      expect(matchesName('Simon and Garfunkel', 'simon & garfunkel'), isTrue);
    });

    test('an empty or punctuation-only query matches nothing', () {
      // Otherwise "!!!" normalises to "" and every name contains "".
      expect(matchesName('Anything', ''), isFalse);
      expect(matchesName('Anything', '!!!'), isFalse);
    });

    test('matches on a substring, not only a prefix', () {
      expect(matchesName('Random Access Memories', 'access'), isTrue);
    });
  });

  test('each section is capped so a broad query is not a wall', () {
    // A one-letter query matches most of a library; a screen listing four
    // thousand rows under a heading is not a result.
    final songs = [for (var i = 0; i < 50; i++) _song('Song $i')];
    expect(_search('song', songs: songs, limit: 5).songs, hasLength(5));
  });

  test('a query matching nothing is empty, not an error', () {
    final results = _search('zzzznothing', songs: [_song('Real')]);
    expect(results.isEmpty, isTrue);
    // The query is kept so the screen can offer to look it up in the catalog.
    expect(results.query, 'zzzznothing');
  });

  test('the query is trimmed before searching', () {
    expect(_search('  bad  ', songs: [_song('Bad')]).songs, hasLength(1));
  });
}
