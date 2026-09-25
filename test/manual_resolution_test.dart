import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/apple_catalog_service.dart';
import 'package:soiboi/base/services/discovery_service.dart';
import 'package:soiboi/base/services/external_playlist_source.dart';
import 'package:soiboi/base/services/linked_playlists.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/manual_resolution.dart';

class _Source extends ExternalPlaylistSource {
  _Source(this._tracks);

  final List<ExternalTrack> _tracks;

  @override
  String get id => 'fake';

  @override
  String get displayName => 'Fake';

  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async => _tracks;
}

UnresolvedTrack _track(String artist, String title) => UnresolvedTrack(
  artist: artist,
  title: title,
  playlist: 'Weekly Exploration',
  added: DateTime.utc(2026, 9, 25),
);

void main() {
  late Directory dir;

  setUpAll(() async {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_resolution');
    await logger.init();
  });

  setUp(() => dir = Directory.systemTemp.createTempSync('soiboi_manual'));
  tearDown(() => dir.deleteSync(recursive: true));

  group('persistence', () {
    test('unresolved tracks survive a restart, without duplicates', () {
      final store = ManualResolution(dir: dir);
      store.addUnresolved([
        _track('Måneskin', 'I Wanna Be Your Slave'),
        _track('Chase Atlantic', 'Into It'),
        // The same song spelled differently is not listed twice.
        _track('Måneskin', 'I Wanna Be Your Slave (feat. Nobody)'),
        _track('', 'No artist'),
      ]);
      expect(store.unresolved.value.map((t) => t.title), [
        'I Wanna Be Your Slave',
        'Into It',
      ]);

      final reloaded = ManualResolution(dir: dir)..load();
      final first = reloaded.unresolved.value.first;
      expect(first.artist, 'Måneskin');
      expect(first.playlist, 'Weekly Exploration');
      expect(first.added, DateTime.utc(2026, 9, 25));
    });

    test('a pick and a skip are remembered and leave the list', () {
      final store = ManualResolution(dir: dir);
      final slave = _track('Måneskin', 'I Wanna Be Your Slave');
      final into = _track('Chase Atlantic', 'Into It');
      store.addUnresolved([slave, into]);
      store.resolve(
        slave,
        'https://music.apple.com/in/song/1',
        artist: 'Jaydan Wolf',
        title: 'I Wanna Be Your Slave',
      );
      store.skip(into);
      expect(store.unresolved.value, isEmpty);

      final reloaded = ManualResolution(dir: dir);
      expect(
        reloaded.overrideFor('Måneskin', 'I Wanna Be Your Slave'),
        'https://music.apple.com/in/song/1',
      );
      expect(
        reloaded.pickedAs('Måneskin', 'I Wanna Be Your Slave')?.artist,
        'Jaydan Wolf',
      );
      expect(reloaded.overrideFor('Chase Atlantic', 'Into It'), skipOverride);
      expect(reloaded.overrideFor('Someone', 'Else'), isNull);
      // Decided tracks are never flagged again.
      reloaded.addUnresolved([slave, into]);
      expect(reloaded.unresolved.value, isEmpty);
      expect(
        jsonDecode(
          File('${dir.path}/resolved_overrides.json').readAsStringSync(),
        ),
        containsPair(overrideKey('Chase Atlantic', 'Into It'), 'SKIP'),
      );
    });

    test('a corrupt file costs the list, not the app', () {
      File('${dir.path}/unresolved_tracks.json').writeAsStringSync('{nope');
      final store = ManualResolution(dir: dir)..load();
      expect(store.unresolved.value, isEmpty);
      store.addUnresolved([_track('A', 'B')]);
      expect(store.unresolved.value, hasLength(1));
    });
  });

  test(
    'discovery uses a pick, leaves a skip out, and searches for neither',
    () async {
      manualResolution.resolve(
        _track('Alan Walker', 'On My Way'),
        'https://music.apple.com/in/album/x/2?i=3',
      );
      manualResolution.skip(_track('Chase Atlantic', 'Into It'));
      registerPlaylistSource(
        _Source(const [
          ExternalTrack(title: 'On My Way', artist: 'Alan Walker'),
          ExternalTrack(title: 'Into It', artist: 'Chase Atlantic'),
        ]),
      );
      addTearDown(playlistSources.clear);

      final tracks = await resolveDiscoveryTracks(
        const ExternalPlaylist(sourceId: 'fake', id: 'p', title: 'P'),
        storefront: 'in',
      );
      expect(tracks![0].appleUrl, 'https://music.apple.com/in/album/x/2?i=3');
      expect(tracks[0].isResolved, isTrue);
      expect(tracks[1].isResolved, isFalse);
      expect(tracks[1].userSkipped, isTrue);
      expect(tracks[1].warning, 'Skipped: you chose no match');
    },
  );

  test('a pick takes the source track\'s place in the linked playlist', () {
    File('${appSupportDir.path}/linked_playlists.json').writeAsStringSync(
      jsonEncode({
        'Weekly Exploration': [
          {'artist': 'Cradles', 'title': 'Sub Urban'},
          {'artist': 'Måneskin', 'title': 'I Wanna Be Your Slave'},
        ],
      }),
    );
    expect(
      linkedPlaylists.replaceTrack(
        'Weekly Exploration',
        const LinkedTrack('Måneskin', 'I Wanna Be Your Slave'),
        const LinkedTrack('Jaydan Wolf', 'I Wanna Be Your Slave'),
      ),
      isTrue,
    );
    expect(
      linkedPlaylists
          .tracksOf('Weekly Exploration')!
          .map((t) => '${t.artist} - ${t.title}'),
      ['Cradles - Sub Urban', 'Jaydan Wolf - I Wanna Be Your Slave'],
    );
    expect(
      linkedPlaylists.replaceTrack(
        'Not linked',
        const LinkedTrack('a', 'b'),
        const LinkedTrack('c', 'd'),
      ),
      isFalse,
    );
  });

  group('candidates', () {
    test('a search row maps to what the picker shows', () {
      final song = appleCandidateFromRow({
        'wrapperType': 'track',
        'kind': 'song',
        'trackId': 1440833098,
        'trackName': 'I Wanna Be Your Slave',
        'artistName': 'Jaydan Wolf',
        'collectionName': 'Covers, Vol. 1',
        'trackViewUrl': 'https://music.apple.com/in/album/x/1?i=1440833098',
        'previewUrl': 'https://audio.invalid/p.m4a',
        'artworkUrl100': 'https://is1.invalid/100x100bb.jpg',
        'releaseDate': '2021-06-04T12:00:00Z',
        'trackTimeMillis': 173240,
      })!;
      expect(song.title, 'I Wanna Be Your Slave');
      expect(song.artist, 'Jaydan Wolf');
      expect(song.album, 'Covers, Vol. 1');
      expect(song.year, '2021');
      expect(song.duration, const Duration(milliseconds: 173240));
      expect(song.artwork, 'https://is1.invalid/300x300bb.jpg');
      expect(song.match.url, song.url);
      expect(song.match.trackId, '1440833098');
    });

    test('rows that are not songs are left out', () {
      expect(
        appleCandidateFromRow({
          'kind': 'music-video',
          'trackName': 'x',
          'trackViewUrl': 'u',
        }),
        isNull,
      );
      expect(appleCandidateFromRow({'trackName': 'no link'}), isNull);
    });

    test('song ids come out of song and album links only', () {
      expect(
        appleSongIdFromUrl('https://music.apple.com/in/album/x/123?i=456'),
        '456',
      );
      expect(
        appleSongIdFromUrl('https://music.apple.com/in/song/into-it/789'),
        '789',
      );
      expect(
        appleSongIdFromUrl('https://music.apple.com/in/album/x/123'),
        isNull,
      );
      expect(appleSongIdFromUrl('https://example.com/song/1'), isNull);
      expect(appleSongIdFromUrl('not a link'), isNull);
    });
  });
}
