/// Resolves tracks against Apple's catalog.
///
/// The archival pipeline needs an Apple Music URL to download anything, but
/// ListenBrainz only supplies artist and title. This bridges that gap using
/// Apple's public iTunes Search API, which needs no key and no account — so it
/// works standalone on any device, which is the whole point of the port away
/// from the self-hosted service.
///
/// The same API also answers for albums and artists, which is what makes a
/// record the user does not own browsable: an album nobody has downloaded can
/// still show its real track list, durations and 30-second previews, and each
/// track carries the URL the downloader needs.
///
/// An unresolved track keeps its warning rather than silently becoming
/// undownloadable later. Surfacing the gap is the useful behaviour: it is
/// exactly the case the archive cannot fill.
library;

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/library_match_service.dart';
import 'package:soiboi/base/services/logger.dart';

const _host = 'itunes.apple.com';
const _timeout = Duration(seconds: 12);

class AppleMatch {
  AppleMatch({
    required this.url,
    this.album,
    this.artwork,
    this.previewUrl,
    this.trackId,
  });

  final String url;
  final String? album;
  final String? artwork;
  final String? previewUrl;
  final String? trackId;
}

/// An album in Apple's catalog, with enough to describe it before download.
class AppleAlbum {
  AppleAlbum({
    required this.id,
    required this.title,
    required this.artist,
    this.url,
    this.artwork,
    this.trackCount,
    this.releaseYear,
    this.genre,
  });

  final String id;
  final String title;
  final String artist;
  final String? url;
  final String? artwork;
  final int? trackCount;
  final String? releaseYear;
  final String? genre;
}

/// One track of a catalog album.
class AppleTrack {
  AppleTrack({
    required this.title,
    required this.url,
    this.trackNumber,
    this.duration,
    this.previewUrl,
  });

  final String title;
  final String url;
  final int? trackNumber;
  final Duration? duration;
  final String? previewUrl;
}

/// Session cache. Resolution is a network round trip per track, and a discovery
/// playlist of fifty would otherwise re-resolve every time the shelf rebuilds.
final Map<String, AppleMatch?> _cache = {};
final Map<String, AppleAlbum?> _albumCache = {};
final Map<String, List<AppleTrack>?> _albumTrackCache = {};
final Map<String, List<AppleAlbum>?> _artistAlbumCache = {};

/// Joined on NUL because it cannot appear in a tag, so no two artist/title
/// pairs can collide by splitting differently.
///
/// Written as the escape rather than a literal NUL byte: the raw byte makes
/// git and grep classify this whole file as binary, so it shows no diff on
/// GitHub and silently drops out of grep results.
String _key(String artist, String title) =>
    '${artist.toLowerCase().trim()}\u0000${title.toLowerCase().trim()}';

/// Best Apple catalog match for [artist] + [title], or null.
///
/// [storefront] matters: a track available in one country may be missing in
/// another, and a URL from the wrong storefront 404s during download.
///
/// Retries on timeout: iTunes Search is rate-limited and occasionally returns
/// 0 results or times out for a track that demonstrably exists. Caching a
/// timeout or a transient empty result would make the "not found" permanent
/// for the session, when the track reappears seconds later on a retry.
Future<AppleMatch?> resolveAppleTrack(
  String artist,
  String title, {
  String? storefront,
}) async {
  storefront ??= appleStorefront;
  if (artist.trim().isEmpty || title.trim().isEmpty) return null;
  final key = '$storefront\u0000${_key(artist, title)}';
  if (_cache.containsKey(key)) return _cache[key];

  for (var attempt = 0; attempt < 2; attempt++) {
    try {
      final uri = Uri.https(_host, '/search', {
        'term': '$artist $title',
        'entity': 'song',
        // Not just the top hit: it is often a compilation's copy. See
        // [pickOriginalRelease].
        'limit': '25',
        'country': storefront,
      });
      final resp = await http.get(uri).timeout(_timeout);
      if (resp.statusCode != 200) {
        // A non-200 is transient (rate-limit, maintenance) — retry once
        // before caching null, so a momentary hiccup doesn't become a
        // permanent "not found".
        if (attempt == 0) {
          await Future.delayed(const Duration(milliseconds: 500));
          continue;
        }
        _cache[key] = null;
        return null;
      }
      final body = jsonDecode(utf8.decode(resp.bodyBytes));
      final results = (body is Map ? body['results'] : null) as List?;
      if (results == null || results.isEmpty) {
        if (attempt == 0) {
          await Future.delayed(const Duration(milliseconds: 500));
          continue;
        }
        _cache[key] = null;
        return null;
      }
      final rows = [
        for (final r in results) (r as Map).cast<String, dynamic>(),
      ];
      final first = pickOriginalRelease(rows, artist: artist, title: title);
      final url = first['trackViewUrl'] as String?;
      if (url == null) {
        _cache[key] = null;
        return null;
      }

      final match = AppleMatch(
        url: url,
        album: first['collectionName'] as String?,
        // The API returns a 100px thumbnail; asking for 300 keeps card artwork
        // from looking soft on a phone.
        artwork: (first['artworkUrl100'] as String?)?.replaceAll(
          '100x100bb',
          '300x300bb',
        ),
        previewUrl: first['previewUrl'] as String?,
        trackId: first['trackId']?.toString(),
      );
      _cache[key] = match;
      return match;
    } on TimeoutException {
      if (attempt == 0) continue; // retry once
      return null; // not cached: a timeout is worth retrying later
    } catch (e) {
      logger.output('apple search: $e');
      return null; // not cached: a parse error is worth retrying later
    }
  }
  return null;
}

