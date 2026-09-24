/// One queue for every download the app starts.
///
/// Before this, each screen that could archive something ran its own `for`
/// loop over [archiveUrl]: the Downloads form, a discovery playlist, a catalog
/// album. Three loops meant three separate ideas of what was in flight, no way
/// to see a download from anywhere else, and — worst — closing the sheet that
/// started a fifty-track playlist silently abandoned the rest of it.
///
/// Execution now lives here and the screens only enqueue. They still watch
/// their own batch for progress, so the sheets read exactly as they did; the
/// difference is that the work outlives the widget that asked for it, and the
/// queue is inspectable from one place.
///
/// Deliberately sequential, one job at a time: Apple rate-limits, and the
/// original pipeline's cooldowns exist for a reason. Parallelism here would
/// buy nothing and cost a session.
library;

import 'dart:async';
import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:path/path.dart' as p;
import 'package:soiboi/base/services/archive_service.dart';
import 'package:soiboi/base/services/error_catalog.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';

enum DownloadJobState {
  queued,
  running,

  /// Finished, file(s) on disk.
  done,

  /// The pipeline reported an error. Retryable.
  failed,

  /// Removed from the queue before it ran.
  cancelled,
}

/// One URL waiting to be, or being, archived.
class DownloadJob {
  DownloadJob({
    required this.id,
    required this.url,
    required this.label,
    this.subtitle,
  });

  final String id;
  final String url;

  /// What to call this in the queue. A track title where the caller knows one,
  /// else the URL — never blank, because a queue of unnamed rows is useless.
  final String label;

  /// Optional second line: the artist, or the album a track came from.
  final String? subtitle;

  DownloadJobState state = DownloadJobState.queued;
  int progress = 0;
  String status = '';

  /// The failure as the pipeline reported it. Kept for the log; people are
  /// shown [failure] instead.
  String? error;

  /// The pipeline's error code for [error], or empty.
  String errorCode = '';

  /// [error] in plain words, with what fixes it. Null unless failed.
  FailureExplanation? get failure => error == null
      ? null
      : describeFailure(error!, code: errorCode);

  /// What the app did for this job, timestamped: the steps before the
  /// downloader starts (wrapper, sign-in) as well as each stage it reports.
  /// A job stuck on "Starting" is stuck in one of these, and the downloader's
  /// own log would be empty.
  final log = <String>[];

  /// Notifies the log view as lines arrive.
  final logChanged = ValueNotifier(0);

  /// The downloader's raw output for this job, mirrored by the pipeline.
  String? downloaderLogPath;

  void addLog(String line) {
    final now = DateTime.now();
    String two(int n) => n.toString().padLeft(2, '0');
    log.add('${two(now.hour)}:${two(now.minute)}:${two(now.second)}  $line');
    // Bounded: a long download reports many stages, and this is kept for
    // every job in the finished history.
    if (log.length > 500) log.removeRange(0, log.length - 500);
    logChanged.value++;
  }

  bool get isTerminal =>
      state == DownloadJobState.done ||
      state == DownloadJobState.failed ||
      state == DownloadJobState.cancelled;

  bool get isActive =>
      state == DownloadJobState.queued || state == DownloadJobState.running;
}

/// What a caller asks for. Separate from [DownloadJob] so the manager owns
/// job identity and state, and callers cannot hand it a half-built job.
class DownloadRequest {
  const DownloadRequest({required this.url, required this.label, this.subtitle});

  final String url;
  final String label;
  final String? subtitle;
}

/// The jobs one `enqueue` produced, so a screen can follow its own work
/// without filtering the global queue or being confused by someone else's.
class DownloadBatch {
  DownloadBatch(this.jobs);

  final List<DownloadJob> jobs;

  /// How many of [jobs] have reached a terminal state. Drives the
  /// "Archiving 3 of 11…" labels the sheets already showed.
  final completed = ValueNotifier<int>(0);

  final _done = Completer<void>();

  /// Completes once every job in the batch is terminal — successful, failed or
  /// cancelled. Never throws: check [errors] instead, the same way the call
  /// sites already treated [archiveUrl]'s nullable return.
  Future<void> get done => _done.future;

  /// Failure messages from this batch, in job order.
  List<String> get errors =>
      jobs.map((job) => job.error).whereType<String>().toList();

