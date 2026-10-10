/// The parts of AI playlist making that need no network and no app state:
/// reading how many songs were asked for, choosing which songs are worth
/// sending, and writing them compactly.
///
/// The model is good at what a request means and slow at reading a whole
/// library. So the model reads the request, this file narrows the library to
/// the songs that could fit, and the model only ever sees those.
library;

import 'dart:math';

import 'package:soiboi/base/services/song_vibe.dart';

/// A song as playlist logic sees it: just what is worth sending.
class Track {
  const Track({
    required this.title,
    required this.artist,
    this.vibe,
    this.album = '',
  });

  final String title;
  final String artist;
  final String album;
  final SongVibe? vibe;
}

/// How many songs a request asks for, and whether that is a floor.
class CountAsk {
  const CountAsk(this.count, {required this.atLeast});
  final int count;

  /// "At least 60" keeps every pick; "40 songs" is trimmed to 40.
  final bool atLeast;
}

final _countPattern = RegExp(
  r'(?:(at\s?least|minimum(?:\s+of)?|min|over|more\s+than)\s+)?'
  r'(\d{1,3})\s*(?:\+\s*)?(?:songs?|tracks?|tunes?)\b',
  caseSensitive: false,
);

/// The song count in [request], or null when it names none.
CountAsk? requestedCount(String request) {
  final m = _countPattern.firstMatch(request);
  if (m == null) return null;
  final n = int.parse(m.group(2)!);
  if (n < 1) return null;
  return CountAsk(
    min(n, 200),
    atLeast: m.group(1) != null || m.group(0)!.contains('+'),
  );
}

/// What a request asks for in terms the saved tags can answer. The model
/// fills this in from the request alone, without seeing the library.
class VibeBrief {
  const VibeBrief({
    this.moods = const [],
    this.avoid = const [],
    this.valence = 'any',
    this.energy = 'any',
    this.languages = const [],
  });

  final List<String> moods;

  /// Moods that rule a song out ("not sad").
  final List<String> avoid;
  final String valence;
  final String energy;

  /// Languages wanted, lower case. Empty means any.
  final List<String> languages;

  bool get isEmpty =>
      moods.isEmpty &&
      avoid.isEmpty &&
      valence == 'any' &&
      energy == 'any' &&
      languages.isEmpty;

  static VibeBrief fromJson(Map<String, dynamic> json) {
    List<String> words(Object? v, {Iterable<String>? only}) => [
      if (v is List)
        for (final w in v)
          if (only == null || only.contains('$w'.toLowerCase().trim()))
            '$w'.toLowerCase().trim(),
    ];
    String level(Object? v) {
      final s = '${v ?? 'any'}'.toLowerCase().trim();
      return vibeLevels.contains(s) ? s : 'any';
    }

    return VibeBrief(
      moods: words(json['moods'], only: vibeMoods),
      avoid: words(json['avoid'], only: vibeMoods),
      valence: level(json['valence']),
      energy: level(json['energy']),
      languages: words(json['languages']),
    );
  }
}

/// How well [vibe] fits [brief]; zero or less means it does not. Language is a
/// hard filter, because a Hindi song in an English-only playlist is wrong
/// however well it fits the mood. An unknown language is let through.
double vibeScore(SongVibe vibe, VibeBrief brief) {
  if (brief.languages.isNotEmpty &&
      vibe.language != 'unknown' &&
      !brief.languages.contains(vibe.language)) {
    return 0;
  }
  if (brief.avoid.any(vibe.moods.contains)) return 0;

  var score = 0.0;
  for (final mood in brief.moods) {
    if (vibe.moods.contains(mood)) score += 2;
  }
  if (brief.valence != 'any') {
    score += _levelFit(vibe.valence, brief.valence);
  }
  if (brief.energy != 'any') {
    score += _levelFit(vibe.energy, brief.energy);
  }
  // A brief with nothing but languages still has to let matching songs in.
  if (brief.moods.isEmpty &&
      brief.valence == 'any' &&
      brief.energy == 'any' &&
      score == 0) {
    return 0.5;
  }
  // A neighbouring level alone is not a fit: "calm" must not admit a party
  // song because both sit at mid energy.
  return score >= 1 ? score : 0;
}

/// 1 for an exact level, 0.4 for a neighbouring one, a penalty for opposite.
/// Adds to a mood match; on its own it does not reach [vibeScore]'s bar.
double _levelFit(String have, String want) {
  final gap = (vibeLevels.indexOf(have) - vibeLevels.indexOf(want)).abs();
  return switch (gap) {
    0 => 1.0,
    1 => 0.4,
    _ => -1.0,
  };
}

