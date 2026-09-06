/// ListenBrainz statistics.
///
/// Two modes, one Home screen:
///
///  * **Not connected** — shelves are derived from local play counts. Works
///    with no network and no accounts, which is the default the app is built
///    around.
///  * **Connected** — a ListenBrainz username makes the rankings reflect
///    everything you listen to, not just what this device played.
///
/// Stats endpoints are public and need only a username, no token. That matters
/// for an offline-first player: your top artists shouldn't depend on your home
/// server being reachable, so this talks to ListenBrainz directly rather than
/// proxying through the download bridge.
///
/// Results are matched back against the local library by name. An entry you own
/// plays locally; one you don't is a gap the archival pipeline can fill, which
/// is why [LbEntry] keeps both the ranking and the local match.
library;

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/services/logger.dart';

const _host = 'api.listenbrainz.org';
const _timeout = Duration(seconds: 12);

/// Configured username. Empty means "not connected" — local-only mode.
final listenBrainzUserNotifier = ValueNotifier<String>('');

bool get listenBrainzConnected =>
    listenBrainzUserNotifier.value.trim().isNotEmpty;

/// How far back the rankings look.
enum LbRange {
  week('week', 'This week'),
  month('month', 'This month'),
  year('year', 'This year'),
  allTime('all_time', 'All time');

  const LbRange(this.api, this.label);
  final String api;
  final String label;
}

/// One ranked item, plus whatever it matched locally.
class LbEntry {
  LbEntry({
    required this.name,
    required this.listenCount,
    this.artistName,
    this.mbid,
    this.artworkUrl,
    this.localArtist,
    this.localAlbum,
  });

  final String name;
  final int listenCount;

  /// Set for releases and recordings; null for artists.
  final String? artistName;
  final String? mbid;

  /// Cover Art Archive thumbnail, when ListenBrainz knew of one.
  final String? artworkUrl;

  /// The matching local entity, when the library has it.
  final Artist? localArtist;
  final Album? localAlbum;

  /// False means you listen to this but don't own it — a gap the download
  /// pipeline could fill.
  bool get isInLibrary => localArtist != null || localAlbum != null;
}

/// Case- and punctuation-insensitive comparison, because tags and MusicBrainz
/// disagree constantly about "&" vs "and", accents, and trailing articles.
String _normalise(String value) => value
    .toLowerCase()
    .replaceAll('&', 'and')
    .replaceAll(RegExp(r'[^a-z0-9]+'), '');

Artist? _matchArtist(String name) {
  final target = _normalise(name);
  for (final artist in artistAlbumManager.artistList) {
    if (_normalise(artist.name) == target) return artist;
  }
  return null;
}

Album? _matchAlbum(String name) {
  final target = _normalise(name);
  for (final album in artistAlbumManager.albumList) {
    if (_normalise(album.name) == target) return album;
  }
  return null;
}

String? _coverArtUrl(Map<String, dynamic> item) {
  final caaId = item['caa_id'];
  final releaseMbid = item['caa_release_mbid'] as String?;
  if (caaId == null || releaseMbid == null) return null;
  return 'https://archive.org/download/mbid-$releaseMbid/mbid-$releaseMbid-$caaId'
      '_thumb250.jpg';
}

Future<List<Map<String, dynamic>>?> _fetch(
  String endpoint,
  String listKey, {
  required LbRange range,
  int count = 12,
}) async {
  final user = listenBrainzUserNotifier.value.trim();
  if (user.isEmpty) return null;
  try {
    final uri = Uri.https(_host, '/1/stats/user/$user/$endpoint', {
      'count': '$count',
      'range': range.api,
    });
    final resp = await http.get(uri).timeout(_timeout);
    // 204 means the user has no stats for this range yet — a normal state for
    // a new account, not a failure.
    if (resp.statusCode == 204) return const [];
    if (resp.statusCode != 200) {
      logger.output('listenbrainz $endpoint: HTTP ${resp.statusCode}');
      return null;
    }
    final body = jsonDecode(utf8.decode(resp.bodyBytes));
    final payload = body is Map ? body['payload'] : null;
    final list = payload is Map ? payload[listKey] : null;
    if (list is! List) return null;
    return list.whereType<Map>().map((e) => e.cast<String, dynamic>()).toList();
  } on TimeoutException {
    return null;
  } catch (e) {
    logger.output('listenbrainz $endpoint: $e');
    return null;
  }
}

/// Top artists. Null means the request failed — callers should fall back to
/// local rankings rather than showing an empty shelf.
Future<List<LbEntry>?> topArtistsFromListenBrainz({
  LbRange range = LbRange.month,
}) async {
  final items = await _fetch('artists', 'artists', range: range);
  if (items == null) return null;
  return items.map((item) {
    final name = item['artist_name'] as String? ?? '';
    return LbEntry(
      name: name,
      listenCount: (item['listen_count'] as num?)?.round() ?? 0,
      mbid: item['artist_mbid'] as String?,
      localArtist: _matchArtist(name),
    );
  }).where((e) => e.name.isNotEmpty).toList();
}

