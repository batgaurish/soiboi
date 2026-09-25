import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/archive_service.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';

/// A queue wired to a fake downloader, so the tests are about queue mechanics
/// rather than Apple Music, Python or the network.
DownloadQueueManager _manager({
  required Future<String?> Function(String url) archive,
  void Function()? onSync,
}) {
  final manager = DownloadQueueManager();
  manager.archive =
      (
        url, {
        bool redownload = false,
        void Function(int, String)? onProgress,
        void Function(String)? onLog,
        void Function(TrackStatus)? onTrack,
        String? logPath,
      }) async {
        onProgress?.call(50, 'Downloading');
        final message = await archive(url);
        return message == null ? null : DownloadFailure(message);
      };
  manager.sync = () async => onSync?.call();
  manager.stopActive = () async {};
  return manager;
}

DownloadRequest _request(String url) =>
    DownloadRequest(url: url, label: url, subtitle: 'Artist');

void main() {
  test('runs a batch and reports every job done', () async {
    final manager = _manager(archive: (_) async => null);

    final batch = manager.enqueue([_request('a'), _request('b')]);
    await batch.done;

    expect(batch.jobs.every((j) => j.state == DownloadJobState.done), isTrue);
    expect(batch.errors, isEmpty);
    expect(batch.succeeded.length, 2);
    expect(batch.completed.value, 2);
  });

  test('runs jobs one at a time, never concurrently', () async {
    var inFlight = 0;
    var maxInFlight = 0;
    final manager = _manager(
      archive: (_) async {
        inFlight++;
        maxInFlight = maxInFlight > inFlight ? maxInFlight : inFlight;
        await Future<void>.delayed(Duration.zero);
        inFlight--;
        return null;
      },
    );

    await manager.enqueue([_request('a'), _request('b'), _request('c')]).done;
    expect(maxInFlight, 1);
  });

  test('a failure fails only its own job and the queue carries on', () async {
    final manager = _manager(
      archive: (url) async => url == 'b' ? 'boom' : null,
    );

    final batch = manager.enqueue([
      _request('a'),
      _request('b'),
      _request('c'),
    ]);
    await batch.done;

    expect(batch.jobs[0].state, DownloadJobState.done);
    expect(batch.jobs[1].state, DownloadJobState.failed);
    expect(batch.jobs[1].error, 'boom');
    expect(batch.jobs[2].state, DownloadJobState.done);
    expect(batch.errors, ['boom']);
  });

  test(
    'a thrown exception is caught rather than stranding the queue',
    () async {
      final manager = _manager(
        archive: (url) async => url == 'a' ? throw StateError('bad') : null,
      );

      final batch = manager.enqueue([_request('a'), _request('b')]);
      await batch.done;

      expect(batch.jobs[0].state, DownloadJobState.failed);
      expect(batch.jobs[0].error, contains('bad'));
      expect(batch.jobs[1].state, DownloadJobState.done);
    },
  );

  test('retry re-runs a failed job', () async {
    var attempts = 0;
    final manager = _manager(
      archive: (_) async => ++attempts == 1 ? 'first attempt failed' : null,
    );

    final batch = manager.enqueue([_request('a')]);
    await batch.done;
    expect(batch.jobs.single.state, DownloadJobState.failed);

    manager.retry(batch.jobs.single);
    // The batch's future has already completed, so wait on the job itself.
    while (batch.jobs.single.isActive) {
      await Future<void>.delayed(Duration.zero);
    }
    expect(batch.jobs.single.state, DownloadJobState.done);
    expect(batch.jobs.single.error, isNull);
    expect(attempts, 2);
  });

  test('cancel drops a queued job but leaves the running one alone', () async {
    final gate = Completer<void>();
    final started = <String>[];
    final manager = _manager(
      archive: (url) async {
        started.add(url);
        if (url == 'a') await gate.future;
        return null;
      },
    );

    final batch = manager.enqueue([_request('a'), _request('b')]);
    await Future<void>.delayed(Duration.zero);

    // 'a' is in flight and cannot be cancelled; 'b' has not started.
    manager.cancel(batch.jobs[0]);
    manager.cancel(batch.jobs[1]);
    expect(batch.jobs[0].state, DownloadJobState.running);
    expect(batch.jobs[1].state, DownloadJobState.cancelled);

    gate.complete();
    await batch.done;
    expect(started, ['a']);
  });

  test('pause holds the queue, and resuming reruns the stopped job', () async {
    final gate = Completer<void>();
    final started = <String>[];
    final manager = _manager(
      archive: (url) async {
        started.add(url);
        if (url == 'a') await gate.future;
        return null;
      },
    );

    final batch = manager.enqueue([_request('a'), _request('b')]);
    await Future<void>.delayed(Duration.zero);
    manager.setPaused(true);
    gate.complete();
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);

    expect(started, ['a'], reason: 'the queued job must not have started');
    expect(batch.jobs[1].state, DownloadJobState.queued);

    manager.setPaused(false);
    await batch.done;
    // 'a' was stopped by the pause and requeued, so it runs again first.
    expect(started, ['a', 'a', 'b']);
  });

  test('the library is synced once per drain, not once per track', () async {
    var syncs = 0;
    final manager = _manager(archive: (_) async => null, onSync: () => syncs++);

    await manager.enqueue([_request('a'), _request('b'), _request('c')]).done;
    // The sync runs after the pump loop ends, which is after the batch
    // completes; one more turn of the event loop settles it.
    await Future<void>.delayed(Duration.zero);
    expect(syncs, 1);
  });

  test('nothing is synced when every job failed', () async {
    var syncs = 0;
    final manager = _manager(
      archive: (_) async => 'nope',
      onSync: () => syncs++,
    );

    await manager.enqueue([_request('a')]).done;
    await Future<void>.delayed(Duration.zero);
    expect(syncs, 0);
  });

  test('an empty batch completes rather than hanging its caller', () async {
    final manager = _manager(archive: (_) async => null);
    await manager.enqueue(const <DownloadRequest>[]).done;
  });

  test('a blank label falls back to the URL', () {
    final manager = _manager(archive: (_) async => null);
    final batch = manager.enqueue([
      const DownloadRequest(url: 'https://music.apple.com/x', label: '  '),
    ]);
    expect(batch.jobs.single.label, 'https://music.apple.com/x');
  });

  test('clearFinished keeps the queue and drops the history', () async {
    final manager = _manager(archive: (_) async => null);
    await manager.enqueue([_request('a')]).done;
    expect(manager.jobs.value, hasLength(1));

    manager.clearFinished();
    expect(manager.jobs.value, isEmpty);
  });

  test('dismiss drops one finished row and nothing still to run', () async {
    final manager = _manager(
      archive: (url) async => url == 'a' ? 'Media is not streamable: 1' : null,
    );
    await manager.enqueue([_request('a'), _request('b')]).done;
    final failed = manager.jobs.value.first;
    expect(failed.state, DownloadJobState.failed);

    manager.dismiss(failed);
    expect(manager.jobs.value.map((j) => j.url), ['b']);

    // A queued job is not a finished row; skipping it is what cancel is for.
    final waiting = manager.enqueue([_request('c')]).jobs.single;
    manager.setPaused(true);
    manager.dismiss(waiting);
    expect(manager.jobs.value, contains(waiting));
  });

  test('progress from the pipeline reaches the job', () async {
    final manager = _manager(archive: (_) async => null);
    final seen = <int>[];
    void listener() {
      final job = manager.jobs.value.firstOrNull;
      if (job != null) seen.add(job.progress);
    }

    manager.jobs.addListener(listener);
    await manager.enqueue([_request('a')]).done;
    manager.jobs.removeListener(listener);

    expect(seen, contains(50));
    expect(seen.last, 100);
  });

  group('stopping a running download', () {
    /// A download that runs until the pipeline is told to stop.
    DownloadQueueManager stoppable(Completer<void> started) {
      final stop = Completer<String?>();
      final manager = _manager(
        archive: (url) {
          if (!started.isCompleted) started.complete();
          return url == 'a' ? stop.future : Future.value(null);
        },
      );
      manager.stopActive = () async => stop.complete('Download stopped');
      return manager;
    }

    test('cancel stops it and the queue moves on', () async {
      final started = Completer<void>();
      final manager = stoppable(started);
      final batch = manager.enqueue([_request('a'), _request('b')]);
      await started.future;

      manager.cancel(batch.jobs[0]);
      await batch.done;

      expect(batch.jobs[0].state, DownloadJobState.cancelled);
      expect(batch.jobs[0].error, isNull);
      expect(batch.jobs[1].state, DownloadJobState.done);
    });

    test('pause stops it and puts it back in the queue', () async {
      final started = Completer<void>();
      final manager = stoppable(started);
      final batch = manager.enqueue([_request('a'), _request('b')]);
      await started.future;

      manager.setPaused(true);
      await pumpEventQueue();

      expect(batch.jobs[0].state, DownloadJobState.queued);
      expect(batch.jobs[1].state, DownloadJobState.queued);
    });

    test('stop all ends the running job and everything waiting', () async {
      final started = Completer<void>();
      final manager = stoppable(started);
      final batch = manager.enqueue([_request('a'), _request('b')]);
      await started.future;

      manager.stopAll();
      await batch.done;

      expect(
        batch.jobs.map((j) => j.state),
        everyElement(DownloadJobState.cancelled),
      );
    });
  });

  test('a stop pressed before the downloader starts is sent again', () async {
    final handover = Completer<void>();
    final stopped = Completer<void>();
    var stops = 0;
    final manager = DownloadQueueManager()
      ..sync = () async {}
      ..stopActive = () async {
        stops++;
        // Only the stop sent once the pipeline is running takes effect.
        if (handover.isCompleted && !stopped.isCompleted) stopped.complete();
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
            await handover.future; // the wrapper is still starting
            onProgress?.call(8, 'Starting'); // the pipeline clears its flag
            await stopped.future;
            return const DownloadFailure('Download stopped', code: 'cancelled');
          };

    final batch = manager.enqueue([_request('a')]);
    await pumpEventQueue();
    manager.cancel(batch.jobs.single);
    handover.complete();
    await batch.done;

    expect(stops, 2);
    final job = batch.jobs.single;
    expect(job.state, DownloadJobState.cancelled);
    expect(job.log.any((l) => l.endsWith('Stop requested')), isTrue);
    expect(job.log.last, endsWith('Stopped'));
    expect(job.log.any((l) => l.contains('Failed')), isFalse);
  });

  test('every job gets its own downloader log, kept across a retry', () async {
    final paths = <String?>[];
    final manager = DownloadQueueManager()
      ..sync = () async {}
      ..stopActive = () async {}
      ..archive =
          (
            url, {
            bool redownload = false,
            void Function(int, String)? onProgress,
            void Function(String)? onLog,
            void Function(TrackStatus)? onTrack,
            String? logPath,
          }) async {
            paths.add(logPath);
            return url == 'a' ? const DownloadFailure('boom') : null;
          };

    final batch = manager.enqueue([_request('a'), _request('b')]);
    await batch.done;
    manager.retry(batch.jobs.first);
    await pumpEventQueue();

    expect(paths, hasLength(3));
    expect(paths[0], isNot(paths[1]));
    expect(paths[2], paths[0]);
    // Not just the id: ids restart at 0 every launch.
    expect(paths[0], isNot(endsWith('/0.log')));
  });
}
