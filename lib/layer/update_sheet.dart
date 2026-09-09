/// The update prompt: what changed, how big it is, and one button.
///
/// This replaces a dialog that could only open a browser at a releases page —
/// which, on a phone, left the user to find the APK in their downloads and
/// install it by hand, and on Linux to unpack a tarball over their own
/// install. The download and the handover to the platform's installer happen
/// here now.
library;

import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/storage_cleanup.dart' show formatBytes;
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/update_service.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:soiboi/base/widgets/release_notes.dart';

Future<void> showUpdateSheet(BuildContext context, AppRelease release) {
  return showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 460,
      height: 540,
      child: _UpdateSheet(release: release),
    ),
  );
}

class _UpdateSheet extends StatefulWidget {
  const _UpdateSheet({required this.release});
  final AppRelease release;

  @override
  State<_UpdateSheet> createState() => _UpdateSheetState();
}

class _UpdateSheetState extends State<_UpdateSheet> {
  bool _busy = false;
  int _received = 0;
  int _total = 0;
  String? _error;

  /// Set once the platform has taken over — on Linux the app is about to exit,
  /// on Android the system installer is in front of us.
  String? _handedOff;

  Future<void> _downloadAndInstall() async {
    final asset = widget.release.assetForThisPlatform;
    if (asset == null) return;

    setState(() {
      _busy = true;
      _error = null;
      _received = 0;
      _total = asset.sizeBytes;
    });

    final path = await downloadRelease(
      asset,
      onProgress: (received, total) {
        if (!mounted) return;
        setState(() {
          _received = received;
          _total = total;
        });
      },
      onError: (message) {
        if (mounted) setState(() => _error = message);
      },
    );
    if (!mounted) return;
    if (path == null) {
      setState(() => _busy = false);
      return;
    }

    final error = await installUpdate(path);
    if (!mounted) return;
    if (error != null) {
      setState(() {
        _busy = false;
        _error = error;
      });
      return;
    }

    setState(() {
      _handedOff = Platform.isLinux
          ? 'Restarting into the new version…'
          : 'Finish the install in the Android prompt.';
    });

    // Linux only: the swap script is already waiting on this process, so the
    // update stays permanently pending until the app actually exits. Android
    // must NOT exit — the installer needs the user in front of it, and killing
    // the app under the prompt looks like a crash.
    if (Platform.isLinux) {
      await Future<void>.delayed(const Duration(milliseconds: 600));
      exit(0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final release = widget.release;
    final asset = release.assetForThisPlatform;

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              release.name,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.w600,
                color: highlightTextColor.value,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              '${release.tag} · you have $versionNumber'
              '${release.prerelease ? " · prerelease" : ""}',
              style: TextStyle(fontSize: 12, color: textColor.value),
            ),
            const SizedBox(height: 12),
            Expanded(
              child: SingleChildScrollView(
                child: release.notes.trim().isEmpty
                    ? Text(
                        'No release notes.',
                        style: TextStyle(
                          fontSize: 12.5,
                          color: textColor.value,
                        ),
                      )
                    : ReleaseNotesView(
                        release.notes,
                        textColor: textColor.value,
                        headingColor: highlightTextColor.value,
                      ),
              ),
            ),
            const SizedBox(height: 12),
            if (_error != null) ...[
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Icon(Icons.error_outline, size: 16, color: Colors.red),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _error!,
                      style: const TextStyle(fontSize: 12, color: Colors.red),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 10),
            ],
            if (_busy && _handedOff == null) ...[
              ClipRRect(
                borderRadius: BorderRadius.circular(3),
                child: TweenAnimationBuilder<double>(
                  tween: Tween(end: _total > 0 ? _received / _total : 0),
                  duration: activeMotion.medium,
                  curve: activeMotion.standard,
                  builder: (context, value, _) => LinearProgressIndicator(
                    // Indeterminate until a total is known: a bar pinned at
                    // zero for a 600 MB download reads as a hang.
                    value: _total > 0 ? value : null,
                    minHeight: 4,
                    backgroundColor: buttonColor.value,
                    color: seekBarColor.value,
                  ),
                ),
              ),
              const SizedBox(height: 6),
            ],
            _footer(asset),
          ],
        ),
      ),
    );
  }

  Widget _footer(ReleaseAsset? asset) {
    if (_handedOff != null) {
      return Row(
        children: [
          Icon(Icons.check_circle_outline, size: 16, color: seekBarColor.value),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              _handedOff!,
              style: TextStyle(fontSize: 12, color: textColor.value),
            ),
          ),
        ],
      );
    }

    // No asset for this platform is not a failure, but it is also not
    // something the in-app installer can do anything with — so the only
    // honest offer left is the releases page.
    if (asset == null) {
      return Row(
        children: [
          Expanded(
            child: Text(
              'This release has no download for ${Platform.operatingSystem}.',
              style: TextStyle(fontSize: 12, color: textColor.value),
            ),
          ),
          TextButton(
            onPressed: () => launchUrl(
              Uri.parse('https://github.com/$updateRepo/releases'),
            ),
            child: const Text('Open releases'),
          ),
        ],
      );
    }

    return Row(
      children: [
        Expanded(
          child: Text(
            _busy
                ? _total > 0
                      ? '${formatBytes(_received)} of ${formatBytes(_total)}'
                      : 'Starting download…'
                : '${asset.name} · ${formatBytes(asset.sizeBytes)}',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: 11.5, color: textColor.value),
          ),
        ),
        const SizedBox(width: 8),
        if (!_busy)
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Later'),
          ),
        FilledButton(
          onPressed: _busy ? null : _downloadAndInstall,
          child: Text(_busy ? 'Downloading…' : 'Download & install'),
        ),
      ],
    );
  }
}
