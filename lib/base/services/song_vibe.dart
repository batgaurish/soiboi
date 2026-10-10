/// The shape of a song's feel: moods, how positive, how energetic, and the
/// language it is sung in. Plain data with no app dependencies, so playlist
/// logic and its tests can use it without loading the app.
library;

/// Every mood a track can carry. Kept short on purpose: a small vocabulary is
/// something a model applies consistently.
const vibeMoods = [
  'calm',
  'warm',
  'happy',
  'uplifting',
  'upbeat',
  'party',
  'romantic',
  'dreamy',
  'nostalgic',
  'melancholic',
  'sad',
  'dark',
  'intense',
  'focus',
];

const vibeLevels = ['low', 'mid', 'high'];

class SongVibe {
  const SongVibe({
    required this.moods,
    required this.valence,
    required this.energy,
    required this.language,
  });

  /// Up to three of [vibeMoods].
  final List<String> moods;

  /// How positive the track feels, from [vibeLevels]. This is the axis the
  /// acoustic analyser cannot measure.
  final String valence;

  /// How energetic it feels, from [vibeLevels].
  final String energy;

  /// Lower-case language of the vocals, or "instrumental" or "unknown".
  final String language;

  Map<String, Object> toJson() => {
    'm': moods,
    'v': valence,
    'e': energy,
    'l': language,
  };

  /// A tag read from the model's answer or from disk, or null when it is too
  /// broken to use. Values outside the vocabulary are dropped, not kept: a
  /// stray "bittersweet" would match no rule and only clutter the file.
  static SongVibe? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final rawMoods = raw['m'] ?? raw['moods'];
    if (rawMoods is! List) return null;
    final moods = <String>[
      for (final m in rawMoods)
        if (vibeMoods.contains('$m'.toLowerCase().trim()))
          '$m'.toLowerCase().trim(),
    ].take(3).toList();
    String level(Object? v) {
      final s = '${v ?? ''}'.toLowerCase().trim();
      return vibeLevels.contains(s) ? s : 'mid';
    }

    if (moods.isEmpty) return null;
    final language = '${raw['l'] ?? raw['language'] ?? 'unknown'}'
        .toLowerCase()
        .trim();
    return SongVibe(
      moods: moods,
      valence: level(raw['v'] ?? raw['valence']),
      energy: level(raw['e'] ?? raw['energy']),
      language: language.isEmpty ? 'unknown' : language,
    );
  }
}
