import 'dart:io';
import 'dart:ui' show PlatformDispatcher;

import 'package:material_ui/material_ui.dart';
import 'package:screen_corner_radius/screen_corner_radius.dart';

const String versionNumber = '1.1.2';

late final Directory appDocsDir;
late final Directory appSupportDir;
late final Directory tmpDir;
String? iosFileProviderStorage;

final isMobile = Platform.isAndroid || Platform.isIOS;
const isTV = bool.fromEnvironment('TV', defaultValue: false);

final globalNavigatorKey = GlobalKey<NavigatorState>();

late final ScreenRadius? screenRadius;

enum ThemeType { vivid, light, dark, custom }

/// Lifts a page's floating button clear of the phone's mini player and of the
/// global Ask AI button, which sits just above the mini player.
const kFabAboveMiniPlayer = EdgeInsets.only(bottom: 172);

/// The same on desktop: above the Ask AI button in the panel's corner.
const double kPanelFabBottom = 104;

final mainPageThemeNotifier = ValueNotifier(systemThemeType());

/// Light or dark, following the system.
ThemeType systemThemeType() =>
    PlatformDispatcher.instance.platformBrightness == Brightness.dark
    ? ThemeType.dark
    : ThemeType.light;

/// A colour source only paints light and dark pages, so choosing one moves a
/// vivid main page to the system's brightness; otherwise the choice would
/// look like it did nothing.
void leaveVividMainTheme() {
  if (mainPageThemeNotifier.value == ThemeType.vivid) {
    mainPageThemeNotifier.value = systemThemeType();
  }
}

final lyricsPageThemeNotifier = ValueNotifier(ThemeType.vivid);

final ValueNotifier<Locale?> localeNotifier = ValueNotifier(null);

enum SourceType { local, webdav, navidrome, emby }

SourceType sourceType = .local;

bool isStreamSource = false;
bool isNotStreamSource = !isStreamSource;

final ValueNotifier<String?> fontFamilyNotifier = ValueNotifier(null);

/// The file [fontFamilyNotifier]'s family was read from, on platforms that
/// need it registered before Skia will resolve the name. Null for fonts the
/// platform resolves by itself, and for imported fonts, which FontManager
/// already re-registers at startup.
final ValueNotifier<String?> fontFamilyFileNotifier = ValueNotifier(null);

final List<String> importedFonts = [];

final isPremiumNotifier = ValueNotifier(true);

enum ViewMode { normal, mini, bigPicture }

final viewModeNotifier = ValueNotifier(ViewMode.normal);

final immersiveWideLayoutNotifier = ValueNotifier(true);
