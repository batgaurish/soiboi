import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/mood_playlists.dart';
import 'package:soiboi/base/data/smart_playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

MyAudioMetadata _song({
  required String id,
  required double energy,
  required double danceable,
  required double relaxed,
}) {
  final meta = MyAudioMetadata(
    AudioMetadata(title: id, artist: 'Artist'),
    id: id,
    path: '/tmp/$id.m4a',
  );
  meta.energy = energy;
  meta.danceable = danceable;
  meta.relaxed = relaxed;
  return meta;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_mood_test');
  });

  test('returns empty when library has no analysed tracks', () {
    expect(autoMoodPlaylists(songs: []), isEmpty);
  });

  test('returns empty when fewer than 5 tracks match any mood', () {
    final songs = [
      _song(id: 'a', energy: 0.9, danceable: 0.6, relaxed: 0.1),
    ];
    expect(autoMoodPlaylists(songs: songs), isEmpty);
  });

  test('morning (6am) picks Morning Light + Deep Focus for gentle tracks', () {
    final morning = DateTime(2025, 1, 1, 6);
    final songs = [
      for (var i = 0; i < 5; i++)
        _song(
          id: 'low$i',
          energy: 0.35,
          danceable: 0.20,
          relaxed: 0.70,
        ),
    ];
    final moods = autoMoodPlaylists(now: morning, songs: songs);
    expect(moods.length, 2);
    expect(moods[0].name, 'Morning Light');
    expect(moods[0].icon, Icons.wb_sunny_rounded);
    expect(moods[0].trackCount, greaterThanOrEqualTo(5));
    expect(moods[1].name, 'Deep Focus');
    expect(moods[1].icon, Icons.self_improvement_rounded);
  });

  test('afternoon (14h) picks Upbeat Mix for energetic tracks', () {
    final afternoon = DateTime(2025, 1, 1, 14);
    final songs = [
      for (var i = 0; i < 7; i++)
        _song(
          id: 'e$i',
          energy: 0.80,
          danceable: 0.60,
          relaxed: 0.10,
        ),
    ];
    final moods = autoMoodPlaylists(now: afternoon, songs: songs);
    expect(moods.first.name, 'Upbeat Mix');
    expect(moods.first.icon, Icons.brightness_5_rounded);
  });

  test('evening (20h) picks Wind Down for relaxed tracks', () {
    final evening = DateTime(2025, 1, 1, 20);
    final songs = [
      for (var i = 0; i < 6; i++)
        _song(
          id: 'r$i',
          energy: 0.30,
          danceable: 0.20,
          relaxed: 0.70,
        ),
    ];
    final moods = autoMoodPlaylists(now: evening, songs: songs);
    expect(moods.first.name, 'Wind Down');
    expect(moods.first.icon, Icons.nights_stay_rounded);
  });

  test('late night (2h) picks Late Night Drive for quiet tracks', () {
    final late = DateTime(2025, 1, 1, 2);
    final songs = [
      for (var i = 0; i < 6; i++)
        _song(
          id: 'q$i',
          energy: 0.20,
          danceable: 0.10,
          relaxed: 0.70,
        ),
    ];
    final moods = autoMoodPlaylists(now: late, songs: songs);
    expect(moods.first.name, 'Late Night Drive');
    expect(moods.first.icon, Icons.brightness_2_rounded);
  });

  test('Deep Focus drops out when its rules match fewer than 5 tracks', () {
    final songs = [
      for (var i = 0; i < 7; i++)
        _song(
          id: 'highenergy$i',
          energy: 0.80,
          danceable: 0.60,
          relaxed: 0.10,
        ),
      _song(id: 'd1', energy: 0.35, danceable: 0.10, relaxed: 0.60),
      _song(id: 'd2', energy: 0.35, danceable: 0.10, relaxed: 0.60),
    ];
    final moods = autoMoodPlaylists(now: DateTime(2025, 1, 1, 14), songs: songs);
    expect(moods.length, 1);
    expect(moods.first.name, 'Upbeat Mix');
  });

  test('MoodCardData exposes SmartPlaylist that can be evaluated', () {
    final songs = [
      for (var i = 0; i < 6; i++)
        _song(
          id: 'm$i',
          energy: 0.25,
          danceable: 0.10,
          relaxed: 0.70,
        ),
    ];
    final morning = DateTime(2025, 1, 1, 7);
    final moods = autoMoodPlaylists(now: morning, songs: songs);
    expect(moods.first.trackCount, moods.first.playlist.evaluate(songs).length);
  });

  test('SmartSort.relaxed is wired for sorting', () {
    final pl = const SmartPlaylist(
      name: 'Relaxation Test',
      rules: [],
      sort: SmartSort.relaxed,
      descending: true,
    );
    final songs = [
      _song(id: 'low', energy: 0.30, danceable: 0.20, relaxed: 0.10),
      _song(id: 'high', energy: 0.80, danceable: 0.60, relaxed: 0.80),
    ];
    final result = pl.evaluate(songs);
    expect(result.first.title, 'high');
    expect(result.last.title, 'low');
  });
}
