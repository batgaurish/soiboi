/// Discovery, end to end, with no server.
///
/// Two halves, and only the first one varies:
///
///   1. An [ExternalPlaylistSource] supplies playlists and their tracks —
///      ListenBrainz's generated weeklies, or a YouTube Music link. Every
///      source here is public and needs no account.
///   2. Apple's iTunes Search API resolves each track to a catalog URL,
///      artwork and a 30-second preview. No key, no account.
///
/// The second half is the expensive, subtle one — caching, ordering, partial
/// results — so it is written once and every source shares it. That is the
/// whole reason sources were abstracted rather than copied.
///
/// This replaces the self-hosted bridge's discovery endpoints, which did the
/// same two steps server-side with a stored token.
///
/// Resolution is the expensive half — one network call per track — so results
/// are cached for the session and resolved lazily. A card only needs the first
/// few covers for its mosaic; the full list waits until a playlist is opened.
library;

import 'package:soiboi/base/services/apple_catalog_service.dart';
import 'package:soiboi/base/services/external_playlist_source.dart';

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

/// Caches keyed by `sourceId:playlistId`, never by a bare id: a hex string is
/// both a plausible MusicBrainz id and a plausible YouTube one, so a bare key
/// would let one platform's cache answer for another's.
final Map<String, List<ExternalTrack>> _rawTracks = {};
final Map<String, List<DiscoveryTrack>> _resolved = {};
final Map<String, Future<List<DiscoveryTrack>?>> _inFlight = {};

List<DiscoveryTrack>? cachedDiscoveryTracks(ExternalPlaylist playlist) =>
    _resolved[playlist.key];

/// Asks [playlist]'s source for its tracks. Empty when the source is not
/// registered, which is a programming error rather than a user-visible state.
Future<List<ExternalTrack>> _sourceTracks(ExternalPlaylist playlist) async {
  final source = sourceById(playlist.sourceId);
  if (source == null) return const [];
  return source.tracks(playlist.id);
}

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
  ExternalPlaylist playlist, {
  int? limit,
  String storefront = 'us',
  void Function(List<DiscoveryTrack> resolved, int total)? onProgress,
}) async {
  final cached = _resolved[playlist.key];
  final full = _rawTracks[playlist.key]?.length;

  // A card prefetches only four covers, so a four-entry cache is a *partial*
  // result, not the playlist. Returning it for an unlimited request is what
  // made a fifty-track playlist open showing four tracks: the sheet asked for
  // everything and got the card's preview back. The true length must be
  // known before a cache can be called complete -- treating "we haven't
  // fetched the playlist yet" as "the four-track preview is everything" was
  // the same bug wearing a different hat.
  final cacheIsComplete =
      cached != null && full != null && cached.length >= full;
  if (cached != null &&
      (limit == null ? cacheIsComplete : cached.length >= limit)) {
    return cached;
  }

  // A second caller (e.g. the sheet, wanting everything, while the card's
  // four-track prefetch is still running) must wait for that work and then
  // re-check the cache with its *own* limit -- not be handed a snapshot of
  // whatever `_resolved[playlist.key]` happens to hold right now. Recursing after
  // the wait costs nothing extra: the cache check above short-circuits if
  // the finished work already satisfies this caller.
  final inFlight = _inFlight[playlist.key];
  if (inFlight != null) {
    await inFlight;
    return resolveDiscoveryTracks(
      playlist,
      limit: limit,
      storefront: storefront,
      onProgress: onProgress,
    );
  }

  final future = _resolveAndCache(
    playlist,
    limit: limit,
    storefront: storefront,
    onProgress: onProgress,
  );
  _inFlight[playlist.key] = future;
  try {
    return await future;
  } finally {
    _inFlight.remove(playlist.key);
  }
}

Future<List<DiscoveryTrack>?> _resolveAndCache(
  ExternalPlaylist playlist, {
  int? limit,
  required String storefront,
  void Function(List<DiscoveryTrack> resolved, int total)? onProgress,
}) async {
  final raw = _rawTracks[playlist.key] ?? await _sourceTracks(playlist);
  if (raw.isEmpty) return null;
  _rawTracks[playlist.key] = raw;

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
  final existing = _resolved[playlist.key];
  if (existing == null || resolved.length > existing.length) {
    _resolved[playlist.key] = resolved;
  }
  return _resolved[playlist.key];
}

/// How many tracks a playlist has, without resolving any of them.
Future<int?> discoveryTrackCount(ExternalPlaylist playlist) async {
  final cached = _rawTracks[playlist.key];
  if (cached != null) return cached.length;
  final raw = await _sourceTracks(playlist);
  if (raw.isEmpty) return null;
  _rawTracks[playlist.key] = raw;
  return raw.length;
}
