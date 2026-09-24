/// The bundled lossless wrapper: wrapper-v2, run by the app itself.
///
/// gamdl can fetch ALAC only through wrapper-v2, which decrypts FairPlay with
/// Apple Music's own Android libraries. Soiboi ships the wrapper without those
/// libraries. The user downloads the one Apple Music build it is pinned to
/// ([appleMusicApkUrl]), and setup extracts and verifies the libraries.
///
/// On Linux the wrapper runs in an unprivileged user namespace
/// (`unshare -rmpf`), which is what lets its chroot work without root.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:material_ui/material_ui.dart';
import 'package:path/path.dart' as p;
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/logger.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';

/// The exact Apple Music build the wrapper's libraries must come from.
///
/// wrapper-v2 pins each library by SHA-256, so only this upload works: the
/// "(arm64-v8a + x86_64)" variant of 3.6.0-beta, build 1109.
const appleMusicApkUrl =
    'https://www.apkmirror.com/apk/apple/apple-music/apple-music-3-6-0-beta-release/'
    'apple-music-3-6-0-beta-4-android-apk-download/';
const appleMusicApkLabel = 'Apple Music 3.6.0-beta, build 1109 (arm64-v8a + x86_64)';

enum WrapperStage {
  /// This platform cannot run the wrapper yet.
  unsupported,

  /// Apple's libraries are not installed.
  needsLibraries,

  stopped,
  starting,
  signedOut,

  /// Apple asked for a two-factor code.
  needsCode,
  ready,
  failed,
}

class WrapperState {
  const WrapperState(this.stage, {this.message, this.account});

  final WrapperStage stage;
  final String? message;

  /// The Apple ID the wrapper reports when signed in.
  final String? account;
}

class WrapperService {
  final state = ValueNotifier(const WrapperState(WrapperStage.stopped));

  Process? _process;
  int? _httpPort;
  int? _decryptPort;

  String get _arch => 'x86_64';

  bool get supported => Platform.isLinux;

  String get installDir => p.join(appSupportDir.path, 'wrapper');
  String get _libDir => p.join(installDir, 'rootfs', 'system', 'lib64');
  String get _baseUrl => 'http://127.0.0.1:$_httpPort';

  bool get librariesInstalled =>
      File(p.join(_libDir, 'libandroidappmusic.so')).existsSync();

  /// Where the build put the wrapper: beside the installed app, or the
  /// development tree's build/ folder.
  String? get _bundleDir {
    final exeDir = p.dirname(Platform.resolvedExecutable);
    for (final candidate in [
      p.join(exeDir, 'data', 'wrapper', _arch),
      p.join(Directory.current.path, 'build', 'wrapper', _arch),
    ]) {
      if (File(p.join(candidate, 'wrapperd')).existsSync()) return candidate;
    }
    return null;
  }

  /// Sets [state] from what is on disk, without starting anything.
  void refresh() {
    if (!supported || _bundleDir == null) {
      state.value = const WrapperState(
        WrapperStage.unsupported,
        message: 'The lossless wrapper is not part of this build.',
      );
    } else if (!librariesInstalled) {
      state.value = const WrapperState(WrapperStage.needsLibraries);
    } else if (_process == null) {
      state.value = const WrapperState(WrapperStage.stopped);
    }
  }

  /// Copies the wrapper into app storage and installs Apple's libraries from
  /// [apkPath]. Returns an error message, or null on success.
  Future<String?> installLibraries(String apkPath) async {
    final bundle = _bundleDir;
    if (bundle == null) return 'The lossless wrapper is not part of this build.';
    await stop();
    // The chroot needs a writable tree for Apple's session database, so the
    // read-only bundle is copied rather than used in place.
    final copy = await Process.run('cp', ['-a', '$bundle/.', installDir]);
    if (copy.exitCode != 0) return 'Could not copy the wrapper: ${copy.stderr}';

    String? error;
    await for (final event in pipelineRunner.run('wrapper_install', {
      'apk_path': apkPath,
      'libs_version': p.join(installDir, 'LIBS_VERSION.json'),
      'arch': _arch,
      'out_dir': _libDir,
    })) {
      if (event.isError) error = event.message;
    }
    refresh();
    return error;
  }

