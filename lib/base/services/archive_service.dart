/// Archiving one URL, and making the result show up in the library.
///
/// Shared by every screen that can start a download — the Downloads form, a
/// discovery playlist, a catalog album — so they cannot drift apart on the two
/// things that are easy to get wrong: passing the pipeline a writable temp
/// directory, and registering the archive folder afterwards.
library;

import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/cookie_store.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';

/// Archives [url]. Returns null on success, or a message describing the
/// failure.
///
/// Never throws: callers are UI code streaming a batch, and an exception
/// halfway through a fifty-track playlist would lose the progress already made.
///
/// Retrying is cheap by default. With [redownload] off the pipeline skips any
/// track whose file is already on disk, so re-running an album that failed at
/// track nine fetches the rest rather than the lot; the pipeline's own
/// docstring explains why this is track-level and not byte-level. Pass
/// [redownload] to deliberately replace a file that is already there.
Future<String?> archiveUrl(
  String url, {
  bool redownload = false,
  void Function(int progress, String status)? onProgress,
}) async {
  // Pull the current settings at call time, not at startup: the user may
  // have changed quality or Widevine config between downloads.
  final payload = <String, dynamic>{
    'url': url,
    'cookies_path': cookiesPath,
    'output_dir': downloadOutputDir,
    'temp_dir': downloadTempDir,
    'overwrite': redownload,
    'codec': downloadCodecNotifier.value,
  };
  if (useWrapperNotifier.value) {
    payload['use_wrapper'] = true;
    final wrapperUrl = wrapperUrlNotifier.value.trim();
    if (wrapperUrl.isNotEmpty) payload['wrapper_url'] = wrapperUrl;
  } else if (wvdPathNotifier.value != null && wvdPathNotifier.value!.isNotEmpty) {
    payload['wvd_path'] = wvdPathNotifier.value;
  }

  String? error;
  await for (final event in pipelineRunner.run('download', payload)) {
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
