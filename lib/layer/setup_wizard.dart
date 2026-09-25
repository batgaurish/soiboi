/// First-run setup: music folders, Apple Music sign-in, download options,
/// ListenBrainz, then a summary. Every step can be skipped.
///
/// Shown in place of the app on the first launch (and while a local library
/// has no folders), and later from Settings > Setup wizard. It hosts the
/// app's existing pieces rather than copies of them: the folder manager, the
/// lossless setup, the Apple sign-in page, the download pickers and the
/// ListenBrainz form are the same widgets Settings uses.
library;

import 'dart:io';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/cookie_store.dart' as cookie_store;
import 'package:soiboi/base/services/listenbrainz_service.dart';
import 'package:soiboi/base/services/wrapper_service.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:soiboi/base/utils/source_type.dart';
import 'package:soiboi/base/widgets/connect_client_widget.dart';
import 'package:soiboi/base/widgets/download_options.dart';
import 'package:soiboi/base/widgets/listenbrainz_form.dart';
import 'package:soiboi/base/widgets/lossless_setup.dart';
import 'package:soiboi/base/widgets/manage_music_folders.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/layer/apple_signin_layer.dart';

/// How the wizard ended.
class SetupResult {
  const SetupResult({
    required this.openDownloads,
    required this.foldersChanged,
  });

  /// The user chose "Find music to download" rather than "Start listening".
  final bool openDownloads;

  /// The library's folders differ from when the wizard opened, so the
  /// library needs a sync.
  final bool foldersChanged;
}

enum _Step { music, apple, download, listenBrainz, done }

class SetupWizard extends StatefulWidget {
  const SetupWizard({super.key, required this.onFinish, this.firstRun = false});

  /// Called once, when the user leaves the summary.
  final Future<void> Function(SetupResult result) onFinish;

  /// The first launch: the music source can still be chosen, and Back on
  /// the first step leaves the app instead of closing a page.
  final bool firstRun;

  @override
  State<SetupWizard> createState() => _SetupWizardState();
}

class _SetupWizardState extends State<SetupWizard> {
  var _step = _Step.music;
  bool _showLossless = false;
  bool _finishing = false;
  late final List<String> _foldersAtStart;

  @override
  void initState() {
    super.initState();
    _foldersAtStart = [for (final folder in library.folderList) folder.id];
  }

  /// Rebuilds whatever reports progress when any of it changes.
  final _progress = Listenable.merge([
    cookie_store.signedInNotifier,
    wrapperService.signedIn,
    wrapperService.state,
    downloadCodecNotifier,
    downloadFolderNotifier,
    listenBrainzUserNotifier,
  ]);

  bool get _foldersChanged {
    final now = [for (final folder in library.folderList) folder.id];
    if (now.length != _foldersAtStart.length) return true;
    for (var i = 0; i < now.length; i++) {
      if (now[i] != _foldersAtStart[i]) return true;
    }
    return false;
  }

  /// ALAC chosen, but only the lossless sign-in can download it.
  bool get _alacWithoutWrapper =>
      downloadCodecNotifier.value == 'alac' && !wrapperService.signedIn.value;

  bool get _hasMusic => isStreamSource || library.folderList.isNotEmpty;

  /// Whether the step has something set, which turns "Skip" into "Next".
  bool _isSet(_Step step) => switch (step) {
    _Step.music => _hasMusic,
    _Step.apple => cookie_store.hasAppleAuth,
    _Step.download => true,
    _Step.listenBrainz => listenBrainzUserNotifier.value.trim().isNotEmpty,
    _Step.done => true,
  };

  void _go(_Step step) => setState(() {
    _step = step;
    _showLossless = false;
  });

  void _next() => _go(_Step.values[_step.index + 1]);

  void _back() => _go(_Step.values[_step.index - 1]);

