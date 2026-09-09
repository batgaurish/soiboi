/// Deezer playlists and tracks, by link.
///
/// Deezer publishes a read API that needs no key, no account and no OAuth —
/// the one standing constraint that ruled Spotify's own API out — and it
/// returns an **ISRC per track**. That matters more than the platform does:
/// ISRC is a exact identifier, so these tracks reach Apple's catalog through
/// `resolveAppleTrackByIsrc` rather than an artist+title guess, which is the
/// most accurate bridge this app has.
///
/// Nothing is downloaded from Deezer. The link supplies names and codes; the
/// audio still comes from Apple Music, same as every other import.
///
/// This replaces the route originally planned here. Odesli/song.link was the
/// obvious candidate — one call resolving a link across every platform — but
/// its public API now answers 401 `PUBLIC_API_ACCESS_DEPRECATED` on both
/// hosts, for every URL shape, so it is no longer a key-free option at all.
library;

import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:soiboi/base/services/external_playlist_source.dart';
import 'package:soiboi/base/services/logger.dart';

const _api = 'https://api.deezer.com';

/// The playlist id in a Deezer URL, or null.
///
/// Accepts the share links people actually paste — `deezer.com/playlist/123`,
/// a localised `deezer.com/en/playlist/123`, and `link.deezer.com` shortlinks
/// are not handled here because they need a redirect to resolve and a paste
/// of one is rare enough to not be worth the round trip.
String? deezerPlaylistId(String input) {
  final trimmed = input.trim();
  if (trimmed.isEmpty) return null;
  final uri = Uri.tryParse(trimmed);
  if (uri == null) return null;
  if (!hostMatches(uri.host, 'deezer.com')) return null;

  final segments = uri.pathSegments;
  final index = segments.indexOf('playlist');
  if (index == -1 || index + 1 >= segments.length) return null;
  final id = segments[index + 1];
  return RegExp(r'^\d+$').hasMatch(id) ? id : null;
}

class DeezerPlaylistSource extends ExternalPlaylistSource {
  @override
  String get id => 'deezer';

  @override
  String get displayName => 'Deezer';

  /// Nothing to offer unprompted: without an account there is no "your"
  /// playlist to list, only whichever one a link points at.
  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  bool get acceptsLinks => true;

  @override
  String? playlistIdFromUrl(String url) => deezerPlaylistId(url);

  Future<Map<String, dynamic>?> _fetch(String playlistId) async {
    try {
      final response = await http
          .get(Uri.parse('$_api/playlist/$playlistId'))
          .timeout(const Duration(seconds: 20));
      if (response.statusCode != 200) return null;
      final decoded = jsonDecode(response.body);
      if (decoded is! Map<String, dynamic>) return null;
      // Deezer answers 200 with an error object rather than a status code,
      // so a missing or private playlist looks like success until this.
      if (decoded['error'] != null) return null;
      return decoded;
    } catch (e) {
      logger.output('deezer: $e');
      return null;
    }
  }

  @override
  Future<String?> titleFor(String playlistId) async =>
      (await _fetch(playlistId))?['title'] as String?;

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async {
    final playlist = await _fetch(playlistId);
    if (playlist == null) return const [];
    final entries = (playlist['tracks'] as Map?)?['data'] as List?;
    if (entries == null) return const [];

    return [
      for (final entry in entries.whereType<Map>())
        ?parseDeezerTrack(Map<String, dynamic>.from(entry)),
    ];
  }
}

/// One Deezer track as an [ExternalTrack], or null if it is unusable.
///
/// Pulled out of the class so it can be tested without a network call.
ExternalTrack? parseDeezerTrack(Map<String, dynamic> json) {
  final title = (json['title'] as String?)?.trim();
  final artist = ((json['artist'] as Map?)?['name'] as String?)?.trim();
  if (title == null || title.isEmpty || artist == null || artist.isEmpty) {
    return null;
  }
  final isrc = (json['isrc'] as String?)?.trim();
  return ExternalTrack(
    title: title,
    artist: artist,
    isrc: isrc == null || isrc.isEmpty ? null : isrc,
  );
}
