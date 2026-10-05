part of 'settings_list.dart';

/// Library sources, folders, analysis, cache, backup and cleanup.
mixin _LibrarySettings on _SettingsBase {
  // Analysis runs off a settings tap and can take minutes on a big library,
  // so its progress lives here rather than in the tile, which rebuilds.
  final _analyseStatus = ValueNotifier<String>('');
  bool _analysing = false;

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
      title: Text(l10n.downloadFolder),
      subtitle: ValueListenableBuilder<String>(
        valueListenable: downloadFolderNotifier,
        builder: (context, value, child) => Text(
          downloadFolderLabel(value),
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
      ),
      trailing: const Icon(Icons.chevron_right_rounded),
      onTap: () => showDownloadFolderDialog(context),
    );
  }

  /// Backfills acoustic features so smart playlists have something to match.
  ///
  /// Only downloads were ever analysed, so a library that came from anywhere
  /// else had no features at all and every smart playlist and mood shelf sat
  /// at zero tracks. This is the way to fix that for music already on disk.
  Widget analyseLibraryListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.graphic_eq_rounded, size: 30),
      title: Text(l10n.analyseLibrary),
      subtitle: ValueListenableBuilder<String>(
        valueListenable: _analyseStatus,
        builder: (context, value, child) => Text(
          value.isEmpty ? l10n.analyseLibraryHint : value,
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
      ),
      onTap: () async {
        if (_analysing) {
          showCenterMessage(l10n.alreadyAnalysing);
          return;
        }
        _analysing = true;
        _analyseStatus.value = l10n.starting;
        final summary = await analyseLibrary(
          onProgress: (p) {
            _analyseStatus.value = p.folderCount > 1
                ? l10n.analyseFolderProgress(
                    p.status,
                    p.folderIndex + 1,
                    p.folderCount,
                  )
                : p.status;
          },
        );
        _analysing = false;
        if (summary.unavailable) {
          _analyseStatus.value = l10n.notAvailableOnDevice;
        } else if (summary.error != null) {
          _analyseStatus.value = l10n.failedWithError('${summary.error}');
        } else {
          _analyseStatus.value =
              l10n.analyseSummary(summary.analysed, summary.skipped) +
              (summary.pending > 0
                  ? l10n.analyseUnreadable(summary.pending)
                  : '');
        }
      },
    );
  }

  Widget aiListTile(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ValueListenableBuilder(
      valueListenable: aiConfigNotifier,
      builder: (context, config, _) => ListTile(
        leading: Icon(Icons.auto_awesome_rounded, size: iconSize),
        title: Text(l10n.aiProviderAndKey),
        subtitle: Text(
          config == null
              ? l10n.aiNotSetUp
              : '${config.info.label} · ${config.effectiveModel}',
        ),
        onTap: () => openAiSetup(context),
      ),
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
                            await config.switchSource(tmp);
                            setState(() {});

                            Loader.reload();
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

  /// What the archive costs on this device, and how to get some of it back.
  Widget storageListTile(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListTile(
      leading: const Icon(Icons.sd_storage_outlined, size: 30),
      title: Text(l10n.storage),
      subtitle: Text(
        l10n.storageHint,
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      onTap: () => showStorageCleanupSheet(context),
    );
  }

  /// The same song held twice, typically an AAC copy and a later ALAC one.
  Widget duplicatesListTile(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListTile(
      leading: const Icon(Icons.library_add_check_outlined, size: 30),
      title: Text(l10n.removeDuplicates),
      subtitle: Text(
        l10n.removeDuplicatesHint,
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      onTap: () => removeDuplicates(context),
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
}
