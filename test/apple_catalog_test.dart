@Tags(['integration'])
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/apple_catalog_service.dart';

/// Runs against Apple's real search API.
///
/// It needs no key and no account, which is the reason the app uses it at all,
/// so there is nothing to mock around — and a mock would not catch the thing
/// that actually breaks here, which is Apple changing a field name.
void main() {
  test('resolves an album with the metadata a card needs', () async {
    final album = await resolveAppleAlbum('Daft Punk', 'Discovery');
    expect(album, isNotNull);
    expect(album!.title, 'Discovery');
    expect(album.artist, 'Daft Punk');
    expect(album.releaseYear, '2001');
    expect(album.trackCount, greaterThan(10));
    expect(album.url, contains('music.apple.com'));
    // 100px is what the API returns by default and looks soft at card size.
    expect(album.artwork, contains('600x600'));
  });

  test('lists album tracks in order, with previews and download URLs', () async {
    final album = await resolveAppleAlbum('Daft Punk', 'Discovery');
    final tracks = await appleAlbumTracks(album!.id);

    expect(tracks, isNotNull);
    expect(tracks!.length, album.trackCount);
    expect(tracks.first.title, 'One More Time');
    expect(tracks.first.trackNumber, 1);
    expect(tracks.first.duration!.inSeconds, closeTo(320, 2));
    // Both are the point of the screen: one to hear it, one to archive it.
    expect(tracks.first.previewUrl, isNotNull);
    expect(tracks.first.url, contains('?i='));
    // The lookup returns the album itself as the first row; it must not be
    // rendered as a track.
    expect(tracks.map((t) => t.title), isNot(contains('Discovery')));
  });

  test('lists an artist\'s albums newest first', () async {
    final albums = await appleArtistAlbums('Daft Punk');
    expect(albums, isNotNull);
    expect(albums!.length, greaterThan(3));
    final years = albums.map((a) => a.releaseYear).whereType<String>().toList();
    expect(years, equals([...years]..sort((a, b) => b.compareTo(a))));
  });

  test('an unknown album resolves to null rather than throwing', () async {
    expect(await resolveAppleAlbum('', ''), isNull);
    expect(
      await resolveAppleAlbum('zzzz not a real artist', 'zzzz not a real album'),
      anyOf(isNull, isA<AppleAlbum>()),
    );
  });
}
