import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

void main() {
  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_artist_album_test_');
    appSupportDir = tempDir;
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  group('ArtistAlbumBase isArtist', () {
    test('Artist has isArtist true', () {
      final artist = Artist('Daft Punk');
      expect(artist.isArtist, isTrue);
    });

    test('Album has isArtist false', () {
      final album = Album('Discovery');
      expect(album.isArtist, isFalse);
    });
  });

  group('ArtistAlbumManager loading and query policies', () {
    late ArtistAlbumManager manager;

    setUp(() {
      manager = ArtistAlbumManager();
      ArtistAlbumManager.isBusyProvider = null;
    });

    test('isBusy defaults to false when isBusyProvider is null', () {
      expect(manager.isBusy, isFalse);
    });

    test('isBusy delegates to isBusyProvider', () {
      bool busy = true;
      ArtistAlbumManager.isBusyProvider = () => busy;
      expect(manager.isBusy, isTrue);
      busy = false;
      expect(manager.isBusy, isFalse);
    });

    test('ensureAlbums returns false for local source when not busy', () async {
      ArtistAlbumManager.isBusyProvider = () => false;
      final reachEnd = await manager.ensureAlbums();
      expect(reachEnd, isFalse);
    });

    test('ensureArtists returns false for local source when not busy', () async {
      ArtistAlbumManager.isBusyProvider = () => false;
      final result = await manager.ensureArtists();
      expect(result, isFalse);
    });

    test('albumFor resolves album from albumMap in local source', () async {
      final album = Album('Discovery');
      manager.albumMap['Discovery'] = album;
      final song = MyAudioMetadata(
        AudioMetadata(title: 'One More Time', album: 'Discovery'),
        id: '1',
        path: '/tmp/1.mp3',
      );

      final result = await manager.albumFor(song);
      expect(result, equals(album));
    });

    test('artistFor resolves artist from artistMap in local source', () async {
      final artist = Artist('Daft Punk');
      manager.artistMap['Daft Punk'] = artist;

      final result = await manager.artistFor('Daft Punk');
      expect(result, equals(artist));
    });

    test('artistFor returns null for null artistName', () async {
      final result = await manager.artistFor(null);
      expect(result, isNull);
    });
  });
}
