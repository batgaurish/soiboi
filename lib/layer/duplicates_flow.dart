/// Settings > Remove duplicates: find songs the library holds more than once,
/// ask which codec wins where the copies differ, and delete the rest.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/duplicates.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/data/storage_cleanup.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/widgets/quality_badge.dart';

/// "ALAC", "AAC", "MP3": the codec half of the quality badge.
String _codec(MyAudioMetadata song) =>
    QualityInfo.of(song).label.split(' ').first;

Future<void> removeDuplicates(BuildContext context) async {
  final groups = findDuplicates(
    library.songList.where((song) => song.path != null),
    artist: (song) => song.artist,
    title: (song) => song.title,
  );
  if (groups.isEmpty) {
    showCenterMessage('No duplicates found');
    return;
  }

  // One question per mix of codecs, not per song: someone with forty
  // AAC + ALAC pairs wants to answer once.
  final choices = <String, String>{};
  for (final group in groups) {
    final codecs = group.map(_codec).toSet().toList()..sort();
    if (codecs.length < 2) continue;
    final mix = codecs.join(' + ');
    if (choices.containsKey(mix)) continue;
    final count = groups
        .where(
          (g) => (g.map(_codec).toSet().toList()..sort()).join(' + ') == mix,
        )
        .length;
    if (!context.mounted) return;
    final chosen = await _askCodec(context, codecs, count);
    if (chosen == null) return;
    choices[mix] = chosen;
  }

  final doomed = <MyAudioMetadata>[];
  for (final group in groups) {
    final codecs = group.map(_codec).toSet().toList()..sort();
    final keep = pickKeeper(
      group,
      codec: _codec,
      bitrate: (song) => song.bitrate ?? 0,
      prefer: choices[codecs.join(' + ')],
    );
    doomed.addAll(group.where((song) => !identical(song, keep)));
  }

  if (!context.mounted) return;
  final entries = [
    for (final song in doomed) StorageEntry(song, fileSize(song.path!)),
  ];
  final confirmed = await showConfirmDialog(
    context,
    'Delete ${entries.length} ${entries.length == 1 ? "copy" : "copies"}',
  );
  if (!confirmed) return;

  var reclaimed = 0;
  for (final entry in entries) {
    reclaimed += await deleteEntry(entry);
  }
  // The library is rebuilt from disk, so a rescan is what drops the deleted
  // copies from Songs, Albums and playlists.
  if (!Loader.busy) await Loader.sync();
  showCenterMessage(
    'Removed ${entries.length} duplicates, freed ${formatBytes(reclaimed)}',
  );
}

Future<String?> _askCodec(
  BuildContext context,
  List<String> codecs,
  int songs,
) {
  return showAnimationDialog<String>(
    context: context,
    child: Builder(
      builder: (context) => SizedBox(
        width: 300,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Keep which copy?',
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.bold,
                  color: colorManager.getSpecificTextColor(),
                ),
              ),
              const SizedBox(height: 15),
              Text(
                '$songs ${songs == 1 ? "song is" : "songs are"} in your '
                'library as both ${codecs.join(" and ")}. The other copies '
                'will be deleted.',
                style: TextStyle(
                  fontSize: 14,
                  color: colorManager.getSpecificTextColor(),
                ),
              ),
              const SizedBox(height: 20),
              Wrap(
                spacing: 12,
                runSpacing: 8,
                children: [
                  for (final codec in codecs)
                    ElevatedButton(
                      onPressed: () => Navigator.pop(context, codec),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: colorManager.getSpecificButtonColor(),
                        foregroundColor: colorManager.getSpecificTextColor(),
                      ),
                      child: Text('Keep $codec'),
                    ),
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: Text(
                      'Cancel',
                      style: TextStyle(
                        color: colorManager.getSpecificTextColor(),
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
