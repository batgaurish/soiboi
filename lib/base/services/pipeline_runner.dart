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

import 'package:flutter/services.dart';
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
    this.canAnalyze,
    this.error,
  });

  final bool canDownload;
  final String pythonVersion;
  final List<String> missing;
  final bool nativeMuxer;

  /// Whether Essentia is available for mood/feature analysis. False on Android
  /// (no wheel) and on desktop if the pipeline venv lacks essentia.
  final bool? canAnalyze;

  /// Set when probing itself failed — no runtime at all, rather than an
  /// incomplete one.
  final String? error;

  static PipelineCapabilities unavailable(String reason) => PipelineCapabilities(
    canDownload: false,
    pythonVersion: '',
    missing: const [],
    nativeMuxer: false,
    canAnalyze: false,
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
        canAnalyze:
            (json['acoustic_analysis'] as Map?)?['available'] as bool?,
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
    // The venv deliberately lives *beside* the package rather than inside it:
    // Chaquopy copies the whole pipeline directory into the APK as Python
    // sources, and a desktop virtualenv in there breaks that build.
    for (final candidate in [
      p.join(p.dirname(root), '.pipeline-venv', 'bin', 'python'),
      p.join(root, '..', '.pipeline-venv', 'bin', 'python'),
    ]) {
      if (File(candidate).existsSync()) return candidate;
    }
    return null;
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

/// Android: the same module, in-process via Chaquopy.
///
/// Android cannot spawn a Python binary, so instead of a subprocess this calls
/// through a platform channel into an embedded interpreter. The Python it runs
/// is byte-for-byte the package the desktop transport executes.
///
/// Progress arrives on a separate EventChannel rather than the method reply,
/// because a download takes minutes and the UI must not wait for it.
class AndroidPipelineRunner extends PipelineRunner {
  static const _method = MethodChannel('com.batgaurish.soiboi/pipeline');
  static const _events = EventChannel('com.batgaurish.soiboi/pipeline_events');

  @override
  Stream<PipelineEvent> run(String command, [Map<String, dynamic>? payload]) {
    final controller = StreamController<PipelineEvent>();

    // Subscribe before invoking, or early progress is missed.
    final subscription = _events.receiveBroadcastStream().listen((data) {
      if (data is! String) return;
      try {
        final decoded = jsonDecode(data);
        if (decoded is Map<String, dynamic>) {
          controller.add(PipelineEvent(decoded));
        }
      } catch (_) {
        // Malformed progress is not worth failing a download over.
      }
    }, onError: (Object _) {});

    _method
        .invokeMethod<String>('run', {
          'command': command,
          'payload': jsonEncode(payload ?? const {}),
        })
        .then((response) {
          if (response != null) {
            try {
              final decoded = jsonDecode(response);
              if (decoded is Map<String, dynamic>) {
                controller.add(PipelineEvent(decoded));
              }
            } catch (e) {
              controller.add(
                PipelineEvent({
                  'event': 'error',
                  'code': 'bad_response',
                  'message': '$e',
                }),
              );
            }
          }
        })
        .catchError((Object e) {
          controller.add(
            PipelineEvent({
              'event': 'error',
              'code': 'channel',
              'message': '$e',
            }),
          );
        })
        .whenComplete(() {
          subscription.cancel();
          controller.close();
        });

    return controller.stream;
  }
}

PipelineRunner buildPipelineRunner() =>
    Platform.isAndroid ? AndroidPipelineRunner() : DesktopPipelineRunner();

/// Where archived files land.
///
/// Defaults to a Soiboi folder beside the app's data rather than the user's
/// music library, so a failed or partial download never scatters junk through
/// a curated collection. Configurable once the library-folder work lands.
String downloadOutputDir = '';

/// Scratch space for in-progress downloads.
///
/// gamdl defaults its temp directory to the process working directory, which
/// is not writable on Android and is wherever the user happened to launch the
/// binary on desktop -- so it is always passed explicitly. Kept out of
/// [downloadOutputDir] so half-written files are never picked up by a library
/// scan.
String downloadTempDir = '';

/// Shared instance and its probed capabilities.
final pipelineRunner = buildPipelineRunner();
final pipelineCapabilitiesNotifier = ValueNotifier<PipelineCapabilities?>(null);

Future<void> refreshPipelineCapabilities() async {
  pipelineCapabilitiesNotifier.value = await pipelineRunner.capabilities();
}
