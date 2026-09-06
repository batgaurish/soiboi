/// Downloads screen: add an Apple Music playlist, watch it archive, and browse
/// the weekly discovery playlists the pipeline generates.
///
/// This is the one screen that requires the download-bridge service. Everything
/// else in the app works offline against local files, so this page has to
/// degrade gracefully when the bridge is unreachable — which, for a service
/// living on someone's home server behind Tailscale, is a normal state rather
/// than an error.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/bridge_client.dart';
import 'package:soiboi/base/services/bridge_service.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/preview_player.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:soiboi/base/widgets/my_divider.dart';
import 'package:smooth_corner/smooth_corner.dart';

class DownloadsLayer extends StatefulWidget {
  const DownloadsLayer({super.key});

  @override
  State<DownloadsLayer> createState() => _DownloadsLayerState();
}

class _DownloadsLayerState extends State<DownloadsLayer> {
  final _urlController = TextEditingController();
  List<String> _playlists = const [];
  List<DiscoverPlaylist> _discover = const [];
  bool _loadingPlaylists = false;

  @override
  void initState() {
    super.initState();
    startPolling();
    _loadLists();
  }

  @override
  void dispose() {
    stopPolling();
    _urlController.dispose();
    super.dispose();
  }

  Future<void> _loadLists() async {
    final client = bridgeClient;
    if (client == null) return;
    setState(() => _loadingPlaylists = true);
    try {
      final playlists = await client.playlists();
      if (mounted) setState(() => _playlists = playlists);
    } on BridgeException {
      // The banner already reports connection trouble; no second complaint.
    }
    try {
      final discover = await client.discoverPlaylists();
      if (mounted) setState(() => _discover = discover);
    } on BridgeException {
      // Discovery needs a ListenBrainz username configured server-side. Its
      // absence is a normal setup state, not a failure worth shouting about.
    }
    if (mounted) setState(() => _loadingPlaylists = false);
  }

