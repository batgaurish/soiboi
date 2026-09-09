/// Deezer and Spotify as playlist sources.
///
/// Both exist because the obvious route did not survive contact: Odesli /
/// song.link would have resolved any link across every platform in one call,
/// but its public API now answers 401 `PUBLIC_API_ACCESS_DEPRECATED` on both
/// of its hosts, for every URL shape. Deezer's read API needs no key and
/// carries ISRCs; Spotify's embed page needs no key and carries names.
///
/// The URL matchers and the parsers are what actually break — a matcher that
/// is too loose steals another source's links, and Spotify's embed is a page
/// meant for embedding rather than a documented API — so those are what these
/// cover. The fixture is trimmed from a real embed response, non-breaking
/// spaces and all.
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/deezer_playlist_source.dart';
import 'package:soiboi/base/services/spotify_playlist_source.dart';
import 'package:soiboi/base/services/youtube_playlist_source.dart';

void main() {
  group('deezer urls', () {
    test('accepts the share links people paste', () {
      expect(
        deezerPlaylistId('https://www.deezer.com/playlist/908622995'),
        '908622995',
      );
      // Deezer localises its paths, so the id is not at a fixed depth.
      expect(
        deezerPlaylistId('https://www.deezer.com/en/playlist/908622995'),
        '908622995',
      );
      expect(
        deezerPlaylistId('https://deezer.com/fr/playlist/123?utm_source=x'),
        '123',
      );
    });

    test('declines anything that is not a deezer playlist', () {
      expect(deezerPlaylistId('https://www.deezer.com/album/123'), isNull);
      expect(deezerPlaylistId('https://open.spotify.com/playlist/abc'), isNull);
      expect(deezerPlaylistId('https://notdeezer.com/playlist/1'), isNull);
      // Non-numeric ids are not Deezer's shape, and accepting them would have
      // this source claim links meant for another.
      expect(deezerPlaylistId('https://deezer.com/playlist/abc'), isNull);
      expect(deezerPlaylistId(''), isNull);
    });
  });

  group('deezer tracks', () {
    test('keeps the ISRC, which is the point of this source', () {
      final track = parseDeezerTrack({
        'title': 'Hey Jude (Remastered 2015)',
        'artist': {'name': 'The Beatles'},
        'isrc': 'GBUM71505902',
      });
      expect(track, isNotNull);
      expect(track!.title, 'Hey Jude (Remastered 2015)');
      expect(track.artist, 'The Beatles');
      expect(track.isrc, 'GBUM71505902');
    });

    test('an entry with no usable name is dropped, not half-built', () {
      expect(parseDeezerTrack({'title': '', 'artist': {'name': 'x'}}), isNull);
      expect(parseDeezerTrack({'title': 'x'}), isNull);
      expect(parseDeezerTrack({'title': 'x', 'artist': {}}), isNull);
    });

    test('a missing ISRC is null rather than empty', () {
      // '' would look like a code and be sent to the ISRC lookup, which then
      // fails instead of falling back to a keyword search.
      final track = parseDeezerTrack({
        'title': 'x',
        'artist': {'name': 'y'},
        'isrc': '',
      });
      expect(track!.isrc, isNull);
    });
  });

  group('spotify urls', () {
    test('accepts web, embed and uri forms', () {
      expect(
        spotifyPlaylistId('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M'),
        '37i9dQZF1DXcBWIGoYBM5M',
      );
      expect(
        spotifyPlaylistId('https://open.spotify.com/embed/playlist/abc123'),
        'abc123',
      );
      expect(spotifyPlaylistId('spotify:playlist:abc123'), 'abc123');
      // The ?si= share token is what the app's own share sheet produces.
      expect(
        spotifyPlaylistId('https://open.spotify.com/playlist/abc123?si=xyz'),
        'abc123',
      );
    });

    test('declines anything that is not a spotify playlist', () {
      expect(spotifyPlaylistId('https://open.spotify.com/track/abc'), isNull);
      expect(spotifyPlaylistId('https://deezer.com/playlist/123'), isNull);
      expect(spotifyPlaylistId('spotify:track:abc'), isNull);
      expect(spotifyPlaylistId(''), isNull);
    });
  });

  test('lookalike domains are not accepted by any source', () {
    // `endsWith(domain)` matched these, so one source would claim links that
    // belong to nobody. Subdomains must still work.
    expect(deezerPlaylistId('https://notdeezer.com/playlist/1'), isNull);
    expect(spotifyPlaylistId('https://evilspotify.com/playlist/abc'), isNull);
    expect(youTubePlaylistId('https://notyoutube.com/watch?list=PL123456789012'),
        isNull);

    expect(deezerPlaylistId('https://www.deezer.com/playlist/1'), '1');
    expect(
      spotifyPlaylistId('https://open.spotify.com/playlist/abc'),
      'abc',
    );
  });

  group('spotify embed parsing', () {
    String fixture() =>
        File('test/fixtures/spotify_playlist_embed.html').readAsStringSync();

    test('reads the playlist out of a real embed page', () {
      final embed = parseSpotifyEmbed(fixture());
      expect(embed, isNotNull);
      expect(embed!.title, isNotEmpty);
      expect(embed.tracks.length, 3);
      expect(embed.tracks.first.title, "Ain't In LA");
      expect(embed.tracks.first.artist, 'ADÉLA');
      // No ISRC on this route, which is why Deezer is preferred when a
      // playlist is on both.
      expect(embed.tracks.first.isrc, isNull);
    });

    test('collapses the non-breaking spaces between collaborators', () {
      // Straight from the real response: the embed joins artists with U+00A0,
      // which does not match what a catalog keyword search expects.
      final embed = parseSpotifyEmbed(fixture());
      final collab = embed!.tracks.firstWhere((t) => t.title == 'BbY WOW');
      expect(collab.artist.contains(' '), isFalse);
      expect(collab.artist, 'KAROL G, Judeline, rusowsky');
    });

    test('a page without the blob fails to null rather than throwing', () {
      // Spotify can change this page whenever it likes; that should cost the
      // import, not the app.
      expect(parseSpotifyEmbed('<html><body>nope</body></html>'), isNull);
      expect(
        parseSpotifyEmbed(
          '<script id="__NEXT_DATA__" type="application/json">{bad</script>',
        ),
        isNull,
      );
    });

    test('an unexpected shape yields no tracks rather than nonsense', () {
      const html =
          '<script id="__NEXT_DATA__" type="application/json">'
          '{"props":{"pageProps":{"state":{"data":{"entity":'
          '{"name":"Empty","trackList":[]}}}}}}</script>';
      final embed = parseSpotifyEmbed(html);
      expect(embed!.title, 'Empty');
      expect(embed.tracks, isEmpty);
    });
  });
}
