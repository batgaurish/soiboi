import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/audio_handler.dart';
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
import 'package:soiboi/layer/storage_cleanup_sheet.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/asset_images.dart';
import 'package:soiboi/base/services/emby_client.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/navidrome_client.dart';
import 'package:soiboi/base/services/stream_client.dart';
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
import 'package:soiboi/layer/premium_layer.dart';
import 'package:soiboi/portrait_view/portrait_view.dart';
import 'package:soiboi/portrait_view/sleep_timer.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/base/widgets/my_switch.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/widgets/app_icon.dart';
import 'package:soiboi/base/services/acoustic_service.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';

class SettingsList extends StatefulWidget {
  final double? iconSize;
  const SettingsList({super.key, this.iconSize});

  @override
  State<StatefulWidget> createState() => _SettingsListState();
}

class _SettingsListState extends State<SettingsList> {
  double? iconSize;
  // Analysis runs off a settings tap and can take minutes on a big library,
  // so its progress lives here rather than in the tile, which rebuilds.
  final _analyseStatus = ValueNotifier<String>('');
  bool _analysing = false;

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

        sliverBox(
          paddingIfNeed(isLandscape, widevineListTile(context, l10n)),
        ),

        sliverBox(
          paddingIfNeed(isLandscape, listenBrainzListTile(context, l10n)),
        ),

        sliverBox(paddingIfNeed(isLandscape, lrclibListTile(l10n))),

        sliverBox(
          paddingIfNeed(isLandscape, downloadQueueListTile(context)),
        ),

        sliverBox(
          paddingIfNeed(isLandscape, downloadFolderListTile(context, l10n)),
        ),

        sliverBox(
          paddingIfNeed(isLandscape, analyseLibraryListTile(context, l10n)),
        ),

        sliverBox(paddingIfNeed(isLandscape, storageListTile(context))),

        sliverBox(
          paddingIfNeed(isLandscape, backupLibraryListTile(context, l10n)),
        ),

        sliverBox(
          paddingIfNeed(isLandscape, restoreLibraryListTile(context, l10n)),
        ),

        sliverBox(paddingIfNeed(isLandscape, autoPlayOnStartupListTile(l10n))),

        if (!isMobile)
          sliverBox(
            paddingForLandscape(exitOnClose(l10n)),
          ), // always landscape style

        if (!Platform.isIOS)
          sliverBox(paddingIfNeed(isLandscape, checkUpdate(context, l10n))),

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

  Widget sliverBox(Widget child) => SliverToBoxAdapter(child: child);

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

