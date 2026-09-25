/// Every saved download log, newest first, from Settings > Download logs.
///
/// A queue row's log button only exists while the row does: clearing the
/// queue or restarting the app left the week of logs on disk with no way to
/// read them. This lists them all.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:path/path.dart' as p;
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/widgets/icon_label.dart';
import 'package:soiboi/layer/download_queue_sheet.dart';

class SavedLog {
  const SavedLog({
    required this.path,
    required this.started,
    required this.title,
  });

  final String path;
  final DateTime started;

  /// The song, else the link, else the file name.
  final String title;
}

final _downloading = RegExp(r'Downloading "(.+)"');
final _processing = RegExp(r'Processing "(.+)"');

/// The logs in [dir], newest first. Files are named `<epoch ms>-<job>.log`;
/// the title comes from the first lines gamdl writes.
List<SavedLog> listSavedLogs(String dir) {
  final logs = <SavedLog>[];
  try {
    for (final file in Directory(dir).listSync().whereType<File>()) {
      if (!file.path.endsWith('.log')) continue;
      final name = p.basenameWithoutExtension(file.path);
      final millis = int.tryParse(name.split('-').first);
      final started = millis == null
          ? file.lastModifiedSync()
          : DateTime.fromMillisecondsSinceEpoch(millis);
      logs.add(
        SavedLog(
          path: file.path,
          started: started,
          title: _title(file) ?? name,
        ),
      );
    }
  } on FileSystemException {
    // No downloads yet, so no folder.
  }
  logs.sort((a, b) => b.started.compareTo(a.started));
  return logs;
}

String? _title(File file) {
  try {
    final raf = file.openSync();
    try {
      final head = utf8.decode(raf.readSync(8 * 1024), allowMalformed: true);
      final song = _downloading.firstMatch(head)?.group(1);
      // gamdl's name for a track it could not look up.
      if (song != null && song != 'Unknown Title') return song;
      return _processing.firstMatch(head)?.group(1);
    } finally {
      raf.closeSync();
    }
  } on FileSystemException {
    return null;
  }
}

String _when(DateTime t) {
  String two(int n) => n.toString().padLeft(2, '0');
  final local = t.toLocal();
  return '${local.year}-${two(local.month)}-${two(local.day)} '
      '${two(local.hour)}:${two(local.minute)}';
}

Future<void> showSavedDownloadLogs(BuildContext context) => showAnimationDialog(
  context: context,
  child: SizedBox(
    width: 560,
    height: 600,
    child: SavedDownloadLogs(dir: downloadLogDir),
  ),
);

class SavedDownloadLogs extends StatefulWidget {
  const SavedDownloadLogs({super.key, required this.dir});

  final String dir;

  @override
  State<SavedDownloadLogs> createState() => _SavedDownloadLogsState();
}

class _SavedDownloadLogsState extends State<SavedDownloadLogs> {
  late final List<SavedLog> _logs = listSavedLogs(widget.dir);
  SavedLog? _open;

  @override
  Widget build(BuildContext context) {
    final open = _open;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              if (open != null)
                IconButton(
                  tooltip: 'All logs',
                  onPressed: () => setState(() => _open = null),
                  icon: labelIcon(
                    'All logs',
                    const Icon(Icons.arrow_back_rounded, size: 20),
                  ),
                ),
              Expanded(
                child: Semantics(
                  header: true,
                  child: Text(
                    open == null ? 'Download logs' : open.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: highlightTextColor.value,
                    ),
                  ),
                ),
              ),
              if (open != null)
                IconButton(
                  tooltip: 'Copy',
                  onPressed: () {
                    Clipboard.setData(
                      ClipboardData(text: readLogTail(open.path)),
                    );
                    showCenterMessage('Log copied');
                  },
                  icon: labelIcon(
                    'Copy',
                    const Icon(Icons.copy_rounded, size: 18),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          Expanded(child: open == null ? _list() : _text(open)),
        ],
      ),
    );
  }

  Widget _list() {
    if (_logs.isEmpty) {
      return Text(
        'No download logs yet. Each download keeps its log here for a week.',
        style: TextStyle(fontSize: 13, color: textColor.value),
      );
    }
    return ListView.builder(
      itemCount: _logs.length,
      itemBuilder: (context, i) {
        final log = _logs[i];
        return ListTile(
          dense: true,
          contentPadding: EdgeInsets.zero,
          title: Text(
            log.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(color: highlightTextColor.value),
          ),
          subtitle: Text(
            _when(log.started),
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
          trailing: const Icon(Icons.chevron_right_rounded),
          onTap: () => setState(() => _open = log),
        );
      },
    );
  }

  Widget _text(SavedLog log) {
    final text = readLogTail(log.path);
    return SingleChildScrollView(
      child: SelectableText(
        text.isEmpty ? '(empty)' : text,
        style: TextStyle(
          fontFamily: 'monospace',
          fontSize: 11.5,
          height: 1.35,
          color: textColor.value,
        ),
      ),
    );
  }
}
