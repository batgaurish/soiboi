/// Tracks automatic matching could not place, and the choices people made.
///
/// Discovery matches every playlist track to Apple's catalog on its own
/// (ISRC, then search in the account's storefront, then narrower searches).
/// What slips through all of that used to stay a grey "Could not match" row
/// forever. Here it is remembered instead: archiving a playlist adds its
/// unmatched tracks to a list on the Downloads page, where the user can pick
/// the right song, paste a link, or skip it. A pick or a skip is kept for
/// good (an override), so the same track never asks twice, and discovery
/// consults overrides before searching at all.
///
/// JSON files in app support: `unresolved_tracks.json` (what still needs a
/// choice), `resolved_overrides.json` (artist|title key to an Apple URL, or
/// [skipOverride]) and `resolved_picks.json` (the picked song's own artist
/// and title, which can differ: a cover, a remix).
library;

import 'dart:convert';
import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:path/path.dart' as p;
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/library_match_service.dart';
import 'package:soiboi/base/services/logger.dart';

/// The override meaning "there is no right match; stop asking".
const skipOverride = 'SKIP';

/// A playlist track with no catalog match, waiting for the user's choice.
class UnresolvedTrack {
  const UnresolvedTrack({
    required this.artist,
    required this.title,
    required this.playlist,
    required this.added,
  });

  factory UnresolvedTrack.fromJson(Map<String, dynamic> json) =>
      UnresolvedTrack(
        artist: json['artist'] as String? ?? '',
        title: json['title'] as String? ?? '',
        playlist: json['playlist'] as String? ?? '',
        added:
            DateTime.tryParse(json['added'] as String? ?? '') ??
            DateTime.fromMillisecondsSinceEpoch(0),
      );

  final String artist;
  final String title;

  /// The playlist it was archived from: the download joins that group, and
  /// the pick replaces this track in the linked local playlist.
  final String playlist;
  final DateTime added;

  String get key => overrideKey(artist, title);

  Map<String, String> toJson() => {
    'artist': artist,
    'title': title,
    'playlist': playlist,
    'added': added.toIso8601String(),
  };
}

/// The same song whatever the source's spelling of credits and "(From ...)".
String overrideKey(String artist, String title) => ownedSongKey(artist, title);

final manualResolution = ManualResolution();

class ManualResolution {
  /// Where the files live; app support unless a test says otherwise.
  ManualResolution({Directory? dir}) : _dir = dir;

  final Directory? _dir;

  /// What still needs a choice, oldest first.
  final unresolved = ValueNotifier<List<UnresolvedTrack>>(const []);

  final _overrides = <String, String>{};
  final _picks = <String, ({String artist, String title})>{};
  bool _loaded = false;

  Directory get _base => _dir ?? appSupportDir;
  File get _unresolvedFile =>
      File(p.join(_base.path, 'unresolved_tracks.json'));
  File get _overridesFile =>
      File(p.join(_base.path, 'resolved_overrides.json'));
  File get _picksFile => File(p.join(_base.path, 'resolved_picks.json'));

  void _load() {
    if (_loaded) return;
    _loaded = true;
    try {
      if (_overridesFile.existsSync()) {
        final raw = jsonDecode(_overridesFile.readAsStringSync());
        if (raw is Map) {
          for (final entry in raw.entries) {
            if (entry.value is String) _overrides['${entry.key}'] = entry.value;
          }
        }
      }
      if (_picksFile.existsSync()) {
        final raw = jsonDecode(_picksFile.readAsStringSync());
        if (raw is Map) {
          for (final entry in raw.entries) {
            final value = entry.value;
            if (value is! Map) continue;
            _picks['${entry.key}'] = (
              artist: '${value['artist'] ?? ''}',
              title: '${value['title'] ?? ''}',
            );
          }
        }
      }
      if (_unresolvedFile.existsSync()) {
        final raw = jsonDecode(_unresolvedFile.readAsStringSync());
        if (raw is List) {
          unresolved.value = List.unmodifiable([
            for (final item in raw.whereType<Map>())
              UnresolvedTrack.fromJson(Map<String, dynamic>.from(item)),
          ]);
        }
      }
    } catch (e) {
      // A corrupt file costs the list, never the app.
      logger.output('manual resolution: read failed: $e');
    }
  }

  /// Reads the files now, so the Downloads card has its list at once.
  void load() => _load();

  void _save() {
    try {
      _overridesFile.writeAsStringSync(jsonEncode(_overrides));
      _picksFile.writeAsStringSync(
        jsonEncode({
          for (final e in _picks.entries)
            e.key: {'artist': e.value.artist, 'title': e.value.title},
        }),
      );
      _unresolvedFile.writeAsStringSync(
        jsonEncode([for (final t in unresolved.value) t.toJson()]),
      );
    } catch (e) {
      logger.output('manual resolution: write failed: $e');
    }
  }

  /// The user's choice for this song: an Apple URL, [skipOverride], or null
  /// when they never made one.
  String? overrideFor(String artist, String title) {
    _load();
    return _overrides[overrideKey(artist, title)];
  }

  /// The song the user picked for this one, when its artist or title
  /// differ, so a linked playlist can list what will actually be in the
  /// library. Null when there was no pick.
  ({String artist, String title})? pickedAs(String artist, String title) {
    _load();
    return _picks[overrideKey(artist, title)];
  }

  /// Adds tracks archiving could not match. Ones already listed, or already
  /// decided, are left alone.
  void addUnresolved(Iterable<UnresolvedTrack> tracks) {
    _load();
    final list = [...unresolved.value];
    final keys = {for (final t in list) t.key};
    var changed = false;
    for (final track in tracks) {
      if (track.artist.trim().isEmpty || track.title.trim().isEmpty) continue;
      if (_overrides.containsKey(track.key) || !keys.add(track.key)) continue;
      list.add(track);
      changed = true;
    }
    if (!changed) return;
    unresolved.value = List.unmodifiable(list);
    _save();
  }

  /// Remembers [url] as the song for [track] and takes it off the list.
  /// [artist] and [title] are the picked song's, when known.
  void resolve(
    UnresolvedTrack track,
    String url, {
    String? artist,
    String? title,
  }) {
    _load();
    if (artist != null && title != null) {
      _picks[track.key] = (artist: artist, title: title);
    }
    _decide(track, url);
  }

  /// Remembers that [track] has no right match and takes it off the list.
  void skip(UnresolvedTrack track) {
    _load();
    _picks.remove(track.key);
    _decide(track, skipOverride);
  }

  void _decide(UnresolvedTrack track, String choice) {
    _load();
    _overrides[track.key] = choice;
    unresolved.value = List.unmodifiable(
      unresolved.value.where((t) => t.key != track.key),
    );
    _save();
  }
}
