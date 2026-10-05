import 'dart:convert';
import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/folder.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';
import 'package:soiboi/base/utils/path.dart';
import 'package:soiboi/base/widgets/manage_music_folders.dart';

MyAudioMetadata makeSong(String id, String path) {
  return MyAudioMetadata(
    AudioMetadata(title: id),
    id: id,
    path: path,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;
  late Directory musicDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_folder_test_');
    appSupportDir = tempDir;
  });

  setUp(() {
    musicDir = Directory('${tempDir.path}/music')..createSync(recursive: true);
    sourceType = SourceType.local;
    recursiveScanNotifier.value = false;
    library.id2Song.clear();
    library.songList.clear();
  });

  tearDown(() {
    if (musicDir.existsSync()) {
      musicDir.deleteSync(recursive: true);
    }
    library.id2Song.clear();
    library.songList.clear();
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  test('Folder constructor initializes _songIdListFile', () {
    final folder = Folder('f1', musicDir.path);
    final hash = md5.convert(utf8.encode('f1')).toString();
    final configFile = File('${getFolderConfigPath(SourceType.local)}/$hash.json');

    expect(configFile.existsSync(), isTrue);
    expect(jsonDecode(configFile.readAsStringSync()), isEmpty);
    expect(folder.scanRecursively, isFalse);
  });

  test('scanRecursively reflects recursiveScanNotifier and downloadOutputDir', () {
    final folder = Folder('f_normal', musicDir.path);
    expect(folder.scanRecursively, isFalse);

    recursiveScanNotifier.value = true;
    expect(folder.scanRecursively, isTrue);

    recursiveScanNotifier.value = false;
    final archiveFolder = Folder(downloadOutputDir, musicDir.path);
    expect(archiveFolder.scanRecursively, isTrue);
  });

  test('setFileAndModified filters supported audio extensions and honors recursion', () async {
    // Top-level files
    File('${musicDir.path}/song1.mp3').writeAsStringSync('audio');
    File('${musicDir.path}/song2.flac').writeAsStringSync('audio');
    File('${musicDir.path}/notes.txt').writeAsStringSync('text');
    File('${musicDir.path}/cover.jpg').writeAsStringSync('image');

    // Subdirectory files
    final subDir = Directory('${musicDir.path}/sub')..createSync();
    File('${subDir.path}/song3.m4a').writeAsStringSync('audio');

    // Non-recursive scan
    final folderFlat = Folder('f_flat', musicDir.path);
    await folderFlat.setFileAndModified();

    expect(folderFlat.pathAndModified.length, 2);
    expect(folderFlat.pathAndModified.containsKey('${musicDir.path}/song1.mp3'), isTrue);
    expect(folderFlat.pathAndModified.containsKey('${musicDir.path}/song2.flac'), isTrue);
    expect(folderFlat.pathAndModified.containsKey('${subDir.path}/song3.m4a'), isFalse);

    // Recursive scan
    recursiveScanNotifier.value = true;
    final folderRecursive = Folder('f_rec', musicDir.path);
    await folderRecursive.setFileAndModified();

    expect(folderRecursive.pathAndModified.length, 3);
    expect(folderRecursive.pathAndModified.containsKey('${subDir.path}/song3.m4a'), isTrue);
  });

  test('load populates songList from config file using library.id2Song', () async {
    final folder = Folder('f_load', musicDir.path);
    final hash = md5.convert(utf8.encode('f_load')).toString();
    final configFile = File('${getFolderConfigPath(SourceType.local)}/$hash.json');

    final s1 = makeSong('id1', '${musicDir.path}/song1.mp3');
    final s2 = makeSong('id2', '${musicDir.path}/song2.mp3');
    library.id2Song['id1'] = s1;
    library.id2Song['id2'] = s2;

    await configFile.writeAsString(jsonEncode(['id1', 'id2', 'id_unknown']));

    int notified = 0;
    folder.changeNotifier.addListener(() => notified++);

    await folder.load();

    expect(folder.songList.length, 2);
    expect(folder.songList[0].id, 'id1');
    expect(folder.songList[1].id, 'id2');
    expect(folder.canModify, isTrue);
    expect(notified, 1);
  });

  test('update, shuffle, and delete modify or remove config file', () async {
    final folder = Folder('f_ops', musicDir.path);
    final hash = md5.convert(utf8.encode('f_ops')).toString();
    final configFile = File('${getFolderConfigPath(SourceType.local)}/$hash.json');

    final s1 = makeSong('id1', '${musicDir.path}/s1.mp3');
    final s2 = makeSong('id2', '${musicDir.path}/s2.mp3');
    final s3 = makeSong('id3', '${musicDir.path}/s3.mp3');
    folder.songList.addAll([s1, s2, s3]);

    await folder.update();
    expect(jsonDecode(configFile.readAsStringSync()), ['id1', 'id2', 'id3']);

    folder.shuffle();
    expect(folder.songList.length, 3);
    expect(configFile.existsSync(), isTrue);

    folder.delete();
    expect(configFile.existsSync(), isFalse);
  });
}
