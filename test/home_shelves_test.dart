import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/home_shelves.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

class _FakeAudioHandler implements MyAudioHandler {
  @override
  int currentIndex = -1;

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

MyAudioMetadata makeSong(
  String id, {
  String? title,
  int? year,
  DateTime? modified,
  int playCount = 0,
}) {
  return MyAudioMetadata(
    AudioMetadata(title: title ?? id, year: year),
    id: id,
    path: '/tmp/$id.mp3',
    modified: modified,
    playCount: playCount,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late _FakeAudioHandler fakeHandler;

  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_shelves_test_');
  });

  setUp(() {
    fakeHandler = _FakeAudioHandler();
    audioHandler = fakeHandler;
    playQueue.clear();
    library.songList.clear();
    artistAlbumManager.artistList.clear();
    artistAlbumManager.albumList.clear();
    playlistManager.playlists.clear();
    playlistManager.playlistMap.clear();
  });

  tearDown(() {
    playQueue.clear();
    library.songList.clear();
    artistAlbumManager.artistList.clear();
    artistAlbumManager.albumList.clear();
    playlistManager.playlists.clear();
    playlistManager.playlistMap.clear();
  });

  group('playNextSongs', () {
    test('returns empty when currentIndex is negative or at/past end of queue', () {
      playQueue.addAll([makeSong('1'), makeSong('2')]);

      fakeHandler.currentIndex = -1;
      expect(playNextSongs(), isEmpty);

      fakeHandler.currentIndex = 1; // last element
      expect(playNextSongs(), isEmpty);

      fakeHandler.currentIndex = 5;
      expect(playNextSongs(), isEmpty);
    });

    test('returns upcoming songs clamped to shelfLimit (12)', () {
      final songs = List.generate(20, (i) => makeSong('song_$i'));
      playQueue.addAll(songs);

      fakeHandler.currentIndex = 2;
      final next = playNextSongs();

      expect(next.length, shelfLimit);
      expect(next.first.id, 'song_3');
      expect(next.last.id, 'song_14');
    });

    test('returns all remaining songs if fewer than shelfLimit remain', () {
      final songs = List.generate(5, (i) => makeSong('song_$i'));
      playQueue.addAll(songs);

      fakeHandler.currentIndex = 2;
      final next = playNextSongs();

      expect(next.length, 2);
      expect(next[0].id, 'song_3');
      expect(next[1].id, 'song_4');
    });
  });

  group('recentlyAddedSongs', () {
    test('filters un-modified songs and sorts descending by modified', () {
      final t1 = DateTime(2025, 1, 1);
      final t2 = DateTime(2025, 2, 1);
      final t3 = DateTime(2025, 3, 1);

      final s1 = makeSong('1', modified: t1);
      final s2 = makeSong('2', modified: t3);
      final s3 = makeSong('3', modified: t2);
      final sNoDate = makeSong('4', modified: null);

      library.songList.addAll([s1, s2, s3, sNoDate]);

      final result = recentlyAddedSongs();
      expect(result.length, 3);
      expect(result.map((s) => s.id).toList(), ['2', '3', '1']);
    });

    test('limits output to shelfLimit', () {
      final songs = List.generate(
        15,
        (i) => makeSong('$i', modified: DateTime(2025, 1, i + 1)),
      );
      library.songList.addAll(songs);

      final result = recentlyAddedSongs();
      expect(result.length, shelfLimit);
      expect(result.first.id, '14'); // most recent
    });
  });

  group('recentlyAddedAlbums', () {
    test('ranks by newest release year first, then newest file arrival', () {
      final tOld = DateTime(2025, 1, 1);
      final tNew = DateTime(2025, 5, 1);

      // Album A: 2020 release, arrived recently
      final albumA = Album('Album 2020', id: 'a');
      albumA.songList.add(makeSong('sA', year: 2020, modified: tNew));

      // Album B: 2024 release, arrived earlier
      final albumB = Album('Album 2024', id: 'b');
      albumB.songList.add(makeSong('sB', year: 2024, modified: tOld));

      // Album C: 2024 release, arrived recently (ties B on year, wins on added)
      final albumC = Album('Album 2024 New', id: 'c');
      albumC.songList.add(makeSong('sC', year: 2024, modified: tNew));

      // Album Empty: no songs, should be omitted
      final albumEmpty = Album('Empty', id: 'empty');

      artistAlbumManager.albumList.addAll([albumA, albumB, albumC, albumEmpty]);

      final result = recentlyAddedAlbums();
      expect(result.length, 3);
      expect(result.map((a) => a.id).toList(), ['c', 'b', 'a']);
    });
  });

  group('topArtists and topAlbums', () {
    test('topArtists excludes zero-play artists and sorts descending by total plays', () {
      final artistA = Artist('Artist A', id: 'artA');
      artistA.songList.addAll([makeSong('1', playCount: 5), makeSong('2', playCount: 10)]); // 15 plays

      final artistB = Artist('Artist B', id: 'artB');
      artistB.songList.addAll([makeSong('3', playCount: 20)]); // 20 plays

      final artistZero = Artist('Artist Zero', id: 'artZero');
      artistZero.songList.addAll([makeSong('4', playCount: 0)]);

      artistAlbumManager.artistList.addAll([artistA, artistB, artistZero]);

      final top = topArtists();
      expect(top.length, 2);
      expect(top[0].id, 'artB');
      expect(top[1].id, 'artA');
    });

    test('topAlbums excludes zero-play albums and sorts descending by total plays', () {
      final albumA = Album('Album A', id: 'albA');
      albumA.songList.addAll([makeSong('1', playCount: 3), makeSong('2', playCount: 4)]); // 7 plays

      final albumB = Album('Album B', id: 'albB');
      albumB.songList.addAll([makeSong('3', playCount: 12)]); // 12 plays

      final albumZero = Album('Album Zero', id: 'albZero');
      albumZero.songList.add(makeSong('4', playCount: 0));

      artistAlbumManager.albumList.addAll([albumA, albumB, albumZero]);

      final top = topAlbums();
      expect(top.length, 2);
      expect(top[0].id, 'albB');
      expect(top[1].id, 'albA');
    });
  });

  group('favouriteSongs', () {
    test('returns empty list if Favorite playlist is not found', () {
      expect(favouriteSongs(), isEmpty);
    });

    test('returns songs from Favorite playlist up to shelfLimit', () {
      final favPlaylist = Playlist(name: 'Favorite', fileBacked: false);
      final songs = List.generate(15, (i) => makeSong('fav_$i'));
      favPlaylist.songList.addAll(songs);

      playlistManager.addPlaylist(favPlaylist);

      final favs = favouriteSongs();
      expect(favs.length, shelfLimit);
      expect(favs.first.id, 'fav_0');
      expect(favs.last.id, 'fav_11');
    });
  });
}
