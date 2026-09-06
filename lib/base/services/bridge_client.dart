/// Client for the download-bridge service.
///
/// download_bridge.py owns the archival pipeline — gamdl for the download,
/// ffmpeg for transcoding, mutagen for tags, plus dedupe and canonical album
/// resolution. None of that is portable to Android (real filesystem, native
/// deps, a Widevine CDM), so it stays a self-hosted Docker service and this app
/// talks to it over LAN or Tailscale.
///
/// Everything here is therefore *remote and optional*. The player is fully
/// usable with no bridge configured — files reach the device through Syncthing
/// and play locally. Nothing in this file may ever be on a playback path.
library;

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:soiboi/base/services/logger.dart';

class BridgeException implements Exception {
  BridgeException(this.message);
  final String message;
  @override
  String toString() => message;
}

/// One track being downloaded right now.
class BridgeTask {
  BridgeTask({
    required this.query,
    required this.progress,
    required this.status,
    required this.indeterminate,
  });

  /// The bridge keys active tasks by query string, usually "Artist - Title".
  final String query;

  /// 0-100. Meaningless while [indeterminate] is set.
  final int progress;
  final String status;
  final bool indeterminate;

  factory BridgeTask.fromEntry(String query, Map<String, dynamic> json) =>
      BridgeTask(
        query: query,
        progress: (json['progress'] as num?)?.round() ?? 0,
        status: json['status'] as String? ?? '',
        indeterminate: json['indeterminate'] as bool? ?? false,
      );
}

class BridgeStats {
  BridgeStats({
    required this.sessionCount,
    required this.skipped,
    required this.activeTasks,
    required this.incomingQueue,
    required this.incomingQueueTotal,
    required this.queuedTasks,
    required this.queuedTasksTotal,
    required this.successList,
    required this.taggerRunning,
    required this.discoveryRunning,
  });

  final int sessionCount;
  final int skipped;
  final List<BridgeTask> activeTasks;

  /// Queue previews are capped by the server at 50; the totals are the truth.
  final List<String> incomingQueue;
  final int incomingQueueTotal;
  final List<String> queuedTasks;
  final int queuedTasksTotal;
  final List<String> successList;
  final bool taggerRunning;
  final bool discoveryRunning;

  bool get isIdle =>
      activeTasks.isEmpty &&
      queuedTasksTotal == 0 &&
      incomingQueueTotal == 0 &&
      !taggerRunning &&
      !discoveryRunning;

  factory BridgeStats.fromJson(Map<String, dynamic> json) {
    final rawTasks = json['active_tasks'] as Map<String, dynamic>? ?? {};
    return BridgeStats(
      sessionCount: (json['session_count'] as num?)?.round() ?? 0,
      skipped: (json['skipped'] as num?)?.round() ?? 0,
      activeTasks: rawTasks.entries
          .map(
            (e) => BridgeTask.fromEntry(
              e.key,
              (e.value as Map).cast<String, dynamic>(),
            ),
          )
          .toList(),
      incomingQueue: _stringList(json['incoming_queue']),
      incomingQueueTotal: (json['incoming_queue_total'] as num?)?.round() ?? 0,
      queuedTasks: _stringList(json['queued_tasks']),
      queuedTasksTotal: (json['queued_tasks_total'] as num?)?.round() ?? 0,
      successList: _stringList(json['success_list']),
      taggerRunning: json['tagger_running'] as bool? ?? false,
      discoveryRunning: json['discovery_running'] as bool? ?? false,
    );
  }
}

/// A weekly playlist generated for the user by ListenBrainz.
class DiscoverPlaylist {
  DiscoverPlaylist({required this.mbid, required this.title, this.lastModified});

  final String mbid;
  final String title;
  final String? lastModified;

  factory DiscoverPlaylist.fromJson(Map<String, dynamic> json) =>
      DiscoverPlaylist(
        mbid: json['mbid'] as String? ?? '',
        title: json['title'] as String? ?? 'Untitled',
        lastModified: json['last_modified'] as String?,
      );
}

