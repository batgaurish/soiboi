/// Updating an app nobody can update for you.
///
/// Distribution is private and sideloaded — not F-Droid, not Play Store — so
/// there is no store to notice a new build and no store to install it. This is
/// that mechanism: read the GitHub releases of the repo the app is actually
/// built from, compare against the running version, download the asset for
/// this platform, and hand it to whatever this platform uses to install.
///
/// Nothing here installs silently, on either platform. Android shows its own
/// package-installer prompt; Linux swaps a directory and relaunches, which the
/// user watches happen. That is deliberate — a music player that can replace
/// itself unattended is a much bigger thing to trust than one that asks.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;
import 'package:soiboi/base/app.dart';

/// The repo the app is built from.
///
/// Inherited from upstream Sylvakru as `AfalpHy/soiboi` — its own update check
/// pointing at itself — which is a repository that does not exist. Every
/// update check this app ever made returned 404.
const updateRepo = 'batgaurish/soiboi';

/// Releases, not `/releases/latest`.
///
/// GitHub excludes prereleases from `latest`, and every build shipped so far
/// is a prerelease (`v4.1.0-debug`), so `latest` answers 404 for this repo
/// today. Listing and filtering is the only thing that works for how this
/// project actually releases.
const _releasesUrl = 'https://api.github.com/repos/$updateRepo/releases';

/// One downloadable file attached to a release.
class ReleaseAsset {
  const ReleaseAsset({
    required this.name,
    required this.url,
    required this.sizeBytes,
  });

  final String name;
  final String url;
  final int sizeBytes;
}

class AppRelease {
  const AppRelease({
    required this.tag,
    required this.name,
    required this.notes,
    required this.prerelease,
    required this.assets,
  });

  final String tag;
  final String name;
  final String notes;
  final bool prerelease;
  final List<ReleaseAsset> assets;

  /// The comparable part of [tag]: `v4.1.0-debug` → `4.1.0`.
  String get version => releaseVersion(tag);

  /// The file to install on this platform, or null if this release does not
  /// ship one — a Linux-only release seen from a phone is not an error, it is
  /// simply not an update for that device.
  ReleaseAsset? get assetForThisPlatform {
    if (Platform.isAndroid) {
      return assets.where((a) => a.name.endsWith('.apk')).firstOrNull;
    }
    if (Platform.isLinux) {
      return assets
          .where((a) => a.name.contains('linux') && a.name.endsWith('.tar.gz'))
          .firstOrNull;
    }
    return null;
  }
}

/// The numeric core of a tag, safe to compare.
///
/// `compareVersion` splits on '.' and `int.parse`s each part, so it throws on
/// every tag this project actually publishes — `v4.1.0-debug` becomes
/// `['4', '1', '0-debug']`. Stripping to the leading digits-and-dots run first
/// is what makes the comparison survive a real tag.
String releaseVersion(String tag) {
  final match = RegExp(r'(\d+(?:\.\d+)*)').firstMatch(tag);
  return match?.group(1) ?? '0';
}

/// Positive when [a] is newer than [b]. Missing components count as zero, so
/// `4.2` is newer than `4.1.9` and equal to `4.2.0`.
int compareReleaseVersion(String a, String b) {
  final left = releaseVersion(a).split('.').map(int.parse).toList();
  final right = releaseVersion(b).split('.').map(int.parse).toList();
  final length = left.length > right.length ? left.length : right.length;
  for (var i = 0; i < length; i++) {
    final l = i < left.length ? left[i] : 0;
    final r = i < right.length ? right[i] : 0;
    if (l != r) return l.compareTo(r);
  }
  return 0;
}

/// Parses GitHub's releases JSON into the newest usable release.
///
/// Drafts are always skipped: they are not published and their assets 404.
/// Prereleases are kept by default because that is all this project ships.
AppRelease? newestRelease(
  List<dynamic> json, {
  bool includePrereleases = true,
}) {
  AppRelease? best;
  for (final entry in json) {
    if (entry is! Map) continue;
    if (entry['draft'] == true) continue;
    final prerelease = entry['prerelease'] == true;
    if (prerelease && !includePrereleases) continue;
    final tag = entry['tag_name'] as String?;
    if (tag == null) continue;

    final release = AppRelease(
      tag: tag,
      name: (entry['name'] as String?)?.trim().isNotEmpty == true
          ? entry['name'] as String
          : tag,
      notes: (entry['body'] as String?) ?? '',
      prerelease: prerelease,
      assets: [
        for (final asset in (entry['assets'] as List? ?? const []))
          if (asset is Map && asset['browser_download_url'] != null)
            ReleaseAsset(
              name: asset['name'] as String? ?? '',
              url: asset['browser_download_url'] as String,
              sizeBytes: (asset['size'] as num?)?.toInt() ?? 0,
            ),
      ],
    );
    // Highest version wins rather than "first in the list": GitHub orders by
    // creation date, and a patch cut from an old branch can be published
    // after a newer release.
    if (best == null || compareReleaseVersion(release.tag, best.tag) > 0) {
      best = release;
    }
  }
  return best;
}

