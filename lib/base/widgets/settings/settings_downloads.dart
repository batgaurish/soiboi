part of 'settings_list.dart';

/// Apple Music account, download quality, lossless, logs and lyrics.
mixin _DownloadSettings on _SettingsBase {
  Widget downloadLogsListTile(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListTile(
      leading: Icon(Icons.article_outlined, size: iconSize),
      title: Text(l10n.downloadLogs),
      subtitle: Text(
        l10n.downloadLogsHint,
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      onTap: () => showSavedDownloadLogs(context),
      trailing: SizedBox(
        width: 50,
        child: MySwitch(
          semanticLabel: l10n.downloadLogs,
          valueNotifier: showDownloadLogsNotifier,
          onToggleCallBack: () {
            setting.save();
          },
        ),
      ),
    );
  }

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
          value.isEmpty ? l10n.listenBrainzNotConnected : value,
          style: TextStyle(fontSize: 12, color: textColor.value),
        ),
      ),
      onTap: () => showAnimationDialog(
        context: context,
        child: SizedBox(
          width: 340,
          height: 280,
          child: SingleChildScrollView(
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
                  l10n.listenBrainzExplainer,
                  style: TextStyle(fontSize: 12, color: textColor.value),
                ),
                const SizedBox(height: 14),
                ListenBrainzForm(
                  autofocus: true,
                  onDone: () => Navigator.of(context).pop(),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// Apple Music session. Required for downloading; the player itself works
  /// without it.
  Widget appleAccountListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.account_circle_outlined, size: 30),
      title: Text(l10n.appleMusicAccount),
      subtitle: ListenableBuilder(
        listenable: Listenable.merge([
          cookie_store.signedInNotifier,
          cookie_store.sessionExpiryNotifier,
          wrapperService.signedIn,
        ]),
        builder: (context, child) {
          final signedIn = cookie_store.signedInNotifier.value;
          final expiry = cookie_store.sessionExpiryNotifier.value;
          final detail = wrapperService.signedIn.value
              ? l10n.signedInViaWrapper
              : signedIn && expiry != null
              ? l10n.signedInExpires(
                  expiry.toLocal().toString().split(' ').first,
                )
              : signedIn
              ? l10n.signedIn
              : l10n.notSignedInNeeded;
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
                child: Text(l10n.signOut),
              )
            : const Icon(Icons.chevron_right_rounded),
      ),
      onTap: () async {
        await Navigator.of(
          context,
        ).push(MaterialPageRoute(builder: (_) => const AppleSignInLayer()));
        await cookie_store.refreshSessionState();
      },
    );
  }

  /// Download quality: which codec gamdl should fetch.
  Widget downloadQualityListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.high_quality_outlined, size: 30),
      title: Text(l10n.downloadQuality),
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
            width: 340,
            height: 330,
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(18.0),
              child: Column(
                children: [
                  Text(
                    l10n.downloadQuality,
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 12),
                  DownloadQualityPicker(
                    onChanged: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  /// The first-run wizard again: folders, Apple Music sign-in, download
  /// options and ListenBrainz in one guided pass.
  Widget setupWizardListTile(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListTile(
      leading: const Icon(Icons.auto_fix_high_outlined, size: 30),
      title: Text(l10n.setupWizard),
      subtitle: Text(
        l10n.setupWizardHint,
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      trailing: const Icon(Icons.chevron_right_rounded),
      onTap: () {
        final navigator = Navigator.of(context, rootNavigator: true);
        navigator.push(
          MaterialPageRoute(
            builder: (_) => SetupWizard(
              onFinish: (result) async {
                navigator.pop();
                setupWizardDoneNotifier.value = true;
                setting.save();
                if (result.openDownloads) {
                  layersManager.switchRootLayer('downloads');
                }
                if (result.foldersChanged) await Loader.sync();
              },
            ),
          ),
        );
      },
    );
  }

  /// Guided setup for the bundled lossless wrapper.
  Widget losslessListTile(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListTile(
      leading: const Icon(Icons.high_quality_rounded, size: 30),
      title: Text(l10n.losslessAlac),
      subtitle: ListenableBuilder(
        listenable: Listenable.merge([
          wrapperService.state,
          wrapperService.signedIn,
        ]),
        builder: (context, _) => Text(switch (wrapperService
            .state
            .value
            .stage) {
          WrapperStage.ready => l10n.ready,
          WrapperStage.needsLibraries => l10n.needsSetup,
          WrapperStage.signedOut || WrapperStage.needsCode => l10n.needsSignIn,
          WrapperStage.unsupported => l10n.notAvailableInBuild,
          // Stopped between downloads is normal once signed in.
          _ when wrapperService.signedIn.value => l10n.signedInStartsOnDemand,
          _ => l10n.setUp,
        }, style: TextStyle(fontSize: 12, color: textColor.value)),
      ),
      trailing: const Icon(Icons.chevron_right_rounded),
      onTap: () =>
          showAnimationDialog(context: context, child: const LosslessSetup()),
    );
  }

  /// Advanced: a .wvd for AAC, or an external wrapper-v2 you run yourself.
  Widget widevineListTile(BuildContext context, AppLocalizations l10n) {
    return ListTile(
      leading: const Icon(Icons.lock_outline, size: 30),
      title: Text(l10n.widevineAdvanced),
      subtitle: ValueListenableBuilder(
        valueListenable: useWrapperNotifier,
        builder: (context, _, child) {
          final detail = useWrapperNotifier.value
              ? wrapperUrlNotifier.value.isEmpty
                    ? l10n.wrapperEnabledSetUrl
                    : l10n.wrapperWithUrl(wrapperUrlNotifier.value)
              : wvdPathNotifier.value != null &&
                    wvdPathNotifier.value!.isNotEmpty
              ? l10n.wvdFileSet
              : l10n.usingBuiltInDevice;
          return Text(
            detail,
            style: TextStyle(fontSize: 12, color: textColor.value),
          );
        },
      ),
      trailing: const Icon(Icons.chevron_right_rounded),
      onTap: () async {
        final wvdController = TextEditingController(
          text: wvdPathNotifier.value ?? '',
        );
        final wrapperController = TextEditingController(
          text: wrapperUrlNotifier.value,
        );
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
                    Text(
                      l10n.widevineConfiguration,
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      l10n.widevineExplainer,
                      style: TextStyle(fontSize: 11, color: textColor.value),
                    ),
                    const SizedBox(height: 16),
                    // Mode toggle
                    SwitchListTile(
                      title: Text(l10n.useWrapperService),
                      subtitle: Text(l10n.insteadOfWvd),
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
                        decoration: InputDecoration(
                          isDense: true,
                          border: OutlineInputBorder(),
                          labelText: l10n.wrapperUrl,
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
                              decoration: InputDecoration(
                                isDense: true,
                                border: OutlineInputBorder(),
                                labelText: l10n.wvdFilePath,
                                hintText: '/path/to/device.wvd',
                              ),
                              style: const TextStyle(fontSize: 13),
                            ),
                          ),
                          const SizedBox(width: 8),
                          IconButton(
                            tooltip: l10n.chooseWvdFile,
                            icon: labelIcon(
                              l10n.chooseWvdFile,
                              const Icon(Icons.folder_open),
                            ),
                            onPressed: () async {
                              final result = await FilePicker.pickFiles(
                                type: FileType.custom,
                                allowedExtensions: ['wvd'],
                              );
                              if (result != null && result.files.isNotEmpty) {
                                wvdController.text =
                                    result.files.first.path ?? '';
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
                          child: Text(l10n.cancel),
                        ),
                        const SizedBox(width: 8),
                        FilledButton(
                          onPressed: () {
                            if (useWrapperNotifier.value) {
                              wrapperUrlNotifier.value = wrapperController.text
                                  .trim();
                              wvdPathNotifier.value = null;
                            } else {
                              wvdPathNotifier.value =
                                  wvdController.text.trim().isEmpty
                                  ? null
                                  : wvdController.text.trim();
                              wrapperUrlNotifier.value = '';
                            }
                            setting.save();
                            Navigator.of(context).pop();
                          },
                          child: Text(l10n.save),
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
      title: Text(l10n.fetchLyricsLrclib),
      subtitle: Text(
        l10n.fetchLyricsLrclibHint,
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      trailing: SizedBox(
        width: 50,
        child: MySwitch(
          semanticLabel: l10n.fetchLyricsLrclib,
          valueNotifier: lrclibEnabledNotifier,
          onToggleCallBack: () {
            setting.save();
          },
        ),
      ),
    );
  }

  /// The download queue, reachable from anywhere.
  ///
  /// The Downloads screen shows the same list inline, but a fifty-track
  /// playlist runs for several minutes and nobody sits on that screen waiting
  /// — this is how you check on it from wherever you actually are.
  Widget downloadQueueListTile(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListTile(
      leading: const Icon(Icons.download_outlined, size: 30),
      title: Text(l10n.downloadQueue),
      subtitle: ValueListenableBuilder<List<DownloadJob>>(
        valueListenable: downloadQueue.jobs,
        builder: (context, jobs, child) {
          final active = jobs.where((job) => job.isActive).length;
          final failed = jobs
              .where((job) => job.state == DownloadJobState.failed)
              .length;
          return Text(
            active > 0
                ? l10n.inProgressCount(active)
                : failed > 0
                ? l10n.failedTapToRetry(failed)
                : l10n.nothingDownloading,
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
}