/// A track within a discovery playlist, resolved against Apple's catalog.
class DiscoverTrack {
  DiscoverTrack({
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

  /// Set when the bridge could not match this to the Apple catalog, or matched
  /// it with reservations. Such a track can't be downloaded reliably, so the
  /// UI surfaces this rather than letting it fail silently later.
  final String? warning;

  bool get isResolved => appleUrl != null && appleUrl!.isNotEmpty;

  factory DiscoverTrack.fromJson(Map<String, dynamic> json) => DiscoverTrack(
    title: json['title'] as String? ?? '',
    artist: json['artist'] as String? ?? '',
    album: json['album'] as String?,
    artwork: json['artwork'] as String?,
    previewUrl: json['preview_url'] as String?,
    appleUrl: json['apple_url'] as String?,
    warning: json['warning'] as String?,
  );

  Map<String, dynamic> toPayload() => {
    'title': title,
    'artist': artist,
    if (album != null) 'album': album,
    if (appleUrl != null) 'apple_url': appleUrl,
  };
}

List<String> _stringList(dynamic raw) =>
    raw is List ? raw.map((e) => e.toString()).toList() : const [];

class BridgeClient {
  BridgeClient(this.baseUrl);

  /// e.g. http://192.168.1.10:5006, or a Tailscale name.
  final String baseUrl;

  String get _root {
    var url = baseUrl.trim();
    if (url.isEmpty) throw BridgeException('No server address configured');
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      url = 'http://$url';
    }
    return url.endsWith('/') ? url.substring(0, url.length - 1) : url;
  }

  Future<dynamic> _get(String path, {Duration? timeout}) async {
    final uri = Uri.parse('$_root$path');
    try {
      final resp = await http
          .get(uri)
          .timeout(timeout ?? const Duration(seconds: 10));
      if (resp.statusCode != 200) {
        throw BridgeException(_describe(resp.statusCode, resp.body));
      }
      return jsonDecode(utf8.decode(resp.bodyBytes));
    } on TimeoutException {
      throw BridgeException('Server did not respond');
    } on BridgeException {
      rethrow;
    } catch (e) {
      logger.output('bridge GET $path: $e');
      throw BridgeException('Could not reach the server');
    }
  }

  Future<dynamic> _post(
    String path, {
    Object? body,
    Duration? timeout,
  }) async {
    final uri = Uri.parse('$_root$path');
    try {
      final resp = await http
          .post(
            uri,
            headers: const {'Content-Type': 'application/json'},
            body: body == null ? null : jsonEncode(body),
          )
          .timeout(timeout ?? const Duration(seconds: 15));
      if (resp.statusCode != 200) {
        throw BridgeException(_describe(resp.statusCode, resp.body));
      }
      return jsonDecode(utf8.decode(resp.bodyBytes));
    } on TimeoutException {
      throw BridgeException('Server did not respond');
    } on BridgeException {
      rethrow;
    } catch (e) {
      logger.output('bridge POST $path: $e');
      throw BridgeException('Could not reach the server');
    }
  }

  /// The bridge reports its own errors as {"status":"error","message":...};
  /// surfacing that beats a bare status code.
  String _describe(int status, String body) {
    try {
      final decoded = jsonDecode(body);
      if (decoded is Map && decoded['message'] is String) {
        return decoded['message'] as String;
      }
    } catch (_) {}
    return 'Server returned $status';
  }

  /// Cheap liveness check used by the settings screen.
  Future<bool> ping() async {
    try {
      await _get('/api/stats', timeout: const Duration(seconds: 5));
      return true;
    } catch (_) {
      return false;
    }
  }

  Future<BridgeStats> stats() async {
    final json = await _get('/api/stats');
    return BridgeStats.fromJson((json as Map).cast<String, dynamic>());
  }

  Future<List<String>> playlists() async {
    final json = await _get('/api/playlists');
    return _stringList((json as Map)['playlists']);
  }

  Future<List<String>> addPlaylist(String url) async {
    final json = await _post('/api/playlists', body: {
      'action': 'add',
      'url': url,
    });
    return _stringList((json as Map)['playlists']);
  }

  Future<List<String>> removePlaylist(String url) async {
    final json = await _post('/api/playlists', body: {
      'action': 'remove',
      'url': url,
    });
    return _stringList((json as Map)['playlists']);
  }

  /// Kicks off the archival run over every configured playlist.
  Future<void> startWorkflow() => _post('/api/start_workflow');

  Future<List<DiscoverPlaylist>> discoverPlaylists() async {
    final json = await _get(
      '/api/discover/playlists',
      timeout: const Duration(seconds: 20),
    );
    final map = (json as Map).cast<String, dynamic>();
    if (map['status'] == 'error') {
      throw BridgeException(map['message'] as String? ?? 'Discovery failed');
    }
    return (map['playlists'] as List? ?? [])
        .map((e) => DiscoverPlaylist.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  /// Resolving a playlist hits the Apple catalog once per track, so this is
  /// slow by nature — the server itself allows up to 90 seconds.
  Future<List<DiscoverTrack>> discoverTracks(String mbid) async {
    final json = await _get(
      '/api/discover/playlist/$mbid/tracks',
      timeout: const Duration(seconds: 90),
    );
    final map = (json as Map).cast<String, dynamic>();
    if (map['status'] == 'error') {
      throw BridgeException(map['message'] as String? ?? 'Could not load tracks');
    }
    return (map['tracks'] as List? ?? [])
        .map((e) => DiscoverTrack.fromJson((e as Map).cast<String, dynamic>()))
        .toList();
  }

  Future<void> downloadTracks(
    List<DiscoverTrack> tracks, {
    bool? lossless,
  }) async {
    await _post('/api/discover/download', body: {
      'tracks': tracks.map((t) => t.toPayload()).toList(),
      // Omitted entirely when null, so the server keeps its configured default
      // rather than being told "not lossless".
      'lossless': ?lossless,
    });
  }

  Future<Map<String, dynamic>> settings() async {
    final json = await _get('/api/settings');
    return (json as Map).cast<String, dynamic>();
  }

  Future<Map<String, dynamic>> updateSettings(
    Map<String, dynamic> partial,
  ) async {
    final json = await _post('/api/settings', body: partial);
    return (json as Map).cast<String, dynamic>();
  }
}