enum UpdateState { upToDate, available, failed }

class UpdateCheck {
  const UpdateCheck(this.state, {this.release, this.error});

  final UpdateState state;
  final AppRelease? release;
  final String? error;
}

/// Asks GitHub what the newest release is and whether it beats this build.
///
/// Never throws: this is called from a settings tap and from a background
/// check, and neither has anywhere useful to put an exception.
Future<UpdateCheck> checkForUpdate({
  http.Client? client,
  String currentVersion = versionNumber,
}) async {
  final owned = client == null;
  final httpClient = client ?? http.Client();
  try {
    final response = await httpClient
        .get(
          Uri.parse('$_releasesUrl?per_page=10'),
          // Unauthenticated calls are rate-limited by IP; the explicit accept
          // header is what GitHub asks API clients to send.
          headers: const {'Accept': 'application/vnd.github+json'},
        )
        .timeout(const Duration(seconds: 10));
    if (response.statusCode != 200) {
      return UpdateCheck(
        UpdateState.failed,
        error: 'GitHub answered ${response.statusCode}',
      );
    }
    final decoded = jsonDecode(response.body);
    if (decoded is! List) {
      return const UpdateCheck(
        UpdateState.failed,
        error: 'Unexpected response from GitHub',
      );
    }
    final release = newestRelease(decoded);
    if (release == null) {
      return const UpdateCheck(UpdateState.failed, error: 'No releases found');
    }
    if (compareReleaseVersion(release.tag, currentVersion) <= 0) {
      return UpdateCheck(UpdateState.upToDate, release: release);
    }
    return UpdateCheck(UpdateState.available, release: release);
  } catch (e) {
    return UpdateCheck(UpdateState.failed, error: '$e');
  } finally {
    if (owned) httpClient.close();
  }
}

/// Where a downloaded update is staged.
///
/// On Android this must be somewhere a FileProvider is declared over, or the
/// package installer cannot read the file it is asked to install.
Future<Directory> updateStagingDir() async {
  final dir = Directory(p.join(appSupportDir.path, 'updates'));
  if (!dir.existsSync()) await dir.create(recursive: true);
  return dir;
}

/// Downloads [asset], reporting bytes as they arrive. Returns the file's path,
/// or null with [onError] called.
///
/// The size check is the only integrity check available: GitHub release assets
/// carry no checksum, so "the whole file arrived" is the strongest claim that
/// can honestly be made before handing it to an installer.
Future<String?> downloadRelease(
  ReleaseAsset asset, {
  void Function(int received, int total)? onProgress,
  void Function(String message)? onError,
  http.Client? client,
}) async {
  final owned = client == null;
  final httpClient = client ?? http.Client();
  File? file;
  try {
    final dir = await updateStagingDir();
    file = File(p.join(dir.path, asset.name));
    // A previous attempt's partial file would otherwise be appended to and
    // produce a corrupt archive that passes nothing but looks complete.
    if (file.existsSync()) await file.delete();

    final request = http.Request('GET', Uri.parse(asset.url));
    final response = await httpClient.send(request);
    if (response.statusCode != 200) {
      onError?.call('Download failed (${response.statusCode})');
      return null;
    }
    final total = response.contentLength ?? asset.sizeBytes;
    var received = 0;
    final sink = file.openWrite();
    try {
      await for (final chunk in response.stream) {
        sink.add(chunk);
        received += chunk.length;
        onProgress?.call(received, total);
      }
    } finally {
      await sink.close();
    }

    final written = await file.length();
    if (asset.sizeBytes > 0 && written != asset.sizeBytes) {
      await file.delete();
      onError?.call(
        'Download incomplete: got $written bytes, expected ${asset.sizeBytes}',
      );
      return null;
    }
    return file.path;
  } catch (e) {
    try {
      if (file != null && file.existsSync()) await file.delete();
    } catch (_) {
      // A leftover partial file is untidy, not dangerous — the next attempt
      // deletes it before writing.
    }
    onError?.call('$e');
    return null;
  } finally {
    if (owned) httpClient.close();
  }
}

const _installChannel = MethodChannel('com.batgaurish.soiboi/update');

