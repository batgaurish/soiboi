/// "Unresolved tracks, need your choice": the Downloads card for playlist
/// tracks automatic matching could not place, and the sheet to place them.
///
/// Per track: Find match (a search the user can edit, showing enough of each
/// song to tell an original from a cover), Paste link, or Skip. A pick is
/// remembered (see [ManualResolution]), downloaded into the playlist's group
/// and swapped into the linked local playlist, so it lands there like the
/// rest.
library;

import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/apple_catalog_service.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/discovery_service.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/linked_playlists.dart';
import 'package:soiboi/base/services/manual_resolution.dart';
import 'package:soiboi/base/services/preview_player.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/widgets/icon_label.dart';
import 'package:soiboi/layer/failure_fix.dart';

/// Saves [url] as [track]'s song, queues it under its playlist and puts it
/// in the linked playlist in the source track's place.
void applyManualPick(
  UnresolvedTrack track,
  String url, {
  String? artist,
  String? title,
}) {
  manualResolution.resolve(track, url, artist: artist, title: title);
  if (artist != null && title != null) {
    linkedPlaylists.replaceTrack(
      track.playlist,
      LinkedTrack(track.artist, track.title),
      LinkedTrack(artist, title),
    );
  }
  // An open discovery playlist should show the pick, not the old gap.
  forgetDiscoveryMatches();
  downloadQueue.enqueue([
    DownloadRequest(
      url: url,
      label: title ?? track.title,
      subtitle: artist ?? track.artist,
      group: track.playlist.isEmpty ? null : track.playlist,
    ),
  ]);
}

/// The card's body: one row per track waiting for a choice.
class UnresolvedTracksList extends StatelessWidget {
  const UnresolvedTracksList({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<List<UnresolvedTrack>>(
      valueListenable: manualResolution.unresolved,
      builder: (context, tracks, _) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Apple had no clear match for these playlist tracks. Pick the '
            'right song, paste a link, or skip it; your choice is kept.',
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
          const SizedBox(height: 8),
          for (final track in tracks) _row(context, track),
        ],
      ),
    );
  }

  Widget _row(BuildContext context, UnresolvedTrack track) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Semantics(
            container: true,
            label: '${track.title} by ${track.artist}, from ${track.playlist}',
            excludeSemantics: true,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  track.title,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: highlightTextColor.value,
                  ),
                ),
                Text(
                  '${track.artist} · ${track.playlist}',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 11, color: textColor.value),
                ),
              ],
            ),
          ),
          // Wraps at large text rather than squeezing three buttons.
          Wrap(
            spacing: 4,
            children: [
              TextButton.icon(
                onPressed: () => _find(context, track),
                icon: const Icon(Icons.search_rounded, size: 17),
                label: Text(
                  'Find match',
                  semanticsLabel: 'Find match for ${track.title}',
                ),
              ),
              TextButton.icon(
                onPressed: () => _paste(context, track),
                icon: const Icon(Icons.link_rounded, size: 17),
                label: Text(
                  'Paste link',
                  semanticsLabel: 'Paste link for ${track.title}',
                ),
              ),
              TextButton.icon(
                onPressed: () => manualResolution.skip(track),
                icon: const Icon(Icons.block_rounded, size: 17),
                label: Text('Skip', semanticsLabel: 'Skip ${track.title}'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _find(BuildContext context, UnresolvedTrack track) async {
    final picked = await showAnimationDialog<AppleCandidate>(
      context: context,
      child: SizedBox(
        width: 560,
        height: 620,
        child: FindMatchSheet(track: track),
      ),
    );
    unawaited(stopPreview());
    if (picked == null) return;
    applyManualPick(
      track,
      picked.url,
      artist: picked.artist,
      title: picked.title,
    );
    showCenterMessage('Queued "${picked.title}"');
  }

  Future<void> _paste(BuildContext context, UnresolvedTrack track) async {
    final url = await showAnimationDialog<String>(
      context: context,
      child: SizedBox(width: 420, child: _PasteLink(track: track)),
    );
    if (url == null || url.isEmpty) return;
    // Named for what the link really is, so the linked playlist lists the
    // song that will land; the source's names when the lookup fails.
    final song = await lookupAppleSong(url);
    applyManualPick(track, url, artist: song?.artist, title: song?.title);
    showCenterMessage('Queued "${song?.title ?? track.title}"');
  }
}

class _PasteLink extends StatefulWidget {
  const _PasteLink({required this.track});

  final UnresolvedTrack track;

  @override
  State<_PasteLink> createState() => _PasteLinkState();
}

class _PasteLinkState extends State<_PasteLink> {
  final _controller = TextEditingController();
  String? _error;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final url = _controller.text.trim();
    if (appleSongIdFromUrl(url) == null) {
      setState(
        () => _error =
            'That is not an Apple Music song link. Use Share > Copy Link on '
            'the song.',
      );
      return;
    }
    Navigator.pop(context, url);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Paste a link for "${widget.track.title}"',
            style: TextStyle(
              fontSize: 15,
              fontWeight: FontWeight.w600,
              color: highlightTextColor.value,
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _controller,
            autofocus: true,
            onSubmitted: (_) => _submit(),
            style: TextStyle(fontSize: 14, color: textColor.value),
            decoration: _fieldDecoration(
              label: 'Apple Music song link',
              hint: 'https://music.apple.com/…',
              error: _error,
            ),
          ),
          const SizedBox(height: 14),
          Row(
            mainAxisAlignment: MainAxisAlignment.end,
            children: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Cancel'),
              ),
              const SizedBox(width: 8),
              FilledButton(onPressed: _submit, child: const Text('Use link')),
            ],
          ),
        ],
      ),
    );
  }
}

