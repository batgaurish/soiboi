import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/ai_playlist_logic.dart';
import 'package:soiboi/base/services/song_vibe.dart';

SongVibe vibe(
  List<String> moods, {
  String valence = 'mid',
  String energy = 'mid',
  String language = 'english',
}) => SongVibe(
  moods: moods,
  valence: valence,
  energy: energy,
  language: language,
);

void main() {
  group('requestedCount', () {
    test('reads a plain count', () {
      final a = requestedCount('40 songs for the gym')!;
      expect(a.count, 40);
      expect(a.atLeast, isFalse);
    });

    test('reads "atleast 60 songs" as a floor', () {
      final a = requestedCount(
        'sweet happy sleep playlist calm but not sad. hindi and english '
        'both, atleast 60 songs',
      )!;
      expect(a.count, 60);
      expect(a.atLeast, isTrue);
    });

    test('reads "at least" and "60+"', () {
      expect(requestedCount('at least 25 tracks')!.atLeast, isTrue);
      expect(requestedCount('60+ songs please')!.atLeast, isTrue);
    });

    test('ignores numbers that are not a count', () {
      expect(requestedCount('songs from 1995'), isNull);
      expect(requestedCount('late night drive'), isNull);
    });

    test('caps absurd counts', () {
      expect(requestedCount('900 songs')!.count, 200);
    });
  });

  group('VibeBrief', () {
    test('keeps only known moods and levels', () {
      final b = VibeBrief.fromJson({
        'moods': ['Calm', 'bittersweet', 'warm'],
        'avoid': ['sad'],
        'valence': 'HIGH',
        'energy': 'medium',
        'languages': ['Hindi', 'english'],
      });
      expect(b.moods, ['calm', 'warm']);
      expect(b.avoid, ['sad']);
      expect(b.valence, 'high');
      expect(b.energy, 'any');
      expect(b.languages, ['hindi', 'english']);
    });

    test('survives a malformed answer', () {
      final b = VibeBrief.fromJson({'moods': 'calm', 'avoid': null});
      expect(b.isEmpty, isTrue);
    });
  });

  group('vibeScore', () {
    const brief = VibeBrief(
      moods: ['calm'],
      avoid: ['sad', 'melancholic'],
      valence: 'high',
      energy: 'low',
      languages: ['hindi', 'english'],
    );

    test('a calm, positive, quiet song fits', () {
      final s = vibeScore(
        vibe(['calm', 'warm'], valence: 'high', energy: 'low', language: 'hindi'),
        brief,
      );
      expect(s, greaterThan(3));
    });

    test('a calm but sad song is ruled out', () {
      expect(
        vibeScore(
          vibe(['calm', 'sad'], valence: 'low', energy: 'low'),
          brief,
        ),
        0,
      );
    });

    test('a language outside the request is ruled out, unknown is let in', () {
      expect(
        vibeScore(vibe(['calm'], language: 'korean'), brief),
        0,
      );
      expect(
        vibeScore(vibe(['calm'], language: 'unknown'), brief),
        greaterThan(0),
      );
    });

    test('a brief with only a language still admits matching songs', () {
      const onlyHindi = VibeBrief(languages: ['hindi']);
      expect(vibeScore(vibe(['upbeat'], language: 'hindi'), onlyHindi), 0.5);
      expect(vibeScore(vibe(['upbeat'], language: 'english'), onlyHindi), 0);
    });
  });

  group('candidates', () {
    final tracks = [
      for (var i = 0; i < 10; i++)
        Track(title: 'a$i', artist: 'Prolific', vibe: vibe(['party'])),
      Track(title: 'b', artist: 'Quiet One', vibe: vibe(['calm'], energy: 'low')),
      Track(title: 'c', artist: 'Quiet Two', vibe: vibe(['calm'], energy: 'low')),
      const Track(title: 'd', artist: 'Untagged'),
    ];

    test('the best fits come first and misfits are left out', () {
      final picked = pickCandidates(
        tracks,
        const VibeBrief(moods: ['calm'], energy: 'low'),
        cap: 5,
        random: Random(1),
      );
      expect(picked.take(2).toSet(), {10, 11});
      expect(picked.contains(0), isFalse);
    });

    test('untagged songs top up a short list', () {
      final picked = pickCandidates(
        tracks,
        const VibeBrief(moods: ['calm'], energy: 'low'),
        cap: 5,
        random: Random(1),
      );
      expect(picked, contains(12));
    });

    test('one prolific artist cannot crowd out the rest', () {
      final picked = diverseSample(tracks, 4, random: Random(2));
      final artists = picked.map((i) => tracks[i].artist).toSet();
      expect(artists.length, greaterThanOrEqualTo(3));
    });

    test('never returns more than the cap or a duplicate', () {
      final picked = diverseSample(tracks, 100, random: Random(3));
      expect(picked.length, tracks.length);
      expect(picked.toSet().length, picked.length);
    });
  });

  test('catalogue lines are compact and carry moods when known', () {
    expect(
      compactLine(7, Track(title: 'A|B', artist: 'X', vibe: vibe(['calm', 'warm']))),
      '7 | A/B | X | calm,warm',
    );
    expect(compactLine(8, const Track(title: 'T', artist: 'Y')), '8 | T | Y');
  });

  test('progress counts track numbers in a half-written answer', () {
    expect(picksSoFar('{"name": "Sleep"'), 0);
    expect(picksSoFar('{"name": "Sleep", "tracks": [12, 4, 90'), 3);
    expect(picksSoFar('{"name": "A [1]", "tracks": [3, 5]}'), 2);
  });

  group('SongVibe', () {
    test('drops moods outside the vocabulary and defaults levels', () {
      final v = SongVibe.fromJson({
        'm': ['calm', 'bittersweet', 'warm', 'happy', 'sad'],
        'v': 'huge',
        'e': 'low',
        'l': 'Hindi',
      })!;
      expect(v.moods, ['calm', 'warm', 'happy']);
      expect(v.valence, 'mid');
      expect(v.energy, 'low');
      expect(v.language, 'hindi');
    });

    test('a tag with no usable mood is rejected', () {
      expect(SongVibe.fromJson({'m': ['bittersweet']}), isNull);
      expect(SongVibe.fromJson({'m': 'calm'}), isNull);
      expect(SongVibe.fromJson('nope'), isNull);
    });

    test('round-trips through json', () {
      final v = vibe(['dreamy'], valence: 'high', energy: 'low', language: 'hindi');
      final back = SongVibe.fromJson(v.toJson())!;
      expect(back.moods, v.moods);
      expect(back.language, 'hindi');
    });
  });
}
