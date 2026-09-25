/// The download queue, in the notification shade.
///
/// While anything is queued: one progress notification ("Downloading 3 of
/// 12", the track and its stage, the overall bar) with Pause or Resume and
/// Stop. On Android that notification belongs to a foreground service, which
/// is what keeps downloads going with the app in the background and the
/// screen off. When the queue empties: a summary of the run, with "Retry
/// failed" if anything failed.
library;

import 'dart:async';
import 'dart:io';

import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/notification_service.dart';

class DownloadNotifier {
  DownloadNotifier(this.queue, this.notifications, {bool? alwaysShowProgress})
    : alwaysShowProgress = alwaysShowProgress ?? Platform.isAndroid;

  /// Whether progress shows even with notifications turned off.
  final bool alwaysShowProgress;

  final DownloadQueueManager queue;
  final NotificationService notifications;

  static const progressKey = 'downloads';
  static const resultKey = 'downloads-result';

  /// Every job that was queued at some point in the current run, so the
  /// count ("3 of 12") and the summary cover the run, not just what is left.
  final _run = <DownloadJob>{};

  /// The jobs that failed in the last finished run, for "Retry failed".
  final _lastFailed = <DownloadJob>[];

  bool _running = false;
  bool _askedPermission = false;

  /// What the progress notification last said, so the queue's many small
  /// changes (every progress tick) don't each post an identical update.
  String? _lastShown;

  StreamSubscription<NotificationTap>? _taps;

  void start() {
    queue.jobs.addListener(_update);
    queue.paused.addListener(_update);
    _taps = notifications.taps.listen(_onTap);
  }

  void stop() {
    queue.jobs.removeListener(_update);
    queue.paused.removeListener(_update);
    unawaited(_taps?.cancel());
  }

  void _update() {
    final waiting = queue.jobs.value.where((job) => job.isActive).toList();
    if (waiting.isNotEmpty) {
      if (!_running) {
        _running = true;
        _run.clear();
        // Android 13+ asks once. Now is when it makes sense: the user just
        // queued something, and the app is on screen to ask from.
        if (!_askedPermission) {
          _askedPermission = true;
          unawaited(notifications.requestPermission());
        }
        // A new run: the last run's summary is out of date.
        unawaited(notifications.dismiss(resultKey));
      }
      _run.addAll(waiting);
      _showProgress(waiting.length);
    } else if (_running) {
      _running = false;
      _lastShown = null;
      unawaited(notifications.dismiss(progressKey));
      _showSummary();
    }
  }

  void _showProgress(int waiting) {
    final total = _run.length;
    final finished = _run.where((job) => job.isTerminal).length;
    final current = queue.active.value;
    final paused = queue.paused.value;

    final title = paused
        ? 'Downloads paused'
        : total == 1
        ? 'Downloading'
        : 'Downloading ${finished + 1} of $total';
    final body = paused
        ? '$waiting waiting. Resume to carry on.'
        : current == null
        ? 'Starting'
        : '${current.label}${current.status.isEmpty ? '' : ' · ${current.status}'}';
    // Overall, with the track in flight counting for its share.
    final progress = paused
        ? null
        : (((finished + (current?.progress ?? 0) / 100) / total) * 100).round();

    final signature = '$title|$body|$progress';
    if (signature == _lastShown) return;
    _lastShown = signature;

    unawaited(
      notifications.show(
        progressKey,
        AppNotification(
          kind: NotificationKind.progress,
          title: title,
          body: body,
          progress: progress,
          actions: [
            paused
                ? const NotificationAction('resume', 'Resume')
                : const NotificationAction('pause', 'Pause'),
            const NotificationAction('stop', 'Stop'),
          ],
        ),
        // On Android this notification is what keeps downloads alive in the
        // background, so it shows whatever the notifications setting says.
        always: alwaysShowProgress,
      ),
    );
  }

  void _showSummary() {
    final done = _run.where((j) => j.state == DownloadJobState.done).toList();
    final failed = _run
        .where((j) => j.state == DownloadJobState.failed)
        .toList();
    _lastFailed
      ..clear()
      ..addAll(failed);
    // Everything stopped by hand: the user knows, nothing to report.
    if (done.isEmpty && failed.isEmpty) return;

    final String title;
    final String body;
    if (_run.length == 1) {
      final job = _run.single;
      title = failed.isEmpty ? 'Downloaded' : 'Download failed';
      body = failed.isEmpty ? job.label : '${job.label}: ${_why(job)}';
    } else {
      title = failed.isEmpty
          ? 'Downloads finished'
          : 'Downloads finished with problems';
      body = [
        '${done.length} downloaded',
        if (failed.isNotEmpty)
          '${failed.length} failed (${failed.first.label}: '
              '${_why(failed.first)})',
      ].join(', ');
    }
    unawaited(
      notifications.show(
        resultKey,
        AppNotification(
          kind: NotificationKind.result,
          title: title,
          body: body,
          actions: [
            if (failed.isNotEmpty)
              const NotificationAction('retry', 'Retry failed'),
          ],
        ),
      ),
    );
  }

  static String _why(DownloadJob job) =>
      job.failure?.title ?? job.error ?? 'unknown error';

  void _onTap(NotificationTap tap) {
    if (tap.key == progressKey) {
      switch (tap.action) {
        case 'pause':
          queue.setPaused(true);
        case 'resume':
          queue.setPaused(false);
        case 'stop':
          queue.stopAll();
          // Stopped while paused: nothing is running to end the run, and
          // cancelled jobs are no longer active, so update now.
          _update();
      }
    } else if (tap.key == resultKey && tap.action == 'retry') {
      unawaited(notifications.dismiss(resultKey));
      final failed = List.of(_lastFailed);
      _lastFailed.clear();
      failed.forEach(queue.retry);
    }
  }
}
