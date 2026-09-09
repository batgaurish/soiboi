String formatDuration(Duration duration, {bool ms = true}) {
  String twoDigits(int n) => n.toString().padLeft(2, "0");
  if (ms) {
    final minutes = twoDigits(duration.inMinutes);
    final seconds = twoDigits(duration.inSeconds.remainder(60));
    return "$minutes:$seconds";
  }

  return "${twoDigits((duration.inMinutes / 60).toInt())}:${twoDigits(duration.inMinutes.remainder(60))}";
}

/// The leading digits-and-dots run of a version string, safe to split and
/// parse.
///
/// `compareVersion` used to split on '.' and `int.parse` each part directly,
/// which throws on any version carrying a suffix -- `1.0b` becomes
/// `['1', '0b']`, and `int.parse('0b')` throws. That is not a hypothetical:
/// versionNumber went from `4.2.3` to `1.0b` and this crashed on every launch
/// for anyone whose version.json still had a version in it, before the
/// window had painted anything -- the app looked like a black screen, not a
/// crash.
String _numericVersionPrefix(String version) {
  final match = RegExp(r'(\d+(?:\.\d+)*)').firstMatch(version);
  return match?.group(1) ?? '0';
}

int compareVersion(String a, String b) {
  final aParts = _numericVersionPrefix(a).split('.').map(int.parse).toList();
  final bParts = _numericVersionPrefix(b).split('.').map(int.parse).toList();

  final length = aParts.length > bParts.length ? aParts.length : bParts.length;

  for (int i = 0; i < length; i++) {
    final aVal = i < aParts.length ? aParts[i] : 0;
    final bVal = i < bParts.length ? bParts[i] : 0;

    if (aVal != bVal) {
      return aVal.compareTo(bVal);
    }
  }
  return 0;
}
