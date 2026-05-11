import 'dart:convert';
import 'dart:io';

import 'package:particle_music/base/app.dart';
import 'package:particle_music/base/services/navidrome_client.dart';
import 'package:particle_music/base/services/webdav_client.dart';

final config = Config();

class Config {
  late final File file;

  Future<void> load() async {
    file = File("${appSupportDir.path}/config.json");
    if (!(file.existsSync())) {
      return;
    }

    final content = await file.readAsString();

    final Map<String, dynamic> map =
        jsonDecode(content) as Map<String, dynamic>;

    final navidromeMap = map['navidrome'] as Map<String, dynamic>?;
    if (navidromeMap != null) {
      navidromeClient = NavidromeClient(
        baseUrl: navidromeMap['baseUrl'],
        username: navidromeMap['username'],
        password: navidromeMap['password'],
      );
    }

    final webdavMap = map['webdav'] as Map<String, dynamic>?;
    if (webdavMap != null) {
      webdavClient = WebDavClient(
        baseUrl: webdavMap['baseUrl'],
        username: webdavMap['username'],
        password: webdavMap['password'],
      );
    }
  }

  void save() {
    file.writeAsStringSync(
      jsonEncode({
        if (navidromeClient != null)
          'navidrome': {
            'baseUrl': navidromeClient!.baseUrl,
            'username': navidromeClient!.username,
            'password': navidromeClient!.password,
          },

        if (webdavClient != null)
          'webdav': {
            'baseUrl': webdavClient!.baseUrl,
            'username': webdavClient!.username,
            'password': webdavClient!.password,
          },
      }),
    );
  }
}
