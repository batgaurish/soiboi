/// Runs the on-device archival pipeline.
///
/// Replaces the HTTP client that used to talk to a self-hosted Flask service.
/// The pipeline is now Python bundled inside the app, reached through one of
/// two transports:
///
///   * **Desktop** spawns `python -m soiboi_pipeline` from a bundled virtual
///     environment and reads newline-delimited JSON from its stdout.
///   * **Android** will call the same module in-process through Chaquopy, since
///     Android cannot spawn executables.
///
/// Both run identical Python, so behaviour is shared and only the transport
/// differs. Callers see a stream of [PipelineEvent] either way and never learn
/// which platform they are on.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:path/path.dart' as p;
import 'package:soiboi/base/services/logger.dart';

/// One JSON line from the pipeline: progress, or a terminal result.
class PipelineEvent {
  PipelineEvent(this.raw);

  final Map<String, dynamic> raw;

  String get event => raw['event'] as String? ?? '';
  bool get isProgress => event == 'progress';
  bool get isDone => event == 'done';
  bool get isError => event == 'error';

  int get progress => (raw['progress'] as num?)?.round() ?? 0;
  String get status => raw['status'] as String? ?? '';
  String get message => raw['message'] as String? ?? '';
  String get code => raw['code'] as String? ?? '';
}

/// What the bundled environment can actually do.
///
/// Probed rather than assumed, so the UI can say precisely what is missing
/// instead of offering a Download tab that fails on first use.
class PipelineCapabilities {
  PipelineCapabilities({
    required this.canDownload,
    required this.pythonVersion,
    required this.missing,
    required this.nativeMuxer,
    this.error,
  });

  final bool canDownload;
  final String pythonVersion;
  final List<String> missing;
  final bool nativeMuxer;

  /// Set when probing itself failed — no runtime at all, rather than an
  /// incomplete one.
  final String? error;

  static PipelineCapabilities unavailable(String reason) => PipelineCapabilities(
    canDownload: false,
    pythonVersion: '',
    missing: const [],
    nativeMuxer: false,
    error: reason,
  );

  factory PipelineCapabilities.fromJson(Map<String, dynamic> json) =>
      PipelineCapabilities(
        canDownload: json['can_download'] as bool? ?? false,
        pythonVersion: json['python'] as String? ?? '',
        missing: (json['missing'] as List? ?? [])
            .map((e) => e.toString())
            .toList(),
        nativeMuxer:
            (json['native_muxer'] as Map?)?['available'] as bool? ?? false,
      );

  /// A short, honest explanation for the UI.
  String get summary {
    if (error != null) return error!;
    if (canDownload) return 'Ready';
    if (!nativeMuxer) return 'Media engine missing for this device';
    if (missing.isNotEmpty) return 'Missing: ${missing.join(', ')}';
    return 'Unavailable';
  }
}

abstract class PipelineRunner {
  /// Streams events until the pipeline produces a terminal result.
  Stream<PipelineEvent> run(String command, [Map<String, dynamic>? payload]);

  Future<PipelineCapabilities> capabilities() async {
    try {
      await for (final event in run('capabilities')) {
        if (event.isDone) return PipelineCapabilities.fromJson(event.raw);
        if (event.isError) {
          return PipelineCapabilities.unavailable(event.message);
        }
      }
    } catch (e) {
      return PipelineCapabilities.unavailable('$e');
    }
    return PipelineCapabilities.unavailable('No response from pipeline');
  }
}

/// Desktop: spawn the bundled interpreter.
class DesktopPipelineRunner extends PipelineRunner {
  DesktopPipelineRunner({String? pythonPath, String? pipelineRoot})
    : _pythonOverride = pythonPath,
      _rootOverride = pipelineRoot;

  final String? _pythonOverride;
  final String? _rootOverride;

