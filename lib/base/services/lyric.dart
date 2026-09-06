import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:charset/charset.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/stream_client.dart';
import 'package:soiboi/base/services/webdav_client.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/lrclib_service.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/l10n/generated/app_localizations_en.dart';

class LyricToken {
  final Duration start;
  final String text;
  Duration? end;

  LyricToken(this.start, this.text, [this.end]);

  Map<String, dynamic> toMap() {
    return {
      'start': start.inMilliseconds,
      'end': end?.inMilliseconds,
      'text': text,
    };
  }

  factory LyricToken.fromMap(Map raw) {
    final map = Map<String, dynamic>.from(raw);

    return LyricToken(
      Duration(milliseconds: map['start'] as int),
      map['text'] as String,
      map['end'] != null ? Duration(milliseconds: map['end'] as int) : null,
    );
  }
}

class LyricLine {
  final Duration start;
  final String text;
  final List<LyricToken> tokens;

  List<String> translates = [];

  LyricLine(this.start, this.text, this.tokens);

  Map<String, dynamic> toMap() {
    return {
      'start': start.inMilliseconds,
      'text': text,
      'tokens': tokens.map((t) => t.toMap()).toList(),
      'translates': translates,
    };
  }

  factory LyricLine.fromMap(Map raw) {
    final map = Map<String, dynamic>.from(raw);
    final lyricLine = LyricLine(
      Duration(milliseconds: map['start'] as int),
      map['text'] as String,
      (map['tokens'] as List).map((e) => LyricToken.fromMap(e as Map)).toList(),
    );
    lyricLine.translates = List<String>.from(map['translates']);
    return lyricLine;
  }
}

class ParsedLyrics {
  bool isKaraoke = false;

  /// False when the lyrics carry no timestamps. Consumers that scroll to the
  /// current line must not try to follow playback in that case.
  bool isSynced = true;
  List<LyricLine> lines = [];
}

Duration parseTime(RegExpMatch m) {
  final min = int.parse(m.group(1)!);
  final sec = int.parse(m.group(2)!);
  final ms = int.parse(m.group(3)!.padRight(3, '0'));
  return Duration(minutes: min, seconds: sec, milliseconds: ms);
}

/// True when nothing usable came back from the local sources.
bool _isBlank(List<String> lines) =>
    lines.isEmpty || lines.every((l) => l.trim().isEmpty);

/// Whether any line carries an [mm:ss] stamp, i.e. these are synced lyrics.
///
/// Distinguishing "has lyrics" from "has *synced* lyrics" is the whole point:
/// a downloaded track always arrives with Apple's plain words embedded, and
/// only the timestamped kind can follow playback.
final _timestampPattern = RegExp(r'[\[<]\d{1,3}:\d{2}([.:]\d{2,3})?[\]>]');

bool _hasTimestamps(List<String> lines) =>
    lines.any((line) => _timestampPattern.hasMatch(line));

Future<void> setParsedLyrics(MyAudioMetadata song) async {
  if (song.parsedLyrics != null) {
    return;
  }
  ParsedLyrics result = ParsedLyrics();
  song.parsedLyrics = result;

  List<String> lines = [];
  late AppLocalizations l10n;

  if (localeNotifier.value != null) {
    l10n = lookupAppLocalizations(localeNotifier.value!);
  } else {
    try {
      l10n = lookupAppLocalizations(PlatformDispatcher.instance.locale);
    } catch (_) {
      l10n = AppLocalizationsEn();
    }
  }

  if (sourceType == .navidrome) {
    final lyrics = await streamClient?.getLyricsById(song.id) ?? '';
    lines = lyrics.split(RegExp(r'[\n]'));
  } else if (sourceType == .emby) {
    result.lines.add(LyricLine(Duration.zero, l10n.noLyrics, []));
    return;
  } else {
    if (song.lyrics == null || song.lyrics!.isEmpty) {
      String path = song.path!;
      path = "${path.substring(0, path.lastIndexOf('.'))}.lrc";

      late File lrcFile;
      if (sourceType == .webdav) {
        lrcFile = File('${tmpDir.path}/soiboi_lyric');
        await webdavClient?.download(remotePath: path, localPath: lrcFile.path);
      } else {
        lrcFile = File(path);
      }
      if (lrcFile.existsSync()) {
        try {
          lines = await lrcFile.readAsLines();
        } catch (e) {
          logger.output(e.toString());
          try {
            lines = await lrcFile.readAsLines(encoding: gbk);
          } catch (e) {
            logger.output(e.toString());
          }
        }
      }
    } else {
      lines = song.lyrics!.split(RegExp(r'[\n]'));
    }
  }

  // Ask LRCLIB when we have nothing, *or* when what we have is untimed.
  //
  // The second case is the one that matters in practice and was previously
  // missed: gamdl embeds Apple's plain lyrics into the tags, so a downloaded
  // track always arrives with unsynced words. Treating "has lyrics" as "done"
  // meant those tracks could never gain synced lyrics, which is precisely what
  // LRCLIB exists to provide. A local *synced* lyric still wins outright and
  // costs no request.
  final localIsSynced = _hasTimestamps(lines);
  if (lrclibEnabledNotifier.value && !localIsSynced) {
    final fetched = await fetchFromLrclib(
      title: song.title,
      artist: song.artist,
      album: song.album,
      duration: song.duration,
    );

    // Only upgrade for genuinely synced lyrics. Swapping our local plain text
    // for LRCLIB's plain text gains nothing and risks trading a correct lyric
    // for a mismatched one.
    final synced = fetched?.synced;
    final hasSynced = synced != null && synced.trim().isNotEmpty;

    if (hasSynced) {
      lines = synced.split(RegExp(r'[\n]'));
      // Cache beside the audio file so this is a one-time cost and the track
      // keeps its synced lyrics with no connection.
      if (sourceType == .local && song.path != null) {
        unawaited(cacheSidecar(song.path!, synced));
      }
    } else if (_isBlank(lines)) {
      // Nothing local at all, so even unsynced words are an improvement.
      final best = fetched?.best;
      if (best != null) {
        lines = best.split(RegExp(r'[\n]'));
        if (sourceType == .local && song.path != null) {
          unawaited(cacheSidecar(song.path!, best));
        }
      }
    }
  }

  applyLrcParsing(
    result,
    lines,
    noLyricsMessage: l10n.noLyrics,
    parseFailedMessage: l10n.lyricsParseFailed,
    songDuration: song.duration,
  );
}

