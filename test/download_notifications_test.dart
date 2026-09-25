import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/archive_service.dart';
import 'package:soiboi/base/services/download_notifications.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/notification_service.dart';

/// Records what would be on screen.
class _Backend implements NotificationBackend {
  final shown = <String, AppNotification>{};
  final tapController = StreamController<NotificationTap>.broadcast();

  @override
  Future<void> show(String key, AppNotification notification) async =>
      shown[key] = notification;

  @override
  Future<void> dismiss(String key) async => shown.remove(key);

  @override
  Stream<NotificationTap> get taps => tapController.stream;

  @override
  Future<bool> requestPermission() async => true;
}

/// A queue whose downloads finish when told to, failing for urls in [fail].
({DownloadQueueManager queue, Map<String, Completer<void>> gates}) _queue({
  Set<String> fail = const {},
}) {
  final gates = <String, Completer<void>>{};
  final queue = DownloadQueueManager()
    ..sync = () async {}
    ..stopActive = () async {
      for (final gate in gates.values) {
        if (!gate.isCompleted) gate.complete();
      }
    }
    ..archive =
        (
          url, {
          bool redownload = false,
          void Function(int, String)? onProgress,
          void Function(String)? onLog,
          void Function(TrackStatus)? onTrack,
          String? logPath,
        }) async {
          onProgress?.call(50, 'Downloading');
          await (gates[url] ??= Completer<void>()).future;
          return fail.contains(url) ? const DownloadFailure('boom') : null;
        };
  return (queue: queue, gates: gates);
}

DownloadRequest _request(String url) =>
    DownloadRequest(url: url, label: 'Song $url');

void main() {
  late _Backend backend;
  late NotificationService service;

  setUp(() {
    backend = _Backend();
    service = NotificationService(
      backend: backend,
      enabled: ValueNotifier(true),
      log: (_) {},
    );
  });

  test('shows the run\'s progress, then a summary', () async {
    final (:queue, :gates) = _queue();
    final notifier = DownloadNotifier(queue, service)..start();
    addTearDown(notifier.stop);

    final batch = queue.enqueue([_request('a'), _request('b')]);
    await pumpEventQueue();
    var progress = backend.shown[DownloadNotifier.progressKey]!;
    expect(progress.title, 'Downloading 1 of 2');
    expect(progress.body, 'Song a · Downloading');
    expect(progress.progress, 25);
    expect(progress.actions.map((a) => a.id), ['pause', 'stop']);

    gates['a']!.complete();
    await pumpEventQueue();
    progress = backend.shown[DownloadNotifier.progressKey]!;
    expect(progress.title, 'Downloading 2 of 2');

    gates['b']!.complete();
    await batch.done;
    await pumpEventQueue();
    expect(backend.shown.containsKey(DownloadNotifier.progressKey), isFalse);
    final summary = backend.shown[DownloadNotifier.resultKey]!;
    expect(summary.title, 'Downloads finished');
    expect(summary.body, '2 downloaded');
    expect(summary.actions, isEmpty);
  });

  test('pause and resume from the notification', () async {
    final (:queue, :gates) = _queue();
    final notifier = DownloadNotifier(queue, service)..start();
    addTearDown(notifier.stop);

    queue.enqueue([_request('a')]);
    await pumpEventQueue();
    backend.tapController.add(
      const NotificationTap(DownloadNotifier.progressKey, 'pause'),
    );
    await pumpEventQueue();
    expect(queue.paused.value, isTrue);
    final paused = backend.shown[DownloadNotifier.progressKey]!;
    expect(paused.title, 'Downloads paused');
    expect(paused.actions.first.id, 'resume');

    gates.clear(); // the requeued job waits on a fresh gate
    backend.tapController.add(
      const NotificationTap(DownloadNotifier.progressKey, 'resume'),
    );
    await pumpEventQueue();
    expect(queue.paused.value, isFalse);
    expect(backend.shown[DownloadNotifier.progressKey]!.title, 'Downloading');
  });

  test('failures are summarised and can be retried from there', () async {
    final (:queue, :gates) = _queue(fail: {'b'});
    final notifier = DownloadNotifier(queue, service)..start();
    addTearDown(notifier.stop);

    final batch = queue.enqueue([_request('a'), _request('b')]);
    await pumpEventQueue();
    gates['a']!.complete();
    await pumpEventQueue();
    gates['b']!.complete();
    await batch.done;
    await pumpEventQueue();

    final summary = backend.shown[DownloadNotifier.resultKey]!;
    expect(summary.title, 'Downloads finished with problems');
    expect(summary.body, startsWith('1 downloaded, 1 failed (Song b: '));
    expect(summary.actions.single.id, 'retry');

    gates.clear();
    backend.tapController.add(
      const NotificationTap(DownloadNotifier.resultKey, 'retry'),
    );
    await pumpEventQueue();
    expect(batch.jobs[1].state, DownloadJobState.running);
    expect(backend.shown.containsKey(DownloadNotifier.resultKey), isFalse);
  });

  test('progress stays up with notifications off only where asked', () async {
    final enabled = ValueNotifier(false);
    service = NotificationService(backend: backend, enabled: enabled);
    final (:queue, :gates) = _queue();
    final notifier = DownloadNotifier(queue, service, alwaysShowProgress: true)
      ..start();
    addTearDown(notifier.stop);

    queue.enqueue([_request('a')]);
    await pumpEventQueue();
    expect(backend.shown.containsKey(DownloadNotifier.progressKey), isTrue);
    gates['a']!.complete();
  });
}
