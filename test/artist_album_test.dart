import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/data/artist_album.dart';

void main() {
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
}
