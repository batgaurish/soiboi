/// Backfills acoustic features for music the app did not download itself.
///
/// Smart playlists filter on energy, relaxed, danceable and so on, and those
/// numbers come from a `.soiboi-acoustic.json` sidecar written next to each
/// file. The pipeline only ever wrote them for tracks it had just downloaded:
/// `download` analyses its own output directory, and nothing else called the
/// analyser at all. So a library that came from anywhere else — copied in,
/// synced, ripped, downloaded by an older build — had no features on any
/// track, and since a missing number matches no threshold, every smart
/// playlist and every mood shelf came out empty. That is the whole of the
/// "smart playlists don't populate" report; the rules and the analyser were
/// both fine and simply never met.
///
/// Analysis is per-file and cached by the sidecar, so this is resumable and
/// cheap to re-run: files already carrying one are skipped, including the
/// empty sidecar written for a file that could not be decoded, which is what
/// stops a corrupt track being retried on every pass.
library;

import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';

/// How far a run has got, for the settings tile to show.
class AcousticProgress {
  const AcousticProgress({
    required this.folder,
    required this.folderIndex,
    required this.folderCount,
    required this.status,
  });

  final String folder;
  final int folderIndex;
  final int folderCount;
  final String status;
}

/// Totals across every folder in one run.
class AcousticSummary {
  const AcousticSummary({
    this.analysed = 0,
    this.skipped = 0,
    this.pending = 0,
    this.unavailable = false,
    this.error,
  });

  /// Files that gained features on this run.
  final int analysed;

  /// Files that already had a sidecar.
  final int skipped;

  /// Files that were attempted and produced nothing — undecodable or DRM
  /// locked. They carry an empty sidecar now and will not be retried.
  final int pending;

  /// The device has no analyser build, so nothing can be done here.
  final bool unavailable;

  final String? error;

  AcousticSummary operator +(AcousticSummary other) => AcousticSummary(
    analysed: analysed + other.analysed,
    skipped: skipped + other.skipped,
    pending: pending + other.pending,
    unavailable: unavailable || other.unavailable,
    error: error ?? other.error,
  );
}

/// Analyses every local folder in [library], newest settings first.
///
/// Never throws: this is called from a settings tap, and a folder that has
/// gone missing since it was added should not take the run down with it.
Future<AcousticSummary> analyseLibrary({
  void Function(AcousticProgress)? onProgress,
}) async {
  // WebDAV folders are not on this device, so there is no file to decode.
  final folders = library.folderList.where((f) => !f.isWebdav).toList();
  if (folders.isEmpty) {
    return const AcousticSummary();
  }

  var total = const AcousticSummary();
  for (var i = 0; i < folders.length; i++) {
    final folder = folders[i];
    onProgress?.call(
      AcousticProgress(
        folder: folder.path,
        folderIndex: i,
        folderCount: folders.length,
        status: 'Starting',
      ),
    );

    try {
      await for (final event in pipelineRunner.run('analyze', {
        'directory': folder.path,
      })) {
        if (event.isProgress) {
          onProgress?.call(
            AcousticProgress(
              folder: folder.path,
              folderIndex: i,
              folderCount: folders.length,
              status: event.status,
            ),
          );
        } else if (event.isError) {
          total = total +
              AcousticSummary(
                unavailable: event.code == 'no_analysis',
                error: event.message,
              );
        } else if (event.isDone) {
          total = total +
              AcousticSummary(
                analysed: (event.raw['analysed'] as num?)?.toInt() ?? 0,
                skipped: (event.raw['skipped'] as num?)?.toInt() ?? 0,
                pending: (event.raw['pending'] as num?)?.toInt() ?? 0,
              );
        }
      }
    } catch (e) {
      total = total + AcousticSummary(error: '$e');
    }
  }

  // The features live on the in-memory song objects, so the library has to
  // re-read the sidecars before any smart playlist will see them.
  for (final song in library.songList) {
    song.loadAcousticFeatures();
  }
  library.changeNotifier.value++;

  return total;
}
