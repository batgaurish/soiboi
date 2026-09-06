/// Downloads: archive Apple Music into the local library.
///
/// Everything runs on this device. There is no server, no Docker and no LAN
/// dependency — the archival pipeline is bundled inside the app and invoked
/// through [pipelineRunner], and discovery comes straight from ListenBrainz.
///
/// Two things gate downloading, and the screen reports each separately so a
/// failure is legible rather than a generic error: an Apple Music session
/// (cookies), and a working pipeline runtime for this platform.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/cookie_store.dart';
import 'package:soiboi/base/services/discovery_service.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/listenbrainz_service.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';
import 'package:soiboi/base/services/preview_player.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/portrait_view/custom_appbar_leading.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:soiboi/layer/apple_signin_layer.dart';
import 'package:smooth_corner/smooth_corner.dart';

/// Makes archived files visible in the library.
///
/// Downloads land in the app's own storage, which is not a folder anyone would
/// ever add by hand — so without this the archive card's promise that files are
/// "added to your library on this device" is simply false, and Songs still
/// reads zero after a successful download.
///
/// Registered on first use rather than at startup, so someone who never
/// downloads anything does not get a phantom empty folder in Manage Folders.
Future<void> syncArchivedToLibrary() async {
  final ids = library.folderList.map((folder) => folder.id).toList();
  if (!ids.contains(downloadOutputDir)) {
    await library.updateFolders([...ids, downloadOutputDir]);
  }
  // Synced regardless: the folder may already be registered from an earlier
  // download, and the new file still has to be picked up.
  if (!Loader.busy) await Loader.sync();
}

class DownloadsLayer extends StatefulWidget {
  const DownloadsLayer({super.key});

  @override
  State<DownloadsLayer> createState() => _DownloadsLayerState();
}

class _DownloadsLayerState extends State<DownloadsLayer> {
  final _urlController = TextEditingController();
  List<LbPlaylist> _discover = const [];
  final _lbUserController = TextEditingController();

  /// Progress for the download in flight, if any.
  int _progress = 0;
  String _status = '';
  bool _busy = false;
  String? _error;
  final List<String> _completed = [];

  @override
  void initState() {
    super.initState();
    _load();
    listenBrainzUserNotifier.addListener(_load);
    // Probe once so the screen can explain itself before anything is attempted.
    refreshPipelineCapabilities();
  }

  @override
  void dispose() {
    listenBrainzUserNotifier.removeListener(_load);
    _urlController.dispose();
    _lbUserController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final playlists = await discoveryPlaylists();
    if (mounted) setState(() => _discover = playlists);
  }

