import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/external_playlist_source.dart';
import 'package:soiboi/base/services/youtube_playlist_source.dart';

/// A source that answers from a fixed map, so the registry and the URL
/// dispatch can be tested without a network or a Python runtime.
class _FakeSource extends ExternalPlaylistSource {
  _FakeSource(this.id, {this.prefix});

  @override
  final String id;
  final String? prefix;

  @override
  String get displayName => id;

  @override
  bool get acceptsLinks => prefix != null;

  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async => const [];

  @override
  String? playlistIdFromUrl(String url) =>
      prefix != null && url.startsWith(prefix!)
      ? url.substring(prefix!.length)
      : null;
}

void main() {
  group('parsing a YouTube entry into artist and title', () {
    test('splits the overwhelmingly common "Artist - Title" shape', () {
      final track = parseYouTubeTrack(
        'Daft Punk - One More Time (Official Audio)',
        'Daft Punk',
      );
      expect(track.artist, 'Daft Punk');
      expect(track.title, 'One More Time');
    });

    test('a "- Topic" channel is trusted over the title', () {
      // YouTube Music's own catalog uploads: the uploader is exactly the
      // artist and the title is exactly the track, so there is nothing to
      // guess and guessing would only make it worse.
      final track = parseYouTubeTrack('Instant Crush', 'Daft Punk - Topic');
      expect(track.artist, 'Daft Punk');
      expect(track.title, 'Instant Crush');
    });

    test('strips stacked promotional noise', () {
      final track = parseYouTubeTrack(
        'Arctic Monkeys - Do I Wanna Know? (Official Video) [HD] (Lyrics)',
        'ArcticMonkeysVEVO',
      );
      expect(track.artist, 'Arctic Monkeys');
      expect(track.title, 'Do I Wanna Know?');
    });

    test('drops a featured-artist suffix', () {
      // Apple lists the track under the primary artist; searching for
      // "Daft Punk ft. Julian Casablancas" matches nothing.
      final track = parseYouTubeTrack(
        'Daft Punk - Instant Crush (Official Video) ft. Julian Casablancas',
        'Daft Punk',
      );
      expect(track.artist, 'Daft Punk');
      expect(track.title, 'Instant Crush');
    });

    test('handles en and em dashes as separators', () {
      expect(parseYouTubeTrack('Sigur Rós – Hoppípolla', 'x').title,
          'Hoppípolla');
      expect(parseYouTubeTrack('Sigur Rós — Hoppípolla', 'x').artist,
          'Sigur Rós');
    });

    test('does not tear a hyphenated name in half', () {
      // No spaces around the hyphen, so it is part of the name.
      final track = parseYouTubeTrack('Sun-El Musician', 'Sun-El Musician');
      expect(track.title, 'Sun-El Musician');
      expect(track.artist, 'Sun-El Musician');
    });

    test('falls back to the uploader when there is no separator', () {
      final track = parseYouTubeTrack('Bohemian Rhapsody', 'Queen');
      expect(track.artist, 'Queen');
      expect(track.title, 'Bohemian Rhapsody');
    });

    test('an empty half is not accepted as a split', () {
      // "- Anthem" would otherwise produce an empty artist, which matches
      // either everything or nothing depending on the search.
      final track = parseYouTubeTrack('- Anthem', 'Some Channel');
      expect(track.artist, 'Some Channel');
      expect(track.title, 'Anthem');
    });

    test('a remaster tag is noise, not part of the title', () {
      final track = parseYouTubeTrack(
        'Michael Jackson - Bad (Remastered 2012)',
        'michaeljacksonVEVO',
      );
      expect(track.title, 'Bad');
    });
  });

  group('recognising a playlist link', () {
    test('reads list= from both YouTube hosts', () {
      expect(
        youTubePlaylistId(
          'https://music.youtube.com/playlist?list=PLSdoVPM5Wnnd123456',
        ),
        'PLSdoVPM5Wnnd123456',
      );
      expect(
        youTubePlaylistId(
          'https://www.youtube.com/playlist?list=OLAK5uy_abcdefghijk',
        ),
        'OLAK5uy_abcdefghijk',
      );
    });

    test('accepts a bare id, because people paste those too', () {
      expect(youTubePlaylistId('PLSdoVPM5Wnnd123456'), 'PLSdoVPM5Wnnd123456');
    });

    test('rejects a link from somewhere else', () {
      expect(
        youTubePlaylistId('https://open.spotify.com/playlist/abc123'),
        isNull,
      );
      expect(youTubePlaylistId('https://music.apple.com/us/album/1'), isNull);
      expect(youTubePlaylistId(''), isNull);
    });

    test('a YouTube link with no playlist is not a playlist', () {
      expect(youTubePlaylistId('https://www.youtube.com/watch?v=abc'), isNull);
    });
  });

  group('the source registry', () {
    setUp(playlistSources.clear);
    tearDown(playlistSources.clear);

    test('dispatches a URL to the source that claims it', () {
      final a = _FakeSource('a', prefix: 'https://a.invalid/');
      final b = _FakeSource('b', prefix: 'https://b.invalid/');
      registerPlaylistSource(a);
      registerPlaylistSource(b);

      final match = sourceForUrl('https://b.invalid/xyz');
      expect(match!.source.id, 'b');
      expect(match.playlistId, 'xyz');
      expect(sourceForUrl('https://c.invalid/xyz'), isNull);
    });

    test('registering the same id twice replaces rather than duplicates', () {
      registerPlaylistSource(_FakeSource('a'));
      registerPlaylistSource(_FakeSource('a'));
      expect(playlistSources, hasLength(1));
    });

    test('a playlist key is namespaced by its source', () {
      // A bare hex id is both a plausible MusicBrainz id and a plausible
      // YouTube one, so an unnamespaced cache key would let one platform
      // answer for another.
      const shared = 'abc123';
      const fromLb = ExternalPlaylist(
        sourceId: 'listenbrainz',
        id: shared,
        title: 'Weekly',
      );
      const fromYt = ExternalPlaylist(
        sourceId: 'youtube',
        id: shared,
        title: 'Mix',
      );
      expect(fromLb.key, isNot(fromYt.key));
      expect(fromLb.key, 'listenbrainz:abc123');
    });

    test('only link-capable sources say so', () {
      expect(_FakeSource('browse-only').acceptsLinks, isFalse);
      expect(YouTubePlaylistSource().acceptsLinks, isTrue);
    });

    test('sourceById finds what was registered, and nothing else', () {
      registerPlaylistSource(_FakeSource('youtube'));
      expect(sourceById('youtube'), isNotNull);
      expect(sourceById('spotify'), isNull);
    });
  });
}
