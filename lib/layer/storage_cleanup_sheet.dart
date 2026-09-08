/// Reclaiming space, by hand.
///
/// Manual review and delete only — no automatic policy. On a device that holds
/// the only copy of everything it downloaded, anything that deletes music
/// without being asked is a data-loss bug waiting for a quiet night, so the
/// app will never guess. It only sorts, shows the cost, and does what it is
/// told.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/data/storage_cleanup.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';

Future<void> showStorageCleanupSheet(BuildContext context) {
  return showAnimationDialog(
    context: context,
    child: const SizedBox(
      width: 460,
      height: 560,
      child: _StorageCleanupSheet(),
    ),
  );
}

class _StorageCleanupSheet extends StatefulWidget {
  const _StorageCleanupSheet();

  @override
  State<_StorageCleanupSheet> createState() => _StorageCleanupSheetState();
}

class _StorageCleanupSheetState extends State<_StorageCleanupSheet> {
  CleanupSort _sort = CleanupSort.largest;
  List<StorageEntry> _entries = const [];

  /// Song ids picked for deletion. Ids rather than entries so a re-sort does
  /// not silently drop the selection.
  final Set<String> _selected = {};

  bool _deleting = false;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  /// Stats every local file, so this is a real disk read rather than a guess.
  ///
  /// Done on the isolate that builds the UI, which is fine at library scale
  /// (a few thousand `stat` calls) and keeps the numbers honest: a cached size
  /// would go stale the moment anything was deleted.
  void _reload() {
    setState(() {
      _entries = storageEntries(songs: library.songList, sort: _sort);
    });
  }

  Future<void> _deleteSelected() async {
    final chosen = _entries
        .where((entry) => _selected.contains(entry.song.id))
        .toList();
    if (chosen.isEmpty) return;
    final confirmed = await showConfirmDialog(
      context,
      'Delete ${chosen.length} ${chosen.length == 1 ? "track" : "tracks"} '
      '(${formatBytes(totalBytes(chosen))})',
    );
    if (!confirmed) return;

    setState(() => _deleting = true);
    var reclaimed = 0;
    for (final entry in chosen) {
      reclaimed += await deleteEntry(entry);
    }
    // The library is rebuilt from what is on disk, so a rescan is what
    // actually removes these from Songs, Albums and every playlist that held
    // them — deleting the file alone would leave the entries behind until the
    // next restart.
    if (!Loader.busy) await Loader.sync();
    if (!mounted) return;
    _selected.clear();
    setState(() => _deleting = false);
    _reload();
    showCenterMessage('Freed ${formatBytes(reclaimed)}');
  }

  @override
  Widget build(BuildContext context) {
    final selectedEntries = _entries
        .where((entry) => _selected.contains(entry.song.id))
        .toList();

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Storage',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: highlightTextColor.value,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              _entries.isEmpty
                  ? 'Nothing stored on this device.'
                  : '${_entries.length} tracks · '
                        '${formatBytes(totalBytes(_entries))} on this device',
              style: TextStyle(fontSize: 12, color: textColor.value),
            ),
            const SizedBox(height: 12),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  for (final sort in CleanupSort.values)
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: ChoiceChip(
                        label: Text(
                          sort.label,
                          style: const TextStyle(fontSize: 12),
                        ),
                        selected: _sort == sort,
                        onSelected: _deleting
                            ? null
                            : (_) {
                                _sort = sort;
                                _reload();
                              },
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: ListView.builder(
                itemCount: _entries.length,
                itemBuilder: (context, i) => _row(_entries[i]),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: Text(
                    selectedEntries.isEmpty
                        ? 'Select tracks to delete'
                        : '${selectedEntries.length} selected · '
                              '${formatBytes(totalBytes(selectedEntries))}',
                    style: TextStyle(fontSize: 12, color: textColor.value),
                  ),
                ),
                if (selectedEntries.isNotEmpty && !_deleting)
                  TextButton(
                    onPressed: () => setState(_selected.clear),
                    child: const Text('Clear'),
                  ),
                FilledButton(
                  onPressed: selectedEntries.isEmpty || _deleting
                      ? null
                      : _deleteSelected,
                  child: Text(_deleting ? 'Deleting…' : 'Delete'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _row(StorageEntry entry) {
    final song = entry.song;
    final selected = _selected.contains(song.id);
    // Whichever fact the current ordering is about — showing play count while
    // sorting by size, or vice versa, makes the list impossible to reason
    // about.
    final detail = switch (_sort) {
      CleanupSort.largest => getArtist(song),
      CleanupSort.leastPlayed =>
        song.playCount == 0 ? 'Never played' : '${song.playCount} plays',
      CleanupSort.oldest => song.lastPlayed == null
          ? 'Never played'
          : 'Last played ${_ago(song.lastPlayed!)}',
    };

    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      selected: selected,
      enabled: !_deleting,
      leading: Checkbox(
        value: selected,
        visualDensity: VisualDensity.compact,
        onChanged: _deleting ? null : (_) => _toggle(song.id),
      ),
      onTap: _deleting ? null : () => _toggle(song.id),
      title: Text(
        getTitle(song),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 13, color: highlightTextColor.value),
      ),
      subtitle: Text(
        detail,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 11, color: textColor.value),
      ),
      trailing: Text(
        formatBytes(entry.sizeBytes),
        style: TextStyle(fontSize: 11.5, color: textColor.value),
      ),
    );
  }

  void _toggle(String id) {
    setState(() {
      if (!_selected.remove(id)) _selected.add(id);
    });
  }

  String _ago(DateTime when) {
    final days = DateTime.now().difference(when).inDays;
    if (days < 1) return 'today';
    if (days < 30) return '$days days ago';
    if (days < 365) return '${days ~/ 30} months ago';
    final years = days ~/ 365;
    return years == 1 ? 'a year ago' : '$years years ago';
  }
}
