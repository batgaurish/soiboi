import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/song_filter.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';
import 'package:soiboi/base/widgets/quality_badge.dart';

final alac = QualityInfo.classify(format: 'MP4', bitrate: 900);
final aac = QualityInfo.classify(format: 'MP4', bitrate: 256);
final mp3 = QualityInfo.classify(format: 'mp3', bitrate: 320);

bool keeps(
  SongFilter filter,
  QualityInfo info, {
  String? genre,
  bool favorite = false,
}) => filter.matchesValues(info: info, genre: genre, favorite: favorite);

MyAudioMetadata song(String id, {int? year, int plays = 0}) => MyAudioMetadata(
  AudioMetadata(title: id, year: year),
  id: id,
  path: '/tmp/$id.m4a',
  playCount: plays,
);

void main() {
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_filter');
  });

  test('an empty filter keeps everything and counts nothing', () {
    const filter = SongFilter();
    expect(filter.isEmpty, isTrue);
    expect(keeps(filter, alac), isTrue);
    expect(keeps(filter, mp3), isTrue);
  });

  test('quality follows the badge: a high-bitrate MP4 is lossless', () {
    const lossless = SongFilter(quality: QualityTier.lossless);
    expect(keeps(lossless, alac), isTrue);
    expect(keeps(lossless, aac), isFalse);
    expect(keeps(const SongFilter(quality: QualityTier.lossy), mp3), isTrue);
  });

  test('codecs match without the bitrate', () {
    expect(codecOf(aac), 'AAC');
    const filter = SongFilter(codecs: {'AAC', 'MP3'});
    expect(keeps(filter, aac), isTrue);
    expect(keeps(filter, mp3), isTrue);
    expect(keeps(filter, alac), isFalse);
  });

  test('genres match as tagged, untagged songs share one entry', () {
    const filter = SongFilter(genres: {'Indie', 'No genre'});
    expect(keeps(filter, aac, genre: ' Indie '), isTrue);
    expect(keeps(filter, aac, genre: null), isTrue);
    expect(keeps(filter, aac, genre: 'Pop'), isFalse);
  });

  test('filters combine, and each one counts once', () {
    const filter = SongFilter(
      quality: QualityTier.lossless,
      genres: {'Indie'},
      favoritesOnly: true,
    );
    expect(filter.activeCount, 3);
    expect(keeps(filter, alac, genre: 'Indie', favorite: true), isTrue);
    expect(keeps(filter, alac, genre: 'Indie'), isFalse);
    expect(keeps(filter, aac, genre: 'Indie', favorite: true), isFalse);
  });

  test('clearing the quality goes back to any', () {
    const filter = SongFilter(quality: QualityTier.lossy);
    expect(filter.copyWith(quality: () => null).quality, isNull);
    expect(filter.copyWith(favoritesOnly: true).quality, QualityTier.lossy);
  });

  test('year and most-played orders', () {
    final songs = [
      song('a', year: 2019, plays: 3),
      song('b', plays: 10),
      song('c', year: 2024, plays: 1),
    ];
    sortSongList(13, songs);
    expect(songs.map((s) => s.id), ['c', 'a', 'b']);
    sortSongList(14, songs);
    expect(songs.map((s) => s.id), ['a', 'c', 'b']);
    sortSongList(15, songs);
    expect(songs.map((s) => s.id), ['b', 'a', 'c']);
  });
}
