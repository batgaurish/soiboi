import 'dart:io';
import 'dart:math' as math;

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/play_queue_logic.dart';

MyAudioMetadata song(String id) {
  // A path is required, not optional: the constructor dereferences it to
  // derive the cover-art cache key for any non-streaming source. It never has
  // to exist on disk for these tests.
  return MyAudioMetadata(
    AudioMetadata(title: id),
    id: id,
    path: '/tmp/$id.m4a',
  );
}

void main() {
  // MyAudioMetadata's constructor reads from appSupportDir to check for a
  // cached cover art file; it only needs to exist, not actually contain
  // anything relevant to these tests.
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_test');
  });

  group('PlayQueueLogic.insert2Next', () {
    test('does nothing when the song is already playing', () {
      final queue = [song('a'), song('b')];
      final result = PlayQueueLogic.insert2Next(queue, 0, queue[0]);

      expect(result, isNull);
      expect(queue.map((e) => e.id), ['a', 'b']);
    });

    test('inserts a brand-new song right after currentIndex', () {
      final queue = [song('a'), song('b')];
      final newSong = song('c');
      final result = PlayQueueLogic.insert2Next(queue, 0, newSong);

      expect(result!.currentIndex, 0);
      expect(result.wasNewlyInserted, isTrue);
      expect(queue.map((e) => e.id), ['a', 'c', 'b']);
    });

    test(
      'moves a song from later in the queue to right after currentIndex',
      () {
        final queue = [song('a'), song('b'), song('c')];
        // 'c' is already queued after the current song ('a').
        final result = PlayQueueLogic.insert2Next(queue, 0, queue[2]);

        expect(result!.currentIndex, 0);
        expect(result.wasNewlyInserted, isFalse);
        expect(queue.map((e) => e.id), ['a', 'c', 'b']);
      },
    );

    test(
      'moving a song from earlier in the queue shifts currentIndex back by one',
      () {
        final queue = [song('a'), song('b'), song('c'), song('d')];
        // 'a' is queued before the current song ('c' at index 2).
        final result = PlayQueueLogic.insert2Next(queue, 2, queue[0]);

        expect(result!.currentIndex, 1);
        expect(result.wasNewlyInserted, isFalse);
        expect(queue.map((e) => e.id), ['b', 'c', 'a', 'd']);
      },
    );
  });

  group('PlayQueueLogic.add2Last', () {
    test('does nothing when the song is already playing', () {
      final queue = [song('a'), song('b')];
      final result = PlayQueueLogic.add2Last(queue, 0, queue[0]);

      expect(result, isNull);
      expect(queue.map((e) => e.id), ['a', 'b']);
    });

    test('appends a brand-new song to the end', () {
      final queue = [song('a'), song('b')];
      final newSong = song('c');
      final result = PlayQueueLogic.add2Last(queue, 0, newSong);

      expect(result!.currentIndex, 0);
      expect(result.wasNewlyInserted, isTrue);
      expect(queue.map((e) => e.id), ['a', 'b', 'c']);
    });

    test(
      'moving a song from before currentIndex to the end shifts currentIndex back',
      () {
        final queue = [song('a'), song('b'), song('c')];
        // 'a' is queued before the current song ('c' at index 2).
        final result = PlayQueueLogic.add2Last(queue, 2, queue[0]);

        expect(result!.currentIndex, 1);
        expect(result.wasNewlyInserted, isFalse);
        expect(queue.map((e) => e.id), ['b', 'c', 'a']);
      },
    );

    test(
      'moving a song from after currentIndex to the end leaves currentIndex unchanged',
      () {
        final queue = [song('a'), song('b'), song('c')];
        // 'c' is queued after the current song ('a' at index 0).
        final result = PlayQueueLogic.add2Last(queue, 0, queue[2]);

        expect(result!.currentIndex, 0);
        expect(result.wasNewlyInserted, isFalse);
        expect(queue.map((e) => e.id), ['a', 'b', 'c']);
      },
    );
  });

  group('PlayQueueLogic.isShuffled', () {
    test('shuffle mode is shuffled', () {
      expect(PlayQueueLogic.isShuffled(1, 0), isTrue);
    });

    test('in-order mode is not', () {
      expect(PlayQueueLogic.isShuffled(0, 1), isFalse);
    });

    test('repeat keeps the order of the mode it replaced', () {
      expect(PlayQueueLogic.isShuffled(2, 1), isTrue);
      expect(PlayQueueLogic.isShuffled(2, 0), isFalse);
    });
  });

  group('PlayQueueLogic.shuffledAround', () {
    test('keeps the current song first and every song once', () {
      final queue = [for (final id in 'abcdefgh'.split('')) song(id)];
      for (var seed = 0; seed < 20; seed++) {
        final shuffled = PlayQueueLogic.shuffledAround(
          queue,
          3,
          math.Random(seed),
        );
        expect(shuffled.first.id, 'd');
        expect(
          shuffled.map((e) => e.id).toSet(),
          queue.map((e) => e.id).toSet(),
        );
        expect(shuffled, hasLength(queue.length));
      }
    });

    test('leaves the original queue untouched', () {
      final queue = [song('a'), song('b'), song('c')];
      PlayQueueLogic.shuffledAround(queue, 1, math.Random(1));
      expect(queue.map((e) => e.id), ['a', 'b', 'c']);
    });

    test('actually reorders the rest', () {
      final queue = [for (var i = 0; i < 30; i++) song('$i')];
      final orders = {
        for (var seed = 0; seed < 5; seed++)
          PlayQueueLogic.shuffledAround(
            queue,
            0,
            math.Random(seed),
          ).map((e) => e.id).join(','),
      };
      expect(orders.length, greaterThan(1));
    });
  });

  group('PlayQueueLogic.startIndex', () {
    test('in order starts at the top', () {
      expect(PlayQueueLogic.startIndex(0, 10, math.Random(7)), 0);
    });

    test('shuffled starts anywhere in range', () {
      for (var seed = 0; seed < 50; seed++) {
        final index = PlayQueueLogic.startIndex(1, 10, math.Random(seed));
        expect(index, inInclusiveRange(0, 9));
      }
    });
  });

  group('PlayQueueLogic.nextIndex and previousIndex', () {
    test('step through the queue', () {
      expect(PlayQueueLogic.nextIndex(2, 5), 3);
      expect(PlayQueueLogic.previousIndex(2, 5), 1);
    });

    test('wrap at both ends', () {
      expect(PlayQueueLogic.nextIndex(4, 5), 0);
      expect(PlayQueueLogic.previousIndex(0, 5), 4);
    });

    test('a one-song queue stays put', () {
      expect(PlayQueueLogic.nextIndex(0, 1), 0);
      expect(PlayQueueLogic.previousIndex(0, 1), 0);
    });
  });

  group('PlayQueueLogic.remap', () {
    test('swaps in the library\'s current copy of each song', () {
      final fresh = {'a': song('a'), 'b': song('b')};
      final remapped = PlayQueueLogic.remap([song('a'), song('b')], fresh);
      expect(remapped[0], same(fresh['a']));
      expect(remapped[1], same(fresh['b']));
    });

    test('drops songs the library no longer has, keeping order', () {
      final fresh = {'a': song('a'), 'c': song('c')};
      final remapped = PlayQueueLogic.remap([
        song('c'),
        song('b'),
        song('a'),
      ], fresh);
      expect(remapped.map((e) => e.id), ['c', 'a']);
    });
  });
}
