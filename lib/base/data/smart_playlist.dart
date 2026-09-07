/// Playlists defined by rules rather than by a fixed list of songs.
///
/// A normal playlist stores the songs it holds; a smart one stores the question
/// and answers it against the library every time it is opened. So it stays
/// right as the library changes: "lossless tracks I haven't played this year"
/// keeps meaning that after the next download, without anyone maintaining it.
///
/// Only fields the library already knows are queryable. Nothing here needs
/// audio analysis, a network call or a service account, so a smart playlist
/// works on a phone with no signal — which is the point of the app.
library;

import 'dart:convert';
import 'dart:io';

import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/utils/path.dart';

/// What a rule looks at.
enum SmartField {
  title('Title', SmartFieldKind.text),
  artist('Artist', SmartFieldKind.text),
  album('Album', SmartFieldKind.text),
  albumArtist('Album artist', SmartFieldKind.text),
  genre('Genre', SmartFieldKind.text),
  format('Format', SmartFieldKind.text),
  year('Year', SmartFieldKind.number),
  playCount('Play count', SmartFieldKind.number),
  bitrate('Bitrate (kbps)', SmartFieldKind.number),
  durationSeconds('Length (seconds)', SmartFieldKind.number),
  bpm('BPM', SmartFieldKind.number),
  energy('Energy', SmartFieldKind.number),
  danceable('Danceable', SmartFieldKind.number),
  relaxed('Relaxed', SmartFieldKind.number),
  aggressive('Aggressive', SmartFieldKind.number),
  lastPlayed('Last played', SmartFieldKind.date),
  added('Date added', SmartFieldKind.date),
  favourite('Favourite', SmartFieldKind.flag);

  const SmartField(this.label, this.kind);
  final String label;
  final SmartFieldKind kind;
}

/// Which operators a field can take. Offering "contains" on a play count, or
/// "greater than" on a title, would only ever produce confusing playlists.
enum SmartFieldKind { text, number, date, flag }

enum SmartOperator {
  contains('contains'),
  notContains('does not contain'),
  equals('is'),
  notEquals('is not'),
  greaterThan('is more than'),
  lessThan('is less than'),
  inLastDays('in the last (days)'),
  notInLastDays('not in the last (days)');

  const SmartOperator(this.label);
  final String label;

  static List<SmartOperator> forKind(SmartFieldKind kind) => switch (kind) {
    SmartFieldKind.text => [contains, notContains, equals, notEquals],
    SmartFieldKind.number => [equals, notEquals, greaterThan, lessThan],
    // A date is only ever interesting as a window, never as an exact value:
    // nobody looks for tracks last played on one particular day.
    SmartFieldKind.date => [inLastDays, notInLastDays],
    SmartFieldKind.flag => [equals],
  };
}

class SmartRule {
  const SmartRule({
    required this.field,
    required this.operator,
    required this.value,
  });

  final SmartField field;
  final SmartOperator operator;

  /// Kept as text whatever the field: it comes from a text box, and parsing at
  /// match time means a half-typed rule degrades to "matches nothing" instead
  /// of failing to save.
  final String value;

  Map<String, dynamic> toJson() => {
    'field': field.name,
    'operator': operator.name,
    'value': value,
  };

  static SmartRule? fromJson(Map<String, dynamic> json) {
    final field = SmartField.values
        .where((f) => f.name == json['field'])
        .firstOrNull;
    final operator = SmartOperator.values
        .where((o) => o.name == json['operator'])
        .firstOrNull;
    if (field == null || operator == null) return null;
    return SmartRule(
      field: field,
      operator: operator,
      value: json['value'] as String? ?? '',
    );
  }

  bool matches(MyAudioMetadata song, {DateTime? now}) {
    switch (field.kind) {
      case SmartFieldKind.text:
        return _matchesText(_textOf(song));
      case SmartFieldKind.number:
        return _matchesNumber(_numberOf(song));
      case SmartFieldKind.date:
        return _matchesDate(_dateOf(song), now ?? DateTime.now());
      case SmartFieldKind.flag:
        final wanted = value.toLowerCase() != 'no' && value.toLowerCase() != 'false';
        return song.isFavoriteNotifier.value == wanted;
    }
  }

  String? _textOf(MyAudioMetadata song) => switch (field) {
    SmartField.title => song.title,
    SmartField.artist => song.artist,
    SmartField.album => song.album,
    SmartField.albumArtist => song.albumArtist,
    SmartField.genre => song.genre,
    SmartField.format => song.format,
    _ => null,
  };

