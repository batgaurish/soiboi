/// The link matchers and parsers for Tidal, JioSaavn, SoundCloud, Qobuz,
/// Gaana, Bandcamp and pasted tracklists.
///
/// Fixtures are trimmed from real responses fetched while writing these
/// sources. The live requests are not repeated here.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/jiosaavn_playlist_source.dart';
import 'package:soiboi/base/services/page_playlist_sources.dart';
import 'package:soiboi/base/services/soundcloud_playlist_source.dart';
import 'package:soiboi/base/services/tidal_playlist_source.dart';
import 'package:soiboi/base/services/tracklist_source.dart';

const _uuid = '0b5df380-47d3-48fe-ae66-8f0dba90b1ee';

void main() {
  group('tidal', () {
    test('accepts every playlist link shape and nothing else', () {
      for (final url in [
        'https://tidal.com/playlist/$_uuid',
        'https://tidal.com/browse/playlist/$_uuid?u',
        'https://listen.tidal.com/playlist/${_uuid.toUpperCase()}',
      ]) {
        expect(tidalPlaylistId(url), _uuid, reason: url);
      }
      expect(tidalPlaylistId('https://tidal.com/album/123'), isNull);
      expect(tidalPlaylistId('https://nottidal.com/playlist/$_uuid'), isNull);
    });

    test('keeps the ISRC and folds the version into the title', () {
      final track = parseTidalItem({
        'type': 'track',
        'item': {
          'title': 'Stop Me',
          'version': 'Live',
          'artist': {'name': 'Buddy Miller'},
          'isrc': 'US27Q2662309',
        },
      })!;
      expect(track.title, 'Stop Me (Live)');
      expect(track.isrc, 'US27Q2662309');
      expect(parseTidalItem({'type': 'video', 'item': {'title': 'x'}}), isNull);
    });
  });

  group('jiosaavn', () {
    test('reads the token from both link shapes', () {
      expect(
        jiosaavnPlaylistToken(
          'https://www.jiosaavn.com/featured/trending_today/I3kvhipIy73uCJW60TJk1Q__',
        ),
        'I3kvhipIy73uCJW60TJk1Q__',
      );
      expect(
        jiosaavnPlaylistToken('https://www.jiosaavn.com/s/playlist/abc/mix/Tok_EN123'),
        'Tok_EN123',
      );
      expect(jiosaavnPlaylistToken('https://www.jiosaavn.com/song/x/abc123'), isNull);
    });

    test('names the primary artists, not the label', () {
      final track = parseJioSaavnSong({
        'title': 'Balam Pichkari',
        'more_info': {
          'artistMap': {
            'primary_artists': [
              {'name': 'Pritam'},
              {'name': 'Vishal Dadlani'},
            ],
          },
        },
      })!;
      expect(track.artist, 'Pritam, Vishal Dadlani');
      expect(parseJioSaavnSong({'title': 'Label Only'}), isNull);
    });
  });

  group('soundcloud', () {
    test('matches sets only, normalised', () {
      expect(
        soundcloudSetUrl('https://soundcloud.com/lofi_girl/sets/when-we-were-kids?si=1'),
        'https://soundcloud.com/lofi_girl/sets/when-we-were-kids',
      );
      expect(soundcloudSetUrl('https://soundcloud.com/lofi_girl/a-track'), isNull);
    });

    test('finds the public client id in the page', () {
      const html = '<script>window.__sc_hydration = [{"hydratable":"geoip","data":{}},'
          '{"hydratable":"apiClient","data":{"id":"Pb72ran","isExpiring":false}}];</script>';
      expect(parseSoundcloudClientId(html), 'Pb72ran');
    });

    test('prefers the label metadata artist, and skips id-only entries', () {
      final track = parseSoundcloudTrack({
        'title': 'Night Drive',
        'user': {'username': 'some channel'},
        'publisher_metadata': {'artist': 'Real Artist', 'isrc': 'GBAYE0000001'},
      })!;
      expect(track.artist, 'Real Artist');
      expect(track.isrc, 'GBAYE0000001');
      expect(parseSoundcloudTrack({'id': 1}), isNull);
    });
  });

  group('schema.org pages (Qobuz, Gaana)', () {
    test('reads a MusicPlaylist', () {
      const html = '''<script type="application/ld+json">
        {"@type":"MusicPlaylist","name":"Hindi Top 50","track":[
          {"@type":"MusicRecording","name":"Tera Mera  Rishta","byArtist":"Mithoon,Saaj Bhatt,Pritam"},
          {"@type":"MusicRecording","name":"Parvati","byArtist":[{"name":"A"},{"name":"B"}]}
        ]}</script>''';
      final parsed = parseJsonLdPlaylist(html)!;
      expect(parsed.title, 'Hindi Top 50');
      expect(parsed.tracks.first.title, 'Tera Mera Rishta');
      expect(parsed.tracks.first.artist, 'Mithoon, Saaj Bhatt');
      expect(parsed.tracks.last.artist, 'A, B');
    });

    test('link matching is per site', () {
      final qobuz = qobuzPlaylistSource();
      expect(
        qobuz.playlistIdFromUrl('https://www.qobuz.com/us-en/playlists/coltrane/70567890?x=1'),
        'https://www.qobuz.com/us-en/playlists/coltrane/70567890',
      );
      expect(qobuz.playlistIdFromUrl('https://gaana.com/playlist/x'), isNull);
      expect(gaanaPlaylistSource().playlistIdFromUrl('https://gaana.com/playlist/top-50'), isNotNull);
    });

    test('a page without the data is null, not empty', () {
      expect(parseJsonLdPlaylist('<html></html>'), isNull);
    });
  });

  group('bandcamp', () {
    test('reads the embedded album', () {
      const html = '<div data-tralbum="{&quot;artist&quot;:&quot;Mitski&quot;,'
          '&quot;current&quot;:{&quot;title&quot;:&quot;Bury Me At Makeout Creek&quot;},'
          '&quot;trackinfo&quot;:[{&quot;title&quot;:&quot;Texas Reznikoff&quot;},'
          '{&quot;title&quot;:&quot;Townie&quot;,&quot;artist&quot;:&quot;Guest&quot;}]}"></div>';
      final album = parseBandcampAlbum(html)!;
      expect(album.title, 'Bury Me At Makeout Creek');
      expect(album.tracks.map((t) => '${t.artist} - ${t.title}'),
          ['Mitski - Texas Reznikoff', 'Guest - Townie']);
    });

    test('matches album links only', () {
      final source = BandcampAlbumSource();
      expect(
        source.playlistIdFromUrl('https://mitski.bandcamp.com/album/bury-me?from=x'),
        'https://mitski.bandcamp.com/album/bury-me',
      );
      expect(source.playlistIdFromUrl('https://mitski.bandcamp.com/track/x'), isNull);
    });
  });

  group('pasted tracklist', () {
    test('reads Artist - Title lines, dropping numbering and junk', () {
      final tracks = parseTracklist('''
1. Radiohead - Reckoner
Björk – Jóga
not a track line
Daft Punk\tOne More Time
''');
      expect(tracks.map((t) => '${t.artist}|${t.title}'),
          ['Radiohead|Reckoner', 'Björk|Jóga', 'Daft Punk|One More Time']);
    });

    test('reads a CSV with an ISRC column, quotes included', () {
      final tracks = parseTracklist('Title,Artist,ISRC\n'
          '"Hello, Goodbye",The Beatles,GBAYE0601690\n'
          'Yellow,Coldplay,\n');
      expect(tracks.first.title, 'Hello, Goodbye');
      expect(tracks.first.isrc, 'GBAYE0601690');
      expect(tracks.last.isrc, isNull);
    });

    test('claims pasted lists but never a single link', () {
      final source = TracklistSource();
      expect(source.playlistIdFromUrl('https://tidal.com/playlist/$_uuid'), isNull);
      expect(source.playlistIdFromUrl('A - B\nC - D'), isNotNull);
      expect(source.playlistIdFromUrl('A - B'), isNull);
    });
  });
}
