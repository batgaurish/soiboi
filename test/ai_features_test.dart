import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/ai_features.dart';
import 'package:soiboi/base/services/ai_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUpAll(() {
    tempDir = Directory.systemTemp.createTempSync('soiboi_ai_test_');
    appSupportDir = tempDir;
  });

  setUp(() {
    aiConfigNotifier.value = null;
    library.songList.clear();
    aiRecsNotifier.value = const [];
    aiRecsBusyNotifier.value = false;
    aiRecsErrorNotifier.value = null;
  });

  tearDownAll(() {
    if (tempDir.existsSync()) {
      tempDir.deleteSync(recursive: true);
    }
  });

  group('AiAlbumPick', () {
    test('serializes to and from json', () {
      final pick = AiAlbumPick(
        artist: 'Tycho',
        album: 'Dive',
        why: 'Ambient classic',
      );

      final json = pick.toJson();
      expect(json['artist'], 'Tycho');
      expect(json['album'], 'Dive');
      expect(json['why'], 'Ambient classic');

      final reconstructed = AiAlbumPick.fromJson(json);
      expect(reconstructed.artist, 'Tycho');
      expect(reconstructed.album, 'Dive');
      expect(reconstructed.why, 'Ambient classic');
    });
  });

  group('AiPlaylist', () {
    test('instantiates with name and songs list', () {
      final pl = AiPlaylist('My Mix', <MyAudioMetadata>[]);
      expect(pl.name, 'My Mix');
      expect(pl.songs, isEmpty);
    });
  });

  group('aiMakePlaylist and aiRecommendAlbums validation', () {
    test('aiMakePlaylist throws AiException when AI is not configured', () async {
      aiConfigNotifier.value = null;
      expect(
        () => aiMakePlaylist('chill music'),
        throwsA(isA<AiException>().having((e) => e.message, 'message', contains('not set up'))),
      );
    });

    test('aiMakePlaylist throws AiException when library is empty', () async {
      aiConfigNotifier.value = const AiConfig(
        provider: AiProvider.gemini,
        key: 'test-key',
      );
      library.songList.clear();

      expect(
        () => aiMakePlaylist('chill music'),
        throwsA(isA<AiException>().having((e) => e.message, 'message', contains('library is empty'))),
      );
    });

    test('aiRecommendAlbums throws AiException when AI is not configured', () async {
      aiConfigNotifier.value = null;
      expect(
        () => aiRecommendAlbums(''),
        throwsA(isA<AiException>().having((e) => e.message, 'message', contains('not set up'))),
      );
    });
  });

  group('loadAiRecommendations', () {
    test('reads existing recommendations from file when valid', () async {
      final file = File('${tempDir.path}/ai_recommendations.json');
      await file.writeAsString(
        jsonEncode({
          'at': DateTime.now().toIso8601String(),
          'picks': [
            {'artist': 'Boards of Canada', 'album': 'Music Has the Right', 'why': 'Masterpiece'},
          ],
        }),
      );

      aiConfigNotifier.value = const AiConfig(
        provider: AiProvider.gemini,
        key: 'test-key',
      );

      await loadAiRecommendations();

      expect(aiRecsNotifier.value.length, 1);
      expect(aiRecsNotifier.value.first.artist, 'Boards of Canada');
      expect(aiRecsNotifier.value.first.album, 'Music Has the Right');
    });
  });
}
