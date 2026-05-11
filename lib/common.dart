import 'dart:async';
import 'dart:io';

import 'package:desktop_multi_window/desktop_multi_window.dart';
import 'package:flutter/material.dart';

import 'package:particle_music/audio_handler.dart';
import 'package:particle_music/history.dart';
import 'package:particle_music/library.dart';
import 'package:particle_music/logger.dart';
import 'package:particle_music/common/widgets/lyrics.dart';
import 'package:particle_music/my_audio_metadata.dart';
import 'package:particle_music/playlists.dart';
import 'package:particle_music/setting_manager.dart';
import 'package:screen_corner_radius/screen_corner_radius.dart';
import 'package:webdav_client/webdav_client.dart';

const String versionNumber = '2.3.0';

// ===================================== App =====================================

late final Directory appDocs;
late final Directory appSupportDir;
late final Directory tmpDir;
late final Directory folderConfigDir;
late final Directory playlistConfigDir;
late final Directory cacheConfigDir;

final isMobile = Platform.isAndroid || Platform.isIOS;
const isTV = bool.fromEnvironment('TV', defaultValue: false);

late final ScreenRadius? screenRadius;

// ===================================== Library =====================================

late Library library;

final ValueNotifier<int> loadedCountNotifier = ValueNotifier(0);
final ValueNotifier<String> currentLoadingFolderNotifier = ValueNotifier('');

final ValueNotifier<bool> loadingLibraryNotifier = ValueNotifier(true);

final ValueNotifier<bool> loadingNavidromeNotifier = ValueNotifier(false);

final ValueNotifier<bool> recursiveScanNotifier = ValueNotifier(false);

// ===================================== MiniMode =====================================

final miniModeNotifier = ValueNotifier(false);

// ===================================== Sidebar =====================================

final ValueNotifier<String> sidebarHighlighLabel = ValueNotifier('');

// ===================================== DesktopMainPage =====================================

MyAudioMetadata? backgroundSong;

// ===================================== Lyrics =====================================

LyricLine? currentLyricLine;
bool currentLyricLineIsKaraoke = false;

double lyricsFontSizeOffset = 0;
final lyricsFontSizeOffsetChangeNotifier = ValueNotifier(0);
final updateLyricsNotifier = ValueNotifier(0);

bool displayLyricsPage = false;

// ===================================== Settings =====================================

ValueNotifier<bool> vibrationOnNoitifier = ValueNotifier(true);

ValueNotifier<bool> sleepTimerOnNotifier = ValueNotifier(false);
ValueNotifier<int> remainTimesNotifier = ValueNotifier(0);
ValueNotifier<bool> pauseAfterCompletedNotifier = ValueNotifier(false);
bool needPause = false;
Timer? pauseTimer;

final playlistsUseLargePictureNotifier = ValueNotifier(true);

final ValueNotifier<Locale?> localeNotifier = ValueNotifier(null);

final autoPlayOnStartupNotifier = ValueNotifier(false);

final exitOnCloseNotifier = ValueNotifier(false);

late SettingManager settingManager;

String webdavUsername = '';
String webdavPassword = '';
String webdavBaseUrl = '';

Client? webdavClient;
// ===================================== Colors =====================================

final mainPageThemeNotifier = ValueNotifier(0);
final lyricsPageThemeNotifier = ValueNotifier(0);

Color backgroundCoverArtColor = Colors.grey;
Color currentCoverArtColor = Colors.grey;

// ===================================== Images =====================================

// ===================================== AudioHandler =====================================

late MyAudioHandler audioHandler;

List<MyAudioMetadata> playQueue = [];

final ValueNotifier<MyAudioMetadata?> currentSongNotifier = ValueNotifier(null);
final ValueNotifier<bool> isPlayingNotifier = ValueNotifier(false);
final ValueNotifier<int> playModeNotifier = ValueNotifier(0);
final ValueNotifier<double> volumeNotifier = ValueNotifier(0.3);

// ===================================== Playlist =====================================

late PlaylistsManager playlistsManager;

// ===================================== DesktopLyrics =====================================

WindowController? lyricsWindowController;
bool lyricsWindowVisible = false;

LyricLine? desktopLyricLine;
Duration desktopLyricsCurrentPosition = Duration.zero;
bool desktopLyricsIsKaraoke = false;

final updateDesktopLyricsNotifier = ValueNotifier(0);

final showDesktopLrcOnAndroidNotifier = ValueNotifier(false);
final lockDesktopLrcOnAndroidNotifier = ValueNotifier(false);

final verticalDesktopLrcNotifier = ValueNotifier(false);

// ===================================== Keyboard =====================================

bool shiftIsPressed = false;
bool ctrlIsPressed = false;

// ===================================== Windows =====================================

ValueNotifier<bool> isMaximizedNotifier = ValueNotifier(false);
ValueNotifier<bool> isFullScreenNotifier = ValueNotifier(false);

// ===================================== History =====================================

final History history = History();

final rankingChangeNotifier = ValueNotifier(0);
final recentlyChangeNotifier = ValueNotifier(0);

// ===================================== Logger =====================================

final logger = Logger();
