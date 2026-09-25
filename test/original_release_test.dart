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
      best!['collectionName'],
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
      best!['collectionName'],
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
      best!['collectionName'],
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
    expect(best!['collectionName'], 'Illuminate');
  });

  test('no row for the asked song or artist is no match, not a cover', () {
    // The store's top hits for Måneskin's song were other artists' covers.
    final covers = [
      row(
        'I Wanna Be Your Slave (feat. Margad)',
        'I Wanna Be Your Slave (feat. Margad) - Single',
        artist: 'Jaydan Wolf, Te Pai & Daniel Chord',
      ),
      row(
        'I Wanna Be Your Slave',
        'I Wanna Be Your Slave - Single',
        artist: 'Guillo Rist & Tinho Vaamonde',
      ),
    ];
    expect(
      pickOriginalRelease(
        covers,
        artist: 'Måneskin',
        title: 'I Wanna Be Your Slave',
      ),
      isNull,
    );
    expect(
      pickOriginalRelease(iktara, artist: 'Someone Else', title: 'Another'),
      isNull,
    );
  });

  test('artists match across credits and spellings, not across people', () {
    const apple = 'Mohammed Irfan, Arijit & Saim Bhat';
    expect(sameArtist('Mohammad Irfan', apple), isTrue); // one letter
    expect(sameArtist('Arijit Singh', apple), isTrue); // "Arijit" alone
    expect(sameArtist('Saim Bhat', apple), isTrue); // not the first credit
    expect(
      sameArtist('Måneskin', 'Jaydan Wolf, Te Pai & Daniel Chord'),
      isFalse,
    );
    expect(sameArtist('Måneskin', 'Måneskin'), isTrue);
    expect(sameArtist('Shawn Mendes', 'Shawn Mendes & Camila Cabello'), isTrue);
    expect(sameArtist('Sia', 'Sza'), isFalse); // too short to guess
    expect(
      sameArtist('अरिजीत सिंह', 'Arijit Singh'),
      isTrue,
    ); // no Latin letters
  });

  test('a respelled artist still finds the soundtrack', () {
    final rows = [
      row(
        'Phir Mohabbat (From "Murder 2")',
        'Best of Arijit Singh',
        artist: 'Mohammed Irfan, Arijit Singh & Saim Bhat',
        collectionArtist: 'Various Artists',
      ),
      row(
        'Phir Mohabbat',
        'Murder 2 (Original Motion Picture Soundtrack)',
        artist: 'Mohammed Irfan, Arijit & Saim Bhat',
      ),
    ];
    final best = pickOriginalRelease(
      rows,
      artist: 'Mohammad Irfan',
      title: 'Phir Mohabbat',
    );
    expect(
      best!['collectionName'],
      'Murder 2 (Original Motion Picture Soundtrack)',
    );
  });
}
