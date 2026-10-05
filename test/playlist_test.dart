import 'dart:convert';
import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/utils/path.dart';

MyAudioMetadata makeSong(String id) {
  return MyAudioMetadata(
    AudioMetadata(title: 'Track $id'),
    id: id,
    path: '/tmp/$id.mp3',
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_playlist_test_');
    appSupportDir = tempDir;
  });

  setUp(() async {
    sourceType = SourceType.local;
    playlistManager.playlists.clear();
    playlistManager.playlistMap.clear();
    await playlistManager.load();
  });

  tearDown(() {
    playlistManager.playlists.clear();
    playlistManager.playlistMap.clear();
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  group('PlaylistManager', () {
    test('initializes with Favorite playlist', () {
      expect(playlistManager.playlists.length, 1);
      expect(playlistManager.playlists.first.name, 'Favorite');
      expect(playlistManager.isPinned(playlistManager.playlists.first), isTrue);
    });

    test('addPlaylist registers in both list and map', () {
      final p = Playlist(name: 'Roadtrip', fileBacked: false);
      playlistManager.addPlaylist(p);

      expect(playlistManager.playlists.length, 2);
      expect(playlistManager.getPlaylistByName('Roadtrip'), p);
      expect(playlistManager.getPlaylistByIndex(1), p);
    });

    test('setPinned and sidebarPlaylists track user pinning state', () {
      final p1 = Playlist(name: 'Focus', fileBacked: false);
      final p2 = Playlist(name: 'Workout', fileBacked: false);
      playlistManager.addPlaylist(p1);
      playlistManager.addPlaylist(p2);

      // Favorite is always pinned
      expect(playlistManager.sidebarPlaylists.map((p) => p.name).toList(), ['Favorite']);

      // Pin Workout
      playlistManager.setPinned(p2, true);
      expect(playlistManager.isPinned(p2), isTrue);
      expect(playlistManager.sidebarPlaylists.map((p) => p.name).toList(), ['Favorite', 'Workout']);

      // Unpin Workout
      playlistManager.setPinned(p2, false);
      expect(playlistManager.isPinned(p2), isFalse);
      expect(playlistManager.sidebarPlaylists.map((p) => p.name).toList(), ['Favorite']);
    });

    test('deletePlaylist removes playlist from lists and files', () async {
      final custom = Playlist(name: 'MyMix', fileBacked: true);
      playlistManager.addPlaylist(custom);
      playlistManager.setPinned(custom, true);

      expect(playlistManager.playlists.length, 2);
      expect(playlistManager.isPinned(custom), isTrue);

      await playlistManager.deletePlaylist(custom);

      expect(playlistManager.playlists.length, 1);
      expect(playlistManager.getPlaylistByName('MyMix'), isNull);
      expect(playlistManager.isPinned(custom), isFalse);
    });
  });

  group('Playlist manipulation', () {
    test('add and remove modify songList and write to file', () async {
      final p = Playlist(name: 'Chill', fileBacked: true);
      p.canModify = true;
      final s1 = makeSong('s1');
      final s2 = makeSong('s2');

      await p.add([s1, s2]);
      // Songs are inserted at index 0 in order
      expect(p.songList.map((s) => s.id).toSet(), {'s1', 's2'});

      final fileData = jsonDecode(await p.songListFile!.readAsString()) as List;
      expect(fileData.toSet(), {'s1', 's2'});

      // Remove s1
      await p.remove([s1]);
      expect(p.songList.map((s) => s.id).toList(), ['s2']);

      final afterRemove = jsonDecode(await p.songListFile!.readAsString()) as List;
      expect(afterRemove, ['s2']);
    });

    test('toggleFavoriteState toggles isFavoriteNotifier and updates Favorite playlist', () async {
      final fav = playlistManager.playlists.first;
      fav.canModify = true;
      final s = makeSong('fav_song');

      expect(s.isFavoriteNotifier.value, isFalse);
      toggleFavoriteState(s);

      // Wait for async add
      await Future.delayed(const Duration(milliseconds: 50));
      expect(s.isFavoriteNotifier.value, isTrue);
      expect(fav.songList.contains(s), isTrue);

      toggleFavoriteState(s);
      // Wait for async remove
      await Future.delayed(const Duration(milliseconds: 50));
      expect(s.isFavoriteNotifier.value, isFalse);
      expect(fav.songList.contains(s), isFalse);
    });

    test('setCover and removeCover manage custom cover art file', () async {
      final p = Playlist(name: 'CoverTest', fileBacked: true);

      // Create a dummy image
      final img = File('${tempDir.path}/test_cover.png')..writeAsStringSync('png data');
      await p.setCover(img.path);

      expect(p.customCover, isNotNull);
      expect(p.coverPicture, isNotNull);

      final picsDir = Directory(getPicturesPath(SourceType.local));
      expect(picsDir.existsSync(), isTrue);

      p.removeCover();
      expect(p.customCover, isNull);
    });
  });
}