  /// Starts the wrapper if it is not running. Returns once it answers.
  Future<void> start() async {
    if (_process != null) return;
    refresh();
    if (state.value.stage != WrapperStage.stopped) return;
    state.value = const WrapperState(WrapperStage.starting);

    _httpPort = await _freePort();
    _decryptPort = await _freePort();
    Directory(
      p.join(installDir, 'rootfs', 'data', 'data', 'com.apple.android.music', 'files'),
    ).createSync(recursive: true);

    try {
      final process = await Process.start(
        'unshare',
        ['-rmpf', p.join(installDir, 'wrapperd')],
        workingDirectory: installDir,
        environment: {
          'WRAPPER_LAUNCHER': p.join(installDir, 'wrapper'),
          'WRAPPER_HOST': '127.0.0.1',
          'WRAPPER_PORT': '$_httpPort',
          'WRAPPER_DECRYPT_HOST': '127.0.0.1',
          'WRAPPER_DECRYPT_PORT': '$_decryptPort',
        },
      );
      _process = process;
      process.stderr
          .transform(utf8.decoder)
          .listen((text) => logger.output('wrapper: ${text.trim()}'));
      process.stdout.drain<void>();
      unawaited(process.exitCode.then((code) {
        if (!identical(_process, process)) return;
        _process = null;
        state.value = WrapperState(
          WrapperStage.failed,
          message: 'The wrapper stopped (exit code $code).',
        );
      }));
    } on ProcessException catch (e) {
      state.value = WrapperState(WrapperStage.failed, message: '$e');
      return;
    }

    // Apple's runtime takes a few seconds to load.
    for (var i = 0; i < 30; i++) {
      if (await _refreshAccount()) return;
      await Future<void>.delayed(const Duration(seconds: 1));
    }
    state.value = const WrapperState(
      WrapperStage.failed,
      message: 'The wrapper did not come up within 30 seconds.',
    );
  }

  /// Reads the sign-in state. Returns false when the wrapper is not answering.
  Future<bool> _refreshAccount() async {
    try {
      final response = await http
          .get(Uri.parse('$_baseUrl/me'))
          .timeout(const Duration(seconds: 3));
      final body = jsonDecode(response.body) as Map<String, dynamic>;
      final auth = body['auth'] as Map<String, dynamic>?;
      if (auth == null) return false;
      state.value = auth['state'] == 'logged_out'
          ? const WrapperState(WrapperStage.signedOut)
          : WrapperState(
              WrapperStage.ready,
              account: auth['apple_id'] as String?,
            );
      return true;
    } on Exception {
      return false;
    }
  }

  /// Signs the wrapper in. The password goes only to the local wrapper, which
  /// hands it to Apple's own sign-in; Soiboi never stores it.
  Future<void> signIn(String appleId, String password) async {
    await _post('/login', {'username': appleId, 'password': password});
  }

  Future<void> submitCode(String code) async {
    await _post('/login/2fa', {'code': code.trim()});
  }

  Future<void> _post(String path, Map<String, String> body) async {
    try {
      final response = await http
          .post(
            Uri.parse('$_baseUrl$path'),
            headers: {'content-type': 'application/json'},
            body: jsonEncode(body),
          )
          .timeout(const Duration(seconds: 90));
      if (response.statusCode == 202) {
        state.value = const WrapperState(WrapperStage.needsCode);
      } else if (response.statusCode == 200) {
        await _refreshAccount();
      } else {
        state.value = WrapperState(
          WrapperStage.signedOut,
          message: response.statusCode == 401
              ? 'Apple did not accept that Apple ID or password.'
              : 'Sign-in failed (${response.statusCode}).',
        );
      }
    } on Exception catch (e) {
      state.value = WrapperState(WrapperStage.signedOut, message: '$e');
    }
  }

  Future<void> signOut() async {
    try {
      await http.delete(Uri.parse('$_baseUrl/login'));
    } on Exception catch (e) {
      logger.output('wrapper sign-out: $e');
    }
    await _refreshAccount();
  }

  Future<void> stop() async {
    final process = _process;
    _process = null;
    if (process == null) return;
    process.kill();
    await process.exitCode.timeout(
      const Duration(seconds: 3),
      onTimeout: () {
        process.kill(ProcessSignal.sigkill);
        return -1;
      },
    );
    refresh();
  }

  /// What a download needs to go through the running wrapper, or null when
  /// it is not signed in.
  Map<String, Object>? get downloadPayload => state.value.stage == WrapperStage.ready
      ? {
          'use_wrapper': true,
          'wrapper_url': _baseUrl,
          'wrapper_decrypt_port': _decryptPort!,
        }
      : null;

  static Future<int> _freePort() async {
    final socket = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final port = socket.port;
    await socket.close();
    return port;
  }
}

final wrapperService = WrapperService();
