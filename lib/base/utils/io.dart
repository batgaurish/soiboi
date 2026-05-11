import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:particle_music/base/app.dart';
import 'package:particle_music/base/utils/logger.dart';
import 'package:particle_music/base/services/webdav_client.dart';
import 'package:particle_music/base/data/library.dart';

Future<List<String>> getWebdavSubDirectoriesFrom(String root) async {
  List<String> dirList = [];
  Queue<String> dirQueue = Queue();
  dirQueue.add(root);
  while (dirQueue.isNotEmpty) {
    String dir = dirQueue.first;
    dirQueue.removeFirst();
    final fileList = await webdavClient!.readDir(dir);
    for (final f in fileList) {
      if (f.isDir!) {
        final tmpPath = f.path!.substring(0, f.path!.length - 1);
        dirList.add(tmpPath);
        dirQueue.add(tmpPath);
      }
    }
  }
  return dirList;
}

bool isFileProviderStorePath(String path) {
  return path.contains('File Provider Storage/');
}

// full path to short path
String convertIOSPath(String path) {
  if (path.contains('File Provider Storage/')) {
    return path.split('File Provider Storage/').last;
  } else {
    path = path.substring(path.indexOf('Documents'));
    return path.replaceFirst('Documents', 'Particle Music');
  }
}

// short path to full path
String revertIOSPath(String path) {
  if (path.startsWith('Particle Music')) {
    return "${appDocsDir.parent.path}/${path.replaceFirst('Particle Music', 'Documents')}";
  } else {
    if (library.iosFileProviderStorage == null) {
      return '';
    }
    return library.iosFileProviderStorage! + path;
  }
}

// full path to short path
String convertIOSSupportPath(String path) {
  return path.split('Application Support/').last;
}

// short path to full path
String revertIOSSupportPath(String path) {
  return "${appSupportDir.path}/$path";
}

Map<String, String>? getWebdavHeaders() {
  if (webdavUsername == '') {
    return null;
  }
  return {
    'Authorization':
        'Basic ${base64Encode(utf8.encode('$webdavUsername:$webdavPassword'))}',
  };
}

Future<void> downloadFile(
  String url,
  String savePath, {
  Map<String, String>? headers,
}) async {
  try {
    final response = await http.get(Uri.parse(url), headers: headers);

    if (response.statusCode == 200) {
      final file = File(savePath);
      await file.create(recursive: true);
      await file.writeAsBytes(response.bodyBytes);
    } else {
      logger.output('$url download failed');
    }
  } catch (e) {
    logger.output(e.toString());
  }
}