InputDecoration _fieldDecoration({
  required String label,
  String? hint,
  String? error,
}) => InputDecoration(
  labelText: label,
  labelStyle: TextStyle(color: textColor.value),
  hintText: hint,
  hintStyle: TextStyle(color: textColor.value),
  errorText: error,
  errorMaxLines: 3,
  errorStyle: TextStyle(color: failureTextColor()),
  isDense: true,
  filled: true,
  fillColor: searchFieldColor.value,
  border: OutlineInputBorder(
    borderRadius: BorderRadius.circular(8 * activeFlavour.cornerScale),
    borderSide: BorderSide.none,
  ),
);

/// Search the catalog for a track and pick one of the results. Pops with
/// the chosen [AppleCandidate].
class FindMatchSheet extends StatefulWidget {
  const FindMatchSheet({super.key, required this.track, this.search});

  final UnresolvedTrack track;

  /// A seam for tests; the live catalog search otherwise.
  final Future<List<AppleCandidate>?> Function(String term)? search;

  @override
  State<FindMatchSheet> createState() => _FindMatchSheetState();
}

class _FindMatchSheetState extends State<FindMatchSheet> {
  late final _controller = TextEditingController(
    text: '${widget.track.artist} ${widget.track.title}',
  );
  List<AppleCandidate>? _results;
  bool _searching = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _search();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final term = _controller.text.trim();
    if (term.isEmpty) return;
    setState(() {
      _searching = true;
      _error = null;
    });
    final results = await (widget.search ?? searchAppleSongs)(term);
    if (!mounted) return;
    setState(() {
      _searching = false;
      _results = results ?? const [];
      if (results == null) _error = 'Could not reach Apple. Try again.';
    });
  }

  @override
  Widget build(BuildContext context) {
    final results = _results;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Find "${widget.track.title}"',
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: highlightTextColor.value,
                  ),
                ),
              ),
              IconButton(
                tooltip: 'Close',
                onPressed: () => Navigator.pop(context),
                icon: labelIcon('Close', const Icon(Icons.close_rounded)),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _controller,
                  onSubmitted: (_) => _search(),
                  textInputAction: TextInputAction.search,
                  style: TextStyle(fontSize: 14, color: textColor.value),
                  decoration: _fieldDecoration(label: 'Search Apple Music'),
                ),
              ),
              const SizedBox(width: 8),
              IconButton(
                tooltip: 'Search',
                onPressed: _searching ? null : _search,
                icon: labelIcon('Search', const Icon(Icons.search_rounded)),
              ),
            ],
          ),
          const SizedBox(height: 10),
          if (_searching)
            const Padding(
              padding: EdgeInsets.all(20),
              child: Center(child: CircularProgressIndicator()),
            )
          else if (_error != null)
            Text(_error!, style: TextStyle(color: failureTextColor()))
          else if (results != null && results.isEmpty)
            Text(
              'Nothing found. Try fewer words, or the song title alone.',
              style: TextStyle(fontSize: 12.5, color: textColor.value),
            )
          else if (results != null)
            Expanded(
              child: ListView.builder(
                itemCount: results.length,
                itemBuilder: (context, i) => _result(results[i]),
              ),
            ),
        ],
      ),
    );
  }

  Widget _result(AppleCandidate song) {
    final details = [
      song.artist,
      ?song.album,
      ?song.year,
      if (song.duration case final d?) formatTrackLength(d),
    ];
    final key = 'match:${song.url}';
    return Row(
      children: [
        Expanded(
          child: Semantics(
            button: true,
            label: 'Use ${song.title}, ${details.join(', ')}',
            excludeSemantics: true,
            child: InkWell(
              onTap: () => Navigator.pop(context, song),
              borderRadius: BorderRadius.circular(6),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Row(
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(4),
                      child: song.artwork == null
                          ? const SizedBox(width: 44, height: 44)
                          : Image.network(
                              song.artwork!,
                              width: 44,
                              height: 44,
                              fit: BoxFit.cover,
                              errorBuilder: (_, _, _) =>
                                  const SizedBox(width: 44, height: 44),
                            ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            song.title,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 13,
                              color: highlightTextColor.value,
                            ),
                          ),
                          Text(
                            details.join(' · '),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 11,
                              color: textColor.value,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
        if (song.previewUrl != null && song.previewUrl!.isNotEmpty)
          ValueListenableBuilder(
            valueListenable: previewingKeyNotifier,
            builder: (context, playing, _) {
              final active = playing == key;
              final label = active ? 'Stop preview' : 'Preview ${song.title}';
              return IconButton(
                iconSize: 20,
                tooltip: active ? 'Stop' : 'Preview',
                onPressed: () => togglePreview(key, song.previewUrl),
                icon: labelIcon(
                  label,
                  Icon(
                    active
                        ? Icons.stop_circle_outlined
                        : Icons.play_circle_outline,
                    color: active ? seekBarColor.value : null,
                  ),
                ),
              );
            },
          ),
      ],
    );
  }
}

/// "3:45": a track's length for a result row.
String formatTrackLength(Duration d) =>
    '${d.inMinutes}:${(d.inSeconds % 60).toString().padLeft(2, '0')}';
