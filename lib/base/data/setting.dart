import 'dart:convert';
import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/theme/color_source.dart';
import 'package:soiboi/base/theme/dynamic_color.dart';
import 'package:soiboi/base/services/listenbrainz_service.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/widgets/lyric_list_view.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/utils/path.dart';
import 'package:soiboi/base/widgets/manage_music_folders.dart';
import 'package:soiboi/portrait_view/portrait_view.dart';

final artistsIsListViewNotifier = ValueNotifier(true);
final artistsIsAscendingNotifier = ValueNotifier(true);
final artistsUseLargePictureNotifier = ValueNotifier(false);
final artistsRandomizeNotifier = ValueNotifier(false);

final albumsIsAscendingNotifier = ValueNotifier(true);
final albumsUseLargePictureNotifier = ValueNotifier(false);
final albumsRandomizeNotifier = ValueNotifier(false);

final playlistsUseLargePictureNotifier = ValueNotifier(true);

final exitOnCloseNotifier = ValueNotifier(false);

/// Download quality: the codec gamdl fetches, passed to
/// `--song-codec-priority` verbatim.
///
/// The values must be members of gamdl's own `SongCodec` enum or it rejects
/// the argument outright. A previous version offered "flac", which is not one
/// of them — Apple Music streams AAC and ALAC, and there is no FLAC to ask
/// for, so choosing it could only ever fail.
///
/// None of these require the user to supply anything: gamdl ships a Widevine
/// device (`gamdl/interface/wvd.py`) and falls back to it whenever no
/// `--wvd-path` is given, so ALAC works out of the box. See
/// [wvdPathNotifier] for when overriding that is worth doing.
final downloadCodecNotifier = ValueNotifier('aac');

/// Available codecs with human-readable labels.
const downloadCodecLabels = <String, String>{
  'aac': 'AAC (256-320 kbps)',
  'alac': 'ALAC (Apple Lossless)',
};

/// Overrides gamdl's bundled Widevine device with a .wvd of your own.
///
/// Optional, and normally unnecessary: gamdl carries its own L3 device and
/// uses it whenever this is unset. It exists because that shared device can
/// be revoked, at which point lossless downloads start failing and supplying
/// a private one is the only fix. Null = use the bundled device.
final wvdPathNotifier = ValueNotifier<String?>(null);

/// Whether to use a Widevine wrapper service instead of a local .wvd file.
final useWrapperNotifier = ValueNotifier(false);

/// URL of the Widevine wrapper service.
final wrapperUrlNotifier = ValueNotifier<String>('');

/// Fetch missing lyrics from LRCLIB. On by default: the pipeline already writes
/// sidecars at archive time, so this only fires for tracks it had nothing for.
final lrclibEnabledNotifier = ValueNotifier(true);

final setting = Setting();

class Setting {
  late final File file;

