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

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/error_catalog.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:soiboi/base/widgets/icon_label.dart';
import 'package:soiboi/layer/failure_fix.dart';

/// One job's log in a dialog, from its row or its "Open log" fix.
Future<void> showDownloadLog(BuildContext context, DownloadJob job) {
  return showAnimationDialog(
    context: context,
    child: SizedBox(width: 640, height: 560, child: DownloadLogView(job: job)),
  );
}

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
            // Inline, the page around this scrolls with unbounded height, so
            // an uncapped list grows to every row and, being a scrollable
            // itself, swallows each drag without moving: a long queue froze
            // the whole Downloads page. Capped, it scrolls in its own box and
            // drags elsewhere scroll the page.
            if (showTitle)
              Flexible(child: _list(active, finished))
            else
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 360),
                child: _list(active, finished),
              ),
          ],
        );
      },
    );
  }

  Widget _list(List<DownloadJob> active, List<DownloadJob> finished) {
    return ListView(
      shrinkWrap: true,
      children: [
        for (final job in active) _row(job),
        if (active.isNotEmpty && finished.isNotEmpty) const SizedBox(height: 6),
        for (final job in finished) _row(job),
      ],
    );
  }

  /// Pause, stop and the bulk actions. Pause and stop both take effect at
  /// once; a paused job goes back to the front of the queue.
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
                  ? '$activeCount paused'
                  : '$activeCount in the queue',
              style: TextStyle(fontSize: 12, color: textColor.value),
            ),
          ),
          if (activeCount > 0)
            TextButton(
              onPressed: () => downloadQueue.setPaused(!paused),
              child: Text(paused ? 'Resume' : 'Pause'),
            ),
          if (activeCount > 0)
            TextButton(
              onPressed: downloadQueue.stopAll,
              child: const Text('Stop all'),
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
      DownloadJobState.failed => (Icons.error_outline, failureTextColor()),
      DownloadJobState.cancelled => (Icons.remove_rounded, textColor.value),
      DownloadJobState.running => (Icons.download_rounded, seekBarColor.value),
      DownloadJobState.queued => (Icons.schedule_rounded, textColor.value),
    };

    // What went wrong in plain words, else the pipeline's stage label, else
    // the artist: the most specific thing is always the one worth the line a
    // row has. The raw failure stays in the job's log.
    final detail =
        job.failure?.sentence ??
        (job.status.isEmpty ? job.subtitle : job.status);

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Icon(icon, size: 16, color: tint),
          const SizedBox(width: 10),
          Expanded(
            // One item for a screen reader: the download and where it is.
            child: Semantics(
              container: true,
              label: _spoken(job, detail),
              excludeSemantics: true,
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
                            ? failureTextColor()
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
          ),
          // A failure always offers its log; other rows only with the
          // Download logs setting on.
          ValueListenableBuilder<bool>(
            valueListenable: showDownloadLogsNotifier,
            builder: (context, show, _) =>
                job.state == DownloadJobState.queued ||
                    (!show && job.state != DownloadJobState.failed)
                ? const SizedBox.shrink()
                : IconButton(
                    iconSize: 17,
                    visualDensity: VisualDensity.compact,
                    tooltip: 'Log',
                    onPressed: () => showDownloadLog(context, job),
                    icon: labelIcon('Log', const Icon(Icons.article_outlined)),
                  ),
          ),
          // The catalog's fix first; Retry beside it when the fix is
          // something else, unless the download can never work.
          if (job.state == DownloadJobState.failed &&
              job.failure?.fix != FailureFix.openLog)
            FailureFixButton(job: job, compact: true),
          if (job.state == DownloadJobState.failed &&
              job.failure?.fix != FailureFix.retry &&
              job.failure?.fix != FailureFix.skip)
            IconButton(
              iconSize: 17,
              visualDensity: VisualDensity.compact,
              tooltip: 'Retry',
              onPressed: () => downloadQueue.retry(job),
              icon: labelIcon('Retry', const Icon(Icons.refresh_rounded)),
            ),
          if (job.state == DownloadJobState.queued ||
              job.state == DownloadJobState.running)
            IconButton(
              iconSize: 17,
              visualDensity: VisualDensity.compact,
              tooltip: job.state == DownloadJobState.running
                  ? 'Stop this download'
                  : 'Remove from queue',
              onPressed: () => downloadQueue.cancel(job),
              icon: labelIcon(
                job.state == DownloadJobState.running
                    ? 'Stop this download'
                    : 'Remove from queue',
                const Icon(Icons.close_rounded),
              ),
            ),
        ],
      ),
    );
  }

  static String _spoken(DownloadJob job, String? detail) {
    final state = switch (job.state) {
      DownloadJobState.queued => 'waiting',
      DownloadJobState.running => [
        'downloading',
        if (job.status.isNotEmpty) job.status,
        '${job.progress}%',
      ].join(', '),
      DownloadJobState.done => 'downloaded',
      DownloadJobState.failed => 'failed. ${job.failure?.sentence ?? ''}',
      DownloadJobState.cancelled => 'cancelled',
    };
    return '${job.label}, $state';
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

/// One download's log, live: the app's own steps, then the downloader's raw
/// output. Rereads the downloader's file every second while open.
class DownloadLogView extends StatefulWidget {
  const DownloadLogView({super.key, required this.job});

  final DownloadJob job;

  @override
  State<DownloadLogView> createState() => _DownloadLogViewState();
}

class _DownloadLogViewState extends State<DownloadLogView> {
  final _scroll = ScrollController();
  Timer? _timer;
  String _output = '';

  @override
  void initState() {
    super.initState();
    widget.job.logChanged.addListener(_refresh);
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    widget.job.logChanged.removeListener(_refresh);
    _scroll.dispose();
    super.dispose();
  }

  void _refresh() {
    final output = _readTail(widget.job.downloaderLogPath);
    if (!mounted) return;
    final atBottom =
        !_scroll.hasClients ||
        _scroll.position.pixels >= _scroll.position.maxScrollExtent - 40;
    setState(() => _output = output);
    // Follow new lines, unless the reader has scrolled up to look at one.
    if (atBottom) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_scroll.hasClients) {
          _scroll.jumpTo(_scroll.position.maxScrollExtent);
        }
      });
    }
  }

  /// The last 64 KB: a long download's output runs to megabytes of progress
  /// lines, and the end is what explains a stall.
  static String _readTail(String? path) {
    if (path == null) return '';
    try {
      final file = File(path);
      if (!file.existsSync()) return '';
      final raf = file.openSync();
      try {
        final length = raf.lengthSync();
        const window = 64 * 1024;
        raf.setPositionSync(length > window ? length - window : 0);
        return utf8.decode(raf.readSync(window), allowMalformed: true);
      } finally {
        raf.closeSync();
      }
    } on FileSystemException {
      return '';
    }
  }

  String get _text =>
      '${widget.job.label}\n${widget.job.url}\n\n'
      '== Steps ==\n${widget.job.log.join('\n')}\n\n'
      '== Downloader output ==\n'
      '${_output.isEmpty ? '(nothing yet: the downloader has not started)' : _output}';

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  'Log: ${widget.job.label}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w600,
                    color: highlightTextColor.value,
                  ),
                ),
              ),
              IconButton(
                tooltip: 'Copy',
                icon: labelIcon(
                  'Copy',
                  const Icon(Icons.copy_rounded, size: 18),
                ),
                onPressed: () {
                  Clipboard.setData(ClipboardData(text: _text));
                  showCenterMessage('Log copied');
                },
              ),
            ],
          ),
          const SizedBox(height: 8),
          Expanded(
            child: SingleChildScrollView(
              controller: _scroll,
              child: SelectableText(
                _text,
                style: TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 11.5,
                  height: 1.35,
                  color: textColor.value,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
