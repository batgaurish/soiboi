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
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/cookie_store.dart';
import 'package:soiboi/base/services/discovery_service.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/external_playlist_source.dart';
import 'package:soiboi/base/services/youtube_playlist_source.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/listenbrainz_service.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';
import 'package:soiboi/base/services/preview_player.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/portrait_view/custom_appbar_leading.dart';
import 'package:soiboi/layer/apple_signin_layer.dart';
import 'package:soiboi/layer/download_queue_sheet.dart';
import 'package:smooth_corner/smooth_corner.dart';

class DownloadsLayer extends StatefulWidget {
  const DownloadsLayer({super.key});

  @override
  State<DownloadsLayer> createState() => _DownloadsLayerState();
}

class _DownloadsLayerState extends State<DownloadsLayer> {
  final _urlController = TextEditingController();
  List<ExternalPlaylist> _discover = const [];
  final _lbUserController = TextEditingController();
  final _importController = TextEditingController();

  /// Set while a pasted link is being read. The fetch is a real network call
  /// through the pipeline and takes a second or two.
  bool _importing = false;
  String? _importError;

  /// Set while this screen's own submission is being handed to the queue, so
  /// the button cannot double-fire. Everything after that — progress, errors,
  /// history — belongs to [downloadQueue] and is read from it, not mirrored
  /// here: mirroring is what made the old per-screen copy grow without bound
  /// and disagree with the sheets.
  bool _submitting = false;

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
    _importController.dispose();
    super.dispose();
  }

  /// Playlists from every registered source, not just ListenBrainz.
  ///
  /// Sources that only accept a link (YouTube Music) return nothing here and
  /// contribute through the import card instead.
  Future<void> _load() async {
    final playlists = <ExternalPlaylist>[];
    for (final source in playlistSources) {
      playlists.addAll(await source.playlists());
    }
    if (mounted) setState(() => _discover = playlists);
  }

  /// Hands the typed URL to the queue and clears the box.
  ///
  /// Deliberately not awaited: the whole point of the queue is that a download
  /// outlives the screen that asked for it, so blocking the form until it
  /// finishes would give that back for nothing. Progress and failures show up
  /// in the queue card below.
  void _archiveUrl() {
    final url = _urlController.text.trim();
    if (url.isEmpty || _submitting) return;
    setState(() => _submitting = true);
    downloadQueue.enqueue([DownloadRequest(url: url, label: url)]);
    _urlController.clear();
    setState(() => _submitting = false);
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
            SliverToBoxAdapter(child: _readinessCard()),
            SliverToBoxAdapter(child: _archiveCard()),
            SliverToBoxAdapter(child: _queueCard()),
            SliverToBoxAdapter(child: _importCard()),
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
    final busy = _submitting;
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
                  enabled: ready && !busy,
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
                onPressed: ready && !busy ? _archiveUrl : null,
                child: const Text('Archive'),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            'Songs, albums or playlists. Files are downloaded, tagged and '
            'added to your library on this device. Downloads are queued, so '
            'they keep going if you leave this screen.',
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
        ],
      ),
    );
  }

  /// The live queue, inline where downloads are started.
  ///
  /// The same widget Settings opens as a sheet, so there is exactly one idea
  /// of what is downloading rather than this screen's copy and the queue's.
  Widget _queueCard() {
    return ValueListenableBuilder<List<DownloadJob>>(
      valueListenable: downloadQueue.jobs,
      builder: (context, jobs, _) {
        final failed = jobs
            .where((job) => job.state == DownloadJobState.failed)
            .toList();
        return _card(
          title: 'Download queue',
          action: jobs.isEmpty
              ? null
              : IconButton(
                  iconSize: 18,
                  visualDensity: VisualDensity.compact,
                  tooltip: 'Open queue',
                  onPressed: () => showDownloadQueueSheet(context),
                  icon: const Icon(Icons.open_in_full_rounded),
                ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // The most recent failure gets a banner of its own: a red row
              // several items down a scrolling list is easy to miss, and a
              // failed download that nobody notices is the worst outcome
              // this screen has.
              if (failed.isNotEmpty) ...[
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(
                      Icons.error_outline,
                      size: 16,
                      color: Colors.red,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        failed.last.error ?? 'Download failed',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.red,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
              ],
              const DownloadQueueView(),
            ],
          ),
        );
      },
    );
  }

  /// Importing a playlist from another platform by link.
  ///
  /// Separate from the Archive box above, and deliberately so: that one takes
  /// an Apple Music URL and downloads it directly, while this one takes a
  /// playlist from somewhere the app cannot download from, matches each track
  /// against Apple's catalog, and lets you choose. Same sheet as a weekly
  /// discovery, because from that point on it is the same problem.
  Widget _importCard() {
    return _card(
      title: 'Import a playlist',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _importController,
                  enabled: !_importing,
                  onSubmitted: (_) => _import(),
                  style: TextStyle(fontSize: 14, color: textColor.value),
                  decoration: InputDecoration(
                    hintText: 'https://music.youtube.com/playlist?list=…',
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
                onPressed: _importing ? null : _import,
                child: Text(_importing ? 'Reading…' : 'Import'),
              ),
            ],
          ),
          if (_importError != null) ...[
            const SizedBox(height: 10),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.error_outline, size: 16, color: Colors.red),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    _importError!,
                    style: const TextStyle(fontSize: 12, color: Colors.red),
                  ),
                ),
              ],
            ),
          ],
          const SizedBox(height: 12),
          Text(
            'Paste a public ${_importSourceNames()} playlist link. Its tracks '
            'are matched against the Apple Music catalog so you can archive '
            'the ones you want — nothing is downloaded from the other '
            'platform.',
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
        ],
      ),
    );
  }

  /// The platforms that actually accept a link, named from the registry rather
  /// than hard-coded, so this sentence cannot drift from what is registered.
  String _importSourceNames() {
    final names = [
      for (final source in playlistSources)
        if (source.acceptsLinks) source.displayName,
    ];
    return names.isEmpty ? 'playlist' : names.join(' or ');
  }

  Future<void> _import() async {
    final text = _importController.text.trim();
    if (text.isEmpty || _importing) return;
    setState(() {
      _importing = true;
      _importError = null;
    });

    final match = sourceForUrl(text);
    if (match == null) {
      setState(() {
        _importing = false;
        _importError = 'That is not a playlist link this app can read.';
      });
      return;
    }

    // Fetched before opening the sheet so a private or deleted playlist fails
    // here, with the platform's own explanation, rather than as an empty sheet
    // that looks like a playlist with nothing in it.
    final title = await match.source.titleFor(match.playlistId);
    if (!mounted) return;
    if (title == null) {
      final source = match.source;
      setState(() {
        _importing = false;
        _importError = source is YouTubePlaylistSource
            ? (source.lastError ?? 'Could not read that playlist.')
            : 'Could not read that playlist.';
      });
      return;
    }

    setState(() => _importing = false);
    _importController.clear();
    await showDiscoverPlaylistSheet(
      context,
      ExternalPlaylist(
        sourceId: match.source.id,
        id: match.playlistId,
        title: title,
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
  ExternalPlaylist playlist,
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
  final ExternalPlaylist playlist;

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
    final cached = cachedDiscoveryTracks(widget.playlist);
    if (cached != null && cached.isNotEmpty) {
      setState(() => _tracks = cached);
    }
    final tracks = await resolveDiscoveryTracks(
      widget.playlist,
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

  /// Hands [tracks] to the shared download queue and follows that batch.
  ///
  /// The queue runs them one at a time — Apple rate-limits, and the original
  /// pipeline's cooldowns exist for good reason — and, unlike the loop this
  /// replaces, keeps going if the sheet is closed halfway through a fifty
  /// track playlist. The library sync afterwards is the queue's job now, once
  /// per drain rather than once per batch.
  Future<void> _archive(List<DiscoveryTrack> tracks) async {
    final requests = [
      for (final track in tracks)
        if (track.appleUrl != null)
          DownloadRequest(
            url: track.appleUrl!,
            label: track.title,
            subtitle: track.artist,
          ),
    ];
    if (requests.isEmpty) return;

    setState(() {
      _sending = true;
      _archivedInBatch = 0;
      _batchSize = requests.length;
      // Marked on enqueue, not on completion: the row's tick means "this is
      // handled", and offering the button again while it sits in the queue
      // would only queue it twice.
      _queued.addAll(tracks.map((track) => track.title));
    });

    final batch = downloadQueue.enqueue(requests);
    void onProgress() {
      if (!mounted) return;
      setState(() => _archivedInBatch = batch.completed.value);
    }

    batch.completed.addListener(onProgress);
    await batch.done;
    batch.completed.removeListener(onProgress);

    if (!mounted) return;
    setState(() {
      _sending = false;
      final errors = batch.errors;
      if (errors.isNotEmpty) _error = errors.last;
      // Cleared either way: leaving the selection behind invites archiving the
      // same tracks twice, and the failures are retryable from the queue.
      _selected.clear();
    });
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
