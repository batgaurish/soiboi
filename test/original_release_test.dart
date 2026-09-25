import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/apple_catalog_service.dart';

/// A trimmed iTunes Search row.
Map<String, dynamic> row(
  String track,
  String collection, {
  String artist = 'Amit Trivedi, Kavita Seth & Amitabh Bhattacharya',
  String? collectionArtist,
}) => {
  'trackName': track,
  'collectionName': collection,
  'artistName': artist,
  'collectionArtistName': ?collectionArtist,
};

void main() {
  // What iTunes Search returned (storefront "in", 2026-09-25) for
  // "Amit Trivedi Iktara", in its order.
  final iktara = [
    row(
      'Iktara (MTV Unplugged Version)',
      'Iktara - Single (MTV Unplugged Version)',
    ),
    row(
      'Iktara (Yaari Version)',
      'Yaari Jam',
      collectionArtist: 'Various Artists',
    ),
    row(
      'Iktara (From "Wake Up Sid")',
      'Handpicked By Karan : Karan Johar\'s Favourites',
      collectionArtist: 'Various Artists',
    ),
    row('Iktara', 'Chai aur Baarish', collectionArtist: 'Various Artists'),
    row(
      'Iktara',
      'Wake Up Sid (Original Motion Picture Soundtrack) [Deluxe Edition]',
      collectionArtist: 'Shankar Ehsaan Loy & Amit Trivedi',
    ),
    row(
      'Iktara',
      'Wake Up Sid (Original Motion Picture Soundtrack)',
      collectionArtist: 'Shankar Ehsaan Loy',
    ),
  ];

  test('search picks the soundtrack over compilations and editions', () {
    final best = pickOriginalRelease(
      iktara,
      artist: 'Amit Trivedi',
      title: 'Iktara',
    );
    expect(
      best['collectionName'],
      'Wake Up Sid (Original Motion Picture Soundtrack)',
    );
  });

  test('a "(From Film)" title still finds the soundtrack', () {
    final best = pickOriginalRelease(
      iktara,
      artist: 'Amit Trivedi',
      title: 'Iktara (From "Wake Up Sid")',
    );
    expect(
      best['collectionName'],
      'Wake Up Sid (Original Motion Picture Soundtrack)',
    );
  });

  test('ISRC rows (one recording) prefer the original release', () {
    final best = pickOriginalRelease([
      row('Iktara', 'Chai aur Baarish', collectionArtist: 'Various Artists'),
      row(
        'Iktara (From "Wake Up Sid")',
        'Handpicked By Karan : Karan Johar\'s Favourites',
        collectionArtist: 'Various Artists',
      ),
      row(
        'Iktara',
        'Wake Up Sid (Original Motion Picture Soundtrack)',
        collectionArtist: 'Shankar Ehsaan Loy',
      ),
    ]);
    expect(
      best['collectionName'],
      'Wake Up Sid (Original Motion Picture Soundtrack)',
    );
  });

  test('the album beats its single, deluxe and anniversary editions', () {
    Map<String, dynamic> shawn(String track, String collection, [String? va]) =>
        row(track, collection, artist: 'Shawn Mendes', collectionArtist: va);
    final best = pickOriginalRelease(
      [
        shawn(
          'Treat You Better (Live From New York)',
          'Illuminate (10th Anniversary Edition)',
        ),
        shawn('Treat You Better', 'Illuminate (10th Anniversary Edition)'),
        shawn('Treat You Better', 'Illuminate (Deluxe)'),
        shawn(
          'Treat You Better',
          'Now That\'s What I Call Music! 2017',
          'Various Artists',
        ),
        shawn('Treat You Better', 'Treat You Better - Single'),
        shawn('Treat You Better', 'Illuminate'),
      ],
      artist: 'Shawn Mendes',
      title: 'Treat You Better',
    );
    expect(best['collectionName'], 'Illuminate');
  });

  test('no row for the asked song keeps the top hit', () {
    final best = pickOriginalRelease(
      iktara,
      artist: 'Someone Else',
      title: 'Another Song',
    );
    expect(best, same(iktara.first));
  });
}
