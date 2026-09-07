/// Archiving one URL, and making the result show up in the library.
///
/// Shared by every screen that can start a download — the Downloads form, a
/// discovery playlist, a catalog album — so they cannot drift apart on the two
/// things that are easy to get wrong: passing the pipeline a writable temp
/// directory, and registering the archive folder afterwards.
library;

import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/services/cookie_store.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';

/// Archives [url]. Returns null on success, or a message describing the
/// failure.
///
/// Never throws: callers are UI code streaming a batch, and an exception
/// halfway through a fifty-track playlist would lose the progress already made.
Future<String?> archiveUrl(
  String url, {
  void Function(int progress, String status)? onProgress,
}) async {
  String? error;
  await for (final event in pipelineRunner.run('download', {
    'url': url,
    'cookies_path': cookiesPath,
    'output_dir': downloadOutputDir,
    'temp_dir': downloadTempDir,
  })) {
    if (event.isProgress) {
      onProgress?.call(event.progress, event.status);
    } else if (event.isError) {
      error = event.message;
    }
  }
  return error;
}

/// Makes archived files visible in the library.
///
/// Downloads land in the app's own storage, which is not a folder anyone would
/// ever add by hand — so without this the promise that files are "added to your
/// library on this device" is simply false, and Songs still reads zero after a
/// successful download.
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
