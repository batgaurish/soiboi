/// Discovery, end to end, with no server.
///
/// Combines two public sources so the whole feature works on a device with
/// nothing configured but a ListenBrainz username:
///
///   1. ListenBrainz supplies the generated playlists and their tracks.
///      Both endpoints are public and need only a username.
///   2. Apple's iTunes Search API resolves each track to a catalog URL,
///      artwork and a 30-second preview. No key, no account.
///
/// This replaces the self-hosted bridge's discovery endpoints, which did the
/// same two steps server-side with a stored token.
///
/// Resolution is the expensive half — one network call per track — so results
/// are cached for the session and resolved lazily. A card only needs the first
/// few covers for its mosaic; the full list waits until a playlist is opened.
library;

import 'package:soiboi/base/services/apple_catalog_service.dart';
import 'package:soiboi/base/services/listenbrainz_service.dart';

/// A discovery track, with whatever Apple resolution found.
class DiscoveryTrack {
  DiscoveryTrack({
    required this.title,
    required this.artist,
    this.album,
    this.artwork,
    this.previewUrl,
    this.appleUrl,
    this.warning,
  });

  final String title;
  final String artist;
  final String? album;
  final String? artwork;
  final String? previewUrl;
  final String? appleUrl;

  /// Set when Apple has no match. Such a track cannot be archived, so the gap
  /// is surfaced rather than discovered at download time.
  final String? warning;

  bool get isResolved => appleUrl != null && appleUrl!.isNotEmpty;

  Map<String, dynamic> toPayload() => {
    'title': title,
    'artist': artist,
    if (album != null) 'album': album,
    if (appleUrl != null) 'apple_url': appleUrl,
  };
}

final Map<String, List<LbTrack>> _rawTracks = {};
final Map<String, List<DiscoveryTrack>> _resolved = {};
final Set<String> _inFlight = {};

List<DiscoveryTrack>? cachedDiscoveryTracks(String mbid) => _resolved[mbid];

/// How many Apple lookups run at once.
///
/// Resolution is one HTTP round trip per track and a weekly playlist holds
/// fifty, so doing them one at a time took over a minute — during which the
/// sheet showed the card's four-cover preview and looked like a playlist with
/// four songs in it. Six is enough to make that a few seconds without
/// hammering a public API nobody is paying for.
const _resolveConcurrency = 6;

/// Playlist tracks with Apple resolution applied.
///
/// [limit] bounds how many tracks are resolved, because each costs a request.
/// A card asking for four covers should not trigger fifty lookups.
///
/// [onProgress] is called as each batch lands, with the tracks resolved so far
/// and the playlist's true length. Without it a long playlist looks stalled:
/// the count in the sheet is whatever the card cached until everything is done.
Future<List<DiscoveryTrack>?> resolveDiscoveryTracks(
  String mbid, {
  int? limit,
  String storefront = 'us',
  void Function(List<DiscoveryTrack> resolved, int total)? onProgress,
}) async {
  final cached = _resolved[mbid];
  final full = _rawTracks[mbid]?.length;

  // A card prefetches only four covers, so a four-entry cache is a *partial*
  // result, not the playlist. Returning it for an unlimited request is what
  // made a fifty-track playlist open showing four tracks: the sheet asked for
  // everything and got the card's preview back.
  final cacheIsComplete =
      cached != null && (full == null || cached.length >= full);
  if (cached != null &&
      (limit == null ? cacheIsComplete : cached.length >= limit)) {
    return cached;
  }
  if (_inFlight.contains(mbid)) return cached;
  _inFlight.add(mbid);

  try {
    final raw = _rawTracks[mbid] ?? await discoveryTracks(mbid);
    if (raw.isEmpty) return null;
    _rawTracks[mbid] = raw;

    final slice = limit == null ? raw : raw.take(limit).toList();
    final resolved = <DiscoveryTrack>[];

    // Chunked rather than a worker pool: a chunk keeps playlist order without
    // any index bookkeeping, and order is what the user sees.
    for (var start = 0; start < slice.length; start += _resolveConcurrency) {
      final chunk = slice.skip(start).take(_resolveConcurrency);
      final matches = await Future.wait([
        for (final track in chunk)
          resolveAppleTrack(track.artist, track.title, storefront: storefront),
      ]);
      var i = 0;
      for (final track in chunk) {
        final match = matches[i++];
        resolved.add(
          DiscoveryTrack(
            title: track.title,
            artist: track.artist,
            album: match?.album,
            artwork: match?.artwork,
            previewUrl: match?.previewUrl,
            appleUrl: match?.url,
            warning: match == null
                ? 'Could not match to Apple Music catalog'
                : null,
          ),
        );
      }
      onProgress?.call(List.unmodifiable(resolved), slice.length);
    }

    // Keep the longer result: a later full resolve should not be replaced by an
    // earlier four-track preview.
    final existing = _resolved[mbid];
    if (existing == null || resolved.length > existing.length) {
      _resolved[mbid] = resolved;
    }
    return _resolved[mbid];
  } finally {
    _inFlight.remove(mbid);
  }
}

/// How many tracks a playlist has, without resolving any of them.
Future<int?> discoveryTrackCount(String mbid) async {
  final cached = _rawTracks[mbid];
  if (cached != null) return cached.length;
  final raw = await discoveryTracks(mbid);
  if (raw.isEmpty) return null;
  _rawTracks[mbid] = raw;
  return raw.length;
}
