/// The error catalog's fix for a failed download, as a button that does it.
///
/// Each catalog entry names one fix (sign in, choose a folder, use AAC...).
/// [runFailureFix] carries it out and, when that removes the cause, queues
/// the download again, so fixing is one tap rather than fix, then retry.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/cookie_store.dart' as cookie_store;
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/error_catalog.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';
import 'package:soiboi/base/services/update_service.dart';
import 'package:soiboi/base/services/wrapper_service.dart';
import 'package:soiboi/base/widgets/download_options.dart';
import 'package:soiboi/base/widgets/lossless_setup.dart';
import 'package:soiboi/layer/apple_signin_layer.dart';
import 'package:soiboi/layer/download_queue_sheet.dart';
import 'package:soiboi/layer/update_sheet.dart';

/// Red for failure text, nudged until it reads on each surface the queue
/// appears on (the Downloads card, the queue sheet, the page). Plain red is
/// under 4.5:1 on several palettes.
Color failureTextColor() => ensureContrastOnAll(Colors.red, [
  menuColor.value,
  panelColor.value,
  pageBackgroundColor.value,
]);

/// Does [job]'s fix, and queues it again if the fix cleared the cause.
Future<void> runFailureFix(BuildContext context, DownloadJob job) async {
  final failure = job.failure;
  if (failure == null) return;
  switch (failure.fix) {
    case FailureFix.retry:
      downloadQueue.retry(job);
    case FailureFix.skip:
      downloadQueue.dismiss(job);
    case FailureFix.openLog:
      await showDownloadLog(context, job);
    case FailureFix.signIn:
      await Navigator.of(
        context,
      ).push(MaterialPageRoute(builder: (_) => const AppleSignInLayer()));
      await cookie_store.refreshSessionState();
      if (cookie_store.hasAppleAuth) downloadQueue.retry(job);
    case FailureFix.chooseFolder:
      final before = downloadOutputDir;
      await showDownloadFolderDialog(context);
      // Only a different folder can change the outcome.
      if (downloadOutputDir != before) downloadQueue.retry(job);
    case FailureFix.setUpWrapper:
      await showAnimationDialog(context: context, child: const LosslessSetup());
      if (wrapperService.signedIn.value) downloadQueue.retry(job);
    case FailureFix.changeQuality:
      downloadCodecNotifier.value = 'aac';
      setting.save();
      showCenterMessage(
        'Downloading in AAC from now on. Change it back in Settings.',
        duration: 4000,
      );
      downloadQueue.retry(job);
    case FailureFix.update:
      showCenterMessage('Checking for updates…');
      final check = await checkForUpdate();
      if (!context.mounted) return;
      switch (check.state) {
        case UpdateState.available:
          await showUpdateSheet(context, check.release!);
        case UpdateState.upToDate:
          showCenterMessage('Soiboi is up to date.');
        case UpdateState.failed:
          showCenterMessage(
            'Could not check for updates: ${check.error}',
            duration: 5000,
          );
      }
  }
}

/// The fix for a failed [job], named for a screen reader with the download
/// it applies to ("Sign in: Iktara").
class FailureFixButton extends StatelessWidget {
  const FailureFixButton({super.key, required this.job, this.compact = false});

  final DownloadJob job;

  /// Tighter, for a queue row.
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final failure = job.failure;
    if (failure == null) return const SizedBox.shrink();
    final label = failure.fix.label;
    return TextButton(
      style: compact
          ? TextButton.styleFrom(
              visualDensity: VisualDensity.compact,
              padding: const EdgeInsets.symmetric(horizontal: 8),
            )
          : null,
      onPressed: () => runFailureFix(context, job),
      child: Text(label, semanticsLabel: '$label: ${job.label}'),
    );
  }
}
