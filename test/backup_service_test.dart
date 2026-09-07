import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/backup_service.dart';
import 'package:soiboi/base/utils/path.dart';

void main() {
  appSupportDir = Directory.systemTemp.createTempSync('soiboi_backup_test');

  Future<void> writeConfig(SourceType type, String dir, String name,
      Map<String, dynamic> content) async {
    final path = dir == 'folder_config'
        ? getFolderConfigPath(type)
        : getPlaylistConfigPath(type);
    final file = File('$path/$name');
    await file.parent.create(recursive: true);
    await file.writeAsString(jsonEncode(content));
  }

  test('collects general settings and every source type\'s config', () async {
    final settingFile = File('${appSupportDir.path}/setting.json');
    await settingFile.create(recursive: true);
    await settingFile.writeAsString(jsonEncode({'listenBrainzUser': 'abc'}));

    await writeConfig(
      SourceType.local,
      'folder_config',
      'smart_playlists.json',
      {'name': 'Energy'},
    );
    await writeConfig(
      SourceType.local,
      'playlist_config',
      'soiboi_playlists.json',
      {'names': []},
    );

    final backup = await collectBackup();

    expect(backup['version'], backupFormatVersion);
    expect(backup['setting'], {'listenBrainzUser': 'abc'});
    final sources = backup['sources'] as Map;
    expect(sources.containsKey('local'), isTrue);
    final local = sources['local'] as Map;
    expect(
      jsonDecode((local['folder_config'] as Map)['smart_playlists.json']),
      {'name': 'Energy'},
    );
    expect(
      jsonDecode((local['playlist_config'] as Map)['soiboi_playlists.json']),
      {'names': []},
    );
  });

  test('a source type with nothing persisted is omitted', () async {
    final backup = await collectBackup();
    final sources = backup['sources'] as Map;
    // webdav/navidrome/emby were never written to in this test run.
    expect(sources.containsKey('webdav'), isFalse);
  });

  test('restore writes files back to the exact paths they were read from',
      () async {
    await restoreBackup({
      'version': backupFormatVersion,
      'setting': {'listenBrainzUser': 'restored-user'},
      'sources': {
        'local': {
          'folder_config': {'smart_playlists.json': '{"name":"Sleep"}'},
          'playlist_config': {'soiboi_playlists.json': '{"names":["Chill"]}'},
        },
      },
    });

    final settingFile = File('${appSupportDir.path}/setting.json');
    expect(jsonDecode(await settingFile.readAsString()), {
      'listenBrainzUser': 'restored-user',
    });

    final smartFile = File(
      '${getFolderConfigPath(SourceType.local)}/smart_playlists.json',
    );
    expect(jsonDecode(await smartFile.readAsString()), {'name': 'Sleep'});

    final playlistFile = File(
      '${getPlaylistConfigPath(SourceType.local)}/soiboi_playlists.json',
    );
    expect(jsonDecode(await playlistFile.readAsString()), {
      'names': ['Chill'],
    });
  });

  test('restore tolerates a missing setting or sources key', () async {
    // Should not throw.
    await restoreBackup({'version': backupFormatVersion});
  });
}