/// Fills in [result] from already-fetched raw LRC lines: parses word/line
/// timestamps, detects karaoke (multiple timed tokens per line) and
/// translation lines (a second line sharing the same timestamp as the one
/// before it), and falls back to [noLyricsMessage]/[parseFailedMessage]
/// placeholders when there's nothing to show. Split out from
/// [setParsedLyrics] so the parsing itself can be unit tested without a
/// network/file round trip.
void applyLrcParsing(
  ParsedLyrics result,
  List<String> rawLines, {
  required String noLyricsMessage,
  required String parseFailedMessage,
  Duration? songDuration,
}) {
  final lines = List<String>.from(rawLines)..removeWhere((e) => e.isEmpty);
  if (lines.isEmpty) {
    result.lines.add(LyricLine(Duration.zero, noLyricsMessage, []));
    return;
  }

  final lineTimeRegex = RegExp(r'^[\[<](\d{2}):(\d{2})[.:](\d{2,3})[\]>]');
  final wordRegex = RegExp(r'[\[<](\d{2}):(\d{2})[.:](\d{2,3})[\]>]([^\[<]*)');

  for (var line in lines) {
    final lineMatch = lineTimeRegex.firstMatch(line);
    if (lineMatch == null) continue;

    final lineStart = parseTime(lineMatch);

    final lastLyric = result.lines.isNotEmpty ? result.lines.last : null;
    bool isTranslate = lastLyric?.start == lineStart;

    if (lastLyric?.tokens.last.end == null && !isTranslate) {
      lastLyric?.tokens.last.end = lineStart;
    }

    final tokenMatches = wordRegex.allMatches(line);

    final tokens = <LyricToken>[];
    final textBuffer = StringBuffer();

    for (final match in tokenMatches) {
      final start = parseTime(match);
      final token = match.group(4)!;

      if (tokens.isNotEmpty) {
        tokens.last.end = start;
      }

      if (token.isNotEmpty) {
        tokens.add(LyricToken(start, token));
        textBuffer.write(token);
      }
    }
    if (tokens.isNotEmpty) {
      if (tokens.length == 1 && tokens[0].text.trim().isEmpty) {
        continue;
      }
      if (tokens.length > 1) {
        result.isKaraoke = true;
      }
      if (isTranslate) {
        lastLyric!.translates.add(textBuffer.toString());
      } else {
        result.lines.add(LyricLine(lineStart, textBuffer.toString(), tokens));
      }
    }
  }
  if (result.lines.isEmpty) {
    // Nothing carried a [mm:ss] timestamp. That is not a parse failure -- it is
    // ordinary unsynced lyrics, which is what most embedded tags and many
    // LRCLIB entries contain. Showing them untimed is far better than telling
    // someone parsing failed while the words sit right there in the file.
    final plain = lines
        .map((line) => line.trim())
        .where((line) => line.isNotEmpty)
        // Drop LRC metadata headers like [ar:...] and [by:...], which are not
        // lyrics and would read as noise at the top.
        .where((line) => !RegExp(r'^\[[a-z]+:').hasMatch(line))
        .toList();

    if (plain.isEmpty) {
      result.lines.add(LyricLine(Duration.zero, parseFailedMessage, []));
      return;
    }

    result.isSynced = false;
    for (final line in plain) {
      result.lines.add(LyricLine(Duration.zero, line, []));
    }
    return;
  } else {
    if (result.lines.last.tokens.last.end == null) {
      result.lines.last.tokens.last.end = songDuration;
    }
  }
}