  Future<void> _addPlaylist() async {
    final url = _urlController.text.trim();
    if (url.isEmpty) return;
    final ok = await runBridgeAction((c) async {
      final updated = await c.addPlaylist(url);
      if (mounted) setState(() => _playlists = updated);
    });
    if (ok) _urlController.clear();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: bridgeUrlNotifier,
      builder: (context, url, _) {
        if (url.trim().isEmpty) return _notConfigured();
        return ListenableBuilder(
          listenable: Listenable.merge([
            bridgeStatsNotifier,
            bridgeErrorNotifier,
            bridgeLoadingNotifier,
          ]),
          builder: (context, _) => _content(context),
        );
      },
    );
  }

  Widget _notConfigured() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.cloud_off_rounded, size: 44, color: textColor.value),
            const SizedBox(height: 14),
            Text(
              'No download server',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.w600,
                color: highlightTextColor.value,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Downloading needs the archival service running on your own '
              'machine. Add its address in Settings to archive Apple Music '
              'playlists from here.\n\nYour local library plays without it.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 13, color: textColor.value),
            ),
          ],
        ),
      ),
    );
  }

  Widget _content(BuildContext context) {
    final stats = bridgeStatsNotifier.value;
    final error = bridgeErrorNotifier.value;

    return RefreshIndicator(
      onRefresh: () async {
        await refreshStats();
        await _loadLists();
      },
      child: CustomScrollView(
        slivers: [
          if (error != null) SliverToBoxAdapter(child: _errorBanner(error)),
          SliverToBoxAdapter(child: _addPlaylistCard()),
          if (_playlists.isNotEmpty)
            SliverToBoxAdapter(child: _playlistsCard()),
          if (stats != null && stats.activeTasks.isNotEmpty)
            SliverToBoxAdapter(child: _activeCard(stats)),
          if (stats != null) SliverToBoxAdapter(child: _queueCard(stats)),
          if (_discover.isNotEmpty)
            SliverToBoxAdapter(child: _discoverCard()),
          const SliverToBoxAdapter(child: SizedBox(height: 90)),
        ],
      ),
    );
  }

  Widget _card({required String title, required Widget child, Widget? action}) {
    final radius = 12.0 * activeFlavour.cornerScale;
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
      child: SmoothClipRRect(
        smoothness: 1,
        borderRadius: BorderRadius.circular(radius),
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

  Widget _errorBanner(String error) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
      child: SmoothClipRRect(
        smoothness: 1,
        borderRadius: BorderRadius.circular(10 * activeFlavour.cornerScale),
        child: Container(
          color: Colors.red.withValues(alpha: 0.14),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 11),
          child: Row(
            children: [
              const Icon(Icons.error_outline, size: 18, color: Colors.red),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  error,
                  style: TextStyle(fontSize: 13, color: textColor.value),
                ),
              ),
              TextButton(
                onPressed: () {
                  refreshStats();
                  _loadLists();
                },
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _addPlaylistCard() {
    return _card(
      title: 'Archive an Apple Music playlist',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _urlController,
                  onSubmitted: (_) => _addPlaylist(),
                  style: TextStyle(fontSize: 14, color: textColor.value),
                  decoration: InputDecoration(
                    hintText: 'https://music.apple.com/…/playlist/…',
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
              FilledButton(onPressed: _addPlaylist, child: const Text('Add')),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            'Added playlists are archived on the next run. Files land in your '
            'library and sync to this device.',
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
        ],
      ),
    );
  }

  Widget _playlistsCard() {
    return _card(
      title: _loadingPlaylists
          ? 'Tracked playlists…'
          : 'Tracked playlists (${_playlists.length})',
      action: FilledButton.tonal(
        onPressed: () => runBridgeAction((c) => c.startWorkflow()),
        child: const Text('Run now'),
      ),
      child: Column(
        children: [
          for (final url in _playlists)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 3),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      url,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(fontSize: 12.5, color: textColor.value),
                    ),
                  ),
                  IconButton(
                    iconSize: 18,
                    tooltip: 'Stop tracking',
                    onPressed: () => runBridgeAction((c) async {
                      final updated = await c.removePlaylist(url);
                      if (mounted) setState(() => _playlists = updated);
                    }),
                    icon: const Icon(Icons.close_rounded),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _activeCard(BridgeStats stats) {
    return _card(
      title: 'Downloading now',
      child: Column(
        children: [
          for (final task in stats.activeTasks)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 7),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    task.query,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13.5,
                      color: highlightTextColor.value,
                    ),
                  ),
                  const SizedBox(height: 6),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(3),
                    child: TweenAnimationBuilder<double>(
                      // Progress arrives in discrete server-side jumps; easing
                      // between them reads as motion rather than stutter.
                      tween: Tween(end: task.progress / 100),
                      duration: activeMotion.medium,
                      curve: activeMotion.standard,
                      builder: (context, value, _) => LinearProgressIndicator(
                        value: task.indeterminate ? null : value,
                        minHeight: 4,
                        backgroundColor: buttonColor.value,
                        color: seekBarColor.value,
                      ),
                    ),
                  ),
                  const SizedBox(height: 5),
                  Text(
                    task.status,
                    style: TextStyle(fontSize: 11.5, color: textColor.value),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _queueCard(BridgeStats stats) {
    return _card(
      title: 'Queue',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              _stat('Queued', '${stats.queuedTasksTotal}'),
              _stat('Incoming', '${stats.incomingQueueTotal}'),
              _stat('Done this run', '${stats.sessionCount}'),
              _stat('Skipped', '${stats.skipped}'),
            ],
          ),
          if (stats.taggerRunning || stats.discoveryRunning) ...[
            const SizedBox(height: 12),
            MyDivider(thickness: 0.5, height: 0.5, color: dividerColor),
            const SizedBox(height: 10),
            Text(
              [
                if (stats.taggerRunning) 'Tagging metadata',
                if (stats.discoveryRunning) 'Resolving discoveries',
              ].join(' · '),
              style: TextStyle(fontSize: 12, color: textColor.value),
            ),
          ],
        ],
      ),
    );
  }

  Widget _stat(String label, String value) {
    return Expanded(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            value,
            style: TextStyle(
              fontSize: 21,
              fontWeight: FontWeight.w600,
              color: highlightTextColor.value,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
          Text(label, style: TextStyle(fontSize: 11, color: textColor.value)),
        ],
      ),
    );
  }

  Widget _discoverCard() {
    return _card(
      title: 'Weekly discoveries',
      child: Column(
        children: [
          for (final playlist in _discover)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              title: Text(
                playlist.title,
                style: TextStyle(fontSize: 13.5, color: textColor.value),
              ),
              subtitle: playlist.lastModified == null
                  ? null
                  : Text(
                      playlist.lastModified!,
                      style: TextStyle(fontSize: 11, color: textColor.value),
                    ),
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => showDiscoverPlaylistSheet(context, playlist),
            ),
        ],
      ),
    );
  }
}