  /// The same failures, explained for people.
  List<FailureExplanation> get failures =>
      jobs.map((job) => job.failure).whereType<FailureExplanation>().toList();

  /// Jobs that actually landed a file.
  List<DownloadJob> get succeeded =>
      jobs.where((job) => job.state == DownloadJobState.done).toList();

  void _refresh() {
    completed.value = jobs.where((job) => job.isTerminal).length;
    if (completed.value == jobs.length && !_done.isCompleted) {
      _done.complete();
    }
  }
}

/// How many finished jobs are kept for review before the oldest are dropped.
///
/// The screens this replaces kept every completed URL for the life of the
/// process, which grew without bound on a long archiving session. A cap is the
/// whole fix: nobody scrolls past the last few dozen.
const _historyLimit = 60;

class DownloadQueueManager {
  DownloadQueueManager();

  final _jobs = <DownloadJob>[];
  final _batches = <DownloadBatch>[];

  /// The queue, newest-enqueued last. Replaced (not mutated) on every change
  /// so a [ValueListenableBuilder] actually rebuilds.
  final jobs = ValueNotifier<List<DownloadJob>>(const []);

  /// Set while a job is being downloaded, so screens can show one bar without
  /// scanning the list.
  final active = ValueNotifier<DownloadJob?>(null);

  /// Whether the queue is holding. Pausing stops the job in flight too and
  /// puts it back at the front, so resuming picks it up again. Tracks it had
  /// already finished stay on disk and are skipped on the retry.
  final paused = ValueNotifier<bool>(false);

  /// Seams for tests. Real downloads need a signed-in session, a Python
  /// runtime and the network; none of that belongs in a unit test of queue
  /// mechanics.
  Future<DownloadFailure?> Function(
    String url, {
    bool redownload,
    void Function(int progress, String status)? onProgress,
    void Function(String line)? onLog,
    String? logPath,
  })
  archive = archiveUrl;
  Future<void> Function() sync = syncArchivedToLibrary;
  Future<void> Function() stopActive = pipelineRunner.cancelDownload;

  /// What should happen to the running job once its stop lands.
  DownloadJobState? _afterStop;

  bool _pumping = false;

  /// Whether anything has landed since the last library sync.
  bool _dirty = false;

  int _nextId = 0;

  int get activeCount => _jobs.where((job) => job.isActive).length;

  /// Adds [requests] to the back of the queue and starts working if idle.
  DownloadBatch enqueue(Iterable<DownloadRequest> requests) {
    final batch = DownloadBatch([
      for (final request in requests)
        DownloadJob(
          id: '${_nextId++}',
          url: request.url,
          label: request.label.trim().isEmpty ? request.url : request.label,
          subtitle: request.subtitle,
        ),
    ]);
    _batches.add(batch);
    _jobs.addAll(batch.jobs);
    _trim();
    _notify();
    // An empty batch is a real possibility — a sheet where every track was
    // already owned — and must still complete, or its caller waits forever.
    batch._refresh();
    unawaited(_pump());
    return batch;
  }

  /// Queues [job] again after a failure. No-op on a job that is still active.
  void retry(DownloadJob job) {
    if (job.isActive) return;
    job.state = DownloadJobState.queued;
    job.error = null;
    job.errorCode = '';
    job.progress = 0;
    job.status = '';
    _notify();
    unawaited(_pump());
  }

  /// Cancels [job], stopping it mid-download if it is running.
  void cancel(DownloadJob job) {
    if (job.state == DownloadJobState.running) {
      _stopActive(DownloadJobState.cancelled);
      return;
    }
    if (job.state != DownloadJobState.queued) return;
    job.state = DownloadJobState.cancelled;
    _notify();
    _refreshBatches();
  }

  /// Stops the running download and cancels everything waiting.
  void stopAll() {
    cancelPending();
    _stopActive(DownloadJobState.cancelled);
  }

  void _stopActive(DownloadJobState then) {
    final job = active.value;
    if (job == null) return;
    _afterStop = then;
    job.status = 'Stopping';
    _notify();
    unawaited(stopActive());
  }

  /// Cancels every job that has not started.
  void cancelPending() {
    for (final job in _jobs) {
      if (job.state == DownloadJobState.queued) {
        job.state = DownloadJobState.cancelled;
      }
    }
    _notify();
    _refreshBatches();
  }

