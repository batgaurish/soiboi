/// Deciding what is worth deleting.
///
/// Everything an archival player downloads stays on the device that downloaded
/// it — that is the whole point, and it is also why storage eventually runs
/// out. The three orderings here are the three questions people actually ask
/// at that moment: what is huge, what did I never play, and what have I not
/// touched in a year.
///
/// No new metadata: size comes from the file itself, and play count and last
/// played are the same fields smart playlists already filter on.
library;

import 'dart:io';

import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

enum CleanupSort {
  largest('Largest'),
  leastPlayed('Least played'),
  oldest('Not played in longest');

  const CleanupSort(this.label);
  final String label;
}

/// One track, with what it costs on disk.
class StorageEntry {
  const StorageEntry(this.song, this.sizeBytes);

  final MyAudioMetadata song;
  final int sizeBytes;
}

/// Size of the file at [path], or 0 if it cannot be read.
///
/// Zero rather than null so a file that vanished between the library scan and
/// this screen sorts harmlessly to the bottom instead of crashing the list.
int fileSize(String path) {
  try {
    return File(path).lengthSync();
  } catch (_) {
    return 0;
  }
}

/// [songs] as deletable entries, ordered by [sort].
///
/// Empty on a streaming source: those songs have no local file to reclaim, and
/// offering to delete one would either do nothing or silently drop a cache
/// entry the user did not ask about.
List<StorageEntry> storageEntries({
  required Iterable<MyAudioMetadata> songs,
  CleanupSort sort = CleanupSort.largest,
  int Function(String path) sizeOf = fileSize,
}) {
  if (isStreamSource) return const [];
  final entries = [
    for (final song in songs)
      if (song.path != null) StorageEntry(song, sizeOf(song.path!)),
  ];

  switch (sort) {
    case CleanupSort.largest:
      entries.sort((a, b) => b.sizeBytes.compareTo(a.sizeBytes));
    case CleanupSort.leastPlayed:
      // Size breaks the tie, because the point of the list is reclaiming
      // space: among a hundred tracks played zero times, the big ones first.
      entries.sort((a, b) {
        final byPlays = a.song.playCount.compareTo(b.song.playCount);
        return byPlays != 0 ? byPlays : b.sizeBytes.compareTo(a.sizeBytes);
      });
    case CleanupSort.oldest:
      // Never played sorts first: it is the strongest form of "not played in
      // longest", and treating null as recent would hide exactly the tracks
      // this ordering exists to surface.
      entries.sort((a, b) {
        final left = a.song.lastPlayed;
        final right = b.song.lastPlayed;
        if (left == null && right == null) {
          return b.sizeBytes.compareTo(a.sizeBytes);
        }
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
      });
  }
  return entries;
}

int totalBytes(Iterable<StorageEntry> entries) =>
    entries.fold(0, (sum, entry) => sum + entry.sizeBytes);

/// Human-readable size. Deliberately coarse — one decimal is enough to choose
/// between two albums, and more digits only make a list harder to scan.
String formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  final kb = bytes / 1024;
  if (kb < 1024) return '${kb.toStringAsFixed(0)} KB';
  final mb = kb / 1024;
  if (mb < 1024) return '${mb.toStringAsFixed(1)} MB';
  return '${(mb / 1024).toStringAsFixed(2)} GB';
}

/// Deletes the file behind [entry], plus the files that only exist to describe
/// it. Returns the bytes actually reclaimed.
///
/// The sidecars matter: an orphaned `.soiboi-acoustic.json` would be re-read on
/// the next scan and quietly attach one track's mood features to whatever
/// lands at that path later.
Future<int> deleteEntry(StorageEntry entry) async {
  final path = entry.song.path;
  if (path == null) return 0;
  var reclaimed = 0;
  final dot = path.lastIndexOf('.');
  for (final candidate in [
    path,
    '$path.soiboi-acoustic.json',
    // An extensionless file has no lyric sidecar to look for; guarding here
    // rather than building "lrc" as a relative path in the working directory.
    if (dot > 0) '${path.substring(0, dot + 1)}lrc',
  ]) {
    final file = File(candidate);
    try {
      if (!file.existsSync()) continue;
      reclaimed += file.lengthSync();
      await file.delete();
    } catch (_) {
      // A file that will not delete — a permission problem, a race with the
      // scanner — must not stop the rest of a bulk cleanup.
    }
  }
  return reclaimed;
}
