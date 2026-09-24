import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/data/duplicates.dart';

typedef Song = ({String artist, String title, String codec, int bitrate});

void main() {
  const aac = (
    artist: 'Rex Orange County',
    title: 'Corduroy Dreams',
    codec: 'AAC',
    bitrate: 286,
  );
  const alac = (
    artist: 'rex orange county',
    title: 'Corduroy Dreams!',
    codec: 'ALAC',
    bitrate: 900,
  );
  const other = (
    artist: 'Rex Orange County',
    title: 'A Song About Being Sad',
    codec: 'AAC',
    bitrate: 296,
  );

  List<List<Song>> find(List<Song> songs) => findDuplicates<Song>(
    songs,
    artist: (s) => s.artist,
    title: (s) => s.title,
  );

  Song keep(List<Song> group, String? prefer) => pickKeeper<Song>(
    group,
    codec: (s) => s.codec,
    bitrate: (s) => s.bitrate,
    prefer: prefer,
  );

  test('groups the same song across codecs and ignores unique songs', () {
    final groups = find([aac, alac, other]);
    expect(groups, hasLength(1));
    expect(groups.single, containsAll([aac, alac]));
  });

  test('keeps the chosen codec even at a lower bitrate', () {
    expect(keep([alac, aac], 'AAC'), aac);
    expect(keep([aac, alac], 'ALAC'), alac);
  });

  test('same codec keeps the higher bitrate', () {
    const low = (artist: 'A', title: 'T', codec: 'AAC', bitrate: 256);
    const high = (artist: 'A', title: 'T', codec: 'AAC', bitrate: 286);
    expect(keep([low, high], null), high);
  });

  test('non-Latin titles are not all one song', () {
    const a = (
      artist: 'Arpit Bala',
      title: 'तारों से',
      codec: 'AAC',
      bitrate: 256,
    );
    const b = (artist: 'Arpit Bala', title: 'मन', codec: 'AAC', bitrate: 256);
    expect(find([a, b]), isEmpty);
  });
}