/// Top releases.
Future<List<LbEntry>?> topAlbumsFromListenBrainz({
  LbRange range = LbRange.month,
}) async {
  final items = await _fetch('releases', 'releases', range: range);
  if (items == null) return null;
  return items.map((item) {
    final name = item['release_name'] as String? ?? '';
    return LbEntry(
      name: name,
      listenCount: (item['listen_count'] as num?)?.round() ?? 0,
      artistName: item['artist_name'] as String?,
      mbid: item['release_mbid'] as String?,
      artworkUrl: _coverArtUrl(item),
      localAlbum: _matchAlbum(name),
    );
  }).where((e) => e.name.isNotEmpty).toList();
}

/// Top recordings (tracks).
Future<List<LbEntry>?> topTracksFromListenBrainz({
  LbRange range = LbRange.month,
}) async {
  final items = await _fetch('recordings', 'recordings', range: range);
  if (items == null) return null;
  return items.map((item) {
    return LbEntry(
      name: item['track_name'] as String? ?? '',
      listenCount: (item['listen_count'] as num?)?.round() ?? 0,
      artistName: item['artist_name'] as String?,
      mbid: item['recording_mbid'] as String?,
      artworkUrl: _coverArtUrl(item),
    );
  }).where((e) => e.name.isNotEmpty).toList();
}

/// Cheap check that a username exists and has stats.
Future<bool> verifyListenBrainzUser(String user) async {
  try {
    final uri = Uri.https(_host, '/1/user/$user/listen-count');
    final resp = await http.get(uri).timeout(_timeout);
    return resp.statusCode == 200;
  } catch (_) {
    return false;
  }
}

// ---------------------------------------------------------------------------
// Discovery playlists
//
// These used to come from the self-hosted bridge, which proxied ListenBrainz
// with a stored token. Both endpoints turn out to be public -- the playlists
// are marked public and need only a username -- so the app fetches them
// directly and the server is no longer involved in discovery at all.
// ---------------------------------------------------------------------------

/// A ListenBrainz-generated playlist ("Weekly Exploration", "Daily Jams"…).
class LbPlaylist {
  LbPlaylist({required this.mbid, required this.title, this.lastModified});

  final String mbid;
  final String title;
  final String? lastModified;
}

/// One track in a discovery playlist, before Apple resolution.
class LbTrack {
  LbTrack({required this.title, required this.artist, this.recordingMbid});

  final String title;
  final String artist;
  final String? recordingMbid;
}

/// Playlists ListenBrainz generated for the configured user.
///
/// Returns an empty list when no username is set, rather than throwing: not
/// being connected is an ordinary state, and the shelf simply does not appear.
Future<List<LbPlaylist>> discoveryPlaylists() async {
  final user = listenBrainzUserNotifier.value.trim();
  if (user.isEmpty) return const [];
  try {
    final uri = Uri.https(_host, '/1/user/$user/playlists/createdfor');
    final resp = await http.get(uri).timeout(_timeout);
    if (resp.statusCode != 200) return const [];
    final body = jsonDecode(utf8.decode(resp.bodyBytes));
    final entries = (body is Map ? body['playlists'] : null) as List?;
    if (entries == null) return const [];

    final playlists = <LbPlaylist>[];
    for (final entry in entries) {
      final playlist = (entry is Map ? entry['playlist'] : null) as Map?;
      if (playlist == null) continue;
      final identifier = playlist['identifier'] as String?;
      if (identifier == null) continue;
      playlists.add(
        LbPlaylist(
          // The identifier is a full URL; the mbid is its last path segment.
          mbid: identifier.replaceAll(RegExp(r'/$'), '').split('/').last,
          title: playlist['title'] as String? ?? 'Untitled',
          lastModified: (playlist['date'] ?? playlist['last_modified_at'])
              as String?,
        ),
      );
    }
    return playlists;
  } on TimeoutException {
    return const [];
  } catch (e) {
    logger.output('listenbrainz playlists: $e');
    return const [];
  }
}

/// Tracks in a discovery playlist. Artist and title only -- Apple resolution
/// happens separately, since it is a per-track network call and only needed
/// when the user actually opens a playlist.
Future<List<LbTrack>> discoveryTracks(String mbid) async {
  try {
    final uri = Uri.https(_host, '/1/playlist/$mbid');
    final resp = await http.get(uri).timeout(const Duration(seconds: 25));
    if (resp.statusCode != 200) return const [];
    final body = jsonDecode(utf8.decode(resp.bodyBytes));
    final playlist = (body is Map ? body['playlist'] : null) as Map?;
    final entries = playlist?['track'] as List?;
    if (entries == null) return const [];

    return entries.whereType<Map>().map((entry) {
      final identifiers = entry['identifier'];
      String? mbid;
      if (identifiers is List && identifiers.isNotEmpty) {
        mbid = identifiers.first.toString().split('/').last;
      } else if (identifiers is String) {
        mbid = identifiers.split('/').last;
      }
      return LbTrack(
        title: entry['title'] as String? ?? '',
        artist: entry['creator'] as String? ?? '',
        recordingMbid: mbid,
      );
    }).where((t) => t.title.isNotEmpty && t.artist.isNotEmpty).toList();
  } on TimeoutException {
    return const [];
  } catch (e) {
    logger.output('listenbrainz playlist tracks: $e');
    return const [];
  }
}
