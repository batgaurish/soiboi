/// A pasted tracklist: the way in for any service Soiboi cannot read.
///
/// Last.fm answers automated requests with a bot challenge, Amazon Music
/// renders its pages in the browser, and Wynk has no public way to look a
/// playlist up. For those, and anything else, the user pastes one track per
/// line (`Artist - Title`, or a CSV with Artist and Title columns and an
/// optional ISRC) and it resolves like any other import.
///
/// Registered last, so a link always goes to its own source first. The pasted
/// text is the playlist id: there is nothing to fetch.
library;

import 'package:soiboi/base/services/external_playlist_source.dart';

const _separators = [' - ', ' – ', ' — ', '\t'];

/// Tracks parsed from pasted text, skipping lines it cannot read.
List<ExternalTrack> parseTracklist(String text) {
  final lines = text
      .split(RegExp(r'\r?\n'))
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .toList();
  if (lines.isEmpty) return const [];

  final header = lines.first.toLowerCase().split(',').map((c) => c.trim()).toList();
  final artistColumn = header.indexOf('artist');
  final titleColumn = header.indexWhere((c) => c == 'title' || c == 'track' || c == 'name');
  if (artistColumn != -1 && titleColumn != -1) {
    final isrcColumn = header.indexOf('isrc');
    return [
      for (final line in lines.skip(1))
        ?_csvTrack(_splitCsv(line), artistColumn, titleColumn, isrcColumn),
    ];
  }

  return [
    for (final line in lines)
      if (_separators.firstWhereOrNull(line.contains) case final separator?)
        if (_pair(line, separator) case (final artist, final title))
          ExternalTrack(title: title, artist: artist),
  ];
}

(String, String)? _pair(String line, String separator) {
  final index = line.indexOf(separator);
  // A leading track number ("1. Artist - Title") is noise, not artist.
  final artist = line.substring(0, index).replaceFirst(RegExp(r'^\d+[.)]\s*'), '').trim();
  final title = line.substring(index + separator.length).trim();
  return artist.isEmpty || title.isEmpty ? null : (artist, title);
}

ExternalTrack? _csvTrack(List<String> cells, int artist, int title, int isrc) {
  String cell(int i) => i >= 0 && i < cells.length ? cells[i].trim() : '';
  if (cell(artist).isEmpty || cell(title).isEmpty) return null;
  return ExternalTrack(
    title: cell(title),
    artist: cell(artist),
    isrc: cell(isrc).isEmpty ? null : cell(isrc),
  );
}

/// Splits one CSV line, honouring double-quoted cells.
List<String> _splitCsv(String line) {
  final cells = <String>[];
  final cell = StringBuffer();
  var quoted = false;
  for (var i = 0; i < line.length; i++) {
    final char = line[i];
    if (char == '"') {
      if (quoted && i + 1 < line.length && line[i + 1] == '"') {
        cell.write('"');
        i++;
      } else {
        quoted = !quoted;
      }
    } else if (char == ',' && !quoted) {
      cells.add(cell.toString());
      cell.clear();
    } else {
      cell.write(char);
    }
  }
  cells.add(cell.toString());
  return cells;
}

extension<T> on List<T> {
  T? firstWhereOrNull(bool Function(T) test) {
    for (final item in this) {
      if (test(item)) return item;
    }
    return null;
  }
}

class TracklistSource extends ExternalPlaylistSource {
  @override
  String get id => 'tracklist';

  @override
  String get displayName => 'a pasted tracklist';

  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  bool get acceptsLinks => true;

  /// Claims pasted text of two or more readable lines, never a single link.
  @override
  String? playlistIdFromUrl(String text) =>
      !text.trim().contains('\n') || parseTracklist(text).length < 2 ? null : text;

  @override
  Future<String?> titleFor(String playlistId) async =>
      'Pasted tracklist (${parseTracklist(playlistId).length} tracks)';

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async => parseTracklist(playlistId);
}
