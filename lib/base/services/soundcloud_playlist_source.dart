/// SoundCloud sets (playlists and albums), by link, without an account.
///
/// SoundCloud's website talks to its own API with a public client id it
/// embeds in every page (`apiClient` in `window.__sc_hydration`). That id
/// identifies the web app, not a user, so reading a public set needs no
/// sign-in. It is read fresh from the page rather than hardcoded, because
/// SoundCloud rotates it.
///
/// A resolved set carries full details for its first few tracks only; the rest
/// arrive as bare ids and are fetched in batches.
library;

import 'dart:convert';

import 'package:soiboi/base/services/external_playlist_source.dart';

const _api = 'https://api-v2.soundcloud.com';
const _batch = 50;

/// The set URL in a SoundCloud link, normalised, or null.
///
/// The set is identified by its URL (`soundcloud.com/<user>/sets/<slug>`)
/// because that is what SoundCloud's resolve endpoint takes.
String? soundcloudSetUrl(String input) {
  final uri = Uri.tryParse(input.trim());
  if (uri == null || !hostMatches(uri.host, 'soundcloud.com')) return null;
  final segments = uri.pathSegments.where((s) => s.isNotEmpty).toList();
  if (segments.length < 3 || segments[1] != 'sets') return null;
  return 'https://soundcloud.com/${segments.take(3).join('/')}';
}

/// The public web client id in a SoundCloud page, or null.
String? parseSoundcloudClientId(String html) {
  final match = RegExp(
    r'window\.__sc_hydration\s*=\s*(\[.*?\]);\s*</script>',
    dotAll: true,
  ).firstMatch(html);
  if (match == null) return null;
  try {
    for (final entry in (jsonDecode(match.group(1)!) as List).whereType<Map>()) {
      if (entry['hydratable'] == 'apiClient') {
        return (entry['data'] as Map?)?['id'] as String?;
      }
    }
  } on FormatException {
    return null;
  }
  return null;
}

/// One SoundCloud track as an [ExternalTrack], or null if it is only an id.
ExternalTrack? parseSoundcloudTrack(Map<String, dynamic> json) {
  final title = (json['title'] as String?)?.trim();
  if (title == null || title.isEmpty) return null;
  final publisher = json['publisher_metadata'] as Map?;
  // The label's metadata names the real artist; the uploader is often a
  // label, a channel or a fan account.
  final artist = ((publisher?['artist'] as String?)?.trim().isNotEmpty ?? false)
      ? (publisher!['artist'] as String).trim()
      : ((json['user'] as Map?)?['username'] as String? ?? '').trim();
  if (artist.isEmpty) return null;
  final isrc = (publisher?['isrc'] as String?)?.trim();
  return ExternalTrack(
    title: title,
    artist: artist,
    isrc: isrc == null || isrc.isEmpty ? null : isrc,
  );
}

class SoundcloudPlaylistSource extends ExternalPlaylistSource {
  @override
  String get id => 'soundcloud';

  @override
  String get displayName => 'SoundCloud';

  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  bool get acceptsLinks => true;

  @override
  String? playlistIdFromUrl(String url) => soundcloudSetUrl(url);

  @override
  String? lastError;

  String? _clientId;

  Future<String?> _client() async {
    if (_clientId != null) return _clientId;
    final page = await fetchText(Uri.parse('https://soundcloud.com/'));
    _clientId = page == null ? null : parseSoundcloudClientId(page);
    return _clientId;
  }

  Future<Map?> _resolve(String setUrl) async {
    final client = await _client();
    if (client == null) {
      lastError = 'Could not reach SoundCloud.';
      return null;
    }
    final set = await fetchJson(
      Uri.parse('$_api/resolve').replace(
        queryParameters: {'url': setUrl, 'client_id': client},
      ),
    );
    lastError = set is Map ? null : 'SoundCloud did not return that set. It may be private.';
    return set is Map ? set : null;
  }

  @override
  Future<String?> titleFor(String playlistId) async =>
      (await _resolve(playlistId))?['title'] as String?;

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async {
    final set = await _resolve(playlistId);
    final entries = (set?['tracks'] as List? ?? const []).whereType<Map>().toList();
    final byId = <Object?, ExternalTrack>{};
    final missing = <Object?>[];
    for (final entry in entries) {
      final track = parseSoundcloudTrack(Map<String, dynamic>.from(entry));
      track == null ? missing.add(entry['id']) : byId[entry['id']] = track;
    }

    final client = await _client();
    for (var i = 0; client != null && i < missing.length; i += _batch) {
      final ids = missing.skip(i).take(_batch).join(',');
      final batch = await fetchJson(
        Uri.parse('$_api/tracks').replace(
          queryParameters: {'ids': ids, 'client_id': client},
        ),
      );
      for (final entry in (batch as List? ?? const []).whereType<Map>()) {
        final track = parseSoundcloudTrack(Map<String, dynamic>.from(entry));
        if (track != null) byId[entry['id']] = track;
      }
    }
    // Keep the set's own order.
    return [for (final entry in entries) ?byId[entry['id']]];
  }
}
