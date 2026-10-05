import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/ai_service.dart';
import 'package:soiboi/base/widgets/ai_widgets.dart';
import 'package:soiboi/base/services/wrapper_service.dart';
import 'package:soiboi/base/widgets/lossless_setup.dart';
import 'package:soiboi/base/widgets/download_options.dart';
import 'package:soiboi/base/widgets/listenbrainz_form.dart';
import 'package:soiboi/layer/setup_wizard.dart';
import 'package:soiboi/layer/saved_download_logs.dart';
import 'package:soiboi/base/data/backup_service.dart';
import 'package:soiboi/base/data/config.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/theme/color_source.dart';
import 'package:soiboi/base/theme/dynamic_color.dart';
import 'package:soiboi/base/services/listenbrainz_service.dart';
import 'package:soiboi/base/services/cookie_store.dart' as cookie_store;
import 'package:soiboi/layer/apple_signin_layer.dart';
import 'package:soiboi/base/services/update_service.dart';
import 'package:soiboi/layer/download_queue_sheet.dart';
import 'package:soiboi/layer/update_sheet.dart';
import 'package:soiboi/layer/duplicates_flow.dart';
import 'package:soiboi/layer/storage_cleanup_sheet.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/asset_images.dart';
import 'package:soiboi/base/widgets/dialogs.dart';
import 'package:soiboi/base/services/center_toast.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/notification_service.dart';
import 'package:soiboi/base/services/system_ui_service.dart';
import 'package:soiboi/base/utils/common_utils.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/base/utils/source_type.dart';
import 'package:soiboi/base/widgets/connect_client_widget.dart';
import 'package:soiboi/base/widgets/equalizer.dart';
import 'package:soiboi/base/widgets/my_divider.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/base/widgets/manage_music_folders.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/portrait_view/sleep_timer.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/base/widgets/my_switch.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/widgets/app_icon.dart';
import 'package:soiboi/base/services/acoustic_service.dart';
import 'package:soiboi/base/widgets/icon_label.dart';
import 'package:soiboi/base/theme/motion.dart';

part 'settings_library.dart';
part 'settings_appearance.dart';
part 'settings_downloads.dart';
part 'settings_app.dart';

class SettingsList extends StatefulWidget {
  final double? iconSize;
  const SettingsList({super.key, this.iconSize});

  @override
  State<StatefulWidget> createState() => _SettingsListState();
}

/// What every settings section can reach: the icon size and the sliver
/// wrapper the theme tiles build with.
abstract class _SettingsBase extends State<SettingsList> {
  double? iconSize;

  Widget sliverBox(Widget child) => SliverToBoxAdapter(child: child);
}