  Future<void> _archiveUrl() async {
    final url = _urlController.text.trim();
    if (url.isEmpty || _busy) return;
    setState(() {
      _busy = true;
      _error = null;
      _progress = 0;
      _status = 'Starting';
    });

    await for (final event in pipelineRunner.run('download', {
      'url': url,
      'cookies_path': cookiesPath,
      'output_dir': downloadOutputDir,
      'temp_dir': downloadTempDir,
    })) {
      if (!mounted) return;
      if (event.isProgress) {
        setState(() {
          _progress = event.progress;
          _status = event.status;
        });
      } else if (event.isError) {
        setState(() => _error = event.message);
      } else if (event.isDone) {
        setState(() => _completed.insert(0, url));
        _urlController.clear();
        await syncArchivedToLibrary();
      }
    }
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    final body = ListenableBuilder(
      listenable: Listenable.merge([
        signedInNotifier,
        pipelineCapabilitiesNotifier,
      ]),
      builder: (context, _) => RefreshIndicator(
        onRefresh: () async {
          await refreshPipelineCapabilities();
          await _load();
        },
        child: CustomScrollView(
          slivers: [
            if (_error != null)
              SliverToBoxAdapter(child: _banner(_error!, isError: true)),
            SliverToBoxAdapter(child: _readinessCard()),
            SliverToBoxAdapter(child: _archiveCard()),
            if (_busy) SliverToBoxAdapter(child: _progressCard()),
            if (_completed.isNotEmpty)
              SliverToBoxAdapter(child: _completedCard()),
            if (_discover.isNotEmpty)
              SliverToBoxAdapter(child: _discoverCard())
            else if (listenBrainzUserNotifier.value.trim().isEmpty)
              SliverToBoxAdapter(child: _discoverPromptCard()),
            const SliverToBoxAdapter(child: SizedBox(height: 90)),
          ],
        ),
      ),
    );

    // Same reason as Home: on a narrow layout the drawer is the only way out,
    // and this screen has no portrait wrapper to supply a menu button. Built
    // outside the ListenableBuilder so the tree shape never changes.
    if (!isTooNarrow(context)) return body;
    return Scaffold(
      backgroundColor: Colors.transparent,
      resizeToAvoidBottomInset: false,
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: customAppBarLeading(context),
        backgroundColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        title: const Text('Downloads'),
        centerTitle: true,
      ),
      body: body,
    );
  }

  Widget _card({required String title, required Widget child, Widget? action}) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
      child: SmoothClipRRect(
        smoothness: 1,
        borderRadius: BorderRadius.circular(12 * activeFlavour.cornerScale),
        child: Container(
          color: menuColor.value,
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      title,
                      style: TextStyle(
                        fontSize: 15,
                        fontWeight: FontWeight.w600,
                        color: highlightTextColor.value,
                      ),
                    ),
                  ),
                  ?action,
                ],
              ),
              const SizedBox(height: 12),
              child,
            ],
          ),
        ),
      ),
    );
  }

  Widget _banner(String text, {bool isError = false}) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
      child: SmoothClipRRect(
        smoothness: 1,
        borderRadius: BorderRadius.circular(10 * activeFlavour.cornerScale),
        child: Container(
          color: (isError ? Colors.red : seekBarColor.value).withValues(
            alpha: 0.14,
          ),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
          child: Row(
            children: [
              Icon(
                isError ? Icons.error_outline : Icons.info_outline,
                size: 18,
                color: isError ? Colors.red : seekBarColor.value,
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  text,
                  style: TextStyle(fontSize: 13, color: textColor.value),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// The two prerequisites, reported separately.
  ///
  /// Collapsing these into one "not ready" message would hide which of the two
  /// is actually wrong, and they have completely different fixes.
  Widget _readinessCard() {
    final caps = pipelineCapabilitiesNotifier.value;
    final signedIn = signedInNotifier.value;
    final runtimeOk = caps?.canDownload ?? false;
    if (signedIn && runtimeOk) return const SizedBox.shrink();

    return _card(
      title: 'Before you can archive',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _requirement(
            ok: signedIn,
            label: 'Apple Music account',
            detail: signedIn ? 'Signed in' : 'Sign in to download',
            action: signedIn
                ? null
                : () async {
                    await Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => const AppleSignInLayer(),
                      ),
                    );
                    await refreshSessionState();
                  },
          ),
          const SizedBox(height: 10),
          _requirement(
            ok: runtimeOk,
            label: 'Download engine',
            detail: caps?.summary ?? 'Checking…',
          ),
        ],
      ),
    );
  }

  Widget _requirement({
    required bool ok,
    required String label,
    required String detail,
    VoidCallback? action,
  }) {
    return Row(
      children: [
        Icon(
          ok ? Icons.check_circle_outline : Icons.radio_button_unchecked,
          size: 18,
          color: ok ? seekBarColor.value : textColor.value,
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: TextStyle(
                  fontSize: 13.5,
                  color: highlightTextColor.value,
                ),
              ),
              Text(
                detail,
                style: TextStyle(fontSize: 11.5, color: textColor.value),
              ),
            ],
          ),
        ),
        if (action != null)
          TextButton(onPressed: action, child: const Text('Sign in')),
      ],
    );
  }

  Widget _archiveCard() {
    final ready =
        signedInNotifier.value &&
        (pipelineCapabilitiesNotifier.value?.canDownload ?? false);
    return _card(
      title: 'Archive from Apple Music',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _urlController,
                  enabled: ready && !_busy,
                  onSubmitted: (_) => _archiveUrl(),
                  style: TextStyle(fontSize: 14, color: textColor.value),
                  decoration: InputDecoration(
                    hintText: 'https://music.apple.com/…',
                    isDense: true,
                    filled: true,
                    fillColor: searchFieldColor.value,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(
                        8 * activeFlavour.cornerScale,
                      ),
                      borderSide: BorderSide.none,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              FilledButton(
                onPressed: ready && !_busy ? _archiveUrl : null,
                child: const Text('Archive'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            'Songs, albums or playlists. Files are downloaded, tagged and '
            'added to your library on this device.',
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
        ],
      ),
    );
  }

  Widget _progressCard() {
    return _card(
      title: 'Downloading',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(3),
            child: TweenAnimationBuilder<double>(
              // Progress arrives in discrete stage jumps, so easing between
              // them reads as motion rather than stutter.
              tween: Tween(end: _progress / 100),
              duration: activeMotion.medium,
              curve: activeMotion.standard,
              builder: (context, value, _) => LinearProgressIndicator(
                value: value,
                minHeight: 4,
                backgroundColor: buttonColor.value,
                color: seekBarColor.value,
              ),
            ),
          ),
          const SizedBox(height: 6),
          Text(
            _status,
            style: TextStyle(fontSize: 11.5, color: textColor.value),
          ),
        ],
      ),
    );
  }

  Widget _completedCard() {
    return _card(
      title: 'Archived this session (${_completed.length})',
      child: Column(
        children: [
          for (final url in _completed.take(8))
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 3),
              child: Row(
                children: [
                  Icon(
                    Icons.check_rounded,
                    size: 15,
                    color: seekBarColor.value,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      url,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 12,
                        color: textColor.value,
                      ),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  /// Shown instead of the shelf when there is no ListenBrainz username.
  ///
  /// Without this the whole discovery feature is invisible: the shelf renders
  /// only once playlists load, the username lives several screens away in
  /// Settings, and nothing on this screen suggests the feature exists.
  Widget _discoverPromptCard() {
    return _card(
      title: 'Weekly discoveries',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'ListenBrainz builds a weekly exploration and jams playlist from '
            'your listening. Add your username to browse and archive them '
            'here — no account link or token needed, the lists are public.',
            style: TextStyle(fontSize: 12.5, color: textColor.value),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _lbUserController,
                  style: const TextStyle(fontSize: 13),
                  decoration: const InputDecoration(
                    isDense: true,
                    border: OutlineInputBorder(),
                    hintText: 'ListenBrainz username',
                  ),
                  onSubmitted: (_) => _connectListenBrainz(),
                ),
              ),
              const SizedBox(width: 10),
              FilledButton(
                onPressed: _connectListenBrainz,
                child: const Text('Connect'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  void _connectListenBrainz() {
    final user = _lbUserController.text.trim();
    if (user.isEmpty) return;
    listenBrainzUserNotifier.value = user;
    setting.save();
    // The listener added in initState reloads the shelf.
  }

  Widget _discoverCard() {
    return _card(
      title: 'Weekly discoveries',
      child: Column(
        children: [
          for (final playlist in _discover.take(8))
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: Text(
                playlist.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 13.5, color: textColor.value),
              ),
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => showDiscoverPlaylistSheet(context, playlist),
            ),
        ],
      ),
    );
  }
}

/// Opens a discovery playlist: resolved tracks, previews, and archiving.
///
/// Uses the app's own [showAnimationDialog] rather than showModalBottomSheet.
/// The layer system nests navigators per root layer, and a raw modal sheet
/// silently fails to find one from inside a layer.
Future<void> showDiscoverPlaylistSheet(
  BuildContext context,
  LbPlaylist playlist,
) {
  return showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 420,
      height: 480,
      child: _DiscoverPlaylistSheet(playlist: playlist),
    ),
  );
}

class _DiscoverPlaylistSheet extends StatefulWidget {
  const _DiscoverPlaylistSheet({required this.playlist});
  final LbPlaylist playlist;

  @override
  State<_DiscoverPlaylistSheet> createState() => _DiscoverPlaylistSheetState();
}

class _DiscoverPlaylistSheetState extends State<_DiscoverPlaylistSheet> {
  List<DiscoveryTrack>? _tracks;
  String? _error;
  bool _sending = false;

  /// Titles already queued from this sheet, so a row can show it landed rather
  /// than letting the same track be queued repeatedly.
  final Set<String> _queued = {};

  /// Tracks picked for archiving, by row key.
  ///
  /// Empty means the sheet is not in selection mode: a playlist of fifty is
  /// mostly browsed, not curated, so checkboxes stay out of the way until a
  /// long press asks for them.
  final Set<String> _selected = {};

  /// How far through a batch we are, for the button label. A fifty-track
  /// playlist takes minutes, and "Archiving…" alone gives no sign of life.
  int _archivedInBatch = 0;
  int _batchSize = 0;

  /// The playlist's true length while resolution is still running, else null.
  int? _resolving;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    // A preview must not outlive the sheet that started it.
    stopPreview();
    super.dispose();
  }

  Future<void> _load() async {
    // Show whatever the card already resolved so the sheet is not blank while
    // the rest arrives, then always ask for the full list.
    final cached = cachedDiscoveryTracks(widget.playlist.mbid);
    if (cached != null && cached.isNotEmpty) {
      setState(() => _tracks = cached);
    }
    final tracks = await resolveDiscoveryTracks(
      widget.playlist.mbid,
      // Fills the list as matches land. A fifty-track playlist takes a few
      // seconds to resolve, and without this the sheet sits showing whatever
      // the card had prefetched -- which reads as a playlist with four songs.
      onProgress: (resolved, total) {
        if (!mounted) return;
        setState(() {
          _tracks = resolved;
          _resolving = resolved.length < total ? total : null;
        });
      },
    );
    if (!mounted) return;
    setState(() => _resolving = null);
    if (tracks == null) {
      setState(() => _error = 'Could not load this playlist');
      return;
    }
    setState(() => _tracks = tracks);
  }

  /// Archives [tracks] through the on-device pipeline, one at a time.
  ///
  /// The pipeline downloads a single URL per call, so a playlist is a loop.
  /// Sequential rather than parallel on purpose: Apple rate-limits, and the
  /// original pipeline's cooldowns exist for good reason.
  Future<void> _archive(List<DiscoveryTrack> tracks) async {
    setState(() {
      _sending = true;
      _archivedInBatch = 0;
      _batchSize = tracks.length;
    });
    for (final track in tracks) {
      if (!mounted) return;
      final url = track.appleUrl;
      if (url == null) continue;
      await for (final event in pipelineRunner.run('download', {
        'url': url,
        'cookies_path': cookiesPath,
        'output_dir': downloadOutputDir,
        'temp_dir': downloadTempDir,
      })) {
        if (event.isError) {
          if (mounted) setState(() => _error = event.message);
          break;
        }
      }
      if (mounted) {
        setState(() {
          _queued.add(track.title);
          _archivedInBatch++;
        });
      }
    }
    // Once for the batch rather than per track: a library sync walks every
    // registered folder, and doing that between tracks would dominate the run.
    if (_queued.isNotEmpty) await syncArchivedToLibrary();
    if (mounted) {
      setState(() {
        _sending = false;
        // Cleared on success: leaving the selection behind invites archiving
        // the same tracks twice.
        _selected.clear();
      });
    }
  }

  String _rowKey(DiscoveryTrack track) => '${track.artist}|${track.title}';

  /// The bar under the list: what will be archived, and the button to do it.
  ///
  /// Two modes rather than one. With nothing selected the sheet offers the
  /// whole playlist, which is what most people want from a weekly discovery
  /// list; once anything is picked it switches to that selection and offers
  /// select-all and clear, so a long list can be pruned instead of taken
  /// wholesale.
  Widget _actionBar(
    List<DiscoveryTrack> tracks,
    List<DiscoveryTrack> downloadable,
  ) {
    final selecting = _selected.isNotEmpty;
    final chosen = selecting
        ? downloadable.where((t) => _selected.contains(_rowKey(t))).toList()
        : downloadable;

    return Row(
      children: [
        Expanded(
          child: Text(
            _sending
                // Counted, not spinning: a fifty-track playlist takes minutes
                // and "Archiving…" alone gives no sign of progress.
                ? 'Archiving $_archivedInBatch of $_batchSize…'
                : _resolving != null
                // The playlist's real length, not the count matched so far:
                // otherwise the number climbs and looks like tracks appearing
                // out of nowhere.
                ? 'Matching ${tracks.length} of $_resolving…'
                : selecting
                ? '${chosen.length} selected'
                : downloadable.length == tracks.length
                ? '${tracks.length} tracks'
                : '${downloadable.length} of ${tracks.length} matched',
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
        ),
        if (selecting && !_sending) ...[
          TextButton(
            onPressed: () => setState(() {
              if (chosen.length == downloadable.length) {
                _selected.clear();
              } else {
                _selected.addAll(downloadable.map(_rowKey));
              }
            }),
            child: Text(
              chosen.length == downloadable.length ? 'Clear' : 'Select all',
            ),
          ),
          const SizedBox(width: 4),
        ],
        FilledButton(
          // Disabled while matching: archiving now would quietly take only
          // the tracks resolved so far and report it as the whole playlist.
          onPressed: chosen.isEmpty || _sending || _resolving != null
              ? null
              : () => _archive(chosen),
          child: Text(_sending ? 'Archiving…' : 'Archive ${chosen.length}'),
        ),
      ],
    );
  }

  void _toggleSelected(DiscoveryTrack track) {
    final key = _rowKey(track);
    setState(() {
      if (!_selected.remove(key)) _selected.add(key);
    });
  }

  @override
  Widget build(BuildContext context) {
    final tracks = _tracks;
    // Only resolved tracks can be fetched; the rest are shown with their
    // warning so the gap is visible rather than a silent no-op.
    final downloadable = tracks?.where((t) => t.isResolved).toList() ?? const [];

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              widget.playlist.title,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: highlightTextColor.value,
              ),
            ),
            const SizedBox(height: 12),
            if (_error != null)
              Text(_error!, style: const TextStyle(color: Colors.red))
            else if (tracks == null)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 28),
                child: Center(
                  child: Column(
                    children: [
                      CircularProgressIndicator(),
                      SizedBox(height: 12),
                      Text(
                        'Matching tracks to the Apple Music catalog…',
                        style: TextStyle(fontSize: 12),
                      ),
                    ],
                  ),
                ),
              )
            else ...[
              Flexible(
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: tracks.length,
                  itemBuilder: (context, i) {
                    final track = tracks[i];
                    final key = _rowKey(track);
                    final selecting = _selected.isNotEmpty;
                    final selected = _selected.contains(key);
                    return ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      selected: selected,
                      // Only matched tracks can be archived, so only they can
                      // be selected; letting the rest be ticked would build a
                      // selection that silently shrinks on archive.
                      onLongPress: track.isResolved
                          ? () => _toggleSelected(track)
                          : null,
                      onTap: selecting && track.isResolved
                          ? () => _toggleSelected(track)
                          : null,
                      leading: selecting
                          ? Checkbox(
                              value: selected,
                              visualDensity: VisualDensity.compact,
                              onChanged: track.isResolved
                                  ? (_) => _toggleSelected(track)
                                  : null,
                            )
                          : track.artwork == null
                          ? null
                          : ClipRRect(
                              borderRadius: BorderRadius.circular(4),
                              child: Image.network(
                                track.artwork!,
                                width: 38,
                                height: 38,
                                fit: BoxFit.cover,
                                errorBuilder: (_, _, _) =>
                                    const SizedBox(width: 38, height: 38),
                              ),
                            ),
                      title: Text(
                        track.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 13,
                          color: track.isResolved
                              ? highlightTextColor.value
                              : textColor.value,
                        ),
                      ),
                      subtitle: Text(
                        track.warning ?? track.artist,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 11,
                          color: track.isResolved
                              ? textColor.value
                              : Colors.orange,
                        ),
                      ),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (track.previewUrl != null &&
                              track.previewUrl!.isNotEmpty)
                            ValueListenableBuilder(
                              valueListenable: previewingKeyNotifier,
                              builder: (context, playing, child) {
                                final active = playing == key;
                                return IconButton(
                                  iconSize: 19,
                                  visualDensity: VisualDensity.compact,
                                  tooltip: active ? 'Stop' : 'Preview',
                                  onPressed: () =>
                                      togglePreview(key, track.previewUrl),
                                  icon: Icon(
                                    active
                                        ? Icons.stop_circle_outlined
                                        : Icons.play_circle_outline,
                                    color: active ? seekBarColor.value : null,
                                  ),
                                );
                              },
                            ),
                          if (track.isResolved)
                            IconButton(
                              iconSize: 18,
                              visualDensity: VisualDensity.compact,
                              tooltip: 'Archive this track',
                              onPressed: _sending || _queued.contains(track.title)
                                  ? null
                                  : () => _archive([track]),
                              icon: Icon(
                                _queued.contains(track.title)
                                    ? Icons.check_rounded
                                    : Icons.download_outlined,
                                color: _queued.contains(track.title)
                                    ? seekBarColor.value
                                    : null,
                              ),
                            ),
                        ],
                      ),
                    );
                  },
                ),
              ),
              const SizedBox(height: 14),
              _actionBar(tracks, downloadable),
            ],
          ],
        ),
      ),
    );
  }
}