  Future<void> load() async {
    file = File("${appSupportDir.path}/setting.json");
    initFile(file, false);

    final json = await readJsonMapFile(file);

    artistsIsListViewNotifier.value =
        json['artistsIsList'] as bool? ?? artistsIsListViewNotifier.value;

    artistsIsAscendingNotifier.value =
        json['artistsIsAscend'] as bool? ?? artistsIsAscendingNotifier.value;

    artistsUseLargePictureNotifier.value =
        json['artistsUseLargePicture'] as bool? ??
        artistsUseLargePictureNotifier.value;

    albumsIsAscendingNotifier.value =
        json['albumsIsAscend'] as bool? ?? albumsIsAscendingNotifier.value;

    albumsUseLargePictureNotifier.value =
        json['albumsUseLargePicture'] as bool? ??
        albumsUseLargePictureNotifier.value;

    playlistsUseLargePictureNotifier.value =
        json['playlistsUseLargePicture'] as bool? ??
        playlistsUseLargePictureNotifier.value;

    endDrawerNotifier.value = json['endDrawer'] as bool? ?? Platform.isIOS;

    vibrationOnNoitifier.value =
        json['vibrationOn'] as bool? ?? vibrationOnNoitifier.value;

    final languageCode = json['language'] as String? ?? '';

    if (languageCode.isNotEmpty) {
      localeNotifier.value = Locale(languageCode);
    }

    immersiveWideLayoutNotifier.value =
        json['immersiveWideLayout'] as bool? ?? true;

    autoPlayOnStartupNotifier.value =
        json['autoPlayOnStartup'] as bool? ?? false;

    if (isPremiumNotifier.value) {
      fontFamilyNotifier.value = json['fontFamily'] as String?;
      fontFamilyFileNotifier.value = json['fontFamilyFile'] as String?;
    }

    flavourNotifier.value = Flavour.values.firstWhere(
      (e) => e.name == json['flavour'],
      orElse: () => Flavour.expressive,
    );

    mainPageThemeNotifier.value = ThemeType.values.firstWhere(
      (e) => e.name == json['mainPageTheme'],
      orElse: () => ThemeType.vivid,
    );

    if (!isPremiumNotifier.value && mainPageThemeNotifier.value == .vivid) {
      mainPageThemeNotifier.value = .light;
    }

    updateHoverFocusColor();

    lyricsPageThemeNotifier.value = ThemeType.values.firstWhere(
      (e) => e.name == json['lyricsPageTheme'],
      orElse: () => ThemeType.vivid,
    );

    lyricsFontSizeOffsetNotifier.value =
        json['lyricsFontSizeOffset'] as double? ??
        lyricsFontSizeOffsetNotifier.value;

    final savedSource = json['colorSource'] as String?;
    if (savedSource != null) {
      colorSourceNotifier.value = ColorSource.values.firstWhere(
        (e) => e.name == savedSource,
        orElse: () => ColorSource.off,
      );
    } else {
      // Migrates a pre-4.2.3 save, when this was a single "Follow system
      // colours" boolean and there was no prebuilt-palette option yet.
      colorSourceNotifier.value =
          (json['dynamicColorEnabled'] as bool? ?? false)
          ? ColorSource.matugen
          : ColorSource.off;
    }
    prebuiltPaletteNotifier.value = PrebuiltPalette.values.firstWhere(
      (e) => e.name == json['prebuiltPalette'],
      orElse: () => PrebuiltPalette.dracula,
    );
    matugenPathNotifier.value = json['matugenPath'] as String? ?? '';
    matugenSchemeNotifier.value =
        json['matugenScheme'] as String? ?? matugenSchemeNotifier.value;
    if (colorSourceNotifier.value == ColorSource.matugen) {
      // Loaded before the first colour resolution so startup paints correctly
      // rather than flashing the app's own colours first. Auto rather than
      // the file alone: the wallpaper may have changed since, and a user with
      // no matugen template at all should still get colours.
      await autoLoadDynamicPalette();
    }

    listenBrainzUserNotifier.value =
        json['listenBrainzUser'] as String? ?? '';

    lrclibEnabledNotifier.value =
        json['lrclibEnabled'] as bool? ?? true;

    exitOnCloseNotifier.value =
        json['exitOnClose'] as bool? ?? exitOnCloseNotifier.value;

    // Anything gamdl would reject falls back rather than persisting: an
    // earlier build offered "flac", which is not one of its codecs, so a
    // saved 'flac' would otherwise fail every download until found by hand.
    final savedCodec = json['downloadCodec'] as String?;
    downloadCodecNotifier.value =
        downloadCodecLabels.containsKey(savedCodec) ? savedCodec! : 'aac';
    wvdPathNotifier.value = json['wvdPath'] as String?;
    useWrapperNotifier.value = json['useWrapper'] as bool? ?? false;
    wrapperUrlNotifier.value = json['wrapperUrl'] as String? ?? '';

    recursiveScanNotifier.value = json['recursiveScan'] as bool? ?? false;
  }

  void save() {
    file.writeAsStringSync(
      jsonEncode({
        'artistsIsList': artistsIsListViewNotifier.value,
        'artistsIsAscend': artistsIsAscendingNotifier.value,
        'artistsUseLargePicture': artistsUseLargePictureNotifier.value,

        'albumsIsAscend': albumsIsAscendingNotifier.value,
        'albumsUseLargePicture': albumsUseLargePictureNotifier.value,

        'playlistsUseLargePicture': playlistsUseLargePictureNotifier.value,

        'endDrawer': endDrawerNotifier.value,

        'vibrationOn': vibrationOnNoitifier.value,
        'language': localeNotifier.value?.languageCode,

        'immersiveWideLayout': immersiveWideLayoutNotifier.value,
        'autoPlayOnStartup': autoPlayOnStartupNotifier.value,

        'fontFamily': fontFamilyNotifier.value,
        'fontFamilyFile': fontFamilyFileNotifier.value,

        'flavour': flavourNotifier.value.name,
        'mainPageTheme': mainPageThemeNotifier.value.name,
        'lyricsPageTheme': lyricsPageThemeNotifier.value.name,

        'lyricsFontSizeOffset': lyricsFontSizeOffsetNotifier.value,
        'colorSource': colorSourceNotifier.value.name,
        'prebuiltPalette': prebuiltPaletteNotifier.value.name,
        'matugenPath': matugenPathNotifier.value,
        'matugenScheme': matugenSchemeNotifier.value,
        'listenBrainzUser': listenBrainzUserNotifier.value,
        'lrclibEnabled': lrclibEnabledNotifier.value,
        'exitOnClose': exitOnCloseNotifier.value,

        'downloadCodec': downloadCodecNotifier.value,
        'wvdPath': wvdPathNotifier.value,
        'useWrapper': useWrapperNotifier.value,
        'wrapperUrl': wrapperUrlNotifier.value,

        'recursiveScan': recursiveScanNotifier.value,
      }),
    );
  }
}