class _SettingsListState extends _SettingsBase
    with
        _LibrarySettings,
        _AppearanceSettings,
        _DownloadSettings,
        _AppSettings {
  @override
  void initState() {
    super.initState();
    iconSize = widget.iconSize;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    bool isLandscape = !isTooNarrow(context);
    return CustomScrollView(
      slivers: [
        if (viewModeNotifier.value == .bigPicture)
          sliverBox(const SizedBox(height: 10)),

        if (isLandscape && viewModeNotifier.value != .bigPicture)
          sliverBox(
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),

              child: Focus(
                child: ListTile(
                  leading: AppIcon(settingImage, size: 50),
                  title: Text(
                    l10n.settings,
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                  ),
                  subtitle: Text(
                    l10n.settingCount(
                      Platform.isAndroid
                          ? 15
                          : Platform.isIOS
                          ? 14
                          : 13,
                    ),
                    style: TextStyle(fontSize: 12),
                  ),
                ),
              ),
            ),
          ),

        if (isLandscape && viewModeNotifier.value != .bigPicture)
          sliverBox(
            MyDivider(
              thickness: 0.5,
              height: 0.5,
              indent: 20,
              endIndent: 20,
              color: dividerColor,
            ),
          ),

        if (isLandscape && viewModeNotifier.value != .bigPicture)
          sliverBox(const SizedBox(height: 10)),

        if (Platform.isIOS && viewModeNotifier.value != .bigPicture)
          sliverBox(
            paddingIfNeed(isLandscape, premiumFeaturesListTile(context, l10n)),
          ),

        if (viewModeNotifier.value != .bigPicture)
          sliverBox(paddingIfNeed(isLandscape, setupWizardListTile(context))),

        sliverBox(
          paddingIfNeed(isLandscape, switchSourceTypeListTile(context, l10n)),
        ),

        sliverBox(
          paddingIfNeed(isLandscape, manageServersListTile(context, l10n)),
        ),

        if (isNotStreamSource)
          sliverBox(
            paddingIfNeed(
              isLandscape,
              selectMusicFoldersListTile(context, l10n),
            ),
          ),

        sliverBox(paddingIfNeed(isLandscape, syncListTile(context, l10n))),

        sliverBox(
          paddingIfNeed(isLandscape, cleanCacheListTile(context, l10n)),
        ),

        sliverBox(paddingIfNeed(isLandscape, flavourListTile(context, l10n))),
        sliverBox(paddingIfNeed(isLandscape, aiListTile(context))),

        sliverBox(
          paddingIfNeed(isLandscape, colorSourceListTile(context, l10n)),
        ),

        sliverBox(paddingIfNeed(isLandscape, themeListTile(context, l10n))),

        sliverBox(paddingIfNeed(isLandscape, languageListTile(context, l10n))),

        if (viewModeNotifier.value != .bigPicture)
          sliverBox(paddingIfNeed(isLandscape, fontListTile(context, l10n))),

        if (Platform.isIOS &&
            !isLandscape &&
            viewModeNotifier.value != .bigPicture)
          sliverBox(paddingIfNeed(isLandscape, drawerListTile(l10n))),

        if (isMobile && !isTV)
          sliverBox(paddingIfNeed(isLandscape, vibrationListTile(l10n))),

        if (isMobile)
          sliverBox(
            paddingIfNeed(
              isLandscape,
              sleepTimerListTile(context, l10n, iconSize: iconSize),
            ),
          ),

        sliverBox(paddingIfNeed(isLandscape, equalizerListTile(context, l10n))),

        if (Platform.isAndroid && !isTV)
          sliverBox(
            paddingIfNeed(isLandscape, immersiveWideLayoutListTile(l10n)),
          ),

        sliverBox(
          paddingIfNeed(isLandscape, appleAccountListTile(context, l10n)),
        ),

        sliverBox(
          paddingIfNeed(isLandscape, downloadQualityListTile(context, l10n)),
        ),

        sliverBox(paddingIfNeed(isLandscape, losslessListTile(context))),

        sliverBox(paddingIfNeed(isLandscape, widevineListTile(context, l10n))),

        sliverBox(paddingIfNeed(isLandscape, reduceMotionListTile())),
        sliverBox(paddingIfNeed(isLandscape, downloadLogsListTile(context))),
        if (notifications.supported)
          sliverBox(paddingIfNeed(isLandscape, notificationsListTile())),

        sliverBox(
          paddingIfNeed(isLandscape, listenBrainzListTile(context, l10n)),
        ),

        sliverBox(paddingIfNeed(isLandscape, lrclibListTile(l10n))),

        sliverBox(paddingIfNeed(isLandscape, downloadQueueListTile(context))),

        sliverBox(
          paddingIfNeed(isLandscape, downloadFolderListTile(context, l10n)),
        ),

        sliverBox(
          paddingIfNeed(isLandscape, analyseLibraryListTile(context, l10n)),
        ),

        sliverBox(paddingIfNeed(isLandscape, storageListTile(context))),
        sliverBox(paddingIfNeed(isLandscape, duplicatesListTile(context))),

        sliverBox(
          paddingIfNeed(isLandscape, backupLibraryListTile(context, l10n)),
        ),

        sliverBox(
          paddingIfNeed(isLandscape, restoreLibraryListTile(context, l10n)),
        ),

        sliverBox(paddingIfNeed(isLandscape, autoPlayOnStartupListTile(l10n))),

        if (!isMobile)
          sliverBox(
            paddingForLandscape(exitOnCloseListTile(l10n)),
          ), // always landscape style

        if (!Platform.isIOS)
          sliverBox(
            paddingIfNeed(isLandscape, checkUpdateListTile(context, l10n)),
          ),

        sliverBox(paddingIfNeed(isLandscape, viewLogListTile(context, l10n))),

        if (viewModeNotifier.value != .bigPicture)
          sliverBox(
            paddingIfNeed(
              isLandscape,
              ListTile(
                leading: AppIcon(infoImage, size: iconSize),
                title: Text(l10n.about),
                onTap: () {
                  layersManager.pushDetail('settings', 'about');
                },
              ),
            ),
          ),

        if (!isLandscape) sliverBox(const SizedBox(height: 100)),

        if (viewModeNotifier.value == .bigPicture)
          sliverBox(const SizedBox(height: 75)),
      ],
    );
  }

  Widget paddingIfNeed(bool isLandscape, Widget child) {
    return isLandscape ? paddingForLandscape(child) : child;
  }

  Widget paddingForLandscape(Widget child) {
    return Padding(
      padding: EdgeInsets.symmetric(
        horizontal: viewModeNotifier.value == .bigPicture ? 50 : 30,
      ),
      child: SmoothClipRRect(
        smoothness: 1,
        borderRadius: BorderRadius.circular(10),
        child: Material(color: Colors.transparent, child: child),
      ),
    );
  }
}
