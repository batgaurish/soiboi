import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/update_service.dart';

/// One release entry shaped the way GitHub actually returns them.
Map<String, dynamic> _release(
  String tag, {
  bool prerelease = true,
  bool draft = false,
  List<String> assets = const [],
  String body = 'notes',
}) => {
  'tag_name': tag,
  'name': '$tag build',
  'body': body,
  'prerelease': prerelease,
  'draft': draft,
  'assets': [
    for (final name in assets)
      {
        'name': name,
        'browser_download_url': 'https://example.invalid/$name',
        'size': 1234,
      },
  ],
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUpAll(() {
    appSupportDir = Directory.systemTemp.createTempSync('soiboi_update_test');
  });

  group('version parsing', () {
    test('strips the tag decoration this project actually publishes', () {
      // The whole reason compareVersion could not be reused: int.parse would
      // throw on '0-debug'.
      expect(releaseVersion('v4.1.0-debug'), '4.1.0');
      expect(releaseVersion('v4.1.0'), '4.1.0');
      expect(releaseVersion('4.2'), '4.2');
      expect(releaseVersion('release-4.10.3-rc1'), '4.10.3');
    });

    test('an unparseable tag reads as the oldest possible version', () {
      expect(releaseVersion('nightly'), '0');
      expect(compareReleaseVersion('nightly', '4.1.0'), lessThan(0));
    });

    test('compares numerically, not lexically', () {
      expect(compareReleaseVersion('v4.10.0', 'v4.9.0'), greaterThan(0));
      expect(compareReleaseVersion('v4.2', 'v4.1.9'), greaterThan(0));
      expect(compareReleaseVersion('v4.2', 'v4.2.0'), 0);
      expect(compareReleaseVersion('v4.1.0-debug', '4.1.0'), 0);
    });
  });

  group('picking a release', () {
    test('takes the highest version, not the most recently published', () {
      final release = newestRelease([
        _release('v4.0.9'),
        _release('v4.2.0'),
        _release('v4.1.0'),
      ]);
      expect(release!.tag, 'v4.2.0');
    });

    test('skips drafts, whose assets 404', () {
      final release = newestRelease([
        _release('v9.0.0', draft: true),
        _release('v4.1.0'),
      ]);
      expect(release!.tag, 'v4.1.0');
    });

    test('keeps prereleases, because that is all this project ships', () {
      expect(newestRelease([_release('v4.2.0-debug')])!.tag, 'v4.2.0-debug');
      expect(
        newestRelease([_release('v4.2.0-debug')], includePrereleases: false),
        isNull,
      );
    });

    test('an empty release list is null rather than an exception', () {
      expect(newestRelease(const []), isNull);
    });

    test('reads notes and assets off the entry', () {
      final release = newestRelease([
        _release('v4.2.0', body: 'Fixes the thing', assets: ['app.apk']),
      ])!;
      expect(release.notes, 'Fixes the thing');
      expect(release.assets.single.name, 'app.apk');
      expect(release.assets.single.sizeBytes, 1234);
      expect(release.assets.single.url, contains('app.apk'));
    });
  });

  group('checkForUpdate', () {
    http.Client clientReturning(Object body, {int status = 200}) =>
        MockClient((_) async => http.Response(jsonEncode(body), status));

    test('reports an update when the release is newer', () async {
      final check = await checkForUpdate(
        client: clientReturning([_release('v4.2.0', assets: ['a.apk'])]),
        currentVersion: '4.1.0',
      );
      expect(check.state, UpdateState.available);
      expect(check.release!.tag, 'v4.2.0');
    });

    test('the same version is up to date, not an update', () async {
      final check = await checkForUpdate(
        client: clientReturning([_release('v4.1.0-debug')]),
        currentVersion: '4.1.0',
      );
      expect(check.state, UpdateState.upToDate);
    });

    test('an older release is up to date, never a downgrade offer', () async {
      final check = await checkForUpdate(
        client: clientReturning([_release('v4.0.0')]),
        currentVersion: '4.1.0',
      );
      expect(check.state, UpdateState.upToDate);
    });

    test('a non-200 fails with the status rather than throwing', () async {
      final check = await checkForUpdate(
        client: clientReturning(const [], status: 404),
        currentVersion: '4.1.0',
      );
      expect(check.state, UpdateState.failed);
      expect(check.error, contains('404'));
    });

    test('a network error is reported, not thrown', () async {
      final check = await checkForUpdate(
        client: MockClient((_) async => throw const SocketException('down')),
        currentVersion: '4.1.0',
      );
      expect(check.state, UpdateState.failed);
      expect(check.error, contains('down'));
    });

    test('an unexpected body shape fails cleanly', () async {
      final check = await checkForUpdate(
        client: clientReturning({'message': 'Not Found'}),
        currentVersion: '4.1.0',
      );
      expect(check.state, UpdateState.failed);
    });
  });

  group('downloadRelease', () {
    test('writes the asset and returns its path', () async {
      final path = await downloadRelease(
        const ReleaseAsset(
          name: 'soiboi.apk',
          url: 'https://example.invalid/soiboi.apk',
          sizeBytes: 5,
        ),
        client: MockClient.streaming((_, _) async {
          return http.StreamedResponse(
            Stream.value(utf8.encode('hello')),
            200,
            contentLength: 5,
          );
        }),
      );
      expect(path, isNotNull);
      expect(File(path!).readAsStringSync(), 'hello');
      expect(path, endsWith('soiboi.apk'));
    });

    test('a short download is deleted, not handed to the installer', () async {
      // The only integrity check available: GitHub attaches no checksums, so
      // "the whole file arrived" is the strongest honest claim. A truncated
      // APK that reached the package installer would fail far less legibly.
      String? error;
      final path = await downloadRelease(
        const ReleaseAsset(
          name: 'short.apk',
          url: 'https://example.invalid/short.apk',
          sizeBytes: 99,
        ),
        onError: (message) => error = message,
        client: MockClient.streaming((_, _) async {
          return http.StreamedResponse(
            Stream.value(utf8.encode('hi')),
            200,
            contentLength: 2,
          );
        }),
      );
      expect(path, isNull);
      expect(error, contains('incomplete'));
      expect(
        File('${(await updateStagingDir()).path}/short.apk').existsSync(),
        isFalse,
      );
    });

    test('a non-200 reports the status and writes nothing', () async {
      String? error;
      final path = await downloadRelease(
        const ReleaseAsset(
          name: 'gone.apk',
          url: 'https://example.invalid/gone.apk',
          sizeBytes: 10,
        ),
        onError: (message) => error = message,
        client: MockClient.streaming(
          (_, _) async =>
              http.StreamedResponse(const Stream.empty(), 404),
        ),
      );
      expect(path, isNull);
      expect(error, contains('404'));
    });

    test('progress is reported as bytes arrive', () async {
      final seen = <int>[];
      await downloadRelease(
        const ReleaseAsset(
          name: 'progress.apk',
          url: 'https://example.invalid/progress.apk',
          sizeBytes: 6,
        ),
        onProgress: (received, _) => seen.add(received),
        client: MockClient.streaming((_, _) async {
          return http.StreamedResponse(
            Stream.fromIterable([
              utf8.encode('abc'),
              utf8.encode('def'),
            ]),
            200,
            contentLength: 6,
          );
        }),
      );
      expect(seen, [3, 6]);
    });
  });

  group('platform asset selection', () {
    final release = newestRelease([
      _release(
        'v4.2.0',
        assets: [
          'soiboi-debug-android-v4.2.0+4.apk',
          'soiboi-debug-linux-x64-v4.2.0+4.tar.gz',
          'source.zip',
        ],
      ),
    ])!;

    test('picks the file this platform can actually install', () {
      final asset = release.assetForThisPlatform;
      if (Platform.isAndroid) {
        expect(asset!.name, endsWith('.apk'));
      } else if (Platform.isLinux) {
        expect(asset!.name, 'soiboi-debug-linux-x64-v4.2.0+4.tar.gz');
      } else {
        expect(asset, isNull);
      }
    });

    test('a release with nothing for this platform is null, not a crash', () {
      final other = newestRelease([
        _release('v4.3.0', assets: ['soiboi-windows.zip']),
      ])!;
      expect(other.assetForThisPlatform, isNull);
    });
  });

  group('the Linux swap', () {
    test('finds the bundle wherever the archive nested it', () {
      final root = Directory.systemTemp.createTempSync('soiboi_bundle_test');
      addTearDown(() => root.deleteSync(recursive: true));
      final nested = Directory('${root.path}/soiboi-4.2.0/bundle')
        ..createSync(recursive: true);
      File('${nested.path}/soiboi').writeAsStringSync('binary');

      expect(findBundleDir(root, 'soiboi')?.path, nested.path);
    });

    test('an archive with no executable is null, not a wrong guess', () {
      final root = Directory.systemTemp.createTempSync('soiboi_bundle_empty');
      addTearDown(() => root.deleteSync(recursive: true));
      Directory('${root.path}/docs').createSync();
      expect(findBundleDir(root, 'soiboi'), isNull);
    });

    test('the script waits for the app, keeps a .bak and relaunches', () {
      final script = linuxSwapScript(
        pid: 4242,
        bundleDir: '/opt/soiboi/bundle',
        executable: 'soiboi',
        newBundlePath: '/tmp/staged/bundle',
      );
      // Waiting on the PID is the whole reason this runs detached: a running
      // binary cannot replace the directory it is executing from.
      expect(script, contains('while kill -0 4242'));
      // The old bundle is moved, never deleted — a bad update has to be
      // recoverable by hand rather than being a reinstall.
      expect(script, contains('mv "/opt/soiboi/bundle" "/opt/soiboi/bundle.bak"'));
      expect(script, contains('mv "/tmp/staged/bundle" "/opt/soiboi/bundle"'));
      expect(script, contains('exec "/opt/soiboi/bundle/soiboi"'));
      expect(script, startsWith('#!/bin/sh'));
    });

    test('the script actually runs and swaps a real directory', () async {
      // Exercised for real rather than only string-matched: a quoting mistake
      // in that heredoc would be invisible to an assertion on its text.
      final root = Directory.systemTemp.createTempSync('soiboi_swap_test');
      addTearDown(() => root.deleteSync(recursive: true));
      final live = Directory('${root.path}/bundle')..createSync();
      File('${live.path}/soiboi').writeAsStringSync('old');
      final staged = Directory('${root.path}/staged')..createSync();
      File('${staged.path}/soiboi').writeAsStringSync('new');

      final script = File('${root.path}/apply.sh');
      // A PID that has already exited, and `true` in place of the relaunch:
      // the test is the swap, not starting a GUI.
      script.writeAsStringSync(
        linuxSwapScript(
          pid: 999999,
          bundleDir: live.path,
          executable: 'soiboi',
          newBundlePath: staged.path,
        ).replaceFirst('exec "${live.path}/soiboi"', 'true'),
      );
      final result = await Process.run('sh', [script.path]);

      expect(result.exitCode, 0, reason: '${result.stderr}');
      expect(File('${live.path}/soiboi').readAsStringSync(), 'new');
      expect(File('${live.path}.bak/soiboi').readAsStringSync(), 'old');
    }, skip: !Platform.isLinux && !Platform.isMacOS);
  });
}