/// The row for the song's own release among iTunes Search [rows].
///
/// Apple sells a song on its original album and again on every compilation
/// that licensed it ("Chai aur Baarish", "Now That's What I Call Music!"),
/// and search often ranks a compilation first. A download takes its album,
/// cover and track number from the row chosen here, so prefer, in order:
/// not a Various Artists compilation, a title without a compilation's
/// `(From "Film")` credit, an album over a single or EP, and the plain
/// edition over a deluxe or anniversary one. Ties keep Apple's order.
///
/// With [artist] and [title], rows for other songs (live takes, remixes,
/// other artists) are left out first; if none are left, Apple's top hit is
/// kept, as before.
Map<String, dynamic> pickOriginalRelease(
  List<Map<String, dynamic>> rows, {
  String? artist,
  String? title,
}) {
  var pool = rows;
  if (artist != null && title != null) {
    final wantTitle = normaliseForMatch(bareSongTitle(title));
    final wantArtist = normaliseForMatch(_mainArtist(artist));
    final same = [
      for (final row in rows)
        if (normaliseForMatch(
                  bareSongTitle(row['trackName'] as String? ?? ''),
                ) ==
                wantTitle &&
            _sameArtist(wantArtist, row['artistName'] as String? ?? ''))
          row,
    ];
    if (same.isEmpty) return rows.first;
    pool = same;
  }
  var best = pool.first;
  var bestCost = _releaseCost(best);
  for (final row in pool.skip(1)) {
    final cost = _releaseCost(row);
    if (cost < bestCost) {
      best = row;
      bestCost = cost;
    }
  }
  return best;
}

String _mainArtist(String artist) => artist
    .split(RegExp(r',|&|\bfeat\.?|\bft\.?|\bx\b', caseSensitive: false))
    .first;

bool _sameArtist(String wantMain, String candidate) {
  if (wantMain.isEmpty) return true;
  final have = normaliseForMatch(candidate);
  return have.contains(wantMain) ||
      wantMain.contains(normaliseForMatch(_mainArtist(candidate)));
}

final _editionWords = RegExp(
  r'deluxe|anniversary|expanded|remaster|special edition|bonus',
  caseSensitive: false,
);

int _releaseCost(Map<String, dynamic> row) {
  final collection = row['collectionName'] as String? ?? '';
  final track = row['trackName'] as String? ?? '';
  final collectionArtist = (row['collectionArtistName'] as String? ?? '')
      .toLowerCase();
  var cost = 0;
  if (collectionArtist == 'various artists') cost += 8;
  if (bareSongTitle(track) != track.trim() &&
      RegExp(r'from\s', caseSensitive: false).hasMatch(track)) {
    cost += 4;
  }
  if (RegExp(r'\s-\s(Single|EP)$').hasMatch(collection)) cost += 2;
  if (_editionWords.hasMatch(collection)) cost += 1;
  return cost;
}

/// Higher-resolution artwork than the API's default 100px thumbnail.
String? _artworkAt(String? url, int size) =>
    url?.replaceAll('100x100bb', '${size}x${size}bb');

Map<String, dynamic>? _firstResult(http.Response resp) {
  if (resp.statusCode != 200) return null;
  final body = jsonDecode(utf8.decode(resp.bodyBytes));
  final results = (body is Map ? body['results'] : null) as List?;
  if (results == null || results.isEmpty) return null;
  return (results.first as Map).cast<String, dynamic>();
}

AppleAlbum _albumFrom(Map<String, dynamic> json) => AppleAlbum(
  id: json['collectionId'].toString(),
  title: json['collectionName'] as String? ?? '',
  artist: json['artistName'] as String? ?? '',
  url: json['collectionViewUrl'] as String?,
  artwork: _artworkAt(json['artworkUrl100'] as String?, 600),
  trackCount: json['trackCount'] as int?,
  // Only the year: the full ISO timestamp is noise in a subtitle.
  releaseYear: (json['releaseDate'] as String?)?.split('-').first,
  genre: json['primaryGenreName'] as String?,
);

