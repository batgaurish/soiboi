import 'dart:convert';
import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/utils/path.dart';

MyAudioMetadata makeSong(String id) {
  return MyAudioMetadata(
    AudioMetadata(title: 'Track $id'),
    id: id,
    path: '/tmp/$id.mp3',
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_library_test_');
    appSupportDir = tempDir;
  });

  setUp(() {
    sourceType = SourceType.local;
    library = Library();
  });

  tearDown(() {
    library.folderList.clear();
    library.id2Song.clear();
    library.songList.clear();
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  test('updateFolders creates, retains, and removes folders, persisting to folder_id_list.json', () async {
    final folderPath1 = '${tempDir.path}/music1';
    final folderPath2 = '${tempDir.path}/music2';
    Directory(folderPath1).createSync(recursive: true);
    Directory(folderPath2).createSync(recursive: true);

    int folderListNotified = 0;
    library.folderListChangeNotifier.addListener(() => folderListNotified++);

    // Initial update adds two folders
    final changed = await library.updateFolders([folderPath1, folderPath2]);
    expect(changed, isTrue);
    expect(library.folderList.length, 2);
    expect(library.getFolderById(folderPath1), matcherNotNull);
    expect(library.getFolderById(folderPath2), matcherNotNull);
    expect(folderListNotified, 1);

    final configFile = File('${getFolderConfigPath(SourceType.local)}/folder_id_list.json');
    expect(configFile.existsSync(), isTrue);
    final savedIds = jsonDecode(configFile.readAsStringSync()) as List;
    expect(savedIds, [folderPath1, folderPath2]);

    // Updating with identical list does nothing
    final noChange = await library.updateFolders([folderPath1, folderPath2]);
    expect(noChange, isFalse);
    expect(folderListNotified, 1);

    // Updating removing folderPath1
    final removeChange = await library.updateFolders([folderPath2]);
    expect(removeChange, isTrue);
    expect(library.folderList.length, 1);
    expect(library.getFolderById(folderPath1), matcherNull);
    expect(library.getFolderById(folderPath2), matcherNotNull);
  });

  test('initFolders loads saved folders from folder_id_list.json', () async {
    final folderPath = '${tempDir.path}/saved_music';
    Directory(folderPath).createSync(recursive: true);

    final configFile = File('${getFolderConfigPath(SourceType.local)}/folder_id_list.json');
    await configFile.writeAsString(jsonEncode([folderPath]));

    final freshLib = Library();
    await freshLib.initFolders();

    expect(freshLib.folderList.length, 1);
    expect(freshLib.folderList.first.id, folderPath);
  });

  test('getFolderById returns null when folder is not found', () {
    expect(library.getFolderById('does_not_exist'), matcherNull);
  });
}

const matcherNotNull = isNotNull;
const matcherNull = isNull;