/// Opens a playlist's resolved tracks and offers to archive them.
///
/// Resolution hits Apple's catalog once per track server-side, so this can take
/// most of a minute on a long playlist — hence the explicit loading state
/// rather than a spinner that looks stuck.
/// Uses the app's own [showAnimationDialog] rather than showModalBottomSheet.
/// The layer system nests navigators per root layer, and a raw modal sheet
/// silently fails to find one from inside a layer.
Future<void> showDiscoverPlaylistSheet(
  BuildContext context,
  DiscoverPlaylist playlist,
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
  final DiscoverPlaylist playlist;

  @override
  State<_DiscoverPlaylistSheet> createState() => _DiscoverPlaylistSheetState();
}

class _DiscoverPlaylistSheetState extends State<_DiscoverPlaylistSheet> {
  List<DiscoverTrack>? _tracks;
  String? _error;
  bool _sending = false;

  /// Titles already queued from this sheet, so the row can show it landed
  /// rather than letting you queue the same track repeatedly.
  final Set<String> _queued = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final client = bridgeClient;
    if (client == null) return;
    final cached = cachedTracks(widget.playlist.mbid);
    if (cached != null) {
      setState(() => _tracks = cached);
      return;
    }
    try {
      final tracks = await client.discoverTracks(widget.playlist.mbid);
      if (mounted) setState(() => _tracks = tracks);
    } on BridgeException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  @override
  void dispose() {
    // A preview must not outlive the sheet that started it.
    stopPreview();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final tracks = _tracks;
    // Only resolved tracks can actually be fetched; the rest are shown with
    // their warning so the gap is visible rather than a silent no-op.
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
              style: TextStyle(
                fontSize: 17,
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
                    return ListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      leading: track.artwork == null
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
                          // Audition before archiving. Apple supplies a 30s
                          // clip for most catalogue tracks.
                          if (track.previewUrl != null &&
                              track.previewUrl!.isNotEmpty)
                            ValueListenableBuilder(
                              valueListenable: previewingKeyNotifier,
                              builder: (context, playing, child) {
                                final key = '${track.artist} — ${track.title}';
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
                          // Archive just this one, rather than the whole list.
                          if (track.isResolved)
                            IconButton(
                              iconSize: 18,
                              visualDensity: VisualDensity.compact,
                              tooltip: 'Archive this track',
                              onPressed: _queued.contains(track.title)
                                  ? null
                                  : () async {
                                      final ok = await runBridgeAction(
                                        (c) => c.downloadTracks([track]),
                                      );
                                      if (ok && mounted) {
                                        setState(
                                          () => _queued.add(track.title),
                                        );
                                      }
                                    },
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
              Row(
                children: [
                  Expanded(
                    child: Text(
                      downloadable.length == tracks.length
                          ? '${tracks.length} tracks'
                          : '${downloadable.length} of ${tracks.length} '
                                'matched',
                      style: TextStyle(fontSize: 12, color: textColor.value),
                    ),
                  ),
                  FilledButton(
                    onPressed: downloadable.isEmpty || _sending
                        ? null
                        : () async {
                            final navigator = Navigator.of(context);
                            setState(() => _sending = true);
                            final ok = await runBridgeAction(
                              (c) => c.downloadTracks(downloadable),
                            );
                            if (!mounted) return;
                            setState(() => _sending = false);
                            if (ok) navigator.pop();
                          },
                    child: Text(
                      _sending
                          ? 'Queuing…'
                          : 'Archive ${downloadable.length}',
                    ),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}
