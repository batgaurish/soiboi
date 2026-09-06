/// LRCLIB lyrics provider.
///
/// Last step in the chain: embedded tags, then a local `.lrc` sidecar, then
/// here. A hit is written back to a sidecar next to the audio file, so a track
/// only ever costs one network round trip and the library keeps working with
/// no connection afterwards — which is the point, since the phone is expected
/// to play from Syncthing-mirrored files with no server in reach.
///
/// The download pipeline also writes `.lrc` sidecars at archive time, so in
/// normal use this rarely fires. It exists for tracks LRCLIB had nothing for
/// when they were archived, and for music added by other means.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:soiboi/base/services/logger.dart';

/// LRCLIB asks clients to identify themselves rather than send a browser UA.
const _userAgent = 'Soiboi (https://github.com/batgaurish/soiboi)';
const _base = 'lrclib.net';
const _timeout = Duration(seconds: 6);

class LrclibResult {
  const LrclibResult({this.synced, this.plain});

  /// Timestamped LRC content, if LRCLIB had it.
  final String? synced;

  /// Unsynchronised fallback.
  final String? plain;

  bool get isEmpty =>
      (synced == null || synced!.trim().isEmpty) &&
      (plain == null || plain!.trim().isEmpty);

  /// Synced lyrics are strongly preferred — an unsynced blob is a different,
  /// much less useful feature wearing the same name.
  String? get best {
    if (synced != null && synced!.trim().isNotEmpty) return synced;
    if (plain != null && plain!.trim().isNotEmpty) return plain;
    return null;
  }
}

/// Looks up [title] by [artist]. Returns null when nothing matches, when the
/// network is unavailable, or when the request times out — every failure is
/// non-fatal, because missing lyrics must never break playback.
Future<LrclibResult?> fetchFromLrclib({
  required String? title,
  required String? artist,
  String? album,
  Duration? duration,
}) async {
  if (title == null || title.trim().isEmpty) return null;
  if (artist == null || artist.trim().isEmpty) return null;

  // The exact-match endpoint keys on duration, which avoids picking up a live
  // or remixed cut with the same name.
  final exact = await _get(
    '/api/get',
    {
      'track_name': title.trim(),
      'artist_name': artist.trim(),
      if (album != null && album.trim().isNotEmpty) 'album_name': album.trim(),
      if (duration != null && duration.inSeconds > 0)
        'duration': '${duration.inSeconds}',
    },
  );
  if (exact != null) return exact;

  // Fall back to search, which tolerates tag drift between our copy and theirs.
  return _search(title.trim(), artist.trim(), duration);
}

Future<LrclibResult?> _get(String path, Map<String, String> query) async {
  try {
    final uri = Uri.https(_base, path, query);
    final resp = await http
        .get(uri, headers: const {'User-Agent': _userAgent})
        .timeout(_timeout);
    if (resp.statusCode != 200) return null;
    final body = jsonDecode(utf8.decode(resp.bodyBytes));
    if (body is! Map) return null;
    final result = LrclibResult(
      synced: body['syncedLyrics'] as String?,
      plain: body['plainLyrics'] as String?,
    );
    return result.isEmpty ? null : result;
  } on TimeoutException {
    return null;
  } catch (e) {
    logger.output('lrclib: $e');
    return null;
  }
}

Future<LrclibResult?> _search(
  String title,
  String artist,
  Duration? duration,
) async {
  try {
    final uri = Uri.https(_base, '/api/search', {
      'track_name': title,
      'artist_name': artist,
    });
    final resp = await http
        .get(uri, headers: const {'User-Agent': _userAgent})
        .timeout(_timeout);
    if (resp.statusCode != 200) return null;
    final body = jsonDecode(utf8.decode(resp.bodyBytes));
    if (body is! List || body.isEmpty) return null;

    // Prefer a candidate whose duration is within a couple of seconds of ours;
    // a large mismatch usually means a different edit of the same song, and
    // synced lyrics from the wrong edit are worse than none.
    Map? chosen;
    if (duration != null && duration.inSeconds > 0) {
      for (final candidate in body) {
        if (candidate is! Map) continue;
        final d = (candidate['duration'] as num?)?.round();
        if (d != null && (d - duration.inSeconds).abs() <= 2) {
          chosen = candidate;
          break;
        }
      }
    }
    chosen ??= body.firstWhere((e) => e is Map, orElse: () => null) as Map?;
    if (chosen == null) return null;

    final result = LrclibResult(
      synced: chosen['syncedLyrics'] as String?,
      plain: chosen['plainLyrics'] as String?,
    );
    return result.isEmpty ? null : result;
  } on TimeoutException {
    return null;
  } catch (e) {
    logger.output('lrclib search: $e');
    return null;
  }
}

/// Writes [lyrics] to a `.lrc` beside [audioPath] so the next play is offline.
///
/// Failure is deliberately silent: an unwritable library (read-only mount, a
/// WebDAV path, storage permissions on Android) should still show the lyrics
/// we just fetched, just without caching them.
Future<void> cacheSidecar(String audioPath, String lyrics) async {
  try {
    final dot = audioPath.lastIndexOf('.');
    if (dot <= 0) return;
    final lrcPath = '${audioPath.substring(0, dot)}.lrc';
    final file = File(lrcPath);
    if (await file.exists()) return;
    await file.writeAsString(lyrics);
  } catch (e) {
    logger.output('lrclib cache: $e');
  }
}
