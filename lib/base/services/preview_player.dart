/// Thirty-second previews for tracks you don't own yet.
///
/// Deliberately a *separate* [Player] from the main audio handler. Previewing a
/// discovery track must not touch the play queue, the now-playing state, MPRIS,
/// or scrobbling — you're auditioning something before archiving it, not
/// listening to your library. Sharing the main player would clobber all of
/// that and put a track you don't own into your play history.
///
/// The main player is paused for the duration and resumed afterwards, since two
/// streams at once is never what anyone wants.
library;

import 'dart:async';

import 'package:material_ui/material_ui.dart';
import 'package:media_kit/media_kit.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/logger.dart';

/// Identity of the track currently previewing, or null. Drives the play/stop
/// icon on each row.
///
/// Keyed on the track rather than its URL: two entries can legitimately share a
/// preview URL when the same recording appears on more than one release, and
/// keying on the URL lights up every row that shares it.
final previewingKeyNotifier = ValueNotifier<String?>(null);

/// 0..1 through the preview, for a progress ring.
final previewProgressNotifier = ValueNotifier<double>(0);

Player? _player;
StreamSubscription<Duration>? _positionSub;
StreamSubscription<bool>? _completedSub;
bool _resumeMainAfter = false;

/// Apple's preview clips are 30s, but trust the stream's own duration when it
/// reports one rather than assuming.
const _assumedPreviewLength = Duration(seconds: 30);

Future<void> togglePreview(String key, String? url) async {
  if (url == null || url.isEmpty) return;
  if (previewingKeyNotifier.value == key) {
    await stopPreview();
    return;
  }
  await _start(key, url);
}

Future<void> _start(String key, String url) async {
  await _teardown();

  // Pause the library if it's playing, and remember to put it back.
  _resumeMainAfter = isPlayingNotifier.value;
  if (_resumeMainAfter) {
    await audioHandler.pause();
  }

  try {
    final player = Player();
    _player = player;
    previewingKeyNotifier.value = key;
    previewProgressNotifier.value = 0;

    _positionSub = player.stream.position.listen((position) {
      final total = player.state.duration.inMilliseconds > 0
          ? player.state.duration
          : _assumedPreviewLength;
      if (total.inMilliseconds <= 0) return;
      previewProgressNotifier.value =
          (position.inMilliseconds / total.inMilliseconds).clamp(0.0, 1.0);
    });

    _completedSub = player.stream.completed.listen((done) {
      if (done) stopPreview();
    });

    await player.open(Media(url));
  } catch (e) {
    logger.output('preview: $e');
    await stopPreview();
  }
}

Future<void> stopPreview() async {
  await _teardown();
  previewingKeyNotifier.value = null;
  previewProgressNotifier.value = 0;
  if (_resumeMainAfter) {
    _resumeMainAfter = false;
    await audioHandler.play();
  }
}

Future<void> _teardown() async {
  await _positionSub?.cancel();
  _positionSub = null;
  await _completedSub?.cancel();
  _completedSub = null;
  final player = _player;
  _player = null;
  if (player != null) {
    try {
      await player.dispose();
    } catch (e) {
      logger.output('preview dispose: $e');
    }
  }
}
