/// JioSaavn playlists, by link, without an account.
///
/// JioSaavn's website reads playlists from an open JSON endpoint keyed by the
/// token at the end of every share link. It needs no key or sign-in, and it
/// names each track's primary artists. (yt-dlp can read these links too, but
/// it reports the record label as the uploader, which is useless for finding
/// the song on Apple Music.)
library;

import 'package:soiboi/base/services/external_playlist_source.dart';

const _pageSize = 500;

/// The playlist token in a JioSaavn link, or null.
///
/// Share links look like `jiosaavn.com/featured/<slug>/<token>` or
/// `jiosaavn.com/s/playlist/<hash>/<slug>/<token>`; the token is the last
/// path segment either way.
String? jiosaavnPlaylistToken(String input) {
  final uri = Uri.tryParse(input.trim());
  if (uri == null || !hostMatches(uri.host, 'jiosaavn.com')) return null;
  final segments = uri.pathSegments.where((s) => s.isNotEmpty).toList();
  final isPlaylist = segments.contains('featured') || segments.contains('playlist');
  if (!isPlaylist || segments.length < 2) return null;
  final token = segments.last;
  return RegExp(r'^[A-Za-z0-9_,-]{6,}$').hasMatch(token) ? token : null;
}

/// JioSaavn escapes titles as HTML entities.
String _unescape(String text) => text
    .replaceAll('&quot;', '"')
    .replaceAll('&#039;', "'")
    .replaceAll('&amp;', '&');

/// One JioSaavn song as an [ExternalTrack], or null if it is unusable.
ExternalTrack? parseJioSaavnSong(Map<String, dynamic> json) {
  final title = (json['title'] as String?)?.trim();
  final artists = ((json['more_info'] as Map?)?['artistMap'] as Map?)?['primary_artists'];
  final names = [
    for (final artist in (artists as List? ?? const []).whereType<Map>())
      if ((artist['name'] as String?)?.trim() case final name? when name.isNotEmpty)
        _unescape(name),
  ];
  if (title == null || title.isEmpty || names.isEmpty) return null;
  return ExternalTrack(title: _unescape(title), artist: names.join(', '));
}

class JioSaavnPlaylistSource extends ExternalPlaylistSource {
  @override
  String get id => 'jiosaavn';

  @override
  String get displayName => 'JioSaavn';

  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  bool get acceptsLinks => true;

  @override
  String? playlistIdFromUrl(String url) => jiosaavnPlaylistToken(url);

  Future<Map?> _fetch(String token, {int count = 1}) async {
    final body = await fetchJson(
      Uri.https('www.jiosaavn.com', '/api.php', {
        '__call': 'webapi.get',
        'token': token,
        'type': 'playlist',
        'p': '1',
        'n': '$count',
        'includeMetaTags': '0',
        'ctx': 'web6dot0',
        'api_version': '4',
        '_format': 'json',
        '_marker': '0',
      }),
    );
    return body is Map && body['list'] != null ? body : null;
  }

  @override
  Future<String?> titleFor(String playlistId) async =>
      (await _fetch(playlistId))?['title'] as String?;

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async {
    final playlist = await _fetch(playlistId, count: _pageSize);
    return [
      for (final song in (playlist?['list'] as List? ?? const []).whereType<Map>())
        ?parseJioSaavnSong(Map<String, dynamic>.from(song)),
    ];
  }
}
