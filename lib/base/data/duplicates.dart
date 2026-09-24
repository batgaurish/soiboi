/// Finding the same song twice in the library, and choosing the copy to keep.
///
/// "Same song" is the key the download pipeline skips on: artist + title,
/// ignoring case and punctuation, and ignoring codec and file name. An AAC
/// and an ALAC copy of one track are duplicates; so are two AAC files an
/// older download left under different names.
library;

import 'package:soiboi/base/services/library_match_service.dart';

/// Groups of two or more [items] that share an artist + title key.
List<List<T>> findDuplicates<T>(
  Iterable<T> items, {
  required String? Function(T) artist,
  required String? Function(T) title,
}) {
  final byKey = <String, List<T>>{};
  for (final item in items) {
    final name = title(item);
    if (name == null || name.trim().isEmpty) continue;
    byKey
        .putIfAbsent(ownedSongKey(artist(item) ?? '', name), () => [])
        .add(item);
  }
  return [
    for (final group in byKey.values)
      if (group.length > 1) group,
  ];
}

/// The copy of [group] to keep: one in [prefer] codec when there is one,
/// then the highest bitrate. Ties keep the earlier entry.
T pickKeeper<T>(
  List<T> group, {
  required String Function(T) codec,
  required int Function(T) bitrate,
  String? prefer,
}) {
  final preferred = prefer == null
      ? group
      : group.where((item) => codec(item) == prefer).toList();
  final pool = preferred.isEmpty ? group : preferred;
  var best = pool.first;
  for (final item in pool.skip(1)) {
    if (bitrate(item) > bitrate(best)) best = item;
  }
  return best;
}