/// Best catalog match for an album, or null.
///
/// Retries once on timeout or empty result for the same reason as
/// [resolveAppleTrack]: a transient failure should not become a permanent
/// "not found" for the session.
Future<AppleAlbum?> resolveAppleAlbum(
  String artist,
  String album, {
  String? storefront,
}) async {
  storefront ??= appleStorefront;
  if (album.trim().isEmpty) return null;
  final key = '$storefront\u0000${_key(artist, album)}';
  if (_albumCache.containsKey(key)) return _albumCache[key];

  for (var attempt = 0; attempt < 2; attempt++) {
    try {
      final uri = Uri.https(_host, '/search', {
        'term': '$artist $album'.trim(),
        'entity': 'album',
        'limit': '1',
        'country': storefront,
      });
      final first = _firstResult(await http.get(uri).timeout(_timeout));
      final match = first == null || first['collectionId'] == null
          ? null
          : _albumFrom(first);
      if (match == null && attempt == 0) {
        await Future.delayed(const Duration(milliseconds: 500));
        continue;
      }
      final found = match ?? await _albumViaSongs(artist, album, storefront);
      _albumCache[key] = found;
      return found;
    } on TimeoutException {
      if (attempt == 0) continue; // retry once
      return null; // not cached: worth retrying later
    } catch (e) {
      logger.output('apple album: $e');
      return null;
    }
  }
  return null;
}

/// Album search misses some catalog albums outright (som.'s "LOVER ON RENT:
/// HEAVY DEPOSIT" returns nothing), while song search finds their tracks. So
/// look for a song on an album of that name by that artist, then fetch the
/// album itself by its id.
Future<AppleAlbum?> _albumViaSongs(
  String artist,
  String album,
  String storefront,
) async {
  String norm(String s) =>
      s.toLowerCase().replaceAll(RegExp(r'[^a-z0-9]+'), ' ').trim();
  final wantAlbum = norm(album);
  final wantArtist = norm(artist);
  final search = Uri.https(_host, '/search', {
    'term': album,
    'entity': 'song',
    'limit': '50',
    'country': storefront,
  });
  final resp = await http.get(search).timeout(_timeout);
  if (resp.statusCode != 200) return null;
  final results =
      (jsonDecode(utf8.decode(resp.bodyBytes))['results'] as List?) ?? [];
  for (final r in results.cast<Map>()) {
    final name = norm(r['collectionName'] as String? ?? '');
    final by = norm(r['artistName'] as String? ?? '');
    final artistOk =
        wantArtist.isEmpty ||
        by.contains(wantArtist) ||
        wantArtist.contains(by);
    if (name != wantAlbum || !artistOk || r['collectionId'] == null) continue;
    final lookup = Uri.https(_host, '/lookup', {
      'id': r['collectionId'].toString(),
      'country': storefront,
    });
    final first = _firstResult(await http.get(lookup).timeout(_timeout));
    return first == null ? null : _albumFrom(first);
  }
  return null;
}

/// The tracks of a catalog album, in running order.
///
/// The lookup returns the album itself as the first result and its tracks
/// after it, so the collection row is dropped rather than shown as a track.
Future<List<AppleTrack>?> appleAlbumTracks(
  String collectionId, {
  String? storefront,
}) async {
  storefront ??= appleStorefront;
  if (_albumTrackCache.containsKey(collectionId)) {
    return _albumTrackCache[collectionId];
  }
  try {
    final uri = Uri.https(_host, '/lookup', {
      'id': collectionId,
      'entity': 'song',
      'country': storefront,
    });
    final resp = await http.get(uri).timeout(_timeout);
    if (resp.statusCode != 200) return null;
    final body = jsonDecode(utf8.decode(resp.bodyBytes));
    final results = (body is Map ? body['results'] : null) as List?;
    if (results == null || results.isEmpty) return null;

    final tracks = <AppleTrack>[];
    for (final row in results) {
      final json = (row as Map).cast<String, dynamic>();
      if (json['wrapperType'] != 'track') continue;
      final url = json['trackViewUrl'] as String?;
      if (url == null) continue;
      final millis = json['trackTimeMillis'] as int?;
      tracks.add(
        AppleTrack(
          title: json['trackName'] as String? ?? '',
          url: url,
          trackNumber: json['trackNumber'] as int?,
          duration: millis == null ? null : Duration(milliseconds: millis),
          previewUrl: json['previewUrl'] as String?,
        ),
      );
    }
    _albumTrackCache[collectionId] = tracks;
    return tracks;
  } on TimeoutException {
    return null;
  } catch (e) {
    logger.output('apple album tracks: $e');
    return null;
  }
}

