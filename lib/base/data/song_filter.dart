/// Filters for the Songs page: quality, codec, genre, favourites.
///
/// Quality and codec come from [QualityInfo], the same rules the quality
/// badge uses, so "Lossless" here always means a row with a lossless badge.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/widgets/quality_badge.dart';

@immutable
class SongFilter {
  const SongFilter({
    this.quality,
    this.codecs = const {},
    this.genres = const {},
    this.favoritesOnly = false,
  });

  /// Lossless or lossy only; null for either.
  final QualityTier? quality;

  /// Codec names as the badge shows them ("ALAC", "AAC", "MP3"); empty for
  /// any.
  final Set<String> codecs;

  /// Genres as tagged; empty for any.
  final Set<String> genres;

  final bool favoritesOnly;

  /// How many filters are on, for the toolbar's "Filter · 2".
  int get activeCount =>
      (quality == null ? 0 : 1) +
      (codecs.isEmpty ? 0 : 1) +
      (genres.isEmpty ? 0 : 1) +
      (favoritesOnly ? 1 : 0);

  bool get isEmpty => activeCount == 0;

  SongFilter copyWith({
    QualityTier? Function()? quality,
    Set<String>? codecs,
    Set<String>? genres,
    bool? favoritesOnly,
  }) => SongFilter(
    quality: quality == null ? this.quality : quality(),
    codecs: codecs ?? this.codecs,
    genres: genres ?? this.genres,
    favoritesOnly: favoritesOnly ?? this.favoritesOnly,
  );

  /// Pure matching, testable without an FFI-backed tag object.
  bool matchesValues({
    required QualityInfo info,
    required String? genre,
    required bool favorite,
  }) {
    if (quality != null && info.tier != quality) return false;
    if (codecs.isNotEmpty && !codecs.contains(codecOf(info))) return false;
    if (genres.isNotEmpty && !genres.contains(genreOf(genre))) return false;
    if (favoritesOnly && !favorite) return false;
    return true;
  }

  bool matches(MyAudioMetadata song) => matchesValues(
    info: QualityInfo.of(song),
    genre: song.genre,
    favorite: song.isFavoriteNotifier.value,
  );

  List<MyAudioMetadata> apply(List<MyAudioMetadata> songs) =>
      isEmpty ? songs : songs.where(matches).toList();
}

/// "AAC 256" → "AAC": the codec without its bitrate.
String codecOf(QualityInfo info) => info.label.split(' ').first;

/// A genre as the filter lists it; untagged songs share one entry.
String genreOf(String? genre) {
  final trimmed = genre?.trim() ?? '';
  return trimmed.isEmpty ? 'No genre' : trimmed;
}

/// The codecs and genres present in [songs], each with how many songs have
/// it, most common first: what the filter sheet offers.
({Map<String, int> codecs, Map<String, int> genres}) filterOptions(
  List<MyAudioMetadata> songs,
) {
  final codecs = <String, int>{};
  final genres = <String, int>{};
  for (final song in songs) {
    final codec = codecOf(QualityInfo.of(song));
    if (codec != '—') codecs.update(codec, (n) => n + 1, ifAbsent: () => 1);
    genres.update(genreOf(song.genre), (n) => n + 1, ifAbsent: () => 1);
  }
  Map<String, int> ranked(Map<String, int> counts) => Map.fromEntries(
    counts.entries.toList()..sort((a, b) => b.value.compareTo(a.value)),
  );
  return (codecs: ranked(codecs), genres: ranked(genres));
}

/// The Songs page's filter. Kept for the session, so it survives leaving and
/// coming back to the page.
final songFilterNotifier = ValueNotifier(const SongFilter());
