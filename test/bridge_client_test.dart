import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/bridge_client.dart';

/// Payload shapes copied from download_bridge.py rather than invented, so these
/// tests fail if the server contract drifts away from what the client expects.
void main() {
  group('BridgeStats', () {
    test('parses a live stats payload', () {
      // Mirrors get_stats() in download_bridge.py.
      final json = jsonDecode('''
      {
        "session_count": 12,
        "skipped": 3,
        "active_tasks": {
          "Calvin Harris - Summer": {
            "progress": 45,
            "status": "Downloading…",
            "indeterminate": false
          }
        },
        "incoming_queue": ["Radiohead - Nude"],
        "incoming_queue_total": 1,
        "queued_tasks": ["Tame Impala - Let It Happen"],
        "queued_tasks_total": 7,
        "success_list": ["Frank Ocean - Nights"],
        "tagger_running": false,
        "discovery_running": true
      }
      ''') as Map<String, dynamic>;

      final stats = BridgeStats.fromJson(json);
      expect(stats.sessionCount, 12);
      expect(stats.skipped, 3);
      expect(stats.activeTasks, hasLength(1));
      expect(stats.activeTasks.first.query, 'Calvin Harris - Summer');
      expect(stats.activeTasks.first.progress, 45);
      expect(stats.activeTasks.first.indeterminate, isFalse);
      expect(stats.discoveryRunning, isTrue);
      expect(stats.isIdle, isFalse);
    });

    test('queue totals are trusted over the capped preview lists', () {
      // The server caps preview lists at 50 but reports true totals, so a
      // client must never infer "how many are queued" from list length.
      final stats = BridgeStats.fromJson({
        'queued_tasks': List.generate(50, (i) => 'track $i'),
        'queued_tasks_total': 312,
      });
      expect(stats.queuedTasks, hasLength(50));
      expect(stats.queuedTasksTotal, 312);
    });

    test('an empty payload is idle rather than a crash', () {
      final stats = BridgeStats.fromJson({});
      expect(stats.isIdle, isTrue);
      expect(stats.activeTasks, isEmpty);
      expect(stats.sessionCount, 0);
    });

    test('idle requires every worker to be quiet', () {
      expect(BridgeStats.fromJson({'tagger_running': true}).isIdle, isFalse);
      expect(BridgeStats.fromJson({'incoming_queue_total': 2}).isIdle, isFalse);
    });
  });

  group('DiscoverTrack', () {
    test('parses a resolved track', () {
      final track = DiscoverTrack.fromJson({
        'title': 'Nights',
        'artist': 'Frank Ocean',
        'album': 'Blonde',
        'artwork': 'https://example.test/a.jpg',
        'apple_url': 'https://music.apple.com/us/album/1',
        'warning': null,
      });
      expect(track.isResolved, isTrue);
      expect(track.warning, isNull);
      expect(track.toPayload()['apple_url'], isNotNull);
    });

    test('an unmatched track is flagged, not silently downloadable', () {
      // _resolve_apple_track returning None is the normal failure mode.
      final track = DiscoverTrack.fromJson({
        'title': 'Obscure B-Side',
        'artist': 'Nobody',
        'album': null,
        'apple_url': null,
        'warning': 'Could not match to Apple Music catalog',
      });
      expect(track.isResolved, isFalse);
      expect(track.warning, isNotNull);
      expect(track.toPayload().containsKey('apple_url'), isFalse);
    });
  });

  group('DiscoverPlaylist', () {
    test('parses the createdfor shape', () {
      final playlist = DiscoverPlaylist.fromJson({
        'mbid': 'abc-123',
        'title': 'Weekly Exploration',
        'last_modified': '2026-09-01',
      });
      expect(playlist.mbid, 'abc-123');
      expect(playlist.title, 'Weekly Exploration');
    });

    test('a missing title degrades rather than throwing', () {
      expect(DiscoverPlaylist.fromJson({'mbid': 'x'}).title, 'Untitled');
    });
  });

  group('BridgeClient address handling', () {
    test('a bare host:port is assumed to be http', () async {
      // Users type "192.168.1.10:5006" or a Tailscale name, not a URL. The
      // address here is TEST-NET-1 (RFC 5737), reserved for documentation and
      // guaranteed not to route, so this test can never reach a real host --
      // least of all somebody's live download bridge.
      final client = BridgeClient('192.0.2.1:5006');
      expect(await client.ping(), isFalse); // must fail closed, not throw
    });

    test('an empty address fails fast instead of hanging', () async {
      expect(await BridgeClient('').ping(), isFalse);
    });
  });
}