  Future<void> _finish({required bool openDownloads}) async {
    if (_finishing) return;
    setState(() => _finishing = true);
    await widget.onFinish(
      SetupResult(
        openDownloads: openDownloads,
        foldersChanged: _foldersChanged,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final count = _Step.values.length;
    final first = _step == _Step.music;
    return PopScope(
      canPop: first && !widget.firstRun,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        if (!first) {
          _back();
        } else if (widget.firstRun) {
          SystemNavigator.pop();
        }
      },
      child: Scaffold(
        backgroundColor: pageBackgroundColor.value,
        body: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 680),
              child: ListenableBuilder(
                listenable: _progress,
                builder: (context, _) => Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // Stays in place while the step below is replaced, so a
                    // screen reader hears its new label on Next and Back.
                    Semantics(
                      liveRegion: true,
                      label:
                          'Step ${_step.index + 1} of $count: '
                          '${_title(_step)}',
                      child: const SizedBox(height: 1),
                    ),
                    Expanded(
                      child: AnimatedSwitcher(
                        duration: motionDuration(
                          const Duration(milliseconds: 200),
                        ),
                        // Top, not the default centre: a short step would
                        // otherwise float in the middle of the page.
                        layoutBuilder: (current, previous) => Stack(
                          alignment: Alignment.topCenter,
                          children: [...previous, ?current],
                        ),
                        // The header scrolls with the step: at large text
                        // sizes a fixed header and footer left no room.
                        child: SingleChildScrollView(
                          key: ValueKey(_step),
                          padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.stretch,
                            children: [_header(count), _body()],
                          ),
                        ),
                      ),
                    ),
                    _footer(),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _header(int count) {
    final number = _step.index + 1;
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Step $number of $count',
                  style: TextStyle(fontSize: 13, color: textColor.value),
                ),
              ),
              // Hidden rather than removed on the summary, so the header
              // keeps its height and the title does not jump.
              Visibility(
                visible: _step != _Step.done,
                maintainSize: true,
                maintainAnimation: true,
                maintainState: true,
                child: TextButton(
                  onPressed: () => _go(_Step.done),
                  child: const Text('Skip setup'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          // The value stays the percentage a progress bar must report; the
          // "Step n of 5" text above already says it in words.
          LinearProgressIndicator(
            value: number / count,
            semanticsLabel: 'Setup progress',
          ),
          const SizedBox(height: 16),
          Semantics(
            header: true,
            child: Text(
              _title(_step),
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.bold,
                color: highlightTextColor.value,
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _title(_Step step) => switch (step) {
    _Step.music => 'Your music',
    _Step.apple => 'Apple Music sign-in',
    _Step.download => 'Download options',
    _Step.listenBrainz => 'ListenBrainz',
    _Step.done => 'All set',
  };

  Widget _footer() {
    final last = _step == _Step.done;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
      child: Wrap(
        alignment: WrapAlignment.spaceBetween,
        crossAxisAlignment: WrapCrossAlignment.center,
        runSpacing: 8,
        children: [
          if (_step != _Step.music)
            TextButton(onPressed: _back, child: const Text('Back'))
          else
            const SizedBox.shrink(),
          if (last)
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (cookie_store.hasAppleAuth)
                  OutlinedButton(
                    onPressed: _finishing
                        ? null
                        : () => _finish(openDownloads: true),
                    child: const Text('Find music to download'),
                  ),
                FilledButton(
                  onPressed: _finishing
                      ? null
                      : () => _finish(openDownloads: false),
                  child: const Text('Start listening'),
                ),
              ],
            )
          else
            FilledButton(
              onPressed: _next,
              child: Text(_isSet(_step) ? 'Next' : 'Skip this step'),
            ),
        ],
      ),
    );
  }

  Widget _body() => switch (_step) {
    _Step.music => _musicStep(),
    _Step.apple => _appleStep(),
    _Step.download => _downloadStep(),
    _Step.listenBrainz => _listenBrainzStep(),
    _Step.done => _summary(),
  };

  Widget _intro(String text) => Padding(
    padding: const EdgeInsets.only(bottom: 16),
    child: Text(
      text,
      style: TextStyle(fontSize: 14, height: 1.4, color: textColor.value),
    ),
  );

  Widget _subheading(String text) => Padding(
    padding: const EdgeInsets.only(top: 8, bottom: 4),
    child: Semantics(
      header: true,
      child: Text(
        text,
        style: TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.w600,
          color: highlightTextColor.value,
        ),
      ),
    ),
  );

  // Step 1 ------------------------------------------------------------------

  Widget _musicStep() {
    final l10n = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _intro(
          'Soiboi plays music stored on this device or on a server you run. '
          'Add the folders that hold your music; downloads can go into one '
          'of them later.',
        ),
        if (widget.firstRun) ...[
          _subheading(l10n.chooseMusicSource),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final type in const [
                SourceType.local,
                SourceType.webdav,
                SourceType.navidrome,
                SourceType.emby,
              ])
                _sourceCard(type),
            ],
          ),
          const SizedBox(height: 12),
          if (sourceType != SourceType.local)
            Card(
              child: ConnectClientWidget(
                key: ValueKey(sourceType),
                sourceType: sourceType,
              ),
            ),
        ] else if (isStreamSource)
          _intro(
            'Music comes from your ${getSourceTypeDisplayName(l10n, sourceType)} '
            'server. To change it, use Switch Source in Settings.',
          ),
        if (isNotStreamSource) ...[
          _subheading('Music folders'),
          Card(
            child: ManageMusicFolders(
              key: ValueKey(sourceType),
              inline: true,
              onChanged: () {
                if (mounted) setState(() {});
              },
            ),
          ),
        ],
      ],
    );
  }

  Widget _sourceCard(SourceType type) {
    final selected = sourceType == type;
    final name = getSourceTypeDisplayName(AppLocalizations.of(context), type);
    // One node: "Local, selected, button", with the tap on it.
    return MergeSemantics(
      child: Semantics(
        selected: selected,
        button: true,
        child: SizedBox(
          width: 140,
          height: 120,
          child: Card(
            clipBehavior: Clip.antiAlias,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
              side: selected
                  ? BorderSide(color: highlightTextColor.value, width: 2)
                  : BorderSide.none,
            ),
            child: InkWell(
              mouseCursor: SystemMouseCursors.click,
              onTap: () async {
                sourceType = type;
                library = Library();
                if (isNotStreamSource) await library.initFolders();
                if (mounted) setState(() {});
              },
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  children: [
                    Expanded(
                      child: ExcludeSemantics(
                        child: Image(
                          image: getSourceTypeImage(type),
                          color:
                              type == SourceType.local ||
                                  type == SourceType.webdav
                              ? iconColor.value
                              : null,
                        ),
                      ),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        if (selected)
                          Icon(
                            Icons.check_circle,
                            size: 16,
                            color: highlightTextColor.value,
                          ),
                        if (selected) const SizedBox(width: 4),
                        Flexible(
                          child: Text(
                            name,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(color: highlightTextColor.value),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  // Step 2 ------------------------------------------------------------------

  Widget _appleStep() {
    final lossless =
        wrapperService.state.value.stage != WrapperStage.unsupported;
    final desktop = !Platform.isAndroid && !Platform.isIOS;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _intro(
          'Downloading needs an Apple Music subscription. Choose one way to '
          'sign in; you can add the other later. Playing music you already '
          'have needs neither.',
        ),
        _signInStatus(),
        const SizedBox(height: 12),
        // Side by side where there is room, one above the other on a phone.
        LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth >= 560
                ? (constraints.maxWidth - 12) / 2
                : constraints.maxWidth;
            return Wrap(
              spacing: 12,
              runSpacing: 12,
              children: [
                _choice(
                  width: width,
                  title: 'Lossless sign-in',
                  points: [
                    'ALAC (Apple Lossless) and AAC',
                    'You sign in with your Apple ID here. The wrapper on this '
                        "device passes it to Apple's own sign-in; Soiboi does "
                        'not keep your password',
                    'Needs one extra file: the Apple Music app, from a link '
                        'Soiboi gives you',
                    if (!lossless) 'Not available in this build',
                  ],
                  action: lossless
                      ? FilledButton(
                          onPressed: () =>
                              setState(() => _showLossless = !_showLossless),
                          child: Text(
                            _showLossless
                                ? 'Hide lossless setup'
                                : wrapperService.signedIn.value
                                ? 'Manage lossless'
                                : 'Set up lossless',
                          ),
                        )
                      : null,
                ),
                _choice(
                  width: width,
                  title: 'Browser sign-in',
                  points: [
                    'AAC only',
                    desktop
                        ? 'Export cookies.txt from a browser where you are signed '
                              'in to music.apple.com'
                        : "Sign in on Apple's own page inside Soiboi",
                    'The session expires after a while; sign in again then',
                  ],
                  action: OutlinedButton(
                    onPressed: () async {
                      await Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (_) => const AppleSignInLayer(),
                        ),
                      );
                      await cookie_store.refreshSessionState();
                    },
                    child: Text(
                      desktop
                          ? 'Import cookies.txt'
                          : "Sign in on Apple's page",
                    ),
                  ),
                ),
              ],
            );
          },
        ),
        if (_showLossless) ...[
          const SizedBox(height: 12),
          const Card(child: LosslessSetup()),
        ],
      ],
    );
  }

  Widget _signInStatus() {
    final wrapper = wrapperService.signedIn.value;
    final cookies = cookie_store.signedInNotifier.value;
    final account = wrapperService.state.value.account;
    final text = wrapper
        ? 'Signed in with lossless sign-in'
              '${account == null ? '' : ' as $account'}.'
        : cookies
        ? 'Signed in with browser sign-in.'
        : 'Not signed in yet.';
    return Semantics(
      liveRegion: true,
      child: Row(
        children: [
          Icon(
            wrapper || cookies
                ? Icons.check_circle_rounded
                : Icons.info_outline_rounded,
            size: 20,
            color: highlightTextColor.value,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              text,
              style: TextStyle(color: highlightTextColor.value),
            ),
          ),
        ],
      ),
    );
  }

  Widget _choice({
    required double width,
    required String title,
    required List<String> points,
    Widget? action,
  }) {
    return SizedBox(
      width: width,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Semantics(
                header: true,
                child: Text(
                  title,
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                    color: highlightTextColor.value,
                  ),
                ),
              ),
              const SizedBox(height: 8),
              for (final point in points)
                Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      ExcludeSemantics(
                        child: Text(
                          '•  ',
                          style: TextStyle(color: textColor.value),
                        ),
                      ),
                      Expanded(
                        child: Text(
                          point,
                          style: TextStyle(
                            fontSize: 13,
                            height: 1.35,
                            color: textColor.value,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              if (action != null) ...[const SizedBox(height: 8), action],
            ],
          ),
        ),
      ),
    );
  }

  // Step 3 ------------------------------------------------------------------

  Widget _downloadStep() {
    final alacWithoutWrapper = _alacWithoutWrapper;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _intro('You can change both later in Settings.'),
        _subheading('Quality'),
        const DownloadQualityPicker(),
        if (alacWithoutWrapper)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
            child: Semantics(
              liveRegion: true,
              child: Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: 8,
                children: [
                  Text(
                    'ALAC needs the lossless sign-in.',
                    style: TextStyle(color: highlightTextColor.value),
                  ),
                  TextButton(
                    onPressed: () => _go(_Step.apple),
                    child: const Text('Set it up'),
                  ),
                ],
              ),
            ),
          ),
        const SizedBox(height: 8),
        _subheading('Save downloads to'),
        const DownloadFolderPicker(shrinkWrap: true),
      ],
    );
  }

  // Step 4 ------------------------------------------------------------------

  Widget _listenBrainzStep() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _intro(
          'Optional. With your ListenBrainz username, Home ranks music by '
          'everything you listen to and Downloads suggests weekly playlists. '
          'Soiboi only reads your public stats: no password, no token.',
        ),
        const ListenBrainzForm(),
      ],
    );
  }

  // Step 5 ------------------------------------------------------------------

  Widget _summary() {
    final l10n = AppLocalizations.of(context);
    final folders = library.folderList.length;
    final music = isStreamSource
        ? getSourceTypeDisplayName(l10n, sourceType)
        : folders == 0
        ? 'No folders yet'
        : folders == 1
        ? library.folderList.first.path
        : '$folders folders';
    final apple = wrapperService.signedIn.value
        ? 'Lossless sign-in'
        : cookie_store.signedInNotifier.value
        ? 'Browser sign-in'
        : 'Not signed in: downloading is off until you sign in';
    final user = listenBrainzUserNotifier.value.trim();
    final alacWithoutWrapper = _alacWithoutWrapper;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _intro(
          'Here is how Soiboi is set up. You can run this again from Settings '
          '> Setup wizard.',
        ),
        _row('Music', music, _hasMusic, _Step.music),
        _row('Apple Music', apple, cookie_store.hasAppleAuth, _Step.apple),
        _row(
          'Quality',
          alacWithoutWrapper
              ? '${downloadCodecLabels['alac']}: needs the lossless sign-in'
              : downloadCodecLabels[downloadCodecNotifier.value] ??
                    downloadCodecNotifier.value,
          !alacWithoutWrapper,
          alacWithoutWrapper ? _Step.apple : _Step.download,
        ),
        _row(
          'Downloads go to',
          downloadFolderLabel(downloadFolderNotifier.value),
          true,
          _Step.download,
        ),
        _row(
          'ListenBrainz',
          user.isEmpty ? 'Not connected' : user,
          user.isNotEmpty,
          _Step.listenBrainz,
        ),
      ],
    );
  }

  Widget _row(String label, String value, bool ok, _Step step) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        ok ? Icons.check_circle_rounded : Icons.remove_circle_outline_rounded,
        color: highlightTextColor.value,
        semanticLabel: ok ? 'Done' : 'Not set',
      ),
      title: Text(label, style: TextStyle(color: highlightTextColor.value)),
      subtitle: Text(
        value,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(color: textColor.value),
      ),
      trailing: TextButton(
        onPressed: () => _go(step),
        child: Text('Change', semanticsLabel: 'Change $label'),
      ),
    );
  }
}
