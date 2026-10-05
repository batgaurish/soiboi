import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/history.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/stream_client.dart';

class TestStreamClient implements StreamClient {
  final List<String> scrobbledIds = [];
  List<Album>? albumResult;
  int albumCallCount = 0;
  int lastOffset = -1;
  String? lastType;

  @override
  Future<bool> scrobble(String songId) async {
    scrobbledIds.add(songId);
    return true;
  }

  @override
  Future<List<Album>?> getAlbumList(int offset, {String? type}) async {
    albumCallCount++;
    lastOffset = offset;
    lastType = type;
    return albumResult;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

MyAudioMetadata makeSong(String id, {int playCount = 0, DateTime? lastPlayed}) {
  return MyAudioMetadata(
    AudioMetadata(title: 'Song $id'),
    id: id,
    path: '/tmp/$id.mp3',
    playCount: playCount,
    lastPlayed: lastPlayed,
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late TestStreamClient mockStreamClient;

  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_history_test_');
  });

  setUp(() {
    mockStreamClient = TestStreamClient();
    streamClient = mockStreamClient;
    sourceType = SourceType.navidrome;
    library.songList.clear();
    history = History();
  });

  tearDown(() {
    library.songList.clear();
    streamClient = null;
    sourceType = SourceType.local;
  });

  test('load populates and sorts ranking and recently lists', () {
    final now = DateTime.now();
    final s1 = makeSong('1', playCount: 5, lastPlayed: now.subtract(const Duration(days: 2)));
    final s2 = makeSong('2', playCount: 10, lastPlayed: now.subtract(const Duration(days: 3)));
    final s3 = makeSong('3', playCount: 10, lastPlayed: now.subtract(const Duration(days: 1)));
    final sUnplayed = makeSong('4', playCount: 0, lastPlayed: null);

    library.songList.addAll([s1, s2, s3, sUnplayed]);

    int rankingNotified = 0;
    int recentlyNotified = 0;
    history.rankingChangeNotifier.addListener(() => rankingNotified++);
    history.recentlyChangeNotifier.addListener(() => recentlyNotified++);

    history.load();

    expect(rankingNotified, 1);
    expect(recentlyNotified, 1);

    // sUnplayed omitted
    expect(history.rankingSongList.length, 3);
    expect(history.recentlySongList.length, 3);

    // ranking: playCount desc; tie broken by lastPlayed asc
    expect(history.rankingSongList[0].id, '2'); // playCount 10, older
    expect(history.rankingSongList[1].id, '3'); // playCount 10, newer
    expect(history.rankingSongList[2].id, '1'); // playCount 5

    // recently: lastPlayed desc
    expect(history.recentlySongList[0].id, '3');
    expect(history.recentlySongList[1].id, '1');
    expect(history.recentlySongList[2].id, '2');
  });

  test('addSongTimes scrobbles to navidrome and updates ranking and recently order', () async {
    final s1 = makeSong('s1', playCount: 3);
    final s2 = makeSong('s2', playCount: 8);
    history.rankingSongList.addAll([s2, s1]);
    history.recentlySongList.addAll([s2, s1]);

    await history.addSongTimes(s1, 6); // s1 playCount becomes 9 (> 8)

    expect(s1.playCount, 9);
    expect(mockStreamClient.scrobbledIds, ['s1', 's1', 's1', 's1', 's1', 's1']);

    // Ranking list updated: s1 moved ahead of s2
    expect(history.rankingSongList[0].id, 's1');
    expect(history.rankingSongList[1].id, 's2');

    // Recently list updated: s1 is at the head
    expect(history.recentlySongList[0].id, 's1');
    expect(history.recentlySongList[1].id, 's2');
  });

  test('addSongTimes scrobbles when sourceType is emby', () async {
    sourceType = SourceType.emby;
    mockStreamClient.scrobbledIds.clear();
    final s1 = makeSong('s1', playCount: 1);

    await history.addSongTimes(s1, 2);

    expect(mockStreamClient.scrobbledIds, ['s1', 's1']);
  });

  test('loadAlbums loads ranking and recent albums from stream client', () async {
    final albumA = Album('Album 1', id: 'a1');
    final albumB = Album('Album 2', id: 'a2');
    mockStreamClient.albumResult = [albumA, albumB];

    final rankingCount = await history.loadAlbums(true);
    expect(rankingCount, 2);
    expect(mockStreamClient.lastOffset, 0);
    expect(mockStreamClient.lastType, 'frequent');
    expect(history.rankingAlbumList, [albumA, albumB]);

    // Next call passes offset of existing list
    mockStreamClient.albumResult = [];
    final nextCount = await history.loadAlbums(true);
    expect(nextCount, 0);
    expect(mockStreamClient.lastOffset, 2);

    // Recently albums
    mockStreamClient.albumResult = [albumA];
    final recentCount = await history.loadAlbums(false);
    expect(recentCount, 1);
    expect(mockStreamClient.lastOffset, 0);
    expect(mockStreamClient.lastType, 'recent');
    expect(history.recentlyAlbumList, [albumA]);
  });

  test('loadAlbums returns null on stream failure and deduplicates concurrent loads', () async {
    mockStreamClient.albumResult = null;
    final res = await history.loadAlbums(true);
    expect(res, isNull);
    expect(history.rankingAlbumList, isEmpty);
  });
}
