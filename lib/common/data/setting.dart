import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:particle_music/common/audio_handler.dart';
import 'package:particle_music/common/data/playlist.dart';
import 'package:particle_music/common/utils/interaction.dart';
import 'package:particle_music/common/utils/webdav_client.dart';
import 'package:particle_music/common/widgets/lyric_list_view.dart';
import 'package:particle_music/common/data/artist_album.dart';
import 'package:particle_music/common/app.dart';
import 'package:particle_music/common/widgets/manage_music_folders.dart';
import 'package:particle_music/common/utils/navidrome_client.dart';

final exitOnCloseNotifier = ValueNotifier(false);

final setting = Setting();

class Setting {
  late final File file;

  Future<void> load() async {
    file = File("${appSupportDir.path}/setting.json");
    if (!(file.existsSync())) {
      save();
      return;
    }

    final content = await file.readAsString();

    final Map<String, dynamic> json =
        jsonDecode(content) as Map<String, dynamic>;

    artistAlbumManager.loadSetting(json);

    playlistManager.useLargePictureNotifier.value =
        json['playlistsUseLargePicture'] as bool? ??
        playlistManager.useLargePictureNotifier.value;

    vibrationOnNoitifier.value =
        json['vibrationOn'] as bool? ?? vibrationOnNoitifier.value;

    final languageCode = json['language'] as String? ?? '';

    if (languageCode.isNotEmpty) {
      localeNotifier.value = Locale(languageCode);
    }

    autoPlayOnStartupNotifier.value =
        json['autoPlayOnStartup'] as bool? ?? false;

    mainPageThemeNotifier.value = ThemeType.values.firstWhere(
      (e) => e.name == json['mainPageTheme'],
      orElse: () => ThemeType.vivid,
    );

    lyricsPageThemeNotifier.value = ThemeType.values.firstWhere(
      (e) => e.name == json['lyricsPageTheme'],
      orElse: () => ThemeType.vivid,
    );

    lyricsFontSizeOffsetNotifier.value =
        json['lyricsFontSizeOffset'] as double? ??
        lyricsFontSizeOffsetNotifier.value;

    exitOnCloseNotifier.value =
        json['exitOnClose'] as bool? ?? exitOnCloseNotifier.value;

    username = json['username'] as String? ?? '';
    password = json['password'] as String? ?? '';
    baseUrl = json['baseUrl'] as String? ?? '';

    webdavUsername = json['webdavUsername'] as String? ?? '';
    webdavPassword = json['webdavPassword'] as String? ?? '';
    webdavBaseUrl = json['webdavBaseUrl'] as String? ?? '';

    recursiveScanNotifier.value = json['recursiveScan'] as bool? ?? false;
  }

  void save() {
    file.writeAsStringSync(
      jsonEncode({
        ...artistAlbumManager.settingToMap(),

        'playlistsUseLargePicture':
            playlistManager.useLargePictureNotifier.value,

        'vibrationOn': vibrationOnNoitifier.value,
        'language': localeNotifier.value?.languageCode,

        'autoPlayOnStartup': autoPlayOnStartupNotifier.value,

        'mainPageTheme': mainPageThemeNotifier.value.name,
        'lyricsPageTheme': lyricsPageThemeNotifier.value.name,

        'lyricsFontSizeOffset': lyricsFontSizeOffsetNotifier.value,
        'exitOnClose': exitOnCloseNotifier.value,

        'username': username,
        'password': password,
        'baseUrl': baseUrl,

        'webdavUsername': webdavUsername,
        'webdavPassword': webdavPassword,
        'webdavBaseUrl': webdavBaseUrl,

        'recursiveScan': recursiveScanNotifier.value,
      }),
    );
  }
}