  /// Where the pipeline lives relative to the running binary.
  ///
  /// Checked in order: an explicit override (tests), the bundle layout used by
  /// a packaged build, then the source tree so `flutter run` works during
  /// development without a packaging step.
  String? get _root {
    if (_rootOverride != null) return _rootOverride;
    final exeDir = p.dirname(Platform.resolvedExecutable);
    final candidates = [
      p.join(exeDir, 'data', 'pipeline'),
      p.join(exeDir, 'pipeline'),
      p.join(Directory.current.path, 'pipeline'),
    ];
    for (final candidate in candidates) {
      if (Directory(p.join(candidate, 'soiboi_pipeline')).existsSync()) {
        return candidate;
      }
    }
    return null;
  }

  String? get _python {
    if (_pythonOverride != null) return _pythonOverride;
    final root = _root;
    if (root == null) return null;
    final bundled = p.join(root, '.venv', 'bin', 'python');
    return File(bundled).existsSync() ? bundled : null;
  }

  @override
  Stream<PipelineEvent> run(String command, [Map<String, dynamic>? payload]) {
    final controller = StreamController<PipelineEvent>();

    final python = _python;
    final root = _root;
    if (python == null || root == null) {
      controller.add(
        PipelineEvent({
          'event': 'error',
          'code': 'no_runtime',
          'message': 'Bundled Python runtime not found',
        }),
      );
      controller.close();
      return controller.stream;
    }

    final args = [
      '-m',
      'soiboi_pipeline',
      command,
      if (payload != null) jsonEncode(payload),
    ];

    Process.start(
      python,
      args,
      // PYTHONPATH so the package resolves without needing to be installed
      // into the venv; PYTHONUNBUFFERED so progress arrives as it happens
      // rather than in one lump when the process exits.
      environment: {'PYTHONPATH': root, 'PYTHONUNBUFFERED': '1'},
      workingDirectory: root,
    ).then((process) {
      process.stdout
          .transform(utf8.decoder)
          .transform(const LineSplitter())
          .listen(
            (line) {
              if (line.trim().isEmpty) return;
              try {
                final decoded = jsonDecode(line);
                if (decoded is Map<String, dynamic>) {
                  controller.add(PipelineEvent(decoded));
                }
              } catch (_) {
                // Non-JSON output is the pipeline's own logging noise, not a
                // protocol violation worth surfacing to the user.
                logger.output('pipeline: $line');
              }
            },
            onDone: () => controller.close(),
            onError: (Object e) {
              controller.add(
                PipelineEvent({
                  'event': 'error',
                  'code': 'io',
                  'message': '$e',
                }),
              );
              controller.close();
            },
          );

      process.stderr.transform(utf8.decoder).listen((text) {
        if (text.trim().isNotEmpty) logger.output('pipeline stderr: $text');
      });
    }).catchError((Object e) {
      controller.add(
        PipelineEvent({
          'event': 'error',
          'code': 'spawn_failed',
          'message': '$e',
        }),
      );
      controller.close();
    });

    return controller.stream;
  }
}

/// Android: call the same module in-process via Chaquopy.
///
/// Deliberately unimplemented rather than faked. Android cannot spawn a Python
/// binary, so this needs a platform channel into Chaquopy; returning plausible
/// events here would make the UI look functional while nothing downloaded.
class AndroidPipelineRunner extends PipelineRunner {
  @override
  Stream<PipelineEvent> run(String command, [Map<String, dynamic>? payload]) {
    return Stream.value(
      PipelineEvent({
        'event': 'error',
        'code': 'not_implemented',
        'message': 'On-device downloading is not wired up on Android yet',
      }),
    );
  }
}

PipelineRunner buildPipelineRunner() =>
    Platform.isAndroid ? AndroidPipelineRunner() : DesktopPipelineRunner();

/// Shared instance and its probed capabilities.
final pipelineRunner = buildPipelineRunner();
final pipelineCapabilitiesNotifier = ValueNotifier<PipelineCapabilities?>(null);

Future<void> refreshPipelineCapabilities() async {
  pipelineCapabilitiesNotifier.value = await pipelineRunner.capabilities();
}
