/// Tidal playlists, by link, without an account.
///
/// Tidal's web player reads public playlists with a fixed client token that
/// ships in its own JavaScript; it identifies the web app, not a user, and
/// needs no sign-in. Tracks come back with an ISRC, so they reach Apple's
/// catalog by exact code rather than by name.
///
/// The token is Tidal's, not ours, and they can rotate it. When they do, reads
/// fail to an empty playlist with a message saying so.
library;

import 'package:soiboi/base/services/external_playlist_source.dart';

const _api = 'https://api.tidal.com/v1';
const _webToken = 'CzET4vdadNUFQ5JU';
const _pageSize = 100;

final _uuid = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  caseSensitive: false,
);

/// The playlist UUID in a Tidal link, or null.
///
/// Accepts `tidal.com/playlist/<uuid>`, `tidal.com/browse/playlist/<uuid>`
/// and `listen.tidal.com/playlist/<uuid>`.
String? tidalPlaylistId(String input) {
  final uri = Uri.tryParse(input.trim());
  if (uri == null || !hostMatches(uri.host, 'tidal.com')) return null;
  final segments = uri.pathSegments;
  final index = segments.indexOf('playlist');
  if (index == -1 || index + 1 >= segments.length) return null;
  final id = segments[index + 1];
  return _uuid.hasMatch(id) ? id.toLowerCase() : null;
}

/// One Tidal playlist item as an [ExternalTrack], or null for anything that
/// is not a playable track (Tidal playlists can also hold videos).
ExternalTrack? parseTidalItem(Map<String, dynamic> json) {
  if (json['type'] != null && json['type'] != 'track') return null;
  final item = json['item'];
  if (item is! Map) return null;
  final title = (item['title'] as String?)?.trim();
  final artist = ((item['artist'] as Map?)?['name'] as String?)?.trim();
  if (title == null || title.isEmpty || artist == null || artist.isEmpty) {
    return null;
  }
  final version = (item['version'] as String?)?.trim();
  final isrc = (item['isrc'] as String?)?.trim();
  return ExternalTrack(
    // Tidal keeps "Remastered" and "Live" apart from the title; Apple folds
    // them in, so matching by name needs them back.
    title: version == null || version.isEmpty ? title : '$title ($version)',
    artist: artist,
    isrc: isrc == null || isrc.isEmpty ? null : isrc,
  );
}

class TidalPlaylistSource extends ExternalPlaylistSource {
  @override
  String get id => 'tidal';

  @override
  String get displayName => 'Tidal';

  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  bool get acceptsLinks => true;

  @override
  String? playlistIdFromUrl(String url) => tidalPlaylistId(url);

  @override
  String? lastError;

  Uri _uri(String path, [Map<String, String> query = const {}]) =>
      Uri.parse('$_api$path').replace(
        queryParameters: {'countryCode': 'US', ...query},
      );

  Future<Object?> _get(String path, [Map<String, String> query = const {}]) =>
      fetchJson(_uri(path, query), headers: const {'x-tidal-token': _webToken});

  @override
  Future<String?> titleFor(String playlistId) async {
    final playlist = await _get('/playlists/$playlistId');
    final title = playlist is Map ? playlist['title'] as String? : null;
    lastError = title == null
        ? 'Tidal did not return that playlist. It may be private, or Tidal '
              'changed how its web player reads playlists.'
        : null;
    return title;
  }

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async {
    final tracks = <ExternalTrack>[];
    for (var offset = 0; ; offset += _pageSize) {
      final page = await _get('/playlists/$playlistId/items', {
        'limit': '$_pageSize',
        'offset': '$offset',
      });
      if (page is! Map) break;
      final items = (page['items'] as List?) ?? const [];
      for (final item in items.whereType<Map>()) {
        final track = parseTidalItem(Map<String, dynamic>.from(item));
        if (track != null) tracks.add(track);
      }
      final total = (page['totalNumberOfItems'] as num?)?.toInt() ?? 0;
      if (items.isEmpty || offset + _pageSize >= total) break;
    }
    return tracks;
  }
}
