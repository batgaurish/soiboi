/// The signed-in account's own Apple Music playlists.
///
/// Importing used to mean pasting one URL at a time, which makes little sense
/// for an app that is already authenticated as that account: the cookies the
/// downloader uses are exactly what Apple's personalised endpoints want, so
/// the library can simply be listed.
///
/// Archiving splits on whether Apple has a catalog copy of the playlist:
///
///  * **Catalog-backed** playlists carry a `pl.*` id, which is an ordinary
///    catalog URL — one request, and the downloader handles the whole thing
///    including order and artwork.
///  * **Library-only** playlists (made by the user, never published) have no
///    such id and no URL to hand over, so their tracks are listed and queued
///    one at a time. A track the user uploaded themselves has no catalog
///    entry at all and cannot be fetched; those are reported, not silently
///    dropped.
library;

import 'package:soiboi/base/services/cookie_store.dart' as cookie_store;
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';

/// One playlist in the account's library.
class ApplePlaylist {
  const ApplePlaylist({
    required this.libraryId,
    required this.name,
    this.catalogId,
    this.trackCount,
    this.artworkUrl,
    this.description,
  });

  final String libraryId;
  final String name;

  /// The `pl.*` catalog id, when Apple has a published copy. Null means this
  /// playlist exists only in the user's library.
  final String? catalogId;

  final int? trackCount;
  final String? artworkUrl;
  final String? description;

  /// Whether this can be archived as a single URL.
  bool get hasCatalogUrl => catalogId != null && catalogId!.isNotEmpty;

  factory ApplePlaylist.fromJson(Map<String, dynamic> json) => ApplePlaylist(
    libraryId: json['library_id'] as String? ?? '',
    name: json['name'] as String? ?? 'Untitled playlist',
    catalogId: json['catalog_id'] as String?,
    trackCount: (json['track_count'] as num?)?.toInt(),
    artworkUrl: json['artwork_url'] as String?,
    description: json['description'] as String?,
  );
}

/// One track of a library-only playlist.
class AppleLibraryTrack {
  const AppleLibraryTrack({
    required this.title,
    this.artist,
    this.album,
    this.catalogId,
  });

  final String title;
  final String? artist;
  final String? album;

  /// Null for a track the user uploaded themselves: there is no catalog entry
  /// to download, so it cannot be archived.
  final String? catalogId;

  bool get archivable => catalogId != null && catalogId!.isNotEmpty;

  factory AppleLibraryTrack.fromJson(Map<String, dynamic> json) =>
      AppleLibraryTrack(
        title: json['title'] as String? ?? 'Unknown track',
        artist: json['artist'] as String?,
        album: json['album'] as String?,
        catalogId: json['catalog_id'] as String?,
      );
}

class ApplePlaylistsResult {
  const ApplePlaylistsResult({
    this.playlists = const [],
    this.storefront = 'us',
    this.error,
    this.needsSignIn = false,
  });

  final List<ApplePlaylist> playlists;

  /// Needed to build catalog URLs: a playlist id is only resolvable inside
  /// the storefront the account belongs to.
  final String storefront;

  final String? error;

  /// The session is gone or was never there, so the fix is signing in rather
  /// than retrying.
  final bool needsSignIn;
}

/// Lists the account's playlists.
///
/// Never throws: this runs off a tap on the Downloads screen.
Future<ApplePlaylistsResult> fetchApplePlaylists() async {
  try {
    String? error;
    var needsSignIn = false;
    ApplePlaylistsResult? result;

    await for (final event in pipelineRunner.run('apple_playlists', {
      'cookies_path': cookie_store.cookiesPath,
    })) {
      if (event.isError) {
        error = event.message;
        needsSignIn = event.code == 'no_cookies' || event.code == 'auth';
      } else if (event.isDone) {
        final raw = (event.raw['playlists'] as List?) ?? const [];
        result = ApplePlaylistsResult(
          playlists: [
            for (final item in raw.whereType<Map>())
              ApplePlaylist.fromJson(Map<String, dynamic>.from(item)),
          ],
          storefront: event.raw['storefront'] as String? ?? 'us',
        );
      }
    }

    return result ??
        ApplePlaylistsResult(
          error: error ?? 'Could not read your Apple Music library',
          needsSignIn: needsSignIn,
        );
  } catch (e) {
    return ApplePlaylistsResult(error: '$e');
  }
}

/// The tracks of a library-only playlist.
Future<List<AppleLibraryTrack>> fetchApplePlaylistTracks(
  String libraryId,
) async {
  try {
    final tracks = <AppleLibraryTrack>[];
    await for (final event in pipelineRunner.run('apple_playlist_tracks', {
      'cookies_path': cookie_store.cookiesPath,
      'library_id': libraryId,
    })) {
      if (event.isDone) {
        for (final item in ((event.raw['tracks'] as List?) ?? const [])
            .whereType<Map>()) {
          tracks.add(AppleLibraryTrack.fromJson(Map<String, dynamic>.from(item)));
        }
      }
    }
    return tracks;
  } catch (_) {
    return const [];
  }
}

/// A catalog URL for [id], which the downloader accepts directly.
String catalogPlaylistUrl(String id, String storefront) =>
    'https://music.apple.com/$storefront/playlist/playlist/$id';

String catalogSongUrl(String id, String storefront) =>
    'https://music.apple.com/$storefront/song/song/$id';

/// What queueing a playlist actually did, so the screen can be honest about
/// tracks it could not take.
class AppleImportOutcome {
  const AppleImportOutcome({
    required this.batch,
    this.queued = 0,
    this.skipped = 0,
    this.error,
  });

  final DownloadBatch? batch;
  final int queued;

  /// Tracks with no catalog entry — user uploads, which cannot be fetched.
  final int skipped;

  final String? error;
}

/// Queues [playlist] for archiving.
///
/// One request when Apple has a catalog copy, otherwise one per track.
Future<AppleImportOutcome> archiveApplePlaylist(
  ApplePlaylist playlist,
  String storefront,
) async {
  if (playlist.hasCatalogUrl) {
    final batch = downloadQueue.enqueue([
      DownloadRequest(
        url: catalogPlaylistUrl(playlist.catalogId!, storefront),
        label: playlist.name,
        subtitle: 'Apple Music playlist',
      ),
    ]);
    return AppleImportOutcome(batch: batch, queued: 1);
  }

  final tracks = await fetchApplePlaylistTracks(playlist.libraryId);
  if (tracks.isEmpty) {
    return const AppleImportOutcome(
      batch: null,
      error: 'That playlist came back empty.',
    );
  }

  final archivable = tracks.where((t) => t.archivable).toList();
  if (archivable.isEmpty) {
    return AppleImportOutcome(
      batch: null,
      skipped: tracks.length,
      error: 'None of these tracks exist in the Apple catalog.',
    );
  }

  final batch = downloadQueue.enqueue([
    for (final track in archivable)
      DownloadRequest(
        url: catalogSongUrl(track.catalogId!, storefront),
        label: track.title,
        subtitle: track.artist,
      ),
  ]);
  return AppleImportOutcome(
    batch: batch,
    queued: archivable.length,
    skipped: tracks.length - archivable.length,
  );
}