  num? _numberOf(MyAudioMetadata song) => switch (field) {
    SmartField.year => song.year,
    SmartField.playCount => song.playCount,
    // Stored in bits per second; rules are written in kbps because that is how
    // the badge on the card reads.
    SmartField.bitrate => song.bitrate == null ? null : song.bitrate! ~/ 1000,
    SmartField.durationSeconds => song.duration?.inSeconds,
    SmartField.bpm => song.bpm,
    SmartField.energy => song.energy,
    SmartField.danceable => song.danceable,
    SmartField.relaxed => song.relaxed,
    SmartField.aggressive => song.aggressive,
    _ => null,
  };

  DateTime? _dateOf(MyAudioMetadata song) => switch (field) {
    SmartField.lastPlayed => song.lastPlayed,
    SmartField.added => song.modified,
    _ => null,
  };

  bool _matchesText(String? actual) {
    final subject = (actual ?? '').toLowerCase();
    final target = value.toLowerCase().trim();
    return switch (operator) {
      SmartOperator.contains => subject.contains(target),
      SmartOperator.notContains => !subject.contains(target),
      SmartOperator.equals => subject == target,
      SmartOperator.notEquals => subject != target,
      _ => false,
    };
  }

  bool _matchesNumber(num? actual) {
    final target = num.tryParse(value.trim());
    if (target == null) return false;
    // A missing value is not zero. A track with no year should not match
    // "year is less than 2000" just because the tag is absent.
    if (actual == null) return operator == SmartOperator.notEquals;
    return switch (operator) {
      SmartOperator.equals => actual == target,
      SmartOperator.notEquals => actual != target,
      SmartOperator.greaterThan => actual > target,
      SmartOperator.lessThan => actual < target,
      _ => false,
    };
  }

  bool _matchesDate(DateTime? actual, DateTime now) {
    final days = int.tryParse(value.trim());
    if (days == null) return false;
    // Never played is the whole point of "not in the last 90 days": a track
    // with no date has certainly not been played recently.
    if (actual == null) return operator == SmartOperator.notInLastDays;
    final within = now.difference(actual).inDays <= days;
    return operator == SmartOperator.inLastDays ? within : !within;
  }
}

/// How the result is ordered before [SmartPlaylist.limit] is applied.
enum SmartSort {
  added('Recently added'),
  playCount('Most played'),
  lastPlayed('Recently played'),
  title('Title'),
  artist('Artist'),
  year('Year'),
  energy('Energy'),
  danceable('Danceable'),
  relaxed('Relaxed'),
  random('Shuffled');

  const SmartSort(this.label);
  final String label;
}

class SmartPlaylist {
  const SmartPlaylist({
    required this.name,
    this.rules = const [],
    this.matchAll = true,
    this.sort = SmartSort.added,
    this.descending = true,
    this.limit,
  });

  final String name;
  final List<SmartRule> rules;

  /// True for "all of these", false for "any of these".
  final bool matchAll;
  final SmartSort sort;
  final bool descending;

  /// Null for everything that matches. A cap is what makes "50 most played
  /// rock tracks" expressible rather than "all rock, sorted by plays".
  final int? limit;

  SmartPlaylist copyWith({
    String? name,
    List<SmartRule>? rules,
    bool? matchAll,
    SmartSort? sort,
    bool? descending,
    int? limit,
    bool clearLimit = false,
  }) => SmartPlaylist(
    name: name ?? this.name,
    rules: rules ?? this.rules,
    matchAll: matchAll ?? this.matchAll,
    sort: sort ?? this.sort,
    descending: descending ?? this.descending,
    limit: clearLimit ? null : (limit ?? this.limit),
  );

  Map<String, dynamic> toJson() => {
    'name': name,
    'rules': rules.map((r) => r.toJson()).toList(),
    'matchAll': matchAll,
    'sort': sort.name,
    'descending': descending,
    if (limit != null) 'limit': limit,
  };

  static SmartPlaylist? fromJson(Map<String, dynamic> json) {
    final name = json['name'] as String?;
    if (name == null || name.isEmpty) return null;
    return SmartPlaylist(
      name: name,
      rules: [
        for (final raw in (json['rules'] as List? ?? const []))
          if (raw is Map)
            ?SmartRule.fromJson(raw.cast<String, dynamic>()),
      ],
      matchAll: json['matchAll'] as bool? ?? true,
      sort:
          SmartSort.values.where((s) => s.name == json['sort']).firstOrNull ??
          SmartSort.added,
      descending: json['descending'] as bool? ?? true,
      limit: json['limit'] as int?,
    );
  }

  /// The songs this playlist currently means.
  ///
  /// [now] is injectable so date rules can be tested without waiting a day.
  List<MyAudioMetadata> evaluate(
    List<MyAudioMetadata> library, {
    DateTime? now,
  }) {
    final when = now ?? DateTime.now();
    // No rules matches everything rather than nothing: a playlist being built
    // should show the library it is narrowing, not an empty screen.
    final matched = rules.isEmpty
        ? [...library]
        : library.where((song) {
            final results = rules.map((r) => r.matches(song, now: when));
            return matchAll ? results.every((r) => r) : results.any((r) => r);
          }).toList();

    _sort(matched);
    if (limit != null && matched.length > limit!) {
      return matched.sublist(0, limit!);
    }
    return matched;
  }

