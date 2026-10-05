import 'dart:convert';
import 'dart:io';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/config.dart';
import 'package:soiboi/base/services/navidrome_client.dart';
import 'package:soiboi/base/services/stream_client.dart';
import 'package:soiboi/base/services/webdav_client.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_config_test_');
    appSupportDir = tempDir;
  });

  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    webdavClient = null;
    streamClient = null;
    sourceType = SourceType.local;
    final file = File('${tempDir.path}/config.json');
    if (file.existsSync()) {
      file.deleteSync();
    }
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  test('load returns safely when config.json does not exist', () async {
    final cfg = Config();
    await cfg.load();

    expect(cfg.file.existsSync(), isFalse);
    expect(cfg.navidromeBaseUrl, isNull);
    expect(cfg.embyBaseUrl, isNull);
  });

  test('load parses webdav, navidrome, and emby configs and populates clients', () async {
    FlutterSecureStorage.setMockInitialValues({
      'webdav_password': 'secure_dav_pw',
      'navidrome_password': 'secure_navi_pw',
      'emby_password': 'secure_emby_pw',
    });

    final configFile = File('${tempDir.path}/config.json');
    await configFile.writeAsString(jsonEncode({
      'sourceType': 'webdav',
      'webdav': {
        'baseUrl': 'https://dav.example.com',
        'username': 'dav_user',
      },
      'navidrome': {
        'baseUrl': 'https://music.example.com',
        'username': 'navi_user',
      },
      'emby': {
        'baseUrl': 'https://emby.example.com',
        'username': 'emby_user',
      },
    }));

    final cfg = Config();
    await cfg.load();

    expect(sourceType, SourceType.webdav);
    expect(webdavClient, isNotNull);
    expect(webdavClient!.baseUrl, 'https://dav.example.com');
    expect(webdavClient!.username, 'dav_user');
    expect(webdavClient!.password, 'secure_dav_pw');

    expect(cfg.navidromeBaseUrl, 'https://music.example.com');
    expect(cfg.navidromeUsername, 'navi_user');
    expect(cfg.navidromePassword, 'secure_navi_pw');

    expect(cfg.embyBaseUrl, 'https://emby.example.com');
    expect(cfg.embyUsername, 'emby_user');
    expect(cfg.embyPassword, 'secure_emby_pw');
  });

  test('sourceType infers order if not specified in config', () async {
    FlutterSecureStorage.setMockInitialValues({});

    final configFile = File('${tempDir.path}/config.json');
    await configFile.writeAsString(jsonEncode({
      'navidrome': {
        'baseUrl': 'https://music.example.com',
        'username': 'navi_user',
        'password': 'plain_password',
      },
    }));

    final cfg = Config();
    await cfg.load();

    expect(sourceType, SourceType.navidrome);
    expect(cfg.navidromePassword, 'plain_password');
    expect(streamClient, isA<NavidromeClient>());
  });

  test('save writes credentials to secure storage and config.json without plaintext passwords', () async {
    final cfg = Config();
    cfg.file = File('${tempDir.path}/config.json');

    sourceType = SourceType.webdav;
    webdavClient = WebDavClient(
      baseUrl: 'https://dav.example.com',
      username: 'dav_user',
      password: 'dav_secret_password',
    );
    cfg.navidromeBaseUrl = 'https://navi.example.com';
    cfg.navidromeUsername = 'navi_user';
    cfg.navidromePassword = 'navi_secret_password';

    cfg.embyBaseUrl = 'https://emby.example.com';
    cfg.embyUsername = 'emby_user';
    cfg.embyPassword = 'emby_secret_password';

    await cfg.save();

    final savedJson = jsonDecode(await cfg.file.readAsString()) as Map<String, dynamic>;
    expect(savedJson['sourceType'], 'webdav');
    expect(savedJson['webdav']['baseUrl'], 'https://dav.example.com');
    expect(savedJson['webdav']['username'], 'dav_user');
    // When secure write succeeds, plaintext passwords should not be in the file
    expect(savedJson['webdav'].containsKey('password'), isFalse);
    expect(savedJson['navidrome'].containsKey('password'), isFalse);
    expect(savedJson['emby'].containsKey('password'), isFalse);

    const storage = FlutterSecureStorage();
    expect(await storage.read(key: 'webdav_password'), 'dav_secret_password');
    expect(await storage.read(key: 'navidrome_password'), 'navi_secret_password');
    expect(await storage.read(key: 'emby_password'), 'emby_secret_password');
  });

  test('savePremium writes isPremium true to secure storage', () async {
    final cfg = Config();
    await cfg.savePremium();

    const storage = FlutterSecureStorage();
    expect(await storage.read(key: 'isPremium'), 'true');
  });
}