/// Hands [path] to the platform's installer. Returns null on success, or a
/// message explaining why it could not start.
///
/// "Success" here means the install was *started*, not finished: on Android
/// the system installer takes over and the user confirms it, and on Linux the
/// app exits so a detached script can replace the bundle it is running from.
Future<String?> installUpdate(String path) async {
  if (Platform.isAndroid) return _installAndroid(path);
  if (Platform.isLinux) return _installLinux(path);
  return 'Updating in place is not supported on this platform yet.';
}

/// Opens Android's package installer on the downloaded APK.
///
/// Android 7+ refuses a `file://` URI across an app boundary, so the APK is
/// handed over as a `content://` URI from the app's own FileProvider, and the
/// app needs REQUEST_INSTALL_PACKAGES to be allowed to ask at all. The system
/// still shows its own confirmation — this triggers that prompt, it does not
/// bypass it.
Future<String?> _installAndroid(String path) async {
  try {
    final error = await _installChannel.invokeMethod<String>('install', {
      'path': path,
    });
    return error;
  } on PlatformException catch (e) {
    return e.message ?? 'Could not start the installer';
  } on MissingPluginException {
    return 'This build has no installer bridge; install the APK manually.';
  }
}

/// Stages the Linux swap and returns once the script is armed.
///
/// A running binary cannot overwrite the directory it is executing from, so
/// the swap is done by a small script that outlives this process: it waits for
/// this PID to exit, moves the old bundle aside, moves the new one into place
/// and starts it again. The old bundle is kept as `.bak` rather than deleted,
/// so a bad update is recoverable by hand instead of being a reinstall.
///
/// **The caller must exit the app after this returns null.** The script is
/// already waiting on this PID; leaving the app running leaves the update
/// permanently pending.
Future<String?> _installLinux(String archivePath) async {
  try {
    final bundleDir = p.dirname(Platform.resolvedExecutable);
    final executable = p.basename(Platform.resolvedExecutable);
    final staging = Directory(p.join(p.dirname(archivePath), 'staged'));
    if (staging.existsSync()) await staging.delete(recursive: true);
    await staging.create(recursive: true);

    final extract = await Process.run('tar', [
      '-xzf',
      archivePath,
      '-C',
      staging.path,
    ]);
    if (extract.exitCode != 0) {
      return 'Could not extract the update: ${extract.stderr}';
    }

    final newBundle = findBundleDir(staging, executable);
    if (newBundle == null) {
      return 'The update archive does not contain a $executable bundle.';
    }

    final script = File(p.join(p.dirname(archivePath), 'apply-update.sh'));
    await script.writeAsString(
      linuxSwapScript(
        pid: pid,
        bundleDir: bundleDir,
        executable: executable,
        newBundlePath: newBundle.path,
      ),
    );
    await Process.run('chmod', ['+x', script.path]);

    // Detached, or the script dies with the process it is waiting for.
    await Process.start('/bin/sh', [
      script.path,
    ], mode: ProcessStartMode.detached);
    return null;
  } catch (e) {
    return '$e';
  }
}

/// The swap script, as text.
///
/// Built by a pure function so the riskiest string in the updater — the one
/// that moves the directory the app is running from — can be asserted in a
/// test instead of only being read.
///
/// `mv` rather than `rsync`: staging lives under the app's support directory,
/// which is usually on the same filesystem as the install, and GNU `mv` falls
/// back to a recursive copy when it is not.
String linuxSwapScript({
  required int pid,
  required String bundleDir,
  required String executable,
  required String newBundlePath,
}) {
  return '''#!/bin/sh
# Written by Soiboi's updater. Waits for the running app to exit, swaps the
# bundle directory, then starts the new one. The old bundle is kept as .bak
# rather than deleted, so a bad update is recoverable by hand.
set -e
while kill -0 $pid 2>/dev/null; do sleep 0.2; done
rm -rf "$bundleDir.bak"
mv "$bundleDir" "$bundleDir.bak"
mv "$newBundlePath" "$bundleDir"
exec "$bundleDir/$executable"
''';
}

/// The directory inside [root] that actually contains [executable].
///
/// Whatever the archive's top-level directory is called — `bundle/`, a
/// version-stamped name, or nothing at all — the new install is wherever the
/// binary is, so it is found rather than assumed.
Directory? findBundleDir(Directory root, String executable) {
  if (File(p.join(root.path, executable)).existsSync()) return root;
  for (final entry in root.listSync()) {
    if (entry is! Directory) continue;
    final found = findBundleDir(entry, executable);
    if (found != null) return found;
  }
  return null;
}
