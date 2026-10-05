/// Deleting songs from the device, not just from the app.
///
/// Every place that offers "Delete from device" (a song's menu on Home and
/// Songs, the player's menu, a multi-selection) comes through
/// [confirmAndDeleteSongs], so they all warn the same way and leave the
/// library in the same state afterwards.
library;

import 'dart:io';

import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/history.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/logger.dart';

/// Whether [song] is a file on this device that can be deleted. Songs from a
/// streaming server or WebDAV are not ours to delete.
bool canDeleteFromDevice(MyAudioMetadata song) {
  final path = song.path;
  return isNotStreamSource &&
      sourceType != .webdav &&
      path != null &&
      !path.startsWith('http://') &&
      !path.startsWith('https://');
}

/// What a deletion did.
class DeletionResult {
  final deleted = <MyAudioMetadata>[];

  /// Songs that could not be deleted, with why.
  final failed = <MyAudioMetadata, String>{};
}

/// The files that belong to the song at [audioPath]: the audio itself, then
/// the synced-lyrics and mood sidecars the app and pipeline write beside it.
List<File> filesFor(String audioPath) {
  final dot = audioPath.lastIndexOf('.');
  final base = dot > audioPath.lastIndexOf('/')
      ? audioPath.substring(0, dot)
      : audioPath;
  return [File(audioPath), File('$base.lrc'), ?acousticSidecarFor(audioPath)];
}

/// Deletes each song's file and sidecars. The audio file decides success: a
/// sidecar that will not go is left behind rather than failing the song.
///
/// [retry] is asked once, after the first permission failure, whether to try
/// again (on Android, by asking for All files access).
Future<DeletionResult> deleteSongFiles(
  List<MyAudioMetadata> songs, {
  Future<bool> Function()? retry,
}) async {
  final result = DeletionResult();
  var askedToRetry = false;
  for (final song in songs) {
    if (!canDeleteFromDevice(song)) {
      result.failed[song] = 'Not a file on this device';
      continue;
    }
    final files = filesFor(song.path!);
    final audio = files.first;
    try {
      await _delete(audio);
    } on FileSystemException catch (e) {
      if (!askedToRetry && retry != null && _isPermission(e)) {
        askedToRetry = true;
        if (await retry()) {
          try {
            await _delete(audio);
          } on FileSystemException catch (e) {
            result.failed[song] = _reason(e);
            continue;
          }
        } else {
          result.failed[song] = _reason(e);
          continue;
        }
      } else {
        result.failed[song] = _reason(e);
        continue;
      }
    }
    for (final sidecar in files.skip(1)) {
      try {
        await _delete(sidecar);
      } on FileSystemException catch (e) {
        logger.output('delete sidecar ${sidecar.path}: $e');
      }
    }
    result.deleted.add(song);
  }
  return result;
}

/// Deletes [file]; one that is already gone counts as deleted.
Future<void> _delete(File file) async {
  if (await file.exists()) await file.delete();
}

bool _isPermission(FileSystemException e) {
  final code = e.osError?.errorCode;
  // EACCES and EPERM.
  return code == 13 || code == 1;
}

String _reason(FileSystemException e) => _isPermission(e)
    ? 'No permission to delete it'
    : e.osError?.message ?? e.message;

/// Takes [songs] out of everything that holds them: the library and its
/// database, folders, playlists, artists and albums, history and the play
/// queue. Done in place rather than by a full library sync, which would
/// rescan every folder and close the page the user is on.
Future<void> removeFromLibrary(List<MyAudioMetadata> songs) async {
  if (songs.isEmpty) return;
  final gone = songs.toSet();
  final ids = {for (final song in songs) song.id};

  library.songList.removeWhere(gone.contains);
  ids.forEach(library.id2Song.remove);
  library.update();

  for (final folder in library.folderList) {
    final before = folder.songList.length;
    folder.songList.removeWhere(gone.contains);
    // A folder's saved id list must not keep a song the library lost:
    // loading it looks every id up with `!`.
    if (folder.songList.length != before) await folder.update();
  }

  for (final playlist in playlistManager.playlists) {
    final inIt = playlist.songList.where(gone.contains).toList();
    if (inIt.isNotEmpty) await playlist.remove(inIt);
  }

  artistAlbumManager.updateArtistAlbum();
  history.load();
  // Rebuilds the queue from the library and, if the song playing was one of
  // these, moves on to the next or stops.
  await audioHandler.sync();
}
