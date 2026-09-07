/// Browsing music that is not in the library yet.
///
/// A ranked album or artist the user does not own used to be a dead card: dimmed,
/// unlabelled beyond "Not in library", and inert. That is the moment someone is
/// most likely to want the record — so these sheets show what Apple's catalog
/// knows about it, play the 30-second previews, and archive any of it.
///
/// Everything here comes from the same keyless iTunes Search API the discovery
/// screen uses, so it works with nothing configured and no account.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/apple_catalog_service.dart';
import 'package:soiboi/base/services/archive_service.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/preview_player.dart';
import 'package:soiboi/base/theme/flavour.dart';

/// Opens an album, resolving it from artist and title.
Future<void> showCatalogAlbumSheet(
  BuildContext context,
  String artist,
  String album,
) {
  return showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 460,
      height: 540,
      child: _CatalogAlbumSheet(artist: artist, album: album),
    ),
  );
}

/// Opens an artist's discography.
Future<void> showCatalogArtistSheet(BuildContext context, String artist) {
  return showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 460,
      height: 540,
      child: _CatalogArtistSheet(artist: artist),
    ),
  );
}

/// Shared chrome: a title, a subtitle and a body that fills what is left.
class _SheetScaffold extends StatelessWidget {
  const _SheetScaffold({
    required this.title,
    required this.child,
    this.subtitle,
    this.artwork,
    this.footer,
  });

  final String title;
  final String? subtitle;
  final String? artwork;
  final Widget child;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (artwork != null) ...[
                  ClipRRect(
                    borderRadius: BorderRadius.circular(
                      8 * activeFlavour.cornerScale,
                    ),
                    child: Image.network(
                      artwork!,
                      width: 64,
                      height: 64,
                      fit: BoxFit.cover,
                      errorBuilder: (_, _, _) =>
                          const SizedBox(width: 64, height: 64),
                    ),
                  ),
                  const SizedBox(width: 12),
                ],
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                          color: highlightTextColor.value,
                        ),
                      ),
                      if (subtitle != null) ...[
                        const SizedBox(height: 4),
                        Text(
                          subtitle!,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 12,
                            color: textColor.value,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Expanded(child: child),
            if (footer != null) ...[const SizedBox(height: 12), footer!],
          ],
        ),
      ),
    );
  }
}

Widget _busy(String message) => Center(
  child: Column(
    mainAxisAlignment: MainAxisAlignment.center,
    children: [
      const CircularProgressIndicator(),
      const SizedBox(height: 12),
      Text(message, style: const TextStyle(fontSize: 12)),
    ],
  ),
);

class _CatalogAlbumSheet extends StatefulWidget {
  const _CatalogAlbumSheet({required this.artist, required this.album});
  final String artist;
  final String album;

  @override
  State<_CatalogAlbumSheet> createState() => _CatalogAlbumSheetState();
}

class _CatalogAlbumSheetState extends State<_CatalogAlbumSheet> {
  AppleAlbum? _album;
  List<AppleTrack>? _tracks;
  String? _error;

  /// Track URLs already archived from this sheet, so a row shows it landed.
  final Set<String> _archived = {};
  final Set<String> _selected = {};
  bool _sending = false;
  int _archivedInBatch = 0;
  int _batchSize = 0;

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
    final album = await resolveAppleAlbum(widget.artist, widget.album);
    if (!mounted) return;
    if (album == null) {
      setState(() => _error = 'Not found in the Apple Music catalog');
      return;
    }
    setState(() => _album = album);

