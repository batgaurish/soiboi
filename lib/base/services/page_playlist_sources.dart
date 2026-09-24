/// Sources read from the public web page itself: Qobuz, Gaana and Bandcamp.
///
/// Qobuz and Gaana publish each playlist as schema.org `MusicPlaylist` data
/// in the page, the markup search engines read, with every track's name and
/// artist. One parser serves both. Bandcamp has no playlists, only albums, and
/// embeds each album's tracklist as JSON in a `data-tralbum` attribute.
///
/// The page URL is the playlist id: these sites have no separate API to hand
/// an id to, and the URL is what a fetch needs.
library;

import 'dart:convert';

import 'package:soiboi/base/services/external_playlist_source.dart';

/// Title and tracks from a page's schema.org `MusicPlaylist` or
/// `MusicAlbum`, or null when the page has none.
({String title, List<ExternalTrack> tracks})? parseJsonLdPlaylist(String html) {
  final blocks = RegExp(
    r'<script[^>]*application/ld\+json[^>]*>(.*?)</script>',
    dotAll: true,
  ).allMatches(html);
  for (final block in blocks) {
    Object? decoded;
    try {
      decoded = jsonDecode(block.group(1)!);
    } on FormatException {
      continue;
    }
    for (final node in decoded is List ? decoded : [decoded]) {
      if (node is! Map) continue;
      final type = node['@type'];
      if (type != 'MusicPlaylist' && type != 'MusicAlbum') continue;
      var entries = node['track'];
      if (entries is Map) entries = entries['itemListElement'];
      if (entries is! List) continue;
      final tracks = [
        for (final entry in entries.whereType<Map>()) ?_jsonLdTrack(entry, node),
      ];
      return (title: (node['name'] as String? ?? 'Playlist').trim(), tracks: tracks);
    }
  }
  return null;
}

ExternalTrack? _jsonLdTrack(Map entry, Map playlist) {
  // An ItemList wraps each recording in a ListItem.
  final recording = entry['item'] is Map ? entry['item'] as Map : entry;
  final name = recording['name'];
  final title = name is String ? _tidy(name) : null;
  final artist = _artistName(recording['byArtist']) ?? _artistName(playlist['byArtist']);
  if (title == null || title.isEmpty || artist == null) return null;
  final isrc = (recording['isrcCode'] as String?)?.trim();
  return ExternalTrack(
    title: title,
    artist: artist,
    isrc: isrc == null || isrc.isEmpty ? null : isrc,
  );
}

/// schema.org allows a name, an object, or a list of either.
///
/// Gaana puts every credited name in one comma-joined string, six or more for
/// a film song. A long credit list only adds noise to the Apple search, so the
/// first two names stand for the track.
String? _artistName(Object? byArtist) {
  final names = <String>[];
  for (final artist in byArtist is List ? byArtist : [byArtist]) {
    final name = artist is Map ? artist['name'] : artist;
    if (name is! String) continue;
    names.addAll(name.split(',').map((n) => n.trim()).where((n) => n.isNotEmpty));
  }
  return names.isEmpty ? null : names.take(2).join(', ');
}

String _tidy(String text) => text.replaceAll(RegExp(r'\s+'), ' ').trim();

/// A source whose playlists are public pages carrying schema.org data.
class JsonLdPlaylistSource extends ExternalPlaylistSource {
  JsonLdPlaylistSource({
    required this.id,
    required this.displayName,
    required this.domain,
    required this.pathMarker,
  });

  @override
  final String id;

  @override
  final String displayName;

  final String domain;

  /// The path segment that marks a playlist page, like `playlist`.
  final String pathMarker;

  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  bool get acceptsLinks => true;

  @override
  String? lastError;

  @override
  String? playlistIdFromUrl(String url) {
    final uri = Uri.tryParse(url.trim());
    if (uri == null || !hostMatches(uri.host, domain)) return null;
    if (!uri.pathSegments.contains(pathMarker)) return null;
    // The page itself, without tracking parameters.
    return 'https://${uri.host}${uri.path}';
  }

  Future<({String title, List<ExternalTrack> tracks})?> _read(String url) async {
    final page = await fetchText(Uri.parse(url));
    final parsed = page == null ? null : parseJsonLdPlaylist(page);
    lastError = parsed == null
        ? 'Could not read a tracklist from that $displayName page.'
        : null;
    return parsed;
  }

  @override
  Future<String?> titleFor(String playlistId) async => (await _read(playlistId))?.title;

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async =>
      (await _read(playlistId))?.tracks ?? const [];
}

JsonLdPlaylistSource qobuzPlaylistSource() => JsonLdPlaylistSource(
  id: 'qobuz',
  displayName: 'Qobuz',
  domain: 'qobuz.com',
  pathMarker: 'playlists',
);

JsonLdPlaylistSource gaanaPlaylistSource() => JsonLdPlaylistSource(
  id: 'gaana',
  displayName: 'Gaana',
  domain: 'gaana.com',
  pathMarker: 'playlist',
);

/// Title and tracks from a Bandcamp album page, or null.
({String title, List<ExternalTrack> tracks})? parseBandcampAlbum(String html) {
  final match = RegExp(r'data-tralbum="([^"]*)"').firstMatch(html);
  if (match == null) return null;
  final Object? data;
  try {
    data = jsonDecode(
      match
          .group(1)!
          .replaceAll('&quot;', '"')
          .replaceAll('&#39;', "'")
          .replaceAll('&lt;', '<')
          .replaceAll('&gt;', '>')
          .replaceAll('&amp;', '&'),
    );
  } on FormatException {
    return null;
  }
  if (data is! Map) return null;
  final albumArtist = (data['artist'] as String?)?.trim();
  final tracks = [
    for (final track in (data['trackinfo'] as List? ?? const []).whereType<Map>())
      if ((track['title'] as String?)?.trim() case final title? when title.isNotEmpty)
        if (((track['artist'] as String?)?.trim() ?? albumArtist) case final artist?
            when artist.isNotEmpty)
          ExternalTrack(title: title, artist: artist),
  ];
  final title = ((data['current'] as Map?)?['title'] as String?)?.trim() ?? 'Bandcamp album';
  return (title: title, tracks: tracks);
}

class BandcampAlbumSource extends ExternalPlaylistSource {
  @override
  String get id => 'bandcamp';

  @override
  String get displayName => 'Bandcamp';

  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  bool get acceptsLinks => true;

  @override
  String? lastError;

  @override
  String? playlistIdFromUrl(String url) {
    final uri = Uri.tryParse(url.trim());
    if (uri == null || !hostMatches(uri.host, 'bandcamp.com')) return null;
    final segments = uri.pathSegments;
    if (segments.length < 2 || segments[0] != 'album') return null;
    return 'https://${uri.host}/album/${segments[1]}';
  }

  Future<({String title, List<ExternalTrack> tracks})?> _read(String url) async {
    final page = await fetchText(Uri.parse(url));
    final parsed = page == null ? null : parseBandcampAlbum(page);
    lastError = parsed == null ? 'Could not read that Bandcamp album.' : null;
    return parsed;
  }

  @override
  Future<String?> titleFor(String playlistId) async => (await _read(playlistId))?.title;

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async =>
      (await _read(playlistId))?.tracks ?? const [];
}
