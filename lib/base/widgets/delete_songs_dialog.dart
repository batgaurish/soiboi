import 'dart:io';
import 'package:material_ui/material_ui.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/center_toast.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';
import 'package:soiboi/base/services/song_deletion.dart';
import 'package:soiboi/base/widgets/dialogs.dart';

/// Asks, then deletes [songs] from the device and the library. Returns
/// whether anything was deleted.
Future<bool> confirmAndDeleteSongs(
  BuildContext context,
  List<MyAudioMetadata> songs,
) async {
  final deletable = songs.where(canDeleteFromDevice).toList();
  if (deletable.isEmpty) {
    showCenterMessage(
      songs.length == 1
          ? 'This song is not a file on this device, so it cannot be deleted.'
          : 'None of these songs are files on this device.',
    );
    return false;
  }

  final count = deletable.length;
  final what = count == 1 ? '"${getTitle(deletable.single)}"' : '$count songs';
  final confirmed = await showConfirmDialog(
    context,
    'Delete $what?',
    message:
        '${count == 1 ? 'The file' : 'The files'} will be deleted from this '
        "device's storage, not just removed from Soiboi. "
        "This can't be undone."
        '${deletable.length < songs.length ? '\n\n${songs.length - count} of '
                  'the selected songs are not files on this device and '
                  'will be left alone.' : ''}',
    confirmText: 'Delete',
  );
  if (!confirmed) return false;

  final result = await deleteSongFiles(
    deletable,
    retry: Platform.isAndroid
        ? () async =>
              (await Permission.manageExternalStorage.request()).isGranted
        : null,
  );
  await removeFromLibrary(result.deleted);

  if (result.failed.isEmpty) {
    showCenterMessage(
      result.deleted.length == 1
          ? 'Deleted from this device'
          : 'Deleted ${result.deleted.length} songs from this device',
    );
  } else {
    final first = result.failed.entries.first;
    showCenterMessage(
      '${result.deleted.isEmpty ? 'Nothing deleted' : 'Deleted ${result.deleted.length}'}. '
      '${result.failed.length} could not be deleted: '
      '${getTitle(first.key)} (${first.value})',
      duration: 4000,
    );
  }
  return result.deleted.isNotEmpty;
}
