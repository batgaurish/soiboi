import 'dart:math' as math;

import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

/// The result of moving a song within/into the play queue: where the
/// currently-playing item ends up afterward, and whether [song] wasn't
/// already queued (which callers use to decide whether it also needs to be
/// remembered in the pre-shuffle queue).
class QueueInsertResult {
  final int currentIndex;
  final bool wasNewlyInserted;

  const QueueInsertResult(this.currentIndex, this.wasNewlyInserted);
}

/// Index bookkeeping for play-queue reordering, split out of
/// [MyAudioHandler] so it can be unit tested without a live Player/platform
/// channel - it only touches the queue list and the current index, no I/O.
class PlayQueueLogic {
  /// Moves [song] to play right after [currentIndex] (or inserts it there
  /// if it isn't queued yet). Mutates [playQueue] in place. Returns null if
  /// [song] is already the currently-playing item - nothing to do.
  static QueueInsertResult? insert2Next(
    List<MyAudioMetadata> playQueue,
    int currentIndex,
    MyAudioMetadata song,
  ) {
    final songIndex = playQueue.indexOf(song);
    if (songIndex != -1) {
      if (songIndex == currentIndex) {
        return null;
      }
      playQueue.removeAt(songIndex);
      if (songIndex < currentIndex) {
        playQueue.insert(currentIndex, song);
        return QueueInsertResult(currentIndex - 1, false);
      } else {
        playQueue.insert(currentIndex + 1, song);
        return QueueInsertResult(currentIndex, false);
      }
    } else {
      if (playQueue.isEmpty) {
        playQueue.add(song);
      } else {
        playQueue.insert(currentIndex + 1, song);
      }
      return QueueInsertResult(currentIndex, true);
    }
  }

  /// Moves [song] to the end of the queue (or appends it if it isn't
  /// queued yet). Mutates [playQueue] in place. Returns null if [song] is
  /// already the currently-playing item - nothing to do.
  static QueueInsertResult? add2Last(
    List<MyAudioMetadata> playQueue,
    int currentIndex,
    MyAudioMetadata song,
  ) {
    final songIndex = playQueue.indexOf(song);
    if (songIndex != -1) {
      if (songIndex == currentIndex) {
        return null;
      }
      final newIndex = songIndex < currentIndex
          ? currentIndex - 1
          : currentIndex;
      playQueue.removeAt(songIndex);
      playQueue.add(song);
      return QueueInsertResult(newIndex, false);
    } else {
      playQueue.add(song);
      return QueueInsertResult(currentIndex, true);
    }
  }

  /// Whether the queue is in shuffle order, so songs added to it also belong
  /// in the pre-shuffle queue. Shuffle is play mode 1; repeat (2) keeps the
  /// order of the mode it replaced.
  static bool isShuffled(int playMode, int playModeBeforeRepeat) =>
      playMode == 1 || (playMode == 2 && playModeBeforeRepeat == 1);

  /// [queue] shuffled with the song at [currentIndex] kept first, so the
  /// track playing now carries on and everything after it is random. The
  /// current song is then at index 0.
  static List<MyAudioMetadata> shuffledAround(
    List<MyAudioMetadata> queue,
    int currentIndex, [
    math.Random? random,
  ]) {
    final others = List.of(queue)..removeAt(currentIndex);
    others.shuffle(random);
    return [queue[currentIndex], ...others];
  }

  /// The index to start a new queue of [length] songs at: the top in order
  /// (play mode 0), anywhere when shuffling.
  static int startIndex(int playMode, int length, [math.Random? random]) =>
      playMode == 0 ? 0 : (random ?? math.Random()).nextInt(length);

  /// The index after [currentIndex], wrapping to the start.
  static int nextIndex(int currentIndex, int length) =>
      (currentIndex + 1) % length;

  /// The index before [currentIndex], wrapping to the end.
  static int previousIndex(int currentIndex, int length) =>
      (currentIndex + length - 1) % length;

  /// [queue] with each song swapped for its current copy in [byId], dropping
  /// songs the library no longer has. Used after a rescan, when the queue
  /// still holds the old objects.
  static List<MyAudioMetadata> remap(
    List<MyAudioMetadata> queue,
    Map<String, MyAudioMetadata> byId,
  ) => [for (final song in queue) ?byId[song.id]];
}
