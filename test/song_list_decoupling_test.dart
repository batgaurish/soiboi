import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/folder.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/widgets/song_list/song_list.dart';
import 'package:soiboi/layer/albums_layer.dart';
import 'package:soiboi/layer/artists_layer.dart';
import 'package:soiboi/layer/folders/folders_layer.dart';
import 'package:soiboi/layer/playlists_layer.dart';
import 'package:soiboi/layer/ranking_layer.dart';
import 'package:soiboi/layer/recently_layer.dart';
import 'package:soiboi/layer/single_album_layer.dart';
import 'package:soiboi/layer/single_artist_layer.dart';
import 'package:soiboi/layer/single_folder_layer.dart';
import 'package:soiboi/layer/single_playlist_layer.dart';

void main() {
  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_song_list_test_');
    appSupportDir = tempDir;
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  group('Single*Layer decoupling from SongList', () {
    testWidgets('SingleAlbumLayer injects albumsVisibleNotifier by default', (tester) async {
      late SongList built;
      await tester.pumpWidget(
        Builder(
          builder: (context) {
            final layer = SingleAlbumLayer(album: Album('Discovery'));
            built = layer.build(context) as SongList;
            return const SizedBox.shrink();
          },
        ),
      );

      expect(built.rootLabel, 'albums');
      expect(built.rootVisibleNotifier, albumsVisibleNotifier);
      expect(built.isRoot, isFalse);
      expect(built.onBackToRoot, isNotNull);
    });

    testWidgets('SingleAlbumLayer injects rankingVisibleNotifier when rootLabel is ranking', (tester) async {
      late SongList built;
      await tester.pumpWidget(
        Builder(
          builder: (context) {
            final layer = SingleAlbumLayer(album: Album('Discovery'), rootLabel: 'ranking');
            built = layer.build(context) as SongList;
            return const SizedBox.shrink();
          },
        ),
      );

      expect(built.rootLabel, 'ranking');
      expect(built.rootVisibleNotifier, rankingVisibleNotifier);
      expect(built.onBackToRoot, isNotNull);
    });

    testWidgets('SingleAlbumLayer injects recentlyVisibleNotifier when rootLabel is recently', (tester) async {
      late SongList built;
      await tester.pumpWidget(
        Builder(
          builder: (context) {
            final layer = SingleAlbumLayer(album: Album('Discovery'), rootLabel: 'recently');
            built = layer.build(context) as SongList;
            return const SizedBox.shrink();
          },
        ),
      );

      expect(built.rootLabel, 'recently');
      expect(built.rootVisibleNotifier, recentlyVisibleNotifier);
      expect(built.onBackToRoot, isNotNull);
    });

    testWidgets('SingleArtistLayer injects artistsVisibleNotifier and rootLabel', (tester) async {
      late SongList built;
      await tester.pumpWidget(
        Builder(
          builder: (context) {
            final layer = SingleArtistLayer(artist: Artist('Daft Punk'));
            built = layer.build(context) as SongList;
            return const SizedBox.shrink();
          },
        ),
      );

      expect(built.rootLabel, 'artists');
      expect(built.rootVisibleNotifier, artistsVisibleNotifier);
      expect(built.isRoot, isFalse);
      expect(built.onBackToRoot, isNotNull);
    });

    testWidgets('SingleFolderLayer injects foldersVisibleNotifier and rootLabel', (tester) async {
      late SongList built;
      await tester.pumpWidget(
        Builder(
          builder: (context) {
            final layer = SingleFolderLayer(folder: Folder('electronic', '/music/electronic'));
            built = layer.build(context) as SongList;
            return const SizedBox.shrink();
          },
        ),
      );

      expect(built.rootLabel, 'folders');
      expect(built.rootVisibleNotifier, foldersVisibleNotifier);
      expect(built.isRoot, isFalse);
      expect(built.onBackToRoot, isNotNull);
    });

    testWidgets('SinglePlaylistLayer injects playlistsVisibleNotifier when not isRoot', (tester) async {
      late SongList built;
      await tester.pumpWidget(
        Builder(
          builder: (context) {
            final layer = SinglePlaylistLayer(playlist: Playlist(name: 'Favorites'), isRoot: false);
            built = layer.build(context) as SongList;
            return const SizedBox.shrink();
          },
        ),
      );

      expect(built.rootLabel, 'playlists');
      expect(built.rootVisibleNotifier, playlistsVisibleNotifier);
      expect(built.isRoot, isFalse);
      expect(built.onBackToRoot, isNotNull);
    });

    testWidgets('SinglePlaylistLayer omits root notifier when isRoot is true', (tester) async {
      late SongList built;
      await tester.pumpWidget(
        Builder(
          builder: (context) {
            final layer = SinglePlaylistLayer(playlist: Playlist(name: 'Favorites'), isRoot: true);
            built = layer.build(context) as SongList;
            return const SizedBox.shrink();
          },
        ),
      );

      expect(built.rootLabel, '');
      expect(built.rootVisibleNotifier, isNull);
      expect(built.isRoot, isTrue);
      expect(built.onBackToRoot, isNull);
    });
  });
}
