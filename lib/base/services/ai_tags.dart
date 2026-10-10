/// What each song feels like, asked of the AI once and kept on the device.
///
/// The acoustic analyser can say a track is quiet and slow. It cannot say
/// whether it is happy or sad, because nothing it measures carries that. A
/// language model has heard of most songs and can say. So the model tags each
/// track once, the answer is saved here, and everything afterwards — smart
/// playlists, the Home mood shelf, picking candidates for an AI playlist —
/// reads the saved tags locally for free.
///
/// Tags come from a fixed vocabulary so they can be filtered on exactly. Free
/// text moods would never match what someone types into a rule.
///
/// Only title, artist, album and genre go to the model, the same as for a
/// playlist request.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/ai_service.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/song_vibe.dart';

export 'package:soiboi/base/services/song_vibe.dart';

class SongTags {
  final Map<String, SongVibe> _byId = {};
  bool _loaded = false;

  /// Bumped whenever tags change, so shelves and playlists can rebuild.
  final changeNotifier = ValueNotifier(0);

  File get _file => File('${appSupportDir.path}/ai_song_tags.json');

  SongVibe? of(MyAudioMetadata song) => _byId[song.id];

  SongVibe? ofId(String id) => _byId[id];

  int get count => _byId.length;

  Future<void> load() async {
    if (_loaded) return;
    _loaded = true;
    try {
      final file = _file;
      if (!await file.exists()) return;
      final json = jsonDecode(await file.readAsString());
      if (json is! Map) return;
      for (final e in json.entries) {
        final vibe = SongVibe.fromJson(e.value);
        if (vibe != null) _byId['${e.key}'] = vibe;
      }
      changeNotifier.value++;
    } catch (e) {
      // A corrupt file costs the tags, which can be asked for again.
      logger.output('ai tags: $e');
    }
  }

  Future<void> save() async {
    try {
      await _file.writeAsString(
        jsonEncode({for (final e in _byId.entries) e.key: e.value.toJson()}),
      );
    } catch (e) {
      logger.output('ai tags: save failed: $e');
    }
  }

  void put(String id, SongVibe vibe) => _byId[id] = vibe;

  /// Share of [songs] that carry a tag, 0 to 1.
  double coverage(List<MyAudioMetadata> songs) {
    if (songs.isEmpty) return 0;
    return songs.where((s) => _byId.containsKey(s.id)).length / songs.length;
  }

  Future<void> clear() async {
    _byId.clear();
    await save();
    changeNotifier.value++;
  }
}

final aiTags = SongTags();

/// Tracks per request. Large enough that a library takes tens of requests,
/// small enough to stay well inside a free tier's context and output limits.
const _batchSize = 100;

/// Where a tagging run stands, for the panel to show.
class TagProgress {
  const TagProgress({required this.done, required this.total});
  final int done;
  final int total;
}

final aiTaggingProgress = ValueNotifier<TagProgress?>(null);
bool _cancelTagging = false;

void cancelTagging() => _cancelTagging = true;

/// Tags every song that has none yet, saving after each batch so a run that
/// stops halfway keeps what it finished. Returns how many songs it tagged.
///
/// Throws [AiException] when the very first batch fails, so a bad key shows
/// as one clear message. A later failure ends the run quietly with what it
/// has: the rest is picked up next time.
Future<int> aiTagLibrary() async {
  final config = aiConfigNotifier.value;
  if (config == null) throw AiException('AI is not set up yet');
  await aiTags.load();

  final todo = [
    for (final s in library.songList)
      if (aiTags.of(s) == null && (s.title ?? '').trim().isNotEmpty) s,
  ];
  if (todo.isEmpty) return 0;

  _cancelTagging = false;
  var tagged = 0;
  aiTaggingProgress.value = TagProgress(done: 0, total: todo.length);
  try {
    for (var start = 0; start < todo.length; start += _batchSize) {
      if (_cancelTagging) break;
      final batch = todo.skip(start).take(_batchSize).toList();
      try {
        tagged += await _tagBatch(config, batch);
      } on AiException {
        if (tagged == 0 && start == 0) rethrow;
        break;
      }
      await aiTags.save();
      aiTags.changeNotifier.value++;
      aiTaggingProgress.value = TagProgress(
        done: (start + batch.length).clamp(0, todo.length),
        total: todo.length,
      );
    }
  } finally {
    aiTaggingProgress.value = null;
  }
  return tagged;
}

Future<int> _tagBatch(AiConfig config, List<MyAudioMetadata> batch) async {
  final lines = [
    for (var i = 0; i < batch.length; i++) _tagLine(i, batch[i]),
  ].join('\n');
  final answer = await aiComplete(
    config,
    system: tagSystemPrompt,
    prompt: 'Songs (number | title | artist | album | genre):\n$lines',
  );
  return applyTagAnswer(answer, batch.map((s) => s.id).toList());
}

String _tagLine(int i, MyAudioMetadata s) {
  String clean(String? v) => (v ?? '').replaceAll('|', '/').trim();
  return '$i | ${clean(s.title)} | ${clean(s.artist)} | ${clean(s.album)} | '
      '${clean(s.genre)}';
}

@visibleForTesting
final tagSystemPrompt =
    'You tag songs by how they feel. For each numbered song, use what you '
    'know about the actual recording; judge from the title and artist, not '
    'from guesses about the genre. Reply with JSON only, no prose, in exactly '
    'this shape: {"songs": [{"n": 0, "m": ["calm", "warm"], "v": "high", '
    '"e": "low", "l": "hindi"}]}. "m" is one to three moods chosen only from: '
    '${vibeMoods.join(', ')}. "v" is how positive the song feels and "e" how '
    'energetic, each one of low, mid, high. A quiet, slow song can be happy '
    '(v high, e low) or sad (v low, e low); do not treat them alike. "l" is '
    'the language of the vocals in lower case, or "instrumental", or '
    '"unknown" if you are not sure. Include every song, in order.';

/// Saves the tags in [answer], where entry `n` is an index into [ids].
/// Returns how many were saved. Anything the model got wrong is skipped.
@visibleForTesting
int applyTagAnswer(String answer, List<String> ids) {
  final json = extractJsonObject(answer);
  var saved = 0;
  for (final entry in (json['songs'] as List? ?? const [])) {
    if (entry is! Map) continue;
    final n = entry['n'] is int ? entry['n'] as int : int.tryParse('${entry['n']}');
    if (n == null || n < 0 || n >= ids.length) continue;
    final vibe = SongVibe.fromJson(entry);
    if (vibe == null) continue;
    aiTags.put(ids[n], vibe);
    saved++;
  }
  return saved;
}
