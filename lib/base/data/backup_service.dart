/// Library/playlist backup and restore.
///
/// Soiboi has no server and no sync -- whichever device downloads a track
/// keeps the only copy of it, and that is a deliberate choice, not a gap.
/// But it means the *organisation* of a library (playlists, smart playlists,
/// folder registrations, settings) has nowhere else to live either: losing a
/// device currently means losing all of that with no way back. This gives
/// the user a file they control instead.
///
/// Deliberately excludes audio files. Folders are per-device paths, not
/// portable, and re-archiving a track is what the standalone model already
/// expects -- restoring should get someone back to "my playlists and rules
/// are here, go re-add your music folders," not attempt to move gigabytes of
/// audio through a JSON file.
library;

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/utils/path.dart';

/// Bump when [collectBackup]'s output shape changes in a way [restoreBackup]
/// must handle differently.
const backupFormatVersion = 1;

/// Everything Soiboi persists about how a library is organised, as one JSON
/// tree: general settings (including the ListenBrainz username and
/// theme/flavour prefs, all stored in a single `setting.json`), plus every
/// source type's folder registrations and playlists.
Future<Map<String, dynamic>> collectBackup() async {
  final sources = <String, dynamic>{};
  for (final type in SourceType.values) {
    final folderConfig = await _readDirAsMap(
      Directory(getFolderConfigPath(type)),
    );
    final playlistConfig = await _readDirAsMap(
      Directory(getPlaylistConfigPath(type)),
    );
    if (folderConfig.isEmpty && playlistConfig.isEmpty) continue;
    sources[type.name] = {
      'folder_config': folderConfig,
      'playlist_config': playlistConfig,
    };
  }

  final settingFile = File('${appSupportDir.path}/setting.json');
  final settingJson = await settingFile.exists()
      ? jsonDecode(await settingFile.readAsString())
      : null;

  return {
    'version': backupFormatVersion,
    'exportedAt': DateTime.now().toIso8601String(),
    'setting': settingJson,
    'sources': sources,
  };
}

Future<Map<String, String>> _readDirAsMap(Directory dir) async {
  final result = <String, String>{};
  if (!await dir.exists()) return result;
  await for (final entity in dir.list()) {
    if (entity is! File) continue;
    try {
      result[entity.uri.pathSegments.last] = await entity.readAsString();
    } catch (_) {
      // A single unreadable file should not fail the whole backup.
    }
  }
  return result;
}

/// Writes [data] (from [collectBackup]) back into the same config paths it
/// came from, overwriting files of the same name.
///
/// Folder registrations reference paths on whatever device the backup came
/// from -- restoring them does not make those folders exist here. The
/// library will not show any songs from a restored playlist until the user
/// re-adds their music folders and the paths happen to line up again, or the
/// smart playlists still evaluate correctly against a freshly-scanned
/// library since they match by rule, not by stored song reference.
Future<void> restoreBackup(Map<String, dynamic> data) async {
  final settingJson = data['setting'];
  if (settingJson is Map) {
    final settingFile = File('${appSupportDir.path}/setting.json');
    await settingFile.parent.create(recursive: true);
    await settingFile.writeAsString(jsonEncode(settingJson));
  }

  final sources = data['sources'];
  if (sources is! Map) return;
  for (final entry in sources.entries) {
    final type = SourceType.values.firstWhere(
      (t) => t.name == entry.key,
      orElse: () => SourceType.local,
    );
    final value = entry.value;
    if (value is! Map) continue;
    await _writeDirFromMap(
      Directory(getFolderConfigPath(type)),
      value['folder_config'],
    );
    await _writeDirFromMap(
      Directory(getPlaylistConfigPath(type)),
      value['playlist_config'],
    );
  }
}

Future<void> _writeDirFromMap(Directory dir, dynamic filesJson) async {
  if (filesJson is! Map) return;
  await dir.create(recursive: true);
  for (final entry in filesJson.entries) {
    final content = entry.value;
    if (content is! String) continue;
    await File('${dir.path}/${entry.key}').writeAsString(content);
  }
}

/// Lets the user choose where to save a backup file. Returns the chosen path,
/// or null if they cancelled.
Future<String?> exportBackupToFile() async {
  final data = await collectBackup();
  final bytes = Uint8List.fromList(utf8.encode(jsonEncode(data)));
  final date = DateTime.now().toIso8601String().split('T').first;
  return FilePicker.saveFile(
    dialogTitle: 'Save Soiboi backup',
    fileName: 'soiboi-backup-$date.json',
    bytes: bytes,
  );
}

/// Thrown when a picked file isn't a Soiboi backup, so the caller can show a
/// specific message instead of a generic failure.
class InvalidBackupException implements Exception {
  InvalidBackupException(this.message);
  final String message;
}

/// Lets the user pick a backup file and restores it. Returns false if they
/// cancelled the picker; throws [InvalidBackupException] for anything picked
/// that isn't a valid Soiboi backup.
Future<bool> importBackupFromFile() async {
  final result = await FilePicker.pickFile(
    type: FileType.custom,
    allowedExtensions: ['json'],
  );
  if (result == null) return false;

  final bytes = await result.readAsBytes();
  final Map<String, dynamic> decoded;
  try {
    final parsed = jsonDecode(utf8.decode(bytes));
    if (parsed is! Map<String, dynamic>) throw const FormatException();
    decoded = parsed;
  } catch (_) {
    throw InvalidBackupException('That file is not valid JSON.');
  }

  if (decoded['version'] is! int || decoded['sources'] is! Map) {
    throw InvalidBackupException('That file is not a Soiboi backup.');
  }

  await restoreBackup(decoded);
  return true;
}
