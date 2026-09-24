/// Archiving one URL, and making the result show up in the library.
///
/// Shared by every screen that can start a download — the Downloads form, a
/// discovery playlist, a catalog album — so they cannot drift apart on the two
/// things that are easy to get wrong: passing the pipeline a writable temp
/// directory, and registering the archive folder afterwards.
library;

import 'dart:io';

import 'package:permission_handler/permission_handler.dart';
import 'package:soiboi/base/services/wrapper_service.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/cookie_store.dart';
import 'package:soiboi/base/services/library_match_service.dart';
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
  await _ensureDownloadFolderWritable();

  // Pull the current settings at call time, not at startup: the user may
  // have changed quality or Widevine config between downloads.
  final payload = <String, dynamic>{
    'url': url,
    'cookies_path': cookiesPath,
    'output_dir': downloadOutputDir,
    'temp_dir': downloadTempDir,
    'overwrite': redownload,
    'codec': downloadCodecNotifier.value,
    // The pipeline skips these whatever their codec or file name; gamdl on
    // its own only skips an identical output path.
    if (!redownload) 'owned': ownedSongKeys(),
  };
  // The bundled wrapper takes every download it can: it is needed for ALAC,
  // and once signed in it is the account's own session, so no cookies file
  // is involved. Starting it here means it only runs when someone downloads.
  final bundled = wrapperService.supported
      ? (downloadCodecNotifier.value == 'alac'
            ? await _bundledWrapperPayload()
            : await wrapperService.ensureReady())
      : null;
  if (bundled != null) {
    payload.addAll(bundled);
  } else if (useWrapperNotifier.value) {
    payload['use_wrapper'] = true;
    final wrapperUrl = wrapperUrlNotifier.value.trim();
    if (wrapperUrl.isNotEmpty) payload['wrapper_url'] = wrapperUrl;
  } else if (wvdPathNotifier.value != null &&
      wvdPathNotifier.value!.isNotEmpty) {
    payload['wvd_path'] = wvdPathNotifier.value;
  }

  String? error;
  var finished = false;
  await for (final event in pipelineRunner.run('download', payload)) {
    if (event.isProgress) {
      onProgress?.call(event.progress, event.status);
    } else if (event.isError) {
      error = event.message;
    } else if (event.isDone) {
      finished = true;
    }
  }
  // A pipeline that dies mid-download (a Python traceback, a killed process)
  // closes the stream without a terminal event. That is a failure, not a
  // silent success.
  if (error == null && !finished) {
    return 'The downloader stopped without finishing. See the log for details.';
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

Future<Map<String, Object>?> _bundledWrapperPayload() async {
  await wrapperService.refresh();
  if (!wrapperService.librariesInstalled) return null;
  await wrapperService.start();
  return wrapperService.downloadPayload;
}

/// On Android, asks for All files access when the chosen download folder
/// needs it.
///
/// Writing into shared storage such as /storage/emulated/0/Music takes All
/// files access; the Music and audio permission only reads. Without it the
/// folder fails the write test and downloads silently fall back to the app's
/// private folder, where no other app can see them.
Future<void> _ensureDownloadFolderWritable() async {
  if (!Platform.isAndroid) return;
  final chosen = downloadFolderNotifier.value.trim();
  if (chosen.isEmpty || downloadDirIsUsable(chosen)) return;
  final status = await Permission.manageExternalStorage.request();
  if (status.isGranted) downloadOutputDir = resolveDownloadDir();
}
