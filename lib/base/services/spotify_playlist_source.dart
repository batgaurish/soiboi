/// Spotify playlists, by link, without an account.
///
/// Spotify's own API needs a client id and secret, which this project ruled
/// out — that is the reason Spotify was rejected the first time it came up.
/// The embed player does not: `open.spotify.com/embed/playlist/<id>` is a
/// public page that ships its own data as JSON in a `__NEXT_DATA__` script
/// tag, including every track's title and artists. That is enough to match
/// against Apple's catalog, and it needs no key, no token and no sign-in.
///
/// What it does not give is ISRC, so these tracks resolve by artist+title
/// keyword search rather than by exact code. [DeezerPlaylistSource] does
/// carry ISRCs and matches more accurately; if a playlist exists on both,
/// the Deezer link is the better one to paste.
///
/// Nothing is downloaded from Spotify — the link supplies names, and the
/// audio still comes from Apple Music.
///
/// The tradeoff worth stating: this reads a page meant for embedding rather
/// than a documented API, so Spotify can change its shape without notice. It
/// fails to an empty list, never a crash, and the parsing is isolated in
/// [parseSpotifyEmbed] so a shape change is one function to fix.
library;

import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:soiboi/base/services/external_playlist_source.dart';
import 'package:soiboi/base/services/logger.dart';

/// The playlist id in a Spotify URL, or null.
///
/// Handles the three shapes people paste: the web link, the embed link, and
/// a `spotify:playlist:<id>` URI. A trailing `?si=…` share token is ignored,
/// which is what `Uri` already does by keeping it in the query.
String? spotifyPlaylistId(String input) {
  final trimmed = input.trim();
  if (trimmed.isEmpty) return null;

  final uriForm = RegExp(r'^spotify:playlist:([A-Za-z0-9]+)$').firstMatch(trimmed);
  if (uriForm != null) return uriForm.group(1);

  final uri = Uri.tryParse(trimmed);
  if (uri == null) return null;
  if (!hostMatches(uri.host, 'spotify.com')) return null;

  final segments = uri.pathSegments;
  final index = segments.indexOf('playlist');
  if (index == -1 || index + 1 >= segments.length) return null;
  final id = segments[index + 1];
  return RegExp(r'^[A-Za-z0-9]+$').hasMatch(id) ? id : null;
}

class SpotifyPlaylistSource extends ExternalPlaylistSource {
  @override
  String get id => 'spotify';

  @override
  String get displayName => 'Spotify';

  /// Nothing to offer unprompted: with no account there is no "your"
  /// playlist to list, only whichever one a link points at.
  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  bool get acceptsLinks => true;

  @override
  String? playlistIdFromUrl(String url) => spotifyPlaylistId(url);

  Future<SpotifyEmbed?> _fetch(String playlistId) async {
    try {
      final response = await http
          .get(
            Uri.parse('https://open.spotify.com/embed/playlist/$playlistId'),
            // The embed page varies its markup by client; a browser agent is
            // what gets the __NEXT_DATA__ shape this parses.
            headers: const {'User-Agent': 'Mozilla/5.0'},
          )
          .timeout(const Duration(seconds: 20));
      if (response.statusCode != 200) return null;
      return parseSpotifyEmbed(response.body);
    } catch (e) {
      logger.output('spotify: $e');
      return null;
    }
  }

  @override
  Future<String?> titleFor(String playlistId) async =>
      (await _fetch(playlistId))?.title;

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async =>
      (await _fetch(playlistId))?.tracks ?? const [];
}

/// A playlist as the embed page describes it.
class SpotifyEmbed {
  const SpotifyEmbed({required this.title, required this.tracks});

  final String? title;
  final List<ExternalTrack> tracks;
}

/// Pulls the playlist out of an embed page's `__NEXT_DATA__` blob.
///
/// Separated from the fetch so the parsing — the part that breaks when
/// Spotify changes the page — is testable against a captured fixture.
SpotifyEmbed? parseSpotifyEmbed(String html) {
  final match = RegExp(
    r'<script id="__NEXT_DATA__" type="application/json">(.*?)</script>',
    dotAll: true,
  ).firstMatch(html);
  if (match == null) return null;

  try {
    final data = jsonDecode(match.group(1)!);
    if (data is! Map) return null;
    final entity = data['props']?['pageProps']?['state']?['data']?['entity'];
    if (entity is! Map) return null;

    final list = entity['trackList'];
    return SpotifyEmbed(
      title: entity['name'] as String?,
      tracks: [
        if (list is List)
          for (final item in list.whereType<Map>()) ?parseSpotifyTrack(item),
      ],
    );
  } catch (_) {
    // A shape change should cost this import, not the app.
    return null;
  }
}

/// One `trackList` entry as an [ExternalTrack], or null if unusable.
///
/// The embed calls them `title` and `subtitle`; subtitle is the artist line,
/// which for a collaboration is comma-separated. It is passed through whole
/// rather than split, because Apple's catalog lists those tracks under the
/// same joined credit.
ExternalTrack? parseSpotifyTrack(Map<dynamic, dynamic> json) {
  final title = _normalise(json['title'] as String?);
  final artist = _normalise(json['subtitle'] as String?);
  if (title == null || title.isEmpty || artist == null || artist.isEmpty) {
    return null;
  }
  // The embed carries no ISRC, so these match by keyword. Deezer's API does
  // carry one, and is the better link to paste when a playlist is on both.
  return ExternalTrack(title: title, artist: artist);
}

/// Trims and collapses whitespace, including the non-breaking spaces the
/// embed separates collaborating artists with.
///
/// Left in, `KAROL G,\u00a0Judeline` is a different string from what a
/// keyword search against Apple's catalog will match, and the track quietly
/// fails to resolve.
String? _normalise(String? value) {
  if (value == null) return null;
  final cleaned = value
      .replaceAll('\u00a0', ' ')
      .replaceAll(RegExp(r'\s+'), ' ')
      .trim();
  return cleaned.isEmpty ? null : cleaned;
}
