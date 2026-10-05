import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/picture_load_scheduler.dart';

void main() {
  late PictureLoadScheduler scheduler;

  setUp(() {
    scheduler = PictureLoadScheduler();
  });

  tearDown(() {
    scheduler.clear();
  });

  test('scheduler executes tasks up to maxConcurrent limit', () async {
    expect(scheduler.maxConcurrent, 6);

    int executed = 0;
    final completers = List.generate(10, (_) => Completer<void>());

    for (var i = 0; i < 10; i++) {
      final index = i;
      scheduler.load('pic_$i', () async {
        executed++;
        await completers[index].future;
      }, i);
    }

    // Give microtasks time to start
    await Future.delayed(Duration.zero);

    // Concurrency limit is 6
    expect(executed, 6);

    // Complete the first 3
    completers[0].complete();
    completers[1].complete();
    completers[2].complete();
    await Future.delayed(Duration.zero);

    // Next 3 should have been picked up from the queue
    expect(executed, 9);

    // Complete all remaining
    for (var i = 3; i < 10; i++) {
      if (!completers[i].isCompleted) completers[i].complete();
    }
    await Future.delayed(Duration.zero);
    expect(executed, 10);
  });

  test('load deduplicates tasks with the same ID', () async {
    int runCount = 0;
    final c = Completer<void>();

    final f1 = scheduler.load('same_id', () async {
      runCount++;
      await c.future;
    }, 1);

    final f2 = scheduler.load('same_id', () async {
      runCount++;
    }, 2);

    expect(identical(f1, f2), isTrue);

    c.complete();
    await f1;
    expect(runCount, 1);
  });

  test('cancel removes pending task before it runs', () async {
    final blockFirst = Completer<void>();
    int runCount = 0;

    // Fill up 6 slots to block queue
    for (var i = 0; i < 6; i++) {
      scheduler.load('block_$i', () => blockFirst.future, null);
    }

    // Enqueue 7th task
    scheduler.load('queued_task', () async {
      runCount++;
    }, 999);

    // Cancel before 7th runs
    scheduler.cancel(999);

    // Unblock the first 6
    blockFirst.complete();
    await Future.delayed(Duration.zero);

    expect(runCount, 0);
  });

  test('resetId allows the same ID to be scheduled again', () async {
    int runCount = 0;

    await scheduler.load('pic_id', () async => runCount++, null);
    expect(runCount, 1);

    scheduler.resetId('pic_id');

    await scheduler.load('pic_id', () async => runCount++, null);
    expect(runCount, 2);
  });

  test('clear cancels pending state and resets scheduler', () {
    scheduler.load('p1', () async {}, 1);
    scheduler.clear();

    // Verify scheduler can accept new work cleanly
    int done = 0;
    scheduler.load('p2', () async => done++, null);
    expect(done, 1);
  });
}
