import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/apple_library_service.dart';

void main() {
  group('ApplePlaylist', () {
    test('fromJson parses full fields and detects catalog URL', () {
      final json = {
        'library_id': 'p.lib123',
        'name': 'Summer Vibes',
        'catalog_id': 'pl.cat456',
        'track_count': 25,
        'artwork_url': 'https://example.com/art.jpg',
        'description': 'Summer hits',
      };

      final playlist = ApplePlaylist.fromJson(json);

      expect(playlist.libraryId, 'p.lib123');
      expect(playlist.name, 'Summer Vibes');
      expect(playlist.catalogId, 'pl.cat456');
      expect(playlist.trackCount, 25);
      expect(playlist.artworkUrl, 'https://example.com/art.jpg');
      expect(playlist.description, 'Summer hits');
      expect(playlist.hasCatalogUrl, isTrue);
    });

    test('hasCatalogUrl is false when catalog_id is null or empty', () {
      const pNull = ApplePlaylist(libraryId: 'l1', name: 'My Mix', catalogId: null);
      expect(pNull.hasCatalogUrl, isFalse);

      const pEmpty = ApplePlaylist(libraryId: 'l2', name: 'My Mix', catalogId: '');
      expect(pEmpty.hasCatalogUrl, isFalse);
    });
  });

  group('AppleLibraryTrack', () {
    test('fromJson parses track and identifies archivable state', () {
      final track = AppleLibraryTrack.fromJson({
        'title': 'Track One',
        'artist': 'Artist One',
        'album': 'Album One',
        'catalog_id': '123456789',
      });

      expect(track.title, 'Track One');
      expect(track.artist, 'Artist One');
      expect(track.album, 'Album One');
      expect(track.catalogId, '123456789');
      expect(track.archivable, isTrue);
    });

    test('archivable is false when catalog_id is absent', () {
      final userUpload = AppleLibraryTrack.fromJson({
        'title': 'My Voice Memo',
      });
      expect(userUpload.archivable, isFalse);
      expect(userUpload.catalogId, isNull);
    });
  });

  group('Catalog URLs', () {
    test('catalogPlaylistUrl formats storefront and id into Apple Music url', () {
      final url = catalogPlaylistUrl('pl.u-xyz', 'us');
      expect(url, 'https://music.apple.com/us/playlist/playlist/pl.u-xyz');
    });

    test('catalogSongUrl formats storefront and song id into Apple Music url', () {
      final url = catalogSongUrl('987654', 'gb');
      expect(url, 'https://music.apple.com/gb/song/song/987654');
    });
  });

  group('ApplePlaylistsResult and AppleLibraryException', () {
    test('ApplePlaylistsResult defaults', () {
      const res = ApplePlaylistsResult();
      expect(res.playlists, isEmpty);
      expect(res.storefront, 'us');
      expect(res.needsSignIn, isFalse);
      expect(res.error, isNull);
    });

    test('AppleLibraryException returns message on toString()', () {
      const ex = AppleLibraryException('Session expired');
      expect(ex.toString(), 'Session expired');
      expect(ex.message, 'Session expired');
    });
  });
}
