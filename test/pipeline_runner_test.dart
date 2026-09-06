@Tags(['integration'])
library;

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:soiboi/base/services/pipeline_runner.dart';

/// Drives the real bundled Python through the real transport.
///
/// Deliberately not mocked: the whole point of this layer is that Dart and
/// Python agree on a protocol, and a mock would only ever test that the mock
/// matches itself. These skip when the bundled environment is absent, so a
/// checkout without the venv built still passes.
void main() {
  final root = p.join(Directory.current.path, 'pipeline');
  final python = p.join(root, '.venv', 'bin', 'python');
  final available =
      File(python).existsSync() &&
      Directory(p.join(root, 'soiboi_pipeline')).existsSync();

  DesktopPipelineRunner runner() =>
      DesktopPipelineRunner(pythonPath: python, pipelineRoot: root);

  group('DesktopPipelineRunner', () {
    test('probes real capabilities from the bundled runtime', () async {
      final caps = await runner().capabilities();
      expect(caps.error, isNull, reason: 'probe should reach the runtime');
      expect(caps.pythonVersion, isNotEmpty);
      // The native muxer must load, since it is the piece most likely to be
      // built for the wrong architecture.
      expect(caps.nativeMuxer, isTrue);
      expect(caps.missing, isEmpty);
      expect(caps.canDownload, isTrue);
    }, skip: available ? false : 'bundled pipeline not built');

    test('a missing runtime degrades instead of throwing', () async {
      final broken = DesktopPipelineRunner(
        pythonPath: '/nonexistent/python',
        pipelineRoot: '/nonexistent',
      );
      final caps = await broken.capabilities();
      expect(caps.canDownload, isFalse);
      expect(caps.error, isNotNull);
      expect(caps.summary, isNotEmpty);
    });

    test('download without cookies reports a typed error', () async {
      final events = await runner()
          .run('download', {
            'url': 'https://music.apple.com/us/album/0',
            'cookies_path': '/nonexistent/cookies.txt',
            'output_dir': Directory.systemTemp.path,
          })
          .toList();

      final error = events.where((e) => e.isError).toList();
      expect(error, isNotEmpty, reason: 'should surface a terminal error');
      // A typed code lets the UI offer "Sign in" rather than a raw string.
      expect(error.last.code, 'no_cookies');
    }, skip: available ? false : 'bundled pipeline not built');

    test('unknown commands are rejected, not silently ignored', () async {
      final events = await runner().run('nonsense').toList();
      expect(events.last.isError, isTrue);
      expect(events.last.code, 'unknown_command');
    }, skip: available ? false : 'bundled pipeline not built');
  });
}
