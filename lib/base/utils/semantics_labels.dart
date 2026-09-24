/// Words for screen readers, shared so that a song, a duration or a
/// percentage is read the same way wherever it appears.
library;

import 'dart:io';

/// "3:21", or "1:02:03" past an hour. Unlike the on-screen clock format
/// there is no leading zero, which screen readers otherwise read aloud.
String durationLabel(Duration duration) {
  final hours = duration.inHours;
  final minutes = duration.inMinutes.remainder(60);
  final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
  return hours > 0
      ? '$hours:${minutes.toString().padLeft(2, '0')}:$seconds'
      : '$minutes:$seconds';
}

/// A song as one item: "One More Time, Daft Punk, 5:20". Missing parts are
/// left out rather than read as "unknown".
String songLabel({
  required String title,
  String? artist,
  Duration? duration,
  String? extra,
}) {
  return [
    title,
    if (artist != null && artist.trim().isNotEmpty) artist,
    if (duration != null && duration > Duration.zero) durationLabel(duration),
    if (extra != null && extra.trim().isNotEmpty) extra,
  ].join(', ');
}

/// "45%", for progress and volume.
String percentLabel(double fraction) => '${(fraction * 100).round()}%';

/// The name of a slider or progress bar, with its value on Linux.
///
/// Flutter's Linux bridge passes Orca a node's label and nothing else: a
/// value never arrives. There the value goes into the name; elsewhere it
/// stays in `Semantics.value`, where TalkBack reads it.
String nameWithValue(String name, String value) =>
    Platform.isLinux ? '$name, $value' : name;
