import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/navidrome_client.dart';

class TestNavidromeClient extends NavidromeClient {
  TestNavidromeClient({
    required super.baseUrl,
    required super.username,
    required super.password,
  });

  Dio get clientDio => dio;
  Map<String, dynamic> testParams([Map<String, dynamic>? extra]) => params(extra);
}

class MockDioAdapter implements HttpClientAdapter {
  final Future<ResponseBody> Function(RequestOptions options) handler;

  MockDioAdapter(this.handler);

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    return handler(options);
  }

  @override
  void close({bool force = false}) {}
}

ResponseBody _subsonicOk(Map<String, dynamic> payload) {
  final body = {
    'subsonic-response': {
      'status': 'ok',
      'version': '1.16.1',
      ...payload,
    },
  };
  return ResponseBody.fromString(
    jsonEncode(body),
    200,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );
}

ResponseBody _subsonicError(int code, String message) {
  final body = {
    'subsonic-response': {
      'status': 'failed',
      'version': '1.16.1',
      'error': {'code': code, 'message': message},
    },
  };
  return ResponseBody.fromString(
    jsonEncode(body),
    200,
    headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    },
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;
  late TestNavidromeClient client;

  setUpAll(() async {
    tempDir = Directory.systemTemp.createTempSync('navidrome_test');
    try {
      appSupportDir = tempDir;
    } catch (_) {}
    try {
      await logger.init();
    } catch (_) {}
  });

  tearDownAll(() {
    sourceType = SourceType.local;
    try {
      tempDir.deleteSync(recursive: true);
    } catch (_) {}
  });

  setUp(() {
    sourceType = SourceType.navidrome;
    client = TestNavidromeClient(
      baseUrl: 'https://music.example.com',
      username: 'testuser',
      password: 'secretpassword',
    );
  });

  group('NavidromeClient credential and parameter generation', () {
    test('buildParams generates valid Subsonic credentials and salt', () {
      final params = client.testParams({'customKey': 'customVal'});

      expect(params['u'], 'testuser');
      expect(params['v'], '1.16.1');
      expect(params['c'], 'Soiboi');
      expect(params['f'], 'json');
      expect(params['customKey'], 'customVal');

      final salt = params['s'] as String;
      final token = params['t'] as String;
      expect(salt.length, 8);

      final expectedToken = md5.convert(utf8.encode('secretpassword$salt')).toString();
      expect(token, expectedToken);
    });

    test('getStreamUrl generates correct subsonic stream URI with auth', () {
      final streamUrl = client.getStreamUrl('song_123');
      final uri = Uri.parse(streamUrl);

      expect(uri.scheme, 'https');
      expect(uri.host, 'music.example.com');
      expect(uri.path, '/rest/stream.view');
      expect(uri.queryParameters['id'], 'song_123');
      expect(uri.queryParameters['u'], 'testuser');
      expect(uri.queryParameters.containsKey('t'), isTrue);
      expect(uri.queryParameters.containsKey('s'), isTrue);
    });
  });

  group('NavidromeClient request execution and response parsing', () {
    test('ping returns true on subsonic status ok', () async {
      RequestOptions? recordedOptions;
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return _subsonicOk({});
      });

      final result = await client.ping();

      expect(result, isTrue);
      expect(recordedOptions?.path, '/rest/ping.view');
      expect(recordedOptions?.queryParameters['u'], 'testuser');
    });

    test('ping returns false on subsonic status failed', () async {
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        return _subsonicError(40, 'Wrong username or password');
      });

      final result = await client.ping();

      expect(result, isFalse);
    });

    test('searchSongs sends correct query parameters and parses songs', () async {
      RequestOptions? recordedOptions;
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return _subsonicOk({
          'searchResult3': {
            'song': [
              {
                'id': 's1',
                'title': 'Track One',
                'artist': 'Artist A',
                'album': 'Album A',
                'duration': 180,
                'path': 'Artist A/Album A/01 Track One.mp3',
              },
            ],
          },
        });
      });

      final songs = await client.searchSongs('track', 20, 5);

      expect(recordedOptions?.path, '/rest/search3.view');
      expect(recordedOptions?.queryParameters['query'], 'track');
      expect(recordedOptions?.queryParameters['songCount'], 20);
      expect(recordedOptions?.queryParameters['songOffset'], 5);
      expect(songs, isNotNull);
      expect(songs!.length, 1);
      expect(songs.first.title, 'Track One');
      expect(songs.first.id, 's1');
    });

    test('getArtistList parses indexed artist records', () async {
      RequestOptions? recordedOptions;
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return _subsonicOk({
          'artists': {
            'index': [
              {
                'name': 'A',
                'artist': [
                  {'id': 'art1', 'name': 'Artist Alpha', 'coverArt': 'cov1'},
                  {'id': 'art2', 'name': 'Artist Beta', 'coverArt': 'cov2'},
                ],
              },
            ],
          },
        });
      });

      final artists = await client.getArtistList();

      expect(recordedOptions?.path, '/rest/getArtists.view');
      expect(artists, isNotNull);
      expect(artists!.length, 2);
      expect(artists.first.name, 'Artist Alpha');
      expect(artists.first.id, 'art1');
      expect(artists[1].name, 'Artist Beta');
    });

    test('getArtistAlbumList parses artist albums', () async {
      RequestOptions? recordedOptions;
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return _subsonicOk({
          'artist': {
            'id': 'art1',
            'name': 'Artist Alpha',
            'album': [
              {'id': 'alb1', 'name': 'First Album', 'coverArt': 'cov_alb1'},
            ],
          },
        });
      });

      final albums = await client.getArtistAlbumList('art1');

      expect(recordedOptions?.path, '/rest/getArtist.view');
      expect(recordedOptions?.queryParameters['id'], 'art1');
      expect(albums, isNotNull);
      expect(albums!.length, 1);
      expect(albums.first.name, 'First Album');
      expect(albums.first.id, 'alb1');
    });

    test('getArtistSongs collects songs across albums', () async {
      int requestCount = 0;
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        requestCount++;
        if (options.path == '/rest/getArtist.view') {
          return _subsonicOk({
            'artist': {
              'id': 'art1',
              'name': 'Artist Alpha',
              'album': [
                {'id': 'alb1', 'name': 'Album 1'},
                {'id': 'alb2', 'name': 'Album 2'},
              ],
            },
          });
        }
        if (options.path == '/rest/getAlbum.view') {
          final albumId = options.queryParameters['id'];
          return _subsonicOk({
            'album': {
              'id': albumId,
              'name': 'Album $albumId',
              'song': [
                {
                  'id': 'song_$albumId',
                  'title': 'Song for $albumId',
                  'artist': 'Artist Alpha',
                  'album': 'Album $albumId',
                  'duration': 200,
                },
              ],
            },
          });
        }
        return _subsonicError(500, 'Unknown');
      });

      final songs = await client.getArtistSongs('art1');

      expect(songs, isNotNull);
      expect(songs!.length, 2);
      expect(requestCount, 3);
      expect(songs[0].id, 'song_alb1');
      expect(songs[1].id, 'song_alb2');
    });

    test('getAlbumList requests with sort type and offset', () async {
      RequestOptions? recordedOptions;
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return _subsonicOk({
          'albumList2': {
            'album': [
              {'id': 'alb2', 'name': 'Second Album', 'coverArt': 'cov_alb2', 'year': 2024},
            ],
          },
        });
      });

      final albums = await client.getAlbumList(10, type: 'newest');

      expect(recordedOptions?.path, '/rest/getAlbumList2.view');
      expect(recordedOptions?.queryParameters['type'], 'newest');
      expect(recordedOptions?.queryParameters['offset'], 10);
      expect(recordedOptions?.queryParameters['size'], 500);
      expect(albums, isNotNull);
      expect(albums!.length, 1);
      expect(albums.first.name, 'Second Album');
    });

    test('getAlbumSongs parses album track metadata', () async {
      RequestOptions? recordedOptions;
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return _subsonicOk({
          'album': {
            'id': 'alb1',
            'name': 'Album One',
            'song': [
              {
                'id': 's10',
                'title': 'Song Ten',
                'artist': 'Artist Alpha',
                'album': 'Album One',
                'duration': 210,
              },
            ],
          },
        });
      });

      final songs = await client.getAlbumSongs('alb1');

      expect(recordedOptions?.path, '/rest/getAlbum.view');
      expect(recordedOptions?.queryParameters['id'], 'alb1');
      expect(songs, isNotNull);
      expect(songs!.length, 1);
      expect(songs.first.title, 'Song Ten');
    });

    test('getPictureBytes returns binary Uint8List', () async {
      final sampleBytes = Uint8List.fromList([1, 2, 3, 4, 5]);
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        return ResponseBody.fromBytes(
          sampleBytes,
          200,
          headers: {
            Headers.contentTypeHeader: ['image/jpeg'],
          },
        );
      });

      final result = await client.getPictureBytes('song_cover');

      expect(result, isNotNull);
      expect(result, equals(sampleBytes));
    });

    test('getLyricsById parses structured lyrics timestamps', () async {
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        return _subsonicOk({
          'lyricsList': {
            'structuredLyrics': [
              {
                'line': [
                  {'start': 1500, 'value': 'Hello world'},
                  {'start': 65200, 'value': 'Second line'},
                ],
              },
            ],
          },
        });
      });

      final lrc = await client.getLyricsById('song_lyric_1');

      expect(lrc, contains('[00:01.500]Hello world'));
      expect(lrc, contains('[01:05.200]Second line'));
    });

    test('getLyricsById falls back to plain lyrics string', () async {
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        return _subsonicOk({
          'lyrics': {'value': 'Plain text lyrics content'},
        });
      });

      final lrc = await client.getLyricsById('song_lyric_plain');

      expect(lrc, 'Plain text lyrics content');
    });

    test('scrobble issues request to rest/scrobble.view', () async {
      RequestOptions? recordedOptions;
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return _subsonicOk({});
      });

      final success = await client.scrobble('song_scrobble_1');

      expect(success, isTrue);
      expect(recordedOptions?.path, '/rest/scrobble.view');
      expect(recordedOptions?.queryParameters['id'], 'song_scrobble_1');
    });
  });

  group('NavidromeClient error handling', () {
    test('safeRequest handles DioException gracefully and returns null', () async {
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        throw DioException(
          requestOptions: options,
          message: 'Connection refused by server',
          type: DioExceptionType.connectionError,
        );
      });

      final pingResult = await client.ping();
      expect(pingResult, isFalse);

      final searchResult = await client.searchSongs('errorQuery', 10, 0);
      expect(searchResult, isNull);
    });

    test('safeRequest handles Subsonic error response and returns null', () async {
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        return _subsonicError(40, 'Token authentication failed');
      });

      final pingResult = await client.ping();
      expect(pingResult, isFalse);

      final albumList = await client.getAlbumList(0);
      expect(albumList, isNull);
    });

    test('safeRequest handles unexpected generic exception gracefully', () async {
      client.clientDio.httpClientAdapter = MockDioAdapter((options) async {
        throw Exception('Unexpected network glitch');
      });

      final pingResult = await client.ping();
      expect(pingResult, isFalse);
    });
  });
}