  Widget syncListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(reloadImage, size: iconSize),
      title: Text(l10n.syncLibrary),
      onTap: () async {
        if (await showConfirmDialog(context, l10n.syncLibrary)) {
          if (Loader.busy) {
            if (context.mounted) {
              showCenterMessage(l10n.syncingTryLater);
            }
            return;
          }
          await Loader.sync();
        }
      },
    );
  }

  /// Where archived music lands.
  ///
  /// Downloads used to go to the app's own private folder unconditionally,
  /// which is why they appeared as a second music folder separate from the
  /// library the user already had. Pointing this at a real music folder puts
  /// both in one place.
  Widget downloadFolderListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.folder_special_outlined, size: 30),
      title: const Text('Download folder'),
      subtitle: ValueListenableBuilder<String>(
        valueListenable: downloadFolderNotifier,
        builder: (context, value, child) => Text(
          value.trim().isEmpty ? "The app's own folder" : value,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
      ),
      trailing: const Icon(Icons.chevron_right_rounded),
      onTap: () async {
        // Offering the folders already configured first is the point: the
        // common case is "put downloads with the music I already have", and
        // those are exactly the folders the library scans.
        final candidates = <String>[
          for (final folder in library.folderList)
            if (!folder.isWebdav && folder.path != defaultDownloadDir)
              folder.path,
        ];

        await showAnimationDialog(
          context: context,
          child: StatefulBuilder(
            builder: (context, setDialogState) => SizedBox(
              width: 380,
              height: 420,
              child: Padding(
                padding: const EdgeInsets.all(18),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Download folder',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Where archived music is saved. Choosing a folder you '
                      'already scan keeps downloads and library together.',
                      style: TextStyle(fontSize: 11, color: textColor.value),
                    ),
                    const SizedBox(height: 12),
                    Expanded(
                      child: ListView(
                        children: [
                          RadioListTile<String>(
                            value: '',
                            groupValue: downloadFolderNotifier.value,
                            dense: true,
                            title: const Text("The app's own folder"),
                            subtitle: Text(
                              'Private to Soiboi, always writable',
                              style: TextStyle(
                                fontSize: 11,
                                color: textColor.value,
                              ),
                            ),
                            onChanged: (v) => setDialogState(
                              () => downloadFolderNotifier.value = '',
                            ),
                          ),
                          for (final path in candidates)
                            RadioListTile<String>(
                              value: path,
                              groupValue: downloadFolderNotifier.value,
                              dense: true,
                              title: Text(
                                path,
                                style: const TextStyle(fontSize: 13),
                              ),
                              onChanged: (v) async {
                                if (v == null) return;
                                final ok = await _useDownloadFolder(v);
                                if (ok) setDialogState(() {});
                              },
                            ),
                          ListTile(
                            dense: true,
                            leading: const Icon(Icons.create_new_folder_outlined),
                            title: const Text('Choose another folder…'),
                            onTap: () async {
                              final picked =
                                  await FilePicker.getDirectoryPath();
                              if (picked == null) return;
                              final ok = await _useDownloadFolder(picked);
                              if (ok) setDialogState(() {});
                            },
                          ),
                        ],
                      ),
                    ),
                    Align(
                      alignment: Alignment.centerRight,
                      child: FilledButton(
                        onPressed: () => Navigator.of(context).pop(),
                        child: const Text('Done'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  /// Accepts [path] only if it can actually be written to.
  ///
  /// Android grants All files access separately from anything the folder
  /// picker returns, so a path can be chosen and still be unwritable. Testing
  /// it here turns a silent failure at download time into an answer now.
  Future<bool> _useDownloadFolder(String path) async {
    if (Platform.isAndroid && !downloadDirIsUsable(path)) {
      final status = await Permission.manageExternalStorage.request();
      if (!status.isGranted) {
        showCenterMessage(
          'Soiboi needs All files access to save there.',
          duration: 4000,
        );
        return false;
      }
    }
    if (!downloadDirIsUsable(path)) {
      showCenterMessage('That folder cannot be written to.', duration: 4000);
      return false;
    }
    downloadFolderNotifier.value = path;
    downloadOutputDir = resolveDownloadDir();
    setting.save();
    return true;
  }

  /// Backfills acoustic features so smart playlists have something to match.
  ///
  /// Only downloads were ever analysed, so a library that came from anywhere
  /// else had no features at all and every smart playlist and mood shelf sat
  /// at zero tracks. This is the way to fix that for music already on disk.
  Widget analyseLibraryListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.graphic_eq_rounded, size: 30),
      title: const Text('Analyse Library'),
      subtitle: ValueListenableBuilder<String>(
        valueListenable: _analyseStatus,
        builder: (context, value, child) => Text(
          value.isEmpty
              ? 'Needed for smart playlists and mood shelves'
              : value,
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
      ),
      onTap: () async {
        if (_analysing) {
          showCenterMessage('Already analysing');
          return;
        }
        _analysing = true;
        _analyseStatus.value = 'Starting…';
        final summary = await analyseLibrary(
          onProgress: (p) {
            _analyseStatus.value = p.folderCount > 1
                ? '${p.status} (folder ${p.folderIndex + 1}/${p.folderCount})'
                : p.status;
          },
        );
        _analysing = false;
        if (summary.unavailable) {
          _analyseStatus.value = 'Not available on this device';
        } else if (summary.error != null) {
          _analyseStatus.value = 'Failed: ${summary.error}';
        } else {
          _analyseStatus.value =
              '${summary.analysed} analysed · ${summary.skipped} already done'
              '${summary.pending > 0 ? " · ${summary.pending} unreadable" : ""}';
        }
      },
    );
  }

  Widget selectMusicFoldersListTile(
    BuildContext context,
    AppLocalizations l10n,
  ) {
    return ListTile(
      leading: AppIcon(folderImage, size: iconSize),
      title: Text(l10n.manageMusicFolder),
      onTap: () {
        showAnimationDialog(context: context, child: ManageMusicFolders());
      },
    );
  }

  Widget premiumFeaturesListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(premiumImage, size: iconSize),
      title: Text(l10n.premiumFeatures),
      onTap: () {
        layersManager.pushDetail('settings', 'premium');
      },
      trailing: ValueListenableBuilder(
        valueListenable: trialRemainingMinNotifier,
        builder: (context, value, child) {
          if (value <= 0) {
            return SizedBox.shrink();
          }
          return Row(
            mainAxisSize: .min,
            children: [
              Text(
                "${l10n.trialRemaining}:${formatDuration(Duration(minutes: value), ms: false)}",
              ),
            ],
          );
        },
      ),
    );
  }

  Widget switchSourceTypeListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(optionImage, size: iconSize),
      title: Text(l10n.switchSource),
      onTap: () {
        if (Loader.busy) {
          showCenterMessage(l10n.syncingTryLater);
          return;
        }
        showAnimationDialog(
          context: context,
          child: SizedBox(
            width: 300,
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: 10.0,
                vertical: 15,
              ),
              child: Builder(
                builder: (context) {
                  return Column(
                    mainAxisSize: .min,
                    children: [
                      SizedBox(
                        height: 35,
                        child: Text(
                          l10n.switchSource,
                          style: .new(fontSize: 18, fontWeight: .bold),
                        ),
                      ),
                      for (final tmp in SourceType.values)
                        ListTile(
                          leading: Image(
                            image: getSourceTypeImage(tmp),
                            width: 30,
                            height: 30,
                            color: tmp == .local || tmp == .webdav
                                ? iconColor.value
                                : null,
                          ),

                          title: Text(getSourceTypeDisplayName(l10n, tmp)),
                          trailing: sourceType == tmp
                              ? Icon(Icons.check)
                              : null,
                          onTap: () async {
                            if (sourceType == tmp) {
                              return;
                            }
                            if (!await showConfirmDialog(
                              context,
                              l10n.switchSource,
                            )) {
                              return;
                            }
                            if (context.mounted) {
                              Navigator.pop(context);
                            }
                            sourceType = tmp;
                            isStreamSource =
                                sourceType == .navidrome || sourceType == .emby;
                            isNotStreamSource = !isStreamSource;
                            streamClient = null;
                            if (sourceType == .navidrome &&
                                config.navidromeBaseUrl != null) {
                              streamClient = NavidromeClient(
                                baseUrl: config.navidromeBaseUrl!,
                                username: config.navidromeUsername!,
                                password: config.navidromePassword!,
                              );
                            } else if (sourceType == .emby &&
                                config.embyBaseUrl != null) {
                              streamClient = EmbyClient(
                                baseUrl: config.embyBaseUrl!,
                                username: config.embyUsername!,
                                password: config.embyPassword!,
                              );
                            }
                            setState(() {});

                            Loader.reload();
                            config.save();
                          },
                        ),
                    ],
                  );
                },
              ),
            ),
          ),
        );
      },
    );
  }

  Widget manageServersListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(serverImage, size: iconSize),
      title: Text(l10n.manageServers),
      onTap: () {
        showAnimationDialog(
          context: context,
          child: SizedBox(
            width: 300,
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: 10.0,
                vertical: 15,
              ),
              child: Builder(
                builder: (context) {
                  return Column(
                    mainAxisSize: .min,
                    children: [
                      SizedBox(
                        height: 35,
                        child: Text(
                          l10n.manageServers,
                          style: .new(fontSize: 18, fontWeight: .bold),
                        ),
                      ),
                      webdavListTile(context, l10n),
                      navidromeListTile(context, l10n),
                      embyListTile(context, l10n),
                    ],
                  );
                },
              ),
            ),
          ),
        );
      },
    );
  }

  Widget webdavListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: Image(
        image: webdavImage,
        width: 30,
        height: 30,
        color: iconColor.value,
      ),

      title: Text(getSourceTypeDisplayName(l10n, .webdav)),
      onTap: () {
        if (Loader.busy && sourceType == .webdav) {
          showCenterMessage(l10n.syncingTryLater);
          return;
        }
        showAnimationDialog(
          context: context,
          child: ConnectClientWidget(sourceType: .webdav),
        );
      },
    );
  }

  Widget navidromeListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: Image(image: navidromeImage, width: 30, height: 30),
      title: Text(getSourceTypeDisplayName(l10n, .navidrome)),
      onTap: () {
        if (Loader.busy && sourceType == .navidrome) {
          showCenterMessage(l10n.syncingTryLater);
          return;
        }
        showAnimationDialog(
          context: context,
          child: ConnectClientWidget(sourceType: .navidrome),
        );
      },
    );
  }

  Widget embyListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: Image(image: embyImage, width: 30, height: 30),

      title: Text(getSourceTypeDisplayName(l10n, .emby)),
      onTap: () {
        if (Loader.busy && sourceType == .emby) {
          showCenterMessage(l10n.syncingTryLater);
          return;
        }
        showAnimationDialog(
          context: context,
          child: ConnectClientWidget(sourceType: .emby),
        );
      },
    );
  }

  Widget cleanCacheListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(cacheImage, size: iconSize),
      title: Text(l10n.clearCache),
      onTap: () async {
        if (Loader.busy) {
          showCenterMessage(l10n.syncLibrary);
          return;
        }
        if (await showConfirmDialog(context, l10n.clear)) {
          showCenterLoading();
          layersManager.clearDataLayers();
          await library.clearCache();
          await library.clearPicture();
          playlistManager.updateNotifier.value++;
          removeCenterLoading();
        }
      },
      trailing: ValueListenableBuilder(
        valueListenable: cacheSizeNotifier,
        builder: (context, value, child) {
          // use blank as placeholders
          return Text("${value.toStringAsFixed(1)}MB  ");
        },
      ),
    );
  }

  Widget languageListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(languageImage, size: iconSize),
      title: Text(l10n.language),
      onTap: () {
        showAnimationDialog(
          context: context,

          child: SizedBox(
            width: 300,
            height: isMobile ? 200 : 180,
            child: Padding(
              padding: const EdgeInsets.all(15.0),
              child: ValueListenableBuilder(
                valueListenable: localeNotifier,
                builder: (context, value, child) {
                  final l10n = AppLocalizations.of(context);

                  return ListView(
                    children: [
                      ListTile(
                        title: Text(l10n.followSystem),
                        onTap: () {
                          localeNotifier.value = null;
                          setting.save();
                        },
                        trailing: value == null ? Icon(Icons.check) : null,
                      ),
                      ListTile(
                        title: Text('English'),
                        onTap: () {
                          localeNotifier.value = Locale('en');
                          setting.save();
                        },
                        trailing: value == Locale('en')
                            ? Icon(Icons.check)
                            : null,
                      ),
                      ListTile(
                        title: Text('中文'),
                        onTap: () {
                          localeNotifier.value = Locale('zh');
                          setting.save();
                        },
                        trailing: value == Locale('zh')
                            ? Icon(Icons.check)
                            : null,
                      ),
                    ],
                  );
                },
              ),
            ),
          ),
        );
      },
    );
  }

  Widget drawerListTile(AppLocalizations l10n) {
    return ListTile(
      leading: Transform.scale(
        scale: 0.95,
        child: Icon(Icons.menu_rounded, size: iconSize),
      ),
      title: Text(l10n.menuOnRight),
      trailing: SizedBox(
        width: 50,
        child: MySwitch(
          valueNotifier: endDrawerNotifier,
          onToggleCallBack: () {
            setting.save();
          },
        ),
      ),
    );
  }

  Widget vibrationListTile(AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(vibrationImage, size: iconSize),
      title: Text(l10n.vibration),
      trailing: SizedBox(
        width: 50,
        child: MySwitch(
          valueNotifier: vibrationOnNoitifier,
          onToggleCallBack: () {
            setting.save();
          },
        ),
      ),
    );
  }

  void _updateMainPageTheme() {
    setting.save();
    colorManager.updateMainPageColors();
  }

  void _updateLyricsPageTheme() {
    setting.save();
    colorManager.updateLyricsPageColors();
  }

  Widget fontListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(fontImage, size: iconSize),

      title: Text(l10n.fonts),
      onTap: () {
        if (!isPremiumNotifier.value) {
          showPremiumDialog(context);
          return;
        }
        layersManager.pushDetail('settings', 'font_picker');
      },
      trailing: ValueListenableBuilder(
        valueListenable: isPremiumNotifier,
        builder: (context, value, child) {
          if (value) {
            return SizedBox.shrink();
          }
          return Icon(Icons.lock);
        },
      ),
    );
  }

  void _updateFlavour() {
    setting.save();
    colorManager.updateColors();
  }

  /// Flavour picker. Distinct from the Theme tile, which controls
  /// light/dark/vivid — flavour is *which* visual identity, brightness is how
  /// light it is, and the two compose.
  Widget flavourListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(themeImage, size: iconSize),
      title: const Text('Flavour'),
      subtitle: ValueListenableBuilder(
        valueListenable: flavourNotifier,
        builder: (context, value, child) => Text(
          value.label,
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
      ),
      onTap: () async {
        flavourNotifier.addListener(_updateFlavour);
        await showAnimationDialog(
          context: context,
          child: SizedBox(
            width: 300,
            height: 290,
            child: Padding(
              padding: const EdgeInsets.all(15.0),
              child: ValueListenableBuilder(
                valueListenable: flavourNotifier,
                builder: (context, value, child) {
                  return Column(
                    children: [
                      const Text(
                        'Flavour',
                        style: TextStyle(fontSize: 18, fontWeight: .bold),
                      ),
                      for (final flavour in Flavour.values)
                        ListTile(
                          title: Text(flavour.label),
                          subtitle: Text(
                            flavour.blurb,
                            style: const TextStyle(fontSize: 11),
                          ),
                          onTap: () {
                            flavourNotifier.value = flavour;
                            updateHoverFocusColor();
                          },
                          trailing: value == flavour
                              ? const Icon(Icons.check)
                              : null,
                        ),
                    ],
                  );
                },
              ),
            ),
          ),
        );
        flavourNotifier.removeListener(_updateFlavour);
      },
    );
  }

  /// System colours override the flavour's palette but keep its shape,
  /// density and motion -- dynamic colour restyles a flavour, it doesn't
  /// replace one.
  /// Colour source: where the app's colours come from, independent of
  /// [Flavour] (shape/motion only, see flavour.dart). Three choices — app's
  /// own colours, matugen/Material You, or a prebuilt named palette — plus
  /// "Album art", which is not a fourth branch here at all: it is
  /// `ThemeType.vivid`, already offered per-page from the Theme tile.
  Widget colorSourceListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.palette_outlined, size: 30),
      title: const Text('Colour source'),
      subtitle: ValueListenableBuilder(
        valueListenable: colorSourceNotifier,
        builder: (context, source, child) {
          final loaded = dynamicDarkNotifier.value != null;
          final label = switch (source) {
            ColorSource.off => 'App colours (default)',
            ColorSource.matugen => !loaded
                ? 'No colours found — tap to set up'
                // Says where the colours actually came from: with two routes
                // (an existing matugen setup's file, or generating from the
                // wallpaper) "it worked" is not enough to debug from.
                : dynamicColorSourceDescription ??
                      (Platform.isAndroid ? 'Material You' : 'Matched via matugen'),
            ColorSource.prebuilt =>
              'Prebuilt · ${prebuiltPaletteNotifier.value.label}',
          };
          return Text(label, style: TextStyle(fontSize: 12, color: textColor.value));
        },
      ),
      onTap: () => _openColorSourcePicker(context),
    );
  }

  Future<void> _openColorSourcePicker(BuildContext context) async {
    await showAnimationDialog(
      context: context,
      child: ValueListenableBuilder(
        valueListenable: colorSourceNotifier,
        builder: (context, source, child) => SizedBox(
          width: 320,
          child: Padding(
            padding: const EdgeInsets.all(15.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  'Colour source',
                  style: TextStyle(fontSize: 18, fontWeight: .bold),
                ),
                ListTile(
                  title: const Text('App colours'),
                  subtitle: const Text(
                    'Default — no system or prebuilt colours',
                    style: TextStyle(fontSize: 11),
                  ),
                  onTap: () {
                    colorSourceNotifier.value = ColorSource.off;
                    clearDynamicPalette();
                    setting.save();
                    colorManager.updateColors();
                    Navigator.of(context).pop();
                  },
                  trailing: source == ColorSource.off
                      ? const Icon(Icons.check)
                      : null,
                ),
                ListTile(
                  title: Text(Platform.isAndroid ? 'Material You' : 'Matugen'),
                  subtitle: Text(
                    Platform.isAndroid
                        ? "Your wallpaper's system palette"
                        : 'Reads or generates a matugen scheme',
                    style: const TextStyle(fontSize: 11),
                  ),
                  onTap: () async {
                    Navigator.of(context).pop();
                    await _openMatugenDialog(context);
                  },
                  trailing: source == ColorSource.matugen
                      ? const Icon(Icons.check)
                      : null,
                ),
                ListTile(
                  title: const Text('Prebuilt palette'),
                  subtitle: const Text(
                    'Dracula, Nord, Catppuccin, and more',
                    style: TextStyle(fontSize: 11),
                  ),
                  onTap: () async {
                    Navigator.of(context).pop();
                    await _openPrebuiltPaletteDialog(context);
                  },
                  trailing: source == ColorSource.prebuilt
                      ? const Icon(Icons.check)
                      : null,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _openMatugenDialog(BuildContext context) async {
    final controller = TextEditingController(
      text: matugenPathNotifier.value.isEmpty
          ? defaultMatugenPath
          : matugenPathNotifier.value,
    );
    String? status;
    await showAnimationDialog(
      context: context,
      child: StatefulBuilder(
        builder: (context, setDialogState) => SizedBox(
          width: 360,
          // Taller than it looks it needs: the scheme row and the
          // optional-path field both wrap on narrow displays, and a
          // Column in a fixed box overflows rather than scrolling.
          height: 340,
          child: Padding(
            padding: const EdgeInsets.all(18.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  Platform.isAndroid ? 'Material You' : 'Matugen',
                  style: const TextStyle(fontSize: 18, fontWeight: .bold),
                ),
                const SizedBox(height: 6),
                Text(
                  Platform.isAndroid
                      // Android derives the palette itself, from the
                      // wallpaper, and hands it over whole — there is
                      // nothing to configure and nothing to run.
                      ? 'Uses the Material You palette Android builds '
                            'from your wallpaper, so Soiboi matches the '
                            'rest of your system. Needs Android 12 or '
                            'newer.'
                      : 'Matches the rest of your desktop. Reads an '
                            'existing matugen scheme if you have one, '
                            'otherwise generates one from your wallpaper.',
                  style: TextStyle(fontSize: 12, color: textColor.value),
                ),
                const SizedBox(height: 12),
                // Both controls below are matugen's, and matugen is
                // Linux's route to this. Showing a scheme picker and a
                // JSON path on a phone would offer settings that cannot
                // affect anything.
                if (!Platform.isAndroid)
                // The scheme only applies when Soiboi generates the
                // colours itself; a file written by someone else's
                // matugen config was already built with their choice.
                Row(
                  children: [
                    const Text('Scheme', style: TextStyle(fontSize: 12)),
                    const SizedBox(width: 12),
                    Expanded(
                      child: DropdownButton<String>(
                        isExpanded: true,
                        value: matugenSchemeNotifier.value,
                        // Both are needed. The default menu paints on the
                        // ambient Material canvas, which this app never
                        // sets, so it comes out white — and the app's own
                        // near-white text on it is unreadable.
                        dropdownColor: menuColor.value,
                        style: TextStyle(
                          fontSize: 12,
                          color: textColor.value,
                        ),
                        items: [
                          for (final scheme in matugenSchemes)
                            DropdownMenuItem(
                              value: scheme,
                              child: Text(schemeLabel(scheme)),
                            ),
                        ],
                        onChanged: (scheme) async {
                          if (scheme == null) return;
                          matugenSchemeNotifier.value = scheme;
                          final ok = await generateMatugenPalette();
                          setDialogState(() {
                            status = ok
                                ? 'Generated a ${schemeLabel(scheme)} '
                                      'scheme from your wallpaper'
                                : 'Could not generate — is matugen '
                                      'installed?';
                          });
                          if (ok) {
                            dynamicColorSourceDescription =
                                '${schemeLabel(scheme)} from your wallpaper';
                            colorSourceNotifier.value = ColorSource.matugen;
                            setting.save();
                            colorManager.updateColors();
                          }
                        },
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                if (!Platform.isAndroid)
                  TextField(
                    controller: controller,
                    style: const TextStyle(fontSize: 12),
                    decoration: const InputDecoration(
                      isDense: true,
                      border: OutlineInputBorder(),
                      labelText: 'matugen JSON (optional)',
                    ),
                  ),
                if (status != null) ...[
                  const SizedBox(height: 8),
                  Text(status!, style: const TextStyle(fontSize: 12)),
                ],
                const Spacer(),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: () {
                        colorSourceNotifier.value = ColorSource.off;
                        clearDynamicPalette();
                        setting.save();
                        colorManager.updateColors();
                        Navigator.of(context).pop();
                      },
                      child: const Text('Turn off'),
                    ),
                    const SizedBox(width: 8),
                    FilledButton(
                      onPressed: () async {
                        matugenPathNotifier.value = controller.text.trim();
                        // Falls back to generating, so an empty or wrong
                        // path is not a dead end.
                        final ok = await autoLoadDynamicPalette();
                        if (!ok) {
                          setDialogState(
                            () => status =
                                Platform.isAndroid
                                    ? 'Android did not provide a palette. '
                                          'Material You needs Android 12 '
                                          'or newer.'
                                    : 'No colours found, and matugen '
                                          'could not generate any from '
                                          'your wallpaper',
                          );
                          return;
                        }
                        colorSourceNotifier.value = ColorSource.matugen;
                        setting.save();
                        colorManager.updateColors();
                        if (context.mounted) Navigator.of(context).pop();
                      },
                      child: const Text('Use these colours'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
    controller.dispose();
  }

  Future<void> _openPrebuiltPaletteDialog(BuildContext context) async {
    final isDark = mainPageThemeNotifier.value == .dark;
    await showAnimationDialog(
      context: context,
      child: SizedBox(
        width: 320,
        height: 420,
        child: Padding(
          padding: const EdgeInsets.all(15.0),
          child: Column(
            children: [
              const Text(
                'Prebuilt palette',
                style: TextStyle(fontSize: 18, fontWeight: .bold),
              ),
              const SizedBox(height: 6),
              Expanded(
                child: ValueListenableBuilder(
                  valueListenable: prebuiltPaletteNotifier,
                  builder: (context, selected, child) => ListView(
                    children: [
                      for (final palette in PrebuiltPalette.values)
                        ListTile(
                          leading: CircleAvatar(
                            radius: 10,
                            backgroundColor: palette.accent(isDark: isDark),
                          ),
                          title: Text(palette.label),
                          onTap: () {
                            prebuiltPaletteNotifier.value = palette;
                            colorSourceNotifier.value = ColorSource.prebuilt;
                            setting.save();
                            colorManager.updateColors();
                            Navigator.of(context).pop();
                          },
                          trailing:
                              selected == palette &&
                                  colorSourceNotifier.value ==
                                      ColorSource.prebuilt
                              ? const Icon(Icons.check)
                              : null,
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget themeListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(themeImage, size: iconSize),
      title: Text(l10n.theme),
      onTap: () async {
        mainPageThemeNotifier.addListener(_updateMainPageTheme);
        lyricsPageThemeNotifier.addListener(_updateLyricsPageTheme);
        await showAnimationDialog(
          context: context,

          child: OrientationBuilder(
            builder: (context, orientation) {
              final size = MediaQuery.of(context).size;
              final shortSide = size.shortestSide;

              bool isPhone = shortSide < 600;

              return SizedBox(
                width: 300,
                height: isPhone && orientation == .landscape
                    ? 350
                    : isMobile
                    ? 420
                    : 370,
                child: Padding(
                  padding: const EdgeInsets.all(15.0),
                  child: CustomScrollView(
                    scrollBehavior: ScrollBehavior().copyWith(
                      scrollbars: false,
                    ),
                    slivers: [
                      sliverBox(
                        ValueListenableBuilder(
                          valueListenable: mainPageThemeNotifier,
                          builder: (context, value, child) {
                            final l10n = AppLocalizations.of(context);
                            return Column(
                              children: [
                                Text(
                                  l10n.mainPageTheme,
                                  style: .new(fontSize: 18, fontWeight: .bold),
                                ),
                                ListTile(
                                  title: Text(l10n.vividMode),
                                  onTap: () {
                                    if (!isPremiumNotifier.value) {
                                      showPremiumDialog(context);
                                      return;
                                    }
                                    mainPageThemeNotifier.value = .vivid;
                                    updateHoverFocusColor();
                                  },
                                  trailing: ValueListenableBuilder(
                                    valueListenable: isPremiumNotifier,
                                    builder: (context, isPremium, child) {
                                      if (!isPremium) {
                                        return Icon(Icons.lock);
                                      }
                                      return value == .vivid
                                          ? Icon(Icons.check)
                                          : SizedBox.shrink();
                                    },
                                  ),
                                ),
                                ListTile(
                                  title: Text(l10n.lightMode),
                                  onTap: () {
                                    mainPageThemeNotifier.value = .light;
                                    updateHoverFocusColor();
                                  },
                                  trailing: value == .light
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                                ListTile(
                                  title: Text(l10n.darkMode),
                                  onTap: () {
                                    mainPageThemeNotifier.value = .dark;
                                    updateHoverFocusColor();
                                  },
                                  trailing: value == .dark
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                              ],
                            );
                          },
                        ),
                      ),
                      sliverBox(
                        ValueListenableBuilder(
                          valueListenable: lyricsPageThemeNotifier,
                          builder: (context, value, child) {
                            final l10n = AppLocalizations.of(context);
                            return Column(
                              children: [
                                Text(
                                  l10n.lyricsPageTheme,
                                  style: .new(fontSize: 18, fontWeight: .bold),
                                ),
                                ListTile(
                                  title: Text(l10n.vividMode),
                                  onTap: () {
                                    lyricsPageThemeNotifier.value = .vivid;
                                  },
                                  trailing: value == .vivid
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                                ListTile(
                                  title: Text(l10n.lightMode),
                                  onTap: () {
                                    lyricsPageThemeNotifier.value = .light;
                                  },
                                  trailing: value == .light
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                                ListTile(
                                  title: Text(l10n.darkMode),
                                  onTap: () {
                                    lyricsPageThemeNotifier.value = .dark;
                                  },
                                  trailing: value == .dark
                                      ? Icon(Icons.check)
                                      : null,
                                ),
                              ],
                            );
                          },
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        );
        mainPageThemeNotifier.removeListener(_updateMainPageTheme);
        lyricsPageThemeNotifier.removeListener(_updateLyricsPageTheme);
      },
    );
  }

  Widget equalizerListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(equalizerImage, size: iconSize),
      title: Text(l10n.equalizer),
      onTap: () {
        if (!isPremiumNotifier.value) {
          showPremiumDialog(context);
          return;
        }
        showAnimationDialog(
          context: context,
          child: OrientationBuilder(
            builder: (context, orientation) {
              final size = MediaQuery.of(context).size;
              final shortSide = size.shortestSide;

              bool isPhone = shortSide < 600;
              if (isMobile && orientation == .portrait) {
                return SizedBox(
                  height: 500,
                  width: isPhone ? 300 : 400,
                  child: EqualizerWidget(),
                );
              } else {
                return SizedBox(
                  height: isPhone ? 350 : 400,
                  width: 540,
                  child: EqualizerWidget(),
                );
              }
            },
          ),
        );
      },
      trailing: ValueListenableBuilder(
        valueListenable: isPremiumNotifier,
        builder: (context, value, child) {
          if (value) {
            return SizedBox.shrink();
          }
          return Icon(Icons.lock);
        },
      ),
    );
  }

  Widget immersiveWideLayoutListTile(AppLocalizations l10n) {
    return ListTile(
      leading: Transform.scale(
        scale: 0.9,
        child: AppIcon(fullscreenImage, size: iconSize),
      ),

      title: Text(l10n.immersiveWideLayout),
      trailing: SizedBox(
        width: 50,
        child: Builder(
          builder: (context) {
            return MySwitch(
              valueNotifier: immersiveWideLayoutNotifier,
              onToggleCallBack: () {
                if (!isTooNarrow(context)) {
                  applySystemUiMode(
                    mode: immersiveWideLayoutNotifier.value
                        ? .immersiveSticky
                        : .edgeToEdge,
                  );
                }
                setting.save();
              },
            );
          },
        ),
      ),
    );
  }

  /// Network lyric lookup. Off means the app never reaches out for lyrics --
  /// worth having as a switch since everything else here works offline.

  /// Optional. Without it, Home ranks by local play counts; with it, rankings
  /// reflect everything you listen to. Stats are public, so no token is needed
  /// and nothing is sent anywhere -- this only reads.
  Widget listenBrainzListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.insights_outlined, size: 30),
      title: const Text('ListenBrainz'),
      subtitle: ValueListenableBuilder(
        valueListenable: listenBrainzUserNotifier,
        builder: (context, value, child) => Text(
          value.isEmpty ? 'Not connected — using local play counts' : value,
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
      ),
      onTap: () async {
        final controller = TextEditingController(
          text: listenBrainzUserNotifier.value,
        );
        String? status;
        await showAnimationDialog(
          context: context,
          child: StatefulBuilder(
            builder: (context, setDialogState) => SizedBox(
              width: 340,
              height: 250,
              child: Padding(
                padding: const EdgeInsets.all(18.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'ListenBrainz',
                      style: TextStyle(fontSize: 18, fontWeight: .bold),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'Your username ranks Home by everything you listen to, '
                      'not just this device. Read-only, no token needed.',
                      style: TextStyle(fontSize: 12, color: textColor.value),
                    ),
                    const SizedBox(height: 14),
                    TextField(
                      controller: controller,
                      autofocus: true,
                      decoration: const InputDecoration(
                        hintText: 'username',
                        isDense: true,
                        border: OutlineInputBorder(),
                      ),
                    ),
                    if (status != null) ...[
                      const SizedBox(height: 10),
                      Text(status!, style: const TextStyle(fontSize: 12)),
                    ],
                    const Spacer(),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        TextButton(
                          onPressed: () {
                            listenBrainzUserNotifier.value = '';
                            setting.save();
                            Navigator.of(context).pop();
                          },
                          child: const Text('Disconnect'),
                        ),
                        const SizedBox(width: 8),
                        FilledButton(
                          onPressed: () async {
                            final user = controller.text.trim();
                            if (user.isEmpty) return;
                            setDialogState(() => status = 'Checking…');
                            final ok = await verifyListenBrainzUser(user);
                            if (!ok) {
                              setDialogState(
                                () => status = 'No such ListenBrainz user',
                              );
                              return;
                            }
                            listenBrainzUserNotifier.value = user;
                            setting.save();
                            if (context.mounted) Navigator.of(context).pop();
                          },
                          child: const Text('Connect'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
        controller.dispose();
      },
    );
  }

  /// Apple Music session. Required for downloading; the player itself works
  /// without it.
  Widget appleAccountListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.account_circle_outlined, size: 30),
      title: const Text('Apple Music account'),
      subtitle: ListenableBuilder(
        listenable: Listenable.merge([
          cookie_store.signedInNotifier,
          cookie_store.sessionExpiryNotifier,
        ]),
        builder: (context, child) {
          final signedIn = cookie_store.signedInNotifier.value;
          final expiry = cookie_store.sessionExpiryNotifier.value;
          final detail = signedIn && expiry != null
              ? 'Signed in · expires ${expiry.toLocal().toString().split(' ').first}'
              : signedIn
              ? 'Signed in'
              : 'Not signed in — needed to download';
          return Text(
            detail,
            style: TextStyle(fontSize: 12, color: textColor.value),
          );
        },
      ),
      trailing: ValueListenableBuilder(
        valueListenable: cookie_store.signedInNotifier,
        builder: (context, signedIn, child) => signedIn
            ? TextButton(
                onPressed: () async {
                  await cookie_store.signOut();
                },
                child: const Text('Sign out'),
              )
            : const Icon(Icons.chevron_right_rounded),
      ),
      onTap: () async {
        await Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const AppleSignInLayer()),
        );
        await cookie_store.refreshSessionState();
      },
    );
  }

  /// Download quality: which codec gamdl should fetch.
  Widget downloadQualityListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.high_quality_outlined, size: 30),
      title: const Text('Download quality'),
      subtitle: ValueListenableBuilder(
        valueListenable: downloadCodecNotifier,
        builder: (context, codec, child) => Text(
          downloadCodecLabels[codec] ?? codec,
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
      ),
      trailing: const Icon(Icons.chevron_right_rounded),
      onTap: () async {
        await showAnimationDialog(
          context: context,
          child: SizedBox(
            width: 320,
            height: 290,
            child: Padding(
              padding: const EdgeInsets.all(18.0),
              child: StatefulBuilder(
                builder: (context, setDialogState) => Column(
                  children: [
                    const Text(
                      'Download quality',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'ALAC is lossless and roughly three times the size. '
                      'Both work as-is — no extra setup.',
                      style: TextStyle(fontSize: 11, color: textColor.value),
                    ),
                    const SizedBox(height: 12),
                    for (final entry in downloadCodecLabels.entries)
                      RadioListTile<String>(
                        value: entry.key,
                        groupValue: downloadCodecNotifier.value,
                        title: Text(entry.value),
                        dense: true,
                        onChanged: (value) {
                          if (value == null) return;
                          downloadCodecNotifier.value = value;
                          setting.save();
                          Navigator.of(context).pop();
                        },
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  /// Widevine configuration for ALAC downloads.
  Widget widevineListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.lock_outline, size: 30),
      title: const Text('Widevine device'),
      subtitle: ValueListenableBuilder(
        valueListenable: useWrapperNotifier,
        builder: (context, _, child) {
          final detail = useWrapperNotifier.value
              ? wrapperUrlNotifier.value.isEmpty
                  ? 'Wrapper enabled — set URL'
                  : 'Wrapper: ${wrapperUrlNotifier.value}'
              : wvdPathNotifier.value != null && wvdPathNotifier.value!.isNotEmpty
                  ? 'WVD file set'
                  : 'Using the built-in device';
          return Text(
            detail,
            style: TextStyle(fontSize: 12, color: textColor.value),
          );
        },
      ),
      trailing: const Icon(Icons.chevron_right_rounded),
      onTap: () async {
        final wvdController = TextEditingController(text: wvdPathNotifier.value ?? '');
        final wrapperController = TextEditingController(text: wrapperUrlNotifier.value);
        await showAnimationDialog(
          context: context,
          child: StatefulBuilder(
            builder: (context, setDialogState) => SizedBox(
              width: 360,
              height: 380,
              child: Padding(
                padding: const EdgeInsets.all(18.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Widevine configuration',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      'Lossless downloads already work — the downloader '
                      'carries its own Widevine device and uses it by '
                      'default. You only need this if ALAC downloads start '
                      'failing to decrypt, which means that shared device '
                      'has been revoked. Then supply your own .wvd, or a '
                      'wrapper service that holds one for you.',
                      style: TextStyle(fontSize: 11, color: textColor.value),
                    ),
                    const SizedBox(height: 16),
                    // Mode toggle
                    SwitchListTile(
                      title: const Text('Use wrapper service'),
                      subtitle: const Text('Instead of a local .wvd file'),
                      value: useWrapperNotifier.value,
                      onChanged: (v) {
                        setDialogState(() => useWrapperNotifier.value = v);
                        setting.save();
                      },
                    ),
                    const SizedBox(height: 12),
                    if (useWrapperNotifier.value)
                      TextField(
                        controller: wrapperController,
                        decoration: const InputDecoration(
                          isDense: true,
                          border: OutlineInputBorder(),
                          labelText: 'Wrapper URL',
                          hintText: 'https://...',
                        ),
                        style: const TextStyle(fontSize: 13),
                      )
                    else
                      Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: wvdController,
                              decoration: const InputDecoration(
                                isDense: true,
                                border: OutlineInputBorder(),
                                labelText: 'WVD file path',
                                hintText: '/path/to/device.wvd',
                              ),
                              style: const TextStyle(fontSize: 13),
                            ),
                          ),
                          const SizedBox(width: 8),
                          IconButton(
                            icon: const Icon(Icons.folder_open),
                            onPressed: () async {
                              final result = await FilePicker.pickFiles(
                                type: FileType.custom,
                                allowedExtensions: ['wvd'],
                              );
                              if (result != null && result.files.isNotEmpty) {
                                wvdController.text = result.files.first.path ?? '';
                              }
                            },
                          ),
                        ],
                      ),
                    const Spacer(),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        TextButton(
                          onPressed: () => Navigator.of(context).pop(),
                          child: const Text('Cancel'),
                        ),
                        const SizedBox(width: 8),
                        FilledButton(
                          onPressed: () {
                            if (useWrapperNotifier.value) {
                              wrapperUrlNotifier.value = wrapperController.text.trim();
                              wvdPathNotifier.value = null;
                            } else {
                              wvdPathNotifier.value = wvdController.text.trim().isEmpty
                                  ? null
                                  : wvdController.text.trim();
                              wrapperUrlNotifier.value = '';
                            }
                            setting.save();
                            Navigator.of(context).pop();
                          },
                          child: const Text('Save'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget lrclibListTile(AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(lyricsImage, size: iconSize),
      title: const Text('Fetch lyrics from LRCLIB'),
      subtitle: Text(
        'Only for tracks with no local lyrics',
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      trailing: SizedBox(
        width: 50,
        child: MySwitch(
          valueNotifier: lrclibEnabledNotifier,
          onToggleCallBack: () {
            setting.save();
          },
        ),
      ),
    );
  }

  Widget autoPlayOnStartupListTile(AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(playOutlinedImage, size: iconSize),

      title: Text(l10n.autoPlayOnStartup),
      trailing: SizedBox(
        width: 50,
        child: MySwitch(
          valueNotifier: autoPlayOnStartupNotifier,
          onToggleCallBack: () {
            setting.save();
          },
        ),
      ),
    );
  }

  Widget exitOnClose(AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(powerOffImage),

      title: Text(l10n.closeAction),
      trailing: SizedBox(
        width: 150,
        child: Row(
          children: [
            Spacer(),
            MySwitch(
              trueText: l10n.exit,
              falseText: l10n.hide,
              valueNotifier: exitOnCloseNotifier,
              onToggleCallBack: () {
                setting.save();
              },
            ),
          ],
        ),
      ),
    );
  }

  /// Checks GitHub for a newer build, and installs it.
  ///
  /// The old version of this tile fetched `AfalpHy/soiboi` — upstream
  /// Sylvakru's own update check, inherited unedited — a repository that does
  /// not exist, so every check this app ever made answered 404. It also only
  /// offered to open a browser; the actual download and install now happen in
  /// the sheet.
  Widget checkUpdate(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(checkUpdateImage, size: iconSize),
      title: Text(l10n.checkUpdate),
      subtitle: Text(
        'You have $versionNumber',
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      onTap: () async {
        showCenterMessage('Checking for updates…');
        final check = await checkForUpdate();
        if (!context.mounted) return;
        switch (check.state) {
          case UpdateState.available:
            await showUpdateSheet(context, check.release!);
          case UpdateState.upToDate:
            showCenterMessage(l10n.alreadyLatest);
          case UpdateState.failed:
            showCenterMessage(
              'Could not check for updates: ${check.error}',
              duration: 5000,
            );
        }
      },
    );
  }

  /// The download queue, reachable from anywhere.
  ///
  /// The Downloads screen shows the same list inline, but a fifty-track
  /// playlist runs for several minutes and nobody sits on that screen waiting
  /// — this is how you check on it from wherever you actually are.
  Widget downloadQueueListTile(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.download_outlined, size: 30),
      title: const Text('Download queue'),
      subtitle: ValueListenableBuilder<List<DownloadJob>>(
        valueListenable: downloadQueue.jobs,
        builder: (context, jobs, child) {
          final active = jobs.where((job) => job.isActive).length;
          final failed = jobs
              .where((job) => job.state == DownloadJobState.failed)
              .length;
          return Text(
            active > 0
                ? '$active in progress'
                : failed > 0
                ? '$failed failed — tap to retry'
                : 'Nothing downloading',
            style: TextStyle(
              fontSize: 12,
              color: failed > 0 && active == 0 ? Colors.red : textColor.value,
            ),
          );
        },
      ),
      onTap: () => showDownloadQueueSheet(context),
    );
  }

  /// What the archive costs on this device, and how to get some of it back.
  Widget storageListTile(BuildContext context) {
    return ListTile(
      leading: const Icon(Icons.sd_storage_outlined, size: 30),
      title: const Text('Storage'),
      subtitle: Text(
        'Review the largest, least played and oldest tracks',
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      onTap: () => showStorageCleanupSheet(context),
    );
  }

  Widget backupLibraryListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(exportLogImage, size: iconSize),
      title: Text(l10n.backupLibrary),
      onTap: () async {
        try {
          final path = await exportBackupToFile();
          if (context.mounted && path != null) {
            showCenterMessage(l10n.backupSaved(path));
          }
        } catch (e) {
          if (context.mounted) {
            showCenterMessage('${l10n.backupFailed}: $e', duration: 5000);
          }
        }
      },
    );
  }

  Widget restoreLibraryListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(reloadImage, size: iconSize),
      title: Text(l10n.restoreLibrary),
      onTap: () async {
        if (!await showConfirmDialog(context, l10n.restoreLibrary)) return;
        try {
          final restored = await importBackupFromFile();
          if (!context.mounted || !restored) return;
          showCenterMessage(l10n.restoreDone, duration: 5000);
        } on InvalidBackupException catch (e) {
          if (context.mounted) {
            showCenterMessage(e.message, duration: 5000);
          }
        } catch (e) {
          if (context.mounted) {
            showCenterMessage('${l10n.backupFailed}: $e', duration: 5000);
          }
        }
      },
    );
  }

  Widget viewLogListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(exportLogImage, size: iconSize),

      title: Text(l10n.viewLog),
      onTap: () async {
        showAnimationDialog(
          context: context,
          child: Builder(
            builder: (context) {
              return ConstrainedBox(
                constraints: BoxConstraints(
                  maxHeight: MediaQuery.heightOf(context) * 0.75,
                  maxWidth: isTooNarrow(context) ? 300 : 400,
                ),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    children: [
                      Expanded(
                        child: SingleChildScrollView(
                          padding: const EdgeInsets.all(16),
                          child: SelectableText(logger.logContent),
                        ),
                      ),
                      SizedBox(height: 20),
                      if (isMobile)
                        ValueListenableBuilder(
                          valueListenable: buttonColor.valueNotifier,
                          builder: (context, value, child) {
                            return ElevatedButton(
                              style: ElevatedButton.styleFrom(
                                backgroundColor: buttonColor.value,
                                shape: RoundedRectangleBorder(
                                  borderRadius: BorderRadius.circular(10),
                                ),
                                padding: EdgeInsets.all(10),
                              ),
                              onPressed: () async {
                                String? result;
                                if (Platform.isAndroid) {
                                  result = await FilePicker.getDirectoryPath();
                                  if (result == null) {
                                    return;
                                  }
                                  logger.export2Directory(result);
                                  if (context.mounted) {
                                    showCenterMessage('Export to $result');
                                  }
                                } else {
                                  result = '${appDocsDir.path}/logs';
                                  logger.export2Directory(result);
                                  showCenterMessage('Export to Soiboi/logs');
                                }
                              },
                              child: Text(l10n.exportLog),
                            );
                          },
                        ),
                    ],
                  ),
                ),
              );
            },
          ),
        );
      },
    );
  }
}
