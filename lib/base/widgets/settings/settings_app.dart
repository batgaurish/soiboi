part of 'settings_list.dart';

/// Premium, notifications, startup, exit, updates and logs.
mixin _AppSettings on _SettingsBase {
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

  Widget notificationsListTile() {
    final l10n = AppLocalizations.of(context);
    return ListTile(
      leading: Icon(Icons.notifications_outlined, size: iconSize),
      title: Text(l10n.notifications),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l10n.notificationsHint,
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              style: TextButton.styleFrom(
                padding: EdgeInsets.zero,
                visualDensity: VisualDensity.compact,
              ),
              onPressed: _sendTestNotification,
              child: Text(l10n.sendATest),
            ),
          ),
        ],
      ),
      trailing: SizedBox(
        width: 50,
        child: MySwitch(
          semanticLabel: l10n.notifications,
          valueNotifier: notificationsEnabledNotifier,
          onToggleCallBack: () {
            setting.save();
            if (notificationsEnabledNotifier.value) {
              notifications.requestPermission();
            }
          },
        ),
      ),
    );
  }

  /// Shows, updates and dismisses a progress notification, then posts a
  /// result: every call a feature makes, so both platforms can be checked
  /// by hand.
  Future<void> _sendTestNotification() async {
    final l10n = AppLocalizations.of(context);
    if (!notificationsEnabledNotifier.value) {
      showCenterMessage(l10n.notificationsOff);
      return;
    }
    if (!await notifications.requestPermission()) {
      showCenterMessage(l10n.notificationsNotAllowed);
      return;
    }
    const key = 'settings-test';
    for (var step = 0; step <= 5; step++) {
      await notifications.show(
        key,
        AppNotification(
          kind: NotificationKind.progress,
          title: l10n.testDownload,
          body: l10n.stepOf(step + 1, 6),
          progress: step * 20,
        ),
      );
      await Future<void>.delayed(const Duration(milliseconds: 700));
    }
    await notifications.dismiss(key);
    await notifications.show(
      '$key-result',
      AppNotification(
        kind: NotificationKind.result,
        title: l10n.testFinished,
        body: l10n.testFinishedBody,
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
          semanticLabel: l10n.autoPlayOnStartup,
          valueNotifier: autoPlayOnStartupNotifier,
          onToggleCallBack: () {
            setting.save();
          },
        ),
      ),
    );
  }

  Widget exitOnCloseListTile(AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(powerOffImage),

      title: Text(l10n.closeAction),
      trailing: SizedBox(
        width: 150,
        child: Row(
          children: [
            Spacer(),
            MySwitch(
              semanticLabel: l10n.closeAction,
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
  Widget checkUpdateListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: AppIcon(checkUpdateImage, size: iconSize),
      title: Text(l10n.checkUpdate),
      subtitle: Text(
        l10n.youHaveVersion(versionNumber),
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      onTap: () async {
        showCenterMessage(l10n.checkingForUpdates);
        final check = await checkForUpdate();
        if (!context.mounted) return;
        switch (check.state) {
          case UpdateState.available:
            await showUpdateSheet(context, check.release!);
          case UpdateState.upToDate:
            showCenterMessage(l10n.alreadyLatest);
          case UpdateState.failed:
            showCenterMessage(
              l10n.couldNotCheckUpdates('${check.error}'),
              duration: 5000,
            );
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
                                    showCenterMessage(l10n.exportedTo(result));
                                  }
                                } else {
                                  result = '${appDocsDir.path}/logs';
                                  logger.export2Directory(result);
                                  showCenterMessage(
                                    l10n.exportedTo('Soiboi/logs'),
                                  );
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
