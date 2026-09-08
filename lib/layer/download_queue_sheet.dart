/// Seeing, and steering, everything the app is downloading.
///
/// Two entry points onto one widget: [DownloadQueueView] sits inline on the
/// Downloads screen, where archiving starts, and [showDownloadQueueSheet]
/// opens the same list from Settings for when the queue is running and you are
/// somewhere else entirely.
///
/// A sheet rather than a pushed layer on purpose. Every settings detail layer
/// in this app is a `part` pair — a portrait page and a landscape panel — and
/// this content is one list; the app's own [showAnimationDialog] is already
/// the idiom for secondary UI like the catalog and discovery sheets, and works
/// from inside a layer's nested navigator where a raw modal sheet does not.
library;

import 'package:material_ui/material_ui.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/theme/motion.dart';

Future<void> showDownloadQueueSheet(BuildContext context) {
  return showAnimationDialog(
    context: context,
    child: const SizedBox(
      width: 460,
      height: 540,
      child: SafeArea(
        child: Padding(
          padding: EdgeInsets.fromLTRB(20, 16, 20, 20),
          child: DownloadQueueView(showTitle: true),
        ),
      ),
    ),
  );
}

/// The queue itself: what is downloading, what is waiting, what failed.
class DownloadQueueView extends StatelessWidget {
  const DownloadQueueView({super.key, this.showTitle = false});

  /// Set when this is the whole sheet rather than the body of a card that
  /// already has a heading of its own.
  final bool showTitle;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<List<DownloadJob>>(
      valueListenable: downloadQueue.jobs,
      builder: (context, jobs, _) {
        if (jobs.isEmpty) {
          return Padding(
            padding: const EdgeInsets.symmetric(vertical: 18),
            child: Text(
              'Nothing in the queue. Anything you archive shows up here, and '
              'keeps going if you leave the screen.',
              style: TextStyle(fontSize: 12.5, color: textColor.value),
            ),
          );
        }

        final active = jobs.where((job) => job.isActive).toList();
        final finished = jobs.reversed.where((job) => job.isTerminal).toList();

        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            if (showTitle) ...[
              Text(
                'Download queue',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  color: highlightTextColor.value,
                ),
              ),
              const SizedBox(height: 12),
            ],
            _controls(active.length, finished.length),
            const SizedBox(height: 8),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final job in active) _row(job),
                  if (active.isNotEmpty && finished.isNotEmpty)
                    const SizedBox(height: 6),
                  for (final job in finished) _row(job),
                ],
              ),
            ),
          ],
        );
      },
    );
  }

  /// Pause, and the two bulk actions. Pause is worded as taking effect after
  /// the track in flight because that is what it does — no transport can
  /// interrupt the pipeline mid-file.
  Widget _controls(int activeCount, int finishedCount) {
    return ValueListenableBuilder<bool>(
      valueListenable: downloadQueue.paused,
      builder: (context, paused, _) => Row(
        children: [
          Expanded(
            child: Text(
              activeCount == 0
                  ? '$finishedCount finished'
                  : paused
                  ? '$activeCount waiting — pausing after this track'
                  : '$activeCount in the queue',
              style: TextStyle(fontSize: 12, color: textColor.value),
            ),
          ),
          if (activeCount > 0)
            TextButton(
              onPressed: () => downloadQueue.setPaused(!paused),
              child: Text(paused ? 'Resume' : 'Pause'),
            ),
          if (activeCount > 1)
            TextButton(
              onPressed: downloadQueue.cancelPending,
              child: const Text('Cancel waiting'),
            ),
          if (finishedCount > 0)
            TextButton(
              onPressed: downloadQueue.clearFinished,
              child: const Text('Clear'),
            ),
        ],
      ),
    );
  }

  Widget _row(DownloadJob job) {
    final (icon, tint) = switch (job.state) {
      DownloadJobState.done => (Icons.check_rounded, seekBarColor.value),
      DownloadJobState.failed => (Icons.error_outline, Colors.red),
      DownloadJobState.cancelled => (Icons.remove_rounded, textColor.value),
      DownloadJobState.running => (
        Icons.download_rounded,
        seekBarColor.value,
      ),
      DownloadJobState.queued => (
        Icons.schedule_rounded,
        textColor.value,
      ),
    };

    // The failure message, else the pipeline's stage label, else the artist —
    // in that order, because the most specific thing is always the one worth
    // the one line a row has.
    final detail = job.error ?? (job.status.isEmpty ? job.subtitle : job.status);

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(icon, size: 16, color: tint),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  job.label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 13,
                    color: highlightTextColor.value,
                  ),
                ),
                if (detail != null && detail.isNotEmpty)
                  Text(
                    detail,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 11,
                      color: job.state == DownloadJobState.failed
                          ? Colors.red
                          : textColor.value,
                    ),
                  ),
                if (job.state == DownloadJobState.running) ...[
                  const SizedBox(height: 5),
                  _bar(job.progress),
                ],
              ],
            ),
          ),
          if (job.state == DownloadJobState.failed)
            IconButton(
              iconSize: 17,
              visualDensity: VisualDensity.compact,
              tooltip: 'Retry',
              onPressed: () => downloadQueue.retry(job),
              icon: const Icon(Icons.refresh_rounded),
            ),
          if (job.state == DownloadJobState.queued)
            IconButton(
              iconSize: 17,
              visualDensity: VisualDensity.compact,
              tooltip: 'Remove from queue',
              onPressed: () => downloadQueue.cancel(job),
              icon: const Icon(Icons.close_rounded),
            ),
        ],
      ),
    );
  }

  Widget _bar(int progress) {
    return SmoothClipRRect(
      smoothness: 1,
      borderRadius: BorderRadius.circular(3),
      child: TweenAnimationBuilder<double>(
        // Progress arrives in discrete stage jumps, so easing between them
        // reads as motion rather than stutter.
        tween: Tween(end: progress / 100),
        duration: activeMotion.medium,
        curve: activeMotion.standard,
        builder: (context, value, _) => LinearProgressIndicator(
          value: value,
          minHeight: 3,
          backgroundColor: buttonColor.value,
          color: seekBarColor.value,
        ),
      ),
    );
  }
}
