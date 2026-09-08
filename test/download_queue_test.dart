import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';

/// A queue wired to a fake downloader, so the tests are about queue mechanics
/// rather than Apple Music, Python or the network.
DownloadQueueManager _manager({
  required Future<String?> Function(String url) archive,
  void Function()? onSync,
}) {
  final manager = DownloadQueueManager();
  manager.archive =
      (url, {bool redownload = false, void Function(int, String)? onProgress}) {
        onProgress?.call(50, 'Downloading');
        return archive(url);
      };
  manager.sync = () async => onSync?.call();
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

  test('a thrown exception is caught rather than stranding the queue', () async {
    final manager = _manager(
      archive: (url) async => url == 'a' ? throw StateError('bad') : null,
    );

    final batch = manager.enqueue([_request('a'), _request('b')]);
    await batch.done;

    expect(batch.jobs[0].state, DownloadJobState.failed);
    expect(batch.jobs[0].error, contains('bad'));
    expect(batch.jobs[1].state, DownloadJobState.done);
  });

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

  test('pause stops the queue after the job in flight', () async {
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
    expect(started, ['a', 'b']);
  });

  test('the library is synced once per drain, not once per track', () async {
    var syncs = 0;
    final manager = _manager(
      archive: (_) async => null,
      onSync: () => syncs++,
    );

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
}