  void _sort(List<MyAudioMetadata> songs) {
    if (sort == SmartSort.random) {
      songs.shuffle();
      return;
    }
    int compare(MyAudioMetadata a, MyAudioMetadata b) => switch (sort) {
      SmartSort.added => _compareDates(a.modified, b.modified),
      SmartSort.lastPlayed => _compareDates(a.lastPlayed, b.lastPlayed),
      SmartSort.playCount => a.playCount.compareTo(b.playCount),
      SmartSort.title => (a.title ?? '').toLowerCase().compareTo(
        (b.title ?? '').toLowerCase(),
      ),
      SmartSort.artist => (a.artist ?? '').toLowerCase().compareTo(
        (b.artist ?? '').toLowerCase(),
      ),
      SmartSort.year => (a.year ?? 0).compareTo(b.year ?? 0),
      SmartSort.energy => (a.energy ?? -1).compareTo(b.energy ?? -1),
      SmartSort.danceable =>
          (a.danceable ?? -1).compareTo(b.danceable ?? -1),
      SmartSort.relaxed =>
          (a.relaxed ?? -1).compareTo(b.relaxed ?? -1),
      SmartSort.random => 0,
    };
    songs.sort((a, b) => descending ? compare(b, a) : compare(a, b));
  }

  /// Missing dates sort last in either direction.
  ///
  /// A never-played track is not "played in 1970", and letting it lead
  /// "recently played" would make the playlist useless.
  static int _compareDates(DateTime? a, DateTime? b) {
    if (a == null && b == null) return 0;
    if (a == null) return -1;
    if (b == null) return 1;
    return a.compareTo(b);
  }
}

/// Stored smart playlists, kept beside the ordinary ones.
final smartPlaylists = SmartPlaylistStore();

class SmartPlaylistStore {
  List<SmartPlaylist> playlists = [];

  File get _file =>
      File('${getFolderConfigPath(sourceType)}/smart_playlists.json');

  Future<void> load() async {
    try {
      final file = _file;
      if (!await file.exists()) return;
      final decoded = jsonDecode(await file.readAsString());
      if (decoded is! List) return;
      playlists = [
        for (final raw in decoded)
          if (raw is Map) ?SmartPlaylist.fromJson(raw.cast<String, dynamic>()),
      ];
    } catch (e) {
      // A corrupt file costs the user their smart playlists, not the app.
      logger.output('smart playlists: $e');
    }
  }

  Future<void> save() async {
    try {
      final file = _file;
      await file.parent.create(recursive: true);
      await file.writeAsString(
        jsonEncode(playlists.map((p) => p.toJson()).toList()),
      );
    } catch (e) {
      logger.output('smart playlists: save failed: $e');
    }
  }

  Future<void> upsert(SmartPlaylist playlist, {String? replacing}) async {
    final target = replacing ?? playlist.name;
    final index = playlists.indexWhere((p) => p.name == target);
    if (index >= 0) {
      playlists[index] = playlist;
    } else {
      playlists.add(playlist);
    }
    await save();
  }

  Future<void> remove(String name) async {
    playlists.removeWhere((p) => p.name == name);
    await save();
  }
}

/// A [SmartPlaylist] presented as an ordinary playlist.
///
/// Wrapping rather than reimplementing: this reuses the whole playlist screen —
/// playback, queueing, sorting, the cover mosaic — instead of building a
/// parallel list view that would drift from it.
class SmartPlaylistView extends Playlist {
  /// Evaluated in the constructor, not on [load].
  ///
  /// The songs screen reads `playlist.songList` directly and never calls load
  /// -- ordinary playlists are filled once at startup by the playlist manager,
  /// and a smart one has no such moment. Evaluating here is what makes the
  /// screen show anything at all.
  SmartPlaylistView(this.spec) : super(name: spec.name, fileBacked: false) {
    songList = spec.evaluate(library.songList);
  }

  final SmartPlaylist spec;

  @override
  Future<void> load() async {
    canModify = false;
    changeNotifier.value++;
    songList = spec.evaluate(library.songList);
    canModify = true;
    changeNotifier.value++;
    layersManager.updateBackground();
  }

  // A smart playlist's contents are an answer, not a list. Adding or removing
  // a song would be undone by the next evaluation, so say why instead of
  // accepting an edit that will not survive.
  @override
  Future<void> add(List<MyAudioMetadata> songList) async => _explain();

  @override
  Future<void> remove(List<MyAudioMetadata> songList) async => _explain();

  void _explain() => showCenterMessage(
    'This playlist follows its rules — edit them to change what it holds',
  );
}