  void setPaused(bool value) {
    paused.value = value;
    if (value) {
      _stopActive(DownloadJobState.queued);
    } else {
      unawaited(_pump());
    }
  }

  /// Clears finished rows. The queue in flight is untouched.
  void clearFinished() {
    _jobs.removeWhere((job) => job.isTerminal);
    _notify();
  }

  Future<void> _pump() async {
    if (_pumping) return;
    _pumping = true;
    try {
      while (!paused.value) {
        final job = _jobs
            .where((j) => j.state == DownloadJobState.queued)
            .firstOrNull;
        if (job == null) break;
        await _run(job);
      }
    } finally {
      _pumping = false;
    }

    // Once, when the queue goes quiet, rather than after every job: a library
    // sync walks every registered folder, and doing that between tracks would
    // dominate a fifty-track run.
    if (_dirty && activeCount == 0) {
      _dirty = false;
      await sync();
    }
  }

  Future<void> _run(DownloadJob job) async {
    job.state = DownloadJobState.running;
    job.progress = 0;
    job.status = 'Starting';
    active.value = job;
    _notify();

    // Beside the temp folder, not in it: stopping a download clears the temp
    // folder, and the log is what explains the stop.
    final logDir = p.join(p.dirname(downloadTempDir), 'download-logs');
    _pruneLogs(logDir);
    job.downloaderLogPath = p.join(logDir, '${job.id}.log');
    job.addLog('Starting ${job.url}');

    DownloadFailure? error;
    try {
      error = await archive(
        job.url,
        onProgress: (progress, status) {
          if (status != job.status) job.addLog('$progress%  $status');
          job.progress = progress;
          job.status = status;
          _notify();
        },
        onLog: job.addLog,
        logPath: job.downloaderLogPath,
      );
    } catch (e) {
      // archiveUrl documents that it never throws, but a queue that dies on a
      // broken promise would strand every job behind it.
      error = DownloadFailure('$e');
    }

    if (error == null) {
      job.addLog('Finished');
    } else {
      job.addLog('Failed: ${error.message}');
      final explained = explainFailure(error.message, code: error.code);
      job.addLog(
        explained == null
            ? 'Not in the error catalog'
            : 'Catalog: ${explained.id} (${explained.title})',
      );
    }
    final stoppedAs = _afterStop;
    _afterStop = null;
    if (stoppedAs != null) {
      // Stopped on purpose: a pause requeues the job, a cancel drops it.
      job.state = stoppedAs;
      job.status = stoppedAs == DownloadJobState.queued ? 'Paused' : 'Stopped';
      job.error = null;
      job.errorCode = '';
      _dirty = true; // tracks finished before the stop still need syncing
    } else if (error != null) {
      job.state = DownloadJobState.failed;
      job.error = error.message;
      job.errorCode = error.code;
      job.status = 'Failed';
    } else {
      job.state = DownloadJobState.done;
      job.progress = 100;
      job.status = 'Done';
      _dirty = true;
    }
    active.value = null;
    _notify();
    _refreshBatches();
  }

  /// Drops downloader logs older than a week, so they cannot pile up.
  static void _pruneLogs(String dir) {
    try {
      final cutoff = DateTime.now().subtract(const Duration(days: 7));
      for (final file in Directory(dir).listSync().whereType<File>()) {
        if (file.lastModifiedSync().isBefore(cutoff)) file.deleteSync();
      }
    } on FileSystemException {
      // No folder yet, or a file vanished: nothing to prune.
    }
  }

  void _refreshBatches() {
    for (final batch in _batches) {
      batch._refresh();
    }
    _batches.removeWhere((batch) => batch._done.isCompleted);
  }

  /// Keeps finished history bounded, oldest first, without ever dropping work
  /// that has not run.
  void _trim() {
    final finished = _jobs.where((job) => job.isTerminal).toList();
    if (finished.length <= _historyLimit) return;
    final excess = finished.take(finished.length - _historyLimit).toSet();
    _jobs.removeWhere(excess.contains);
  }

  void _notify() {
    jobs.value = List.unmodifiable(_jobs);
  }
}

/// The app's queue. One per process: two would race on the same pipeline.
final downloadQueue = DownloadQueueManager();
