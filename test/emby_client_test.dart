import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/emby_client.dart';

class TestEmbyClient extends EmbyClient {
  TestEmbyClient({
    required super.baseUrl,
    required super.username,
    required super.password,
  });

  Dio get clientDio => dio;
}

void main() {
  test('EmbyClient normalizes baseUrl and sets default headers', () {
    final client = TestEmbyClient(
      baseUrl: 'https://emby.local:8096/',
      username: 'user',
      password: 'password',
    );

    expect(client.clientDio.options.baseUrl, 'https://emby.local:8096');
    expect(client.clientDio.options.headers['Content-Type'], 'application/json');
    expect(
      client.clientDio.options.headers['X-Emby-Authorization'],
      contains('MediaBrowser Client="Soiboi"'),
    );
    expect(
      client.clientDio.options.headers['X-Emby-Authorization'],
      contains('Version="$versionNumber"'),
    );
  });

  test('getStreamUrl constructs audio stream endpoint with credentials', () {
    final client = TestEmbyClient(
      baseUrl: 'https://emby.local:8096',
      username: 'user',
      password: 'password',
    );
    client.userId = 'user_123';
    client.accessToken = 'token_abc';

    final streamUrl = client.getStreamUrl('item_456');

    expect(
      streamUrl,
      'https://emby.local:8096/Audio/item_456/stream?UserId=user_123&api_key=token_abc&static=true',
    );
  });
}
