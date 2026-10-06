import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/webdav_client.dart';

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

const _sampleWebDavXml = '''<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/webdav/Music/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype><d:collection/></d:resourcetype>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/webdav/Music/Rock/</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype><d:collection/></d:resourcetype>
        <d:getlastmodified>Sun, 06 Oct 2024 10:00:00 GMT</d:getlastmodified>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/webdav/Music/song1.mp3</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype/>
        <d:getlastmodified>Sun, 06 Oct 2024 10:05:00 GMT</d:getlastmodified>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>''';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;
  late WebDavClient client;

  setUpAll(() async {
    tempDir = Directory.systemTemp.createTempSync('webdav_test');
    try {
      appSupportDir = tempDir;
    } catch (_) {}
    try {
      await logger.init();
    } catch (_) {}
  });

  tearDownAll(() {
    try {
      tempDir.deleteSync(recursive: true);
    } catch (_) {}
  });

  setUp(() {
    client = WebDavClient(
      baseUrl: 'https://dav.example.com/remote.php/webdav/',
      username: 'testdavuser',
      password: 'davpassword123',
    );
  });

  group('WebDavClient initialization and URL parsing', () {
    test('extracts cleanBaseUrl and initialPath correctly', () {
      expect(client.cleanBaseUrl, 'https://dav.example.com');
      expect(client.initialPath, '/remote.php/webdav');
    });

    test('sets Basic auth header on client dio', () {
      final expectedAuth = 'Basic ${base64Encode(utf8.encode('testdavuser:davpassword123'))}';
      expect(client.headers['authorization'], expectedAuth);
    });
  });

  group('WebDavClient HTTP methods and XML parsing', () {
    test('ping sends PROPFIND with Depth 0 to initial path', () async {
      RequestOptions? recordedOptions;
      client.dio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return ResponseBody.fromString(
          '',
          207,
          headers: {
            Headers.contentTypeHeader: ['application/xml'],
          },
        );
      });

      final result = await client.ping();

      expect(result, isTrue);
      expect(recordedOptions?.method, 'PROPFIND');
      expect(recordedOptions?.path, '/remote.php/webdav');
      expect(recordedOptions?.headers['Depth'], '0');
    });

    test('ping returns false when response status is an error', () async {
      client.dio.httpClientAdapter = MockDioAdapter((options) async {
        return ResponseBody.fromString('', 401);
      });

      final result = await client.ping();

      expect(result, isFalse);
    });

    test('list requests directory items and parses files and collections', () async {
      RequestOptions? recordedOptions;
      client.dio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return ResponseBody.fromString(
          _sampleWebDavXml,
          207,
          headers: {
            Headers.contentTypeHeader: ['application/xml; charset=utf-8'],
          },
        );
      });

      final items = await client.list('/Music');

      expect(recordedOptions?.method, 'PROPFIND');
      expect(recordedOptions?.path, '/remote.php/webdav/Music/');
      expect(recordedOptions?.headers['Depth'], '1');
      expect(items.length, 2);

      final dirItem = items.firstWhere((e) => e.name == 'Rock');
      expect(dirItem.isDirectory, isTrue);
      expect(dirItem.path, '/remote.php/webdav/Music/Rock/');
      expect(dirItem.modified, isNotNull);

      final fileItem = items.firstWhere((e) => e.name == 'song1.mp3');
      expect(fileItem.isDirectory, isFalse);
      expect(fileItem.path, '/remote.php/webdav/Music/song1.mp3');
      expect(fileItem.modified, isNotNull);
    });

    test('mkdir issues MKCOL request', () async {
      RequestOptions? recordedOptions;
      client.dio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return ResponseBody.fromString('', 201);
      });

      final result = await client.mkdir('/remote.php/webdav/Music/Jazz');

      expect(result, isTrue);
      expect(recordedOptions?.method, 'MKCOL');
      expect(recordedOptions?.path, '/remote.php/webdav/Music/Jazz');
    });

    test('delete issues DELETE request', () async {
      RequestOptions? recordedOptions;
      client.dio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return ResponseBody.fromString('', 204);
      });

      final result = await client.delete('/remote.php/webdav/Music/old.mp3');

      expect(result, isTrue);
      expect(recordedOptions?.method, 'DELETE');
      expect(recordedOptions?.path, '/remote.php/webdav/Music/old.mp3');
    });

    test('move issues MOVE request with destination header', () async {
      RequestOptions? recordedOptions;
      client.dio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return ResponseBody.fromString('', 201);
      });

      final result = await client.move(
        source: '/remote.php/webdav/Music/temp.mp3',
        destination: 'Music/final.mp3',
      );

      expect(result, isTrue);
      expect(recordedOptions?.method, 'MOVE');
      expect(recordedOptions?.path, '/remote.php/webdav/Music/temp.mp3');
      expect(
        recordedOptions?.headers['Destination'],
        'https://dav.example.com/remote.php/webdav/Music/final.mp3',
      );
    });

    test('copy issues COPY request with destination header', () async {
      RequestOptions? recordedOptions;
      client.dio.httpClientAdapter = MockDioAdapter((options) async {
        recordedOptions = options;
        return ResponseBody.fromString('', 201);
      });

      final result = await client.copy(
        source: '/remote.php/webdav/Music/orig.mp3',
        destination: 'Music/copy.mp3',
      );

      expect(result, isTrue);
      expect(recordedOptions?.method, 'COPY');
      expect(recordedOptions?.path, '/remote.php/webdav/Music/orig.mp3');
      expect(
        recordedOptions?.headers['Destination'],
        'https://dav.example.com/remote.php/webdav/Music/copy.mp3',
      );
    });
  });

  group('WebDavClient error handling', () {
    test('handles DioException gracefully on ping failure', () async {
      client.dio.httpClientAdapter = MockDioAdapter((options) async {
        throw DioException(
          requestOptions: options,
          message: 'Connection timed out',
          type: DioExceptionType.connectionTimeout,
        );
      });

      final result = await client.ping();
      expect(result, isFalse);
    });

    test('handles DioException gracefully on list failure', () async {
      client.dio.httpClientAdapter = MockDioAdapter((options) async {
        throw DioException(
          requestOptions: options,
          message: 'Host unreachable',
        );
      });

      final items = await client.list('/Music');
      expect(items, isEmpty);
    });
  });
}
