/// The bundled lossless wrapper: wrapper-v2, run by the app itself.
///
/// gamdl can fetch ALAC only through wrapper-v2, which decrypts FairPlay with
/// Apple Music's own Android libraries. Soiboi ships the wrapper without those
/// libraries. The user downloads the one Apple Music build it is pinned to
/// ([appleMusicApkUrl]), and setup extracts and verifies the libraries.
///
/// On Linux the wrapper runs in an unprivileged user namespace
/// (`unshare -rmpf`), which is what lets its chroot work without root. On
/// Android there is no chroot: the two programs ship in the APK as native
/// libraries (Android runs executables only from there), and the worker loads
/// Apple's libraries from app storage.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
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

  /// Whether the wrapper holds a signed-in Apple Music session.
  ///
  /// The wrapper runs only on demand, so [state] alone cannot say this at
  /// startup. Apple keeps the session in the wrapper's own storage, and a
  /// marker beside it remembers what the wrapper last reported. A signed-in
  /// wrapper is Apple's own client logged into the account, so it stands in
  /// for browser cookies everywhere the app talks to Apple.
  final signedIn = ValueNotifier(false);

  Process? _process;
  int? _httpPort;
  int? _decryptPort;

  static const _channel = MethodChannel('com.batgaurish.soiboi/pipeline');

  /// Android only: the native library folder and the device ABI.
  String? _nativeDir;
  String _arch = 'x86_64';

  bool get supported => Platform.isLinux || Platform.isAndroid;

  String get installDir => p.join(appSupportDir.path, 'wrapper');
  String get _libDir => Platform.isAndroid
      ? p.join(installDir, 'lib')
      : p.join(installDir, 'rootfs', 'system', 'lib64');

  /// Reads the Android install paths once. A no-op on Linux.
  Future<void> _loadPlatform() async {
    if (!Platform.isAndroid || _nativeDir != null) return;
    final paths = await _channel.invokeMapMethod<String, String>('wrapperPaths');
    _nativeDir = paths?['nativeLibraryDir'];
    _arch = paths?['abi'] ?? 'arm64-v8a';
  }

  String get _baseUrl => 'http://127.0.0.1:$_httpPort';

  File get _signedInMarker => File(p.join(installDir, 'signed_in'));

  void _setSignedIn(bool value) {
    signedIn.value = value;
    try {
      if (value) {
        _signedInMarker.createSync(recursive: true);
      } else if (_signedInMarker.existsSync()) {
        _signedInMarker.deleteSync();
      }
    } on FileSystemException catch (e) {
      logger.output('wrapper sign-in marker: $e');
    }
  }

  bool get librariesInstalled =>
      File(p.join(_libDir, 'libandroidappmusic.so')).existsSync();

  /// Where the build put the wrapper: beside the installed app, or the
  /// development tree's build/ folder.
  String? get _bundleDir {
    if (Platform.isAndroid) {
      final dir = _nativeDir;
      return dir != null && File(p.join(dir, 'libwrapperd.so')).existsSync()
          ? dir
          : null;
    }
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
  Future<void> refresh() async {
    await _loadPlatform();
    signedIn.value =
        supported && librariesInstalled && _signedInMarker.existsSync();
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
    if (Platform.isLinux) {
      // The chroot needs a writable tree for Apple's session database, so the
      // read-only bundle is copied rather than used in place.
      final copy = await Process.run('cp', ['-a', '$bundle/.', installDir]);
      if (copy.exitCode != 0) return 'Could not copy the wrapper: ${copy.stderr}';
    }

    String? error;
    await for (final event in pipelineRunner.run('wrapper_install', {
      'apk_path': apkPath,
      'arch': _arch,
      'out_dir': _libDir,
    })) {
      if (event.isError) error = event.message;
    }
    await refresh();
    return error;
  }

  /// Starts the wrapper if it is not running. Returns once it answers.
  Future<void> start() async {
    if (_process != null) return;
    await refresh();
    if (state.value.stage != WrapperStage.stopped) return;
    state.value = const WrapperState(WrapperStage.starting);

    _httpPort = await _freePort();
    _decryptPort = await _freePort();
    final ports = {
      'WRAPPER_HOST': '127.0.0.1',
      'WRAPPER_PORT': '$_httpPort',
      'WRAPPER_DECRYPT_HOST': '127.0.0.1',
      'WRAPPER_DECRYPT_PORT': '$_decryptPort',
    };

    try {
      final Process process;
      if (Platform.isAndroid) {
        final baseDir = p.join(installDir, 'base');
        Directory(baseDir).createSync(recursive: true);
        final certs = p.join(_libDir, 'cacert.pem');
        process = await Process.start(
          p.join(_nativeDir!, 'libwrapperd.so'),
          const [],
          workingDirectory: installDir,
          environment: {
            ...ports,
            'WRAPPER_LAUNCHER': p.join(_nativeDir!, 'libwrapperworker.so'),
            'WRAPPER_NATIVE_ANDROID': '1',
            'WRAPPER_LIBS_DIR': _libDir,
            'WRAPPER_BASE_DIR': baseDir,
            'LD_LIBRARY_PATH': _libDir,
            'SSL_CERT_FILE': certs,
            'CURL_CA_BUNDLE': certs,
          },
        );
      } else {
        Directory(
          p.join(installDir, 'rootfs', 'data', 'data', 'com.apple.android.music', 'files'),
        ).createSync(recursive: true);
        await _killLeftovers();
        process = await Process.start(
          'unshare',
          // --kill-child: the wrapper is PID 1 in its namespace and ignores
          // SIGTERM, so without this, stopping or losing the app left it
          // running with nobody knowing its port.
          ['-rmpf', '--kill-child', p.join(installDir, 'wrapperd')],
          workingDirectory: installDir,
          environment: {...ports, 'WRAPPER_LAUNCHER': p.join(installDir, 'wrapper')},
        );
      }
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
      _setSignedIn(auth['state'] != 'logged_out');
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
    await refresh();
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

  /// Starts a signed-in wrapper and returns its [downloadPayload], or null
  /// when the wrapper is not set up or not signed in.
  Future<Map<String, Object>?> ensureReady() async {
    await refresh();
    if (!librariesInstalled || !signedIn.value) return null;
    await start();
    return downloadPayload;
  }

  /// Force-stops wrappers from an earlier run of the app. Linux only: they
  /// are found by their executable, which lives in this app's own storage.
  Future<void> _killLeftovers() async {
    final exe = p.join(installDir, 'wrapperd');
    for (final entry in Directory('/proc').listSync()) {
      final pid = int.tryParse(p.basename(entry.path));
      if (pid == null) continue;
      try {
        final cmdline = File('${entry.path}/cmdline').readAsStringSync();
        if (cmdline.split('\x00').contains(exe)) {
          Process.killPid(pid, ProcessSignal.sigkill);
        }
      } on FileSystemException {
        // Exited while we looked, or not ours to read.
      }
    }
  }

  /// At startup: a wrapper set up and signed in before the app remembered
  /// sign-ins has no marker yet, so ask it once rather than wait for a
  /// download to start it. Leaves it running, since signed in means it will
  /// be used.
  Future<void> probeSignIn() async {
    await refresh();
    if (!librariesInstalled || signedIn.value) return;
    await start();
    if (!signedIn.value) await stop();
  }

  static Future<int> _freePort() async {
    final socket = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final port = socket.port;
    await socket.close();
    return port;
  }
}

final wrapperService = WrapperService();