/// An artist's albums, newest first.
///
/// Searched by name rather than looked up by id: ListenBrainz supplies a
/// MusicBrainz id, which Apple has never heard of.
Future<List<AppleAlbum>?> appleArtistAlbums(
  String artist, {
  String? storefront,
  int limit = 25,
}) async {
  storefront ??= appleStorefront;
  if (artist.trim().isEmpty) return null;
  final key = '$storefront\u0000${_key(artist, 'albums')}';
  if (_artistAlbumCache.containsKey(key)) return _artistAlbumCache[key];

  try {
    final search = Uri.https(_host, '/search', {
      'term': artist,
      'entity': 'musicArtist',
      'limit': '1',
      'country': storefront,
    });
    final found = _firstResult(await http.get(search).timeout(_timeout));
    final artistId = found?['artistId']?.toString();
    if (artistId == null) {
      _artistAlbumCache[key] = null;
      return null;
    }

    final uri = Uri.https(_host, '/lookup', {
      'id': artistId,
      'entity': 'album',
      'limit': '$limit',
      'country': storefront,
    });
    final resp = await http.get(uri).timeout(_timeout);
    if (resp.statusCode != 200) return null;
    final body = jsonDecode(utf8.decode(resp.bodyBytes));
    final results = (body is Map ? body['results'] : null) as List?;
    if (results == null) return null;

    final albums = [
      for (final row in results)
        if ((row as Map)['wrapperType'] == 'collection')
          _albumFrom(row.cast<String, dynamic>()),
    ];
    // Newest first: an artist page opening on a decades-old debut reads as
    // stale, and the recent releases are what someone is looking for.
    albums.sort((a, b) => (b.releaseYear ?? '').compareTo(a.releaseYear ?? ''));
    _artistAlbumCache[key] = albums;
    return albums;
  } on TimeoutException {
    return null;
  } catch (e) {
    logger.output('apple artist albums: $e');
    return null;
  }
}

/// Resolve a track by its ISRC (International Standard Recording Code).
///
/// ISRC is the one identifier that bridges platforms: Spotify, YouTube Music,
/// ListenBrainz and Apple Music all tag tracks with it, and Apple's `/lookup`
/// endpoint accepts it directly. This makes it the most reliable way to
/// convert a playlist from another platform to Apple Music URLs — far more
/// accurate than artist+title keyword search, which fails on common titles,
/// remasters, and any track iTunes Search doesn't rank.
///
/// Returns null if the ISRC is not in Apple's catalog.
Future<AppleMatch?> resolveAppleTrackByIsrc(
  String isrc, {
  String? storefront,
}) async {
  storefront ??= appleStorefront;
  if (isrc.trim().isEmpty) return null;
  final key = '$storefront\u0000isrc:${isrc.toLowerCase().trim()}';
  if (_cache.containsKey(key)) return _cache[key];

  try {
    final uri = Uri.https(_host, '/lookup', {
      'isrc': isrc,
      'country': storefront,
    });
    final resp = await http.get(uri).timeout(_timeout);
    // A server error is transient, so it is not cached; only a real "no
    // such ISRC" answer below is.
    if (resp.statusCode != 200) return null;
    final body = jsonDecode(utf8.decode(resp.bodyBytes));
    final results = (body is Map ? body['results'] : null) as List?;
    if (results == null || results.isEmpty) {
      _cache[key] = null;
      return null;
    }
    // One ISRC is one recording, but Apple sells it on the original album
    // and on every compilation that licensed it; the lookup may also list a
    // collection row. Keep the tracks and prefer the original release.
    final tracks = [
      for (final row in results)
        if ((row as Map)['wrapperType'] == 'track') row.cast<String, dynamic>(),
    ];
    final trackRow = tracks.isEmpty ? null : pickOriginalRelease(tracks);
    if (trackRow == null) {
      _cache[key] = null;
      return null;
    }
    final url = trackRow['trackViewUrl'] as String?;
    if (url == null) {
      _cache[key] = null;
      return null;
    }
    final match = AppleMatch(
      url: url,
      album: trackRow['collectionName'] as String?,
      artwork: (trackRow['artworkUrl100'] as String?)?.replaceAll(
        '100x100bb',
        '300x300bb',
      ),
      previewUrl: trackRow['previewUrl'] as String?,
      trackId: trackRow['trackId']?.toString(),
    );
    _cache[key] = match;
    return match;
  } on TimeoutException {
    return null;
  } catch (e) {
    logger.output('apple isrc: $e');
    return null;
  }
}