/// A pick of at most [cap] indexes into [tracks] that keeps variety: songs are
/// taken from each artist in turn, so one prolific artist cannot crowd out the
/// rest. [only] limits the choice to those indexes when given.
List<int> diverseSample(
  List<Track> tracks,
  int cap, {
  Iterable<int>? only,
  Random? random,
}) {
  final rng = random ?? Random();
  final byArtist = <String, List<int>>{};
  for (final i in only ?? List.generate(tracks.length, (i) => i)) {
    byArtist.putIfAbsent(tracks[i].artist.toLowerCase(), () => []).add(i);
  }
  final queues = byArtist.values.toList()..shuffle(rng);
  for (final q in queues) {
    q.shuffle(rng);
  }
  final out = <int>[];
  var round = 0;
  while (out.length < cap) {
    var any = false;
    for (final q in queues) {
      if (round < q.length) {
        out.add(q[round]);
        any = true;
        if (out.length >= cap) break;
      }
    }
    if (!any) break;
    round++;
  }
  return out;
}

/// The songs worth sending for [brief], as indexes into [tracks], best first.
///
/// Tagged songs that fit the brief come first. When too few fit, the rest of
/// the pool is topped up with a diverse sample of untagged songs, so a
/// half-tagged library still gets a full list and nothing is silently lost.
List<int> pickCandidates(
  List<Track> tracks,
  VibeBrief brief, {
  required int cap,
  Random? random,
}) {
  final scored = <(int, double)>[];
  final untagged = <int>[];
  for (var i = 0; i < tracks.length; i++) {
    final vibe = tracks[i].vibe;
    if (vibe == null) {
      untagged.add(i);
      continue;
    }
    final s = vibeScore(vibe, brief);
    if (s > 0) scored.add((i, s));
  }
  scored.sort((a, b) => b.$2.compareTo(a.$2));

  // Equal scores are common; without this the same artists always lead.
  final best = <int>[];
  var tier = 0;
  while (tier < scored.length) {
    var end = tier;
    while (end < scored.length && scored[end].$2 == scored[tier].$2) {
      end++;
    }
    final group = [for (var k = tier; k < end; k++) scored[k].$1];
    best.addAll(
      diverseSample(
        tracks,
        group.length,
        only: group,
        random: random,
      ),
    );
    tier = end;
  }

  final picked = best.take(cap).toList();
  if (picked.length < cap) {
    picked.addAll(
      diverseSample(
        tracks,
        cap - picked.length,
        only: untagged,
        random: random,
      ),
    );
  }
  return picked;
}

/// One catalogue line: number, title, artist and, when known, the moods.
/// Album, genre and year are left out. They cost tokens and the moods say
/// more about how a song feels than they do.
String compactLine(int number, Track t) {
  String clean(String v) => v.replaceAll('|', '/').trim();
  final moods = t.vibe == null ? '' : ' | ${t.vibe!.moods.join(',')}';
  return '$number | ${clean(t.title)} | ${clean(t.artist)}$moods';
}

/// Prompt that turns a request into a [VibeBrief], with no library in it.
const briefSystemPrompt =
    'You read a request for a playlist and describe what it needs. Reply '
    'with JSON only, no prose, in exactly this shape: {"moods": [...], '
    '"avoid": [...], "valence": "any", "energy": "any", "languages": [...]}. '
    '"moods" are the feelings wanted and "avoid" the feelings ruled out, each '
    'chosen only from: calm, warm, happy, uplifting, upbeat, party, romantic, '
    'dreamy, nostalgic, melancholic, sad, dark, intense, focus. "valence" is '
    'how positive the songs should feel and "energy" how energetic, each one '
    'of any, low, mid, high. "languages" lists the languages of the vocals in '
    'lower case, empty if the request does not care. "Calm but not sad" is '
    '{"moods": ["calm"], "avoid": ["sad", "melancholic"], "energy": "low"}.';

/// How many track numbers a half-written answer holds so far, for a progress
/// readout. Counts the numbers after `"tracks"`; before that key appears, none.
int picksSoFar(String partial) {
  final key = partial.indexOf('"tracks"');
  if (key < 0) return 0;
  final open = partial.indexOf('[', key);
  if (open < 0) return 0;
  final close = partial.indexOf(']', open);
  final body = partial.substring(open + 1, close < 0 ? partial.length : close);
  return RegExp(r'\d+').allMatches(body).length;
}