    final tracks = await appleAlbumTracks(album.id);
    if (!mounted) return;
    setState(() {
      _tracks = tracks ?? const [];
      if (tracks == null) _error = 'Could not load the track list';
    });
  }

  Future<void> _archive(List<AppleTrack> tracks) async {
    setState(() {
      _sending = true;
      _archivedInBatch = 0;
      _batchSize = tracks.length;
    });
    for (final track in tracks) {
      if (!mounted) return;
      final error = await archiveUrl(track.url);
      if (!mounted) return;
      setState(() {
        if (error != null) {
          _error = error;
        } else {
          _archived.add(track.url);
        }
        _archivedInBatch++;
      });
    }
    if (_archived.isNotEmpty) await syncArchivedToLibrary();
    if (mounted) {
      setState(() {
        _sending = false;
        _selected.clear();
      });
    }
  }

  void _toggleSelected(AppleTrack track) {
    setState(() {
      if (!_selected.remove(track.url)) _selected.add(track.url);
    });
  }

  @override
  Widget build(BuildContext context) {
    final album = _album;
    final tracks = _tracks;

    if (_error != null && album == null) {
      return _SheetScaffold(
        title: widget.album,
        subtitle: widget.artist,
        child: Center(
          child: Text(_error!, style: const TextStyle(color: Colors.red)),
        ),
      );
    }
    if (album == null) {
      return _SheetScaffold(
        title: widget.album,
        subtitle: widget.artist,
        child: _busy('Looking up the album…'),
      );
    }

    final selecting = _selected.isNotEmpty;
    final chosen = selecting
        ? (tracks ?? const <AppleTrack>[])
              .where((t) => _selected.contains(t.url))
              .toList()
        : (tracks ?? const <AppleTrack>[]);

    return _SheetScaffold(
      title: album.title,
      artwork: album.artwork,
      subtitle: [
        album.artist,
        if (album.releaseYear != null) album.releaseYear!,
        if (album.genre != null) album.genre!,
        if (album.trackCount != null) '${album.trackCount} tracks',
      ].join(' · '),
      footer: tracks == null || tracks.isEmpty
          ? null
          : Row(
              children: [
                Expanded(
                  child: Text(
                    _sending
                        ? 'Archiving $_archivedInBatch of $_batchSize…'
                        : selecting
                        ? '${chosen.length} selected'
                        : 'Long press to pick tracks',
                    style: TextStyle(fontSize: 12, color: textColor.value),
                  ),
                ),
                if (selecting && !_sending) ...[
                  TextButton(
                    onPressed: () => setState(() {
                      if (chosen.length == tracks.length) {
                        _selected.clear();
                      } else {
                        _selected.addAll(tracks.map((t) => t.url));
                      }
                    }),
                    child: Text(
                      chosen.length == tracks.length ? 'Clear' : 'Select all',
                    ),
                  ),
                  const SizedBox(width: 4),
                ],
                FilledButton(
                  onPressed: chosen.isEmpty || _sending
                      ? null
                      : () => _archive(chosen),
                  child: Text(
                    _sending ? 'Archiving…' : 'Archive ${chosen.length}',
                  ),
                ),
              ],
            ),
      child: tracks == null
          ? _busy('Loading tracks…')
          : tracks.isEmpty
          ? Center(
              child: Text(
                _error ?? 'No tracks listed for this album',
                style: TextStyle(fontSize: 12, color: textColor.value),
              ),
            )
          : ListView.builder(
              itemCount: tracks.length,
              itemBuilder: (context, i) => _trackRow(tracks[i], selecting),
            ),
    );
  }

  Widget _trackRow(AppleTrack track, bool selecting) {
    final selected = _selected.contains(track.url);
    final done = _archived.contains(track.url);
    final duration = track.duration;

    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      selected: selected,
      onLongPress: () => _toggleSelected(track),
      onTap: selecting ? () => _toggleSelected(track) : null,
      leading: SizedBox(
        width: 34,
        child: selecting
            ? Checkbox(
                value: selected,
                visualDensity: VisualDensity.compact,
                onChanged: (_) => _toggleSelected(track),
              )
            : Text(
                '${track.trackNumber ?? ''}',
                textAlign: TextAlign.center,
                style: TextStyle(fontSize: 12, color: textColor.value),
              ),
      ),
      title: Text(
        track.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 13, color: highlightTextColor.value),
      ),
      subtitle: duration == null
          ? null
          : Text(
              '${duration.inMinutes}:'
              '${(duration.inSeconds % 60).toString().padLeft(2, '0')}',
              style: TextStyle(fontSize: 11, color: textColor.value),
            ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (track.previewUrl != null && track.previewUrl!.isNotEmpty)
            ValueListenableBuilder(
              valueListenable: previewingKeyNotifier,
              builder: (context, playing, child) {
                final active = playing == track.url;
                return IconButton(
                  iconSize: 19,
                  visualDensity: VisualDensity.compact,
                  tooltip: active ? 'Stop' : 'Preview',
                  onPressed: () => togglePreview(track.url, track.previewUrl),
                  icon: Icon(
                    active
                        ? Icons.stop_circle_outlined
                        : Icons.play_circle_outline,
                    color: active ? seekBarColor.value : null,
                  ),
                );
              },
            ),
          IconButton(
            iconSize: 18,
            visualDensity: VisualDensity.compact,
            tooltip: 'Archive this track',
            onPressed: _sending || done ? null : () => _archive([track]),
            icon: Icon(
              done ? Icons.check_rounded : Icons.download_outlined,
              color: done ? seekBarColor.value : null,
            ),
          ),
        ],
      ),
    );
  }
}

class _CatalogArtistSheet extends StatefulWidget {
  const _CatalogArtistSheet({required this.artist});
  final String artist;

  @override
  State<_CatalogArtistSheet> createState() => _CatalogArtistSheetState();
}

class _CatalogArtistSheetState extends State<_CatalogArtistSheet> {
  List<AppleAlbum>? _albums;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final albums = await appleArtistAlbums(widget.artist);
    if (!mounted) return;
    setState(() {
      _albums = albums ?? const [];
      if (albums == null) _error = 'Not found in the Apple Music catalog';
    });
  }

  @override
  Widget build(BuildContext context) {
    final albums = _albums;

    return _SheetScaffold(
      title: widget.artist,
      subtitle: albums == null || albums.isEmpty
          ? null
          : '${albums.length} releases in the catalog',
      child: albums == null
          ? _busy('Looking up releases…')
          : albums.isEmpty
          ? Center(
              child: Text(
                _error ?? 'No releases listed',
                style: TextStyle(fontSize: 12, color: textColor.value),
              ),
            )
          : ListView.builder(
              itemCount: albums.length,
              itemBuilder: (context, i) {
                final album = albums[i];
                return ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  leading: album.artwork == null
                      ? null
                      : ClipRRect(
                          borderRadius: BorderRadius.circular(4),
                          child: Image.network(
                            album.artwork!,
                            width: 40,
                            height: 40,
                            fit: BoxFit.cover,
                            errorBuilder: (_, _, _) =>
                                const SizedBox(width: 40, height: 40),
                          ),
                        ),
                  title: Text(
                    album.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      color: highlightTextColor.value,
                    ),
                  ),
                  subtitle: Text(
                    [
                      if (album.releaseYear != null) album.releaseYear!,
                      if (album.trackCount != null) '${album.trackCount} tracks',
                    ].join(' · '),
                    style: TextStyle(fontSize: 11, color: textColor.value),
                  ),
                  trailing: const Icon(Icons.chevron_right_rounded, size: 20),
                  onTap: () {
                    // Replaces this sheet rather than stacking: the layer
                    // system nests navigators, and two sheets deep leaves no
                    // obvious way back.
                    Navigator.of(context).pop();
                    showCatalogAlbumSheet(context, album.artist, album.title);
                  },
                );
              },
            ),
    );
  }
}
