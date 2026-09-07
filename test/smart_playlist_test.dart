import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/smart_playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

final _now = DateTime(2026, 9, 7);

MyAudioMetadata song({
  required String id,
  String? title,
  String? artist,
  String? genre,
  int? year,
  int? bitrate,
  Duration? duration,
  int playCount = 0,
  DateTime? lastPlayed,
  DateTime? added,
  bool favourite = false,
}) {
  final meta = MyAudioMetadata(
    AudioMetadata(
      title: title ?? id,
      artist: artist,
      genre: genre,
      year: year,
      bitrate: bitrate,
      duration: duration,
    ),
    id: id,
    path: '/tmp/$id.m4a',
    playCount: playCount,
    lastPlayed: lastPlayed,
    modified: added,
  );
  meta.isFavoriteNotifier.value = favourite;
  return meta;
}

void main() {
  // Set before the groups, not in setUpAll: the fixtures below are built while
  // the tests are being declared, which happens first, and MyAudioMetadata's
  // constructor derives a cover-art cache path from this.
  appSupportDir = Directory.systemTemp.createTempSync('soiboi_test');

  group('text rules', () {
    final library = [
      song(id: 'a', artist: 'Daft Punk', genre: 'Electronic'),
      song(id: 'b', artist: 'Röyksopp', genre: 'Electronic'),
      song(id: 'c', artist: 'Miles Davis', genre: 'Jazz'),
    ];

    test('contains is case-insensitive', () {
      final playlist = SmartPlaylist(
        name: 'x',
        rules: const [
          SmartRule(
            field: SmartField.artist,
            operator: SmartOperator.contains,
            value: 'daft',
          ),
        ],
      );
      expect(playlist.evaluate(library).map((s) => s.id), ['a']);
    });

    test('all rules must match by default', () {
      final playlist = SmartPlaylist(
        name: 'x',
        rules: const [
          SmartRule(
            field: SmartField.genre,
            operator: SmartOperator.equals,
            value: 'Electronic',
          ),
          SmartRule(
            field: SmartField.artist,
            operator: SmartOperator.notContains,
            value: 'daft',
          ),
        ],
      );
      expect(playlist.evaluate(library).map((s) => s.id), ['b']);
    });

    test('any-of widens instead of narrowing', () {
      final playlist = SmartPlaylist(
        name: 'x',
        matchAll: false,
        sort: SmartSort.title,
        descending: false,
        rules: const [
          SmartRule(
            field: SmartField.genre,
            operator: SmartOperator.equals,
            value: 'Jazz',
          ),
          SmartRule(
            field: SmartField.artist,
            operator: SmartOperator.contains,
            value: 'daft',
          ),
        ],
      );
      expect(playlist.evaluate(library).map((s) => s.id), ['a', 'c']);
    });
  });

  group('number rules', () {
    test('bitrate is compared in kbps, as the badge shows it', () {
      final library = [
        song(id: 'lossy', bitrate: 256000),
        song(id: 'lossless', bitrate: 950000),
      ];
      final playlist = SmartPlaylist(
        name: 'x',
        rules: const [
          SmartRule(
            field: SmartField.bitrate,
            operator: SmartOperator.greaterThan,
            value: '500',
          ),
        ],
      );
      expect(playlist.evaluate(library).map((s) => s.id), ['lossless']);
    });

    test('a missing value does not count as zero', () {
      // The trap: a track with no year tag matching "released before 2000"
      // would quietly fill a decade playlist with untagged files.
      final library = [song(id: 'untagged'), song(id: 'old', year: 1975)];
      final playlist = SmartPlaylist(
        name: 'x',
        rules: const [
          SmartRule(
            field: SmartField.year,
            operator: SmartOperator.lessThan,
            value: '2000',
          ),
        ],
      );
      expect(playlist.evaluate(library).map((s) => s.id), ['old']);
    });

    test('an unparseable value matches nothing rather than throwing', () {
      final playlist = SmartPlaylist(
        name: 'x',
        rules: const [
          SmartRule(
            field: SmartField.playCount,
            operator: SmartOperator.greaterThan,
            value: 'not a number',
          ),
        ],
      );
      expect(playlist.evaluate([song(id: 'a', playCount: 9)]), isEmpty);
    });
  });

  group('date rules', () {
    final library = [
      song(id: 'recent', lastPlayed: _now.subtract(const Duration(days: 3))),
      song(id: 'stale', lastPlayed: _now.subtract(const Duration(days: 200))),
      song(id: 'never'),
    ];

    test('in the last N days', () {
      final playlist = SmartPlaylist(
        name: 'x',
        rules: const [
          SmartRule(
            field: SmartField.lastPlayed,
            operator: SmartOperator.inLastDays,
            value: '30',
          ),
        ],
      );
      expect(playlist.evaluate(library, now: _now).map((s) => s.id), ['recent']);
    });

    test('never played counts as not recent', () {
      // "Not played in 90 days" is how someone finds forgotten music, and a
      // track never played at all is the most forgotten of the lot.
      final playlist = SmartPlaylist(
        name: 'x',
        sort: SmartSort.title,
        descending: false,
        rules: const [
          SmartRule(
            field: SmartField.lastPlayed,
            operator: SmartOperator.notInLastDays,
            value: '90',
          ),
        ],
      );
      expect(playlist.evaluate(library, now: _now).map((s) => s.id), [
        'never',
        'stale',
      ]);
    });
  });

  group('sorting and limits', () {
    final library = [
      song(id: 'a', playCount: 5, title: 'A'),
      song(id: 'b', playCount: 50, title: 'B'),
      song(id: 'c', playCount: 20, title: 'C'),
    ];

    test('most played, capped', () {
      final playlist = SmartPlaylist(
        name: 'x',
        sort: SmartSort.playCount,
        limit: 2,
      );
      expect(playlist.evaluate(library).map((s) => s.id), ['b', 'c']);
    });

    test('never-played tracks sort last, not first', () {
      final withDates = [
        song(id: 'never'),
        song(id: 'old', lastPlayed: _now.subtract(const Duration(days: 100))),
        song(id: 'new', lastPlayed: _now.subtract(const Duration(days: 1))),
      ];
      final playlist = SmartPlaylist(name: 'x', sort: SmartSort.lastPlayed);
      expect(playlist.evaluate(withDates).map((s) => s.id), [
        'new',
        'old',
        'never',
      ]);
    });

    test('no rules means the whole library, not an empty list', () {
      // A playlist being built shows what it is narrowing.
      expect(SmartPlaylist(name: 'x').evaluate(library).length, 3);
    });
  });

  group('persistence', () {
    test('survives a round trip through JSON', () {
      final original = SmartPlaylist(
        name: 'Forgotten favourites',
        matchAll: false,
        sort: SmartSort.artist,
        descending: false,
        limit: 25,
        rules: const [
          SmartRule(
            field: SmartField.favourite,
            operator: SmartOperator.equals,
            value: 'yes',
          ),
          SmartRule(
            field: SmartField.lastPlayed,
            operator: SmartOperator.notInLastDays,
            value: '90',
          ),
        ],
      );
      final restored = SmartPlaylist.fromJson(original.toJson())!;

      expect(restored.name, original.name);
      expect(restored.matchAll, isFalse);
      expect(restored.sort, SmartSort.artist);
      expect(restored.descending, isFalse);
      expect(restored.limit, 25);
      expect(restored.rules.length, 2);
      expect(restored.rules.first.field, SmartField.favourite);
    });

    test('a rule naming a field that no longer exists is dropped', () {
      // Rather than failing the whole file and losing every other playlist.
      final json = {
        'name': 'x',
        'rules': [
          {'field': 'removedField', 'operator': 'contains', 'value': 'a'},
          {'field': 'artist', 'operator': 'contains', 'value': 'b'},
        ],
      };
      final restored = SmartPlaylist.fromJson(json)!;
      expect(restored.rules.length, 1);
      expect(restored.rules.single.field, SmartField.artist);
    });
  });

  test('operators offered suit the field', () {
    // Offering "contains" on a play count only ever produces confusion.
    expect(
      SmartOperator.forKind(SmartFieldKind.number),
      isNot(contains(SmartOperator.contains)),
    );
    expect(
      SmartOperator.forKind(SmartFieldKind.date),
      everyElement(
        anyOf(SmartOperator.inLastDays, SmartOperator.notInLastDays),
      ),
    );
  });
}
