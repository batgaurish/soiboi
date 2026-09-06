import 'dart:convert';
import 'dart:io';

import 'package:cupertino_ui/cupertino_ui.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/config.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/font_manager.dart';
import 'package:soiboi/base/services/bookmark_service.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/history.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:soiboi/base/services/picture_load_scheduler.dart';
import 'package:soiboi/base/services/picture_service.dart';
import 'package:soiboi/base/utils/common_utils.dart';
import 'package:soiboi/base/utils/path.dart';
import 'package:soiboi/layer/layers_manager.dart';

bool firstLaunch = true;

class Loader {
  static bool _busy = false;

  static bool get busy => _busy;

  static final stateNotifier = ValueNotifier(0);

  static Future<void> init() async {
    if (Platform.isAndroid) {
      await Permission.storage.request();
      await Permission.audio.request();
    } else if (Platform.isIOS) {
      await BookmarkService.init();
      File keepFile = File('${appDocsDir.path}/soiboi.keep');
      if (!keepFile.existsSync()) {
        keepFile.createSync();
      }
    }

    _handleLegacyVersionData();

    await config.load();
    await setting.load();

    colorManager.updateColors();

    await fontManager.loadFonts();
  }

  static Future<void> load() async {
    _busy = true;
    stateNotifier.value++;

    await library.load();

    audioHandler.loadStates();

    history.load();

    await playlistManager.load();

    if (isNotStreamSource) {
      artistAlbumManager.classify();
    }
    _busy = false;
    stateNotifier.value++;
  }

  static Future<void> reload() async {
    if (viewModeNotifier.value == .normal) {
      layersManager.clearDataLayers();
    }
    pictureLoadScheduler.clear();
    audioHandler.justClear();

    globalPictureList = [];

    library = Library();
    artistAlbumManager = ArtistAlbumManager();
    history = History();

    await load();
  }

  static Future<void> sync() async {
    _busy = true;
    stateNotifier.value++;

    if (viewModeNotifier.value == .normal) {
      layersManager.clearDataLayers();
    }

    globalPictureList = [];

    artistAlbumManager = ArtistAlbumManager();

    history = History();

    await library.sync();

    audioHandler.sync();

    history.load();

    await playlistManager.load();

    if (isNotStreamSource) {
      artistAlbumManager.classify();
    }

    _busy = false;
    stateNotifier.value++;
  }

  static Future<void> firstSync() async {
    _busy = true;
    stateNotifier.value++;

    layersManager.switchRootLayer('songs');

    artistAlbumManager = ArtistAlbumManager();

    history = History();

    await library.sync();

    audioHandler.loadStates();

    history.load();

    await playlistManager.load();

    if (isNotStreamSource) {
      artistAlbumManager.classify();
    }

    _busy = false;
    stateNotifier.value++;
  }

  static void _handleLegacyVersionData() {
    File tmp = File('${appSupportDir.path}/version.json');
    if (tmp.existsSync()) {
      firstLaunch = false;
      if (compareVersion('4.0.1', jsonDecode(tmp.readAsStringSync())) > 0) {
        File playlistsFile = File(
          "${getPlaylistConfigPath(.local)}/soiboi_playlists.json",
        );
        if (playlistsFile.existsSync()) {
          final content = playlistsFile.readAsStringSync();
          final list = jsonDecode(content) as List;
          if (list.isNotEmpty && list[0] == 'Favorite') {
            playlistsFile.writeAsStringSync(jsonEncode(list.skip(1).toList()));
          }
        }

        playlistsFile = File(
          "${getPlaylistConfigPath(.webdav)}/soiboi_playlists.json",
        );
        if (playlistsFile.existsSync()) {
          final content = playlistsFile.readAsStringSync();
          final list = jsonDecode(content) as List;
          if (list.isNotEmpty && list[0] == 'Favorite') {
            playlistsFile.writeAsStringSync(jsonEncode(list.skip(1).toList()));
          }
        }

        Directory tmpDir = Directory('${appSupportDir.path}/subsonic');
        if (tmpDir.existsSync()) {
          tmpDir.deleteSync(recursive: true);
        }

        tmpDir = Directory('${appSupportDir.path}/navidrome');
        if (tmpDir.existsSync()) {
          tmpDir.deleteSync(recursive: true);
        }

        tmpDir = Directory('${appSupportDir.path}/emby');
        if (tmpDir.existsSync()) {
          tmpDir.deleteSync(recursive: true);
        }
      }
    }
    tmp.writeAsStringSync(jsonEncode(versionNumber));
  }
}
