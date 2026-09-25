/// The Downloads screen's status panel: every "why can't I download" the
/// app can detect, one line each, with the one button that fixes it.
///
/// Always shown. When nothing needs attention it folds to a single "Ready"
/// line that opens on request; any problem opens it by itself.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/cookie_store.dart' as cookie_store;
import 'package:soiboi/base/services/download_status.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';
import 'package:soiboi/base/services/wrapper_service.dart';
import 'package:soiboi/base/widgets/download_options.dart';
import 'package:soiboi/base/widgets/lossless_setup.dart';
import 'package:soiboi/layer/apple_signin_layer.dart';

class DownloadStatusPanel extends StatefulWidget {
  const DownloadStatusPanel({super.key});

  @override
  State<DownloadStatusPanel> createState() => _DownloadStatusPanelState();
}

class _DownloadStatusPanelState extends State<DownloadStatusPanel> {
  int? _freeBytes;
  bool? _online;
  bool _checking = false;
  bool _open = false;

  @override
  void initState() {
    super.initState();
    StatusInputs.changes.addListener(_changed);
    WidgetsBinding.instance.addPostFrameCallback((_) => _check());
  }

  @override
  void dispose() {
    StatusInputs.changes.removeListener(_changed);
    super.dispose();
  }

  void _changed() {
    if (mounted) setState(() {});
  }

  /// Free space and the network, which no notifier reports.
  Future<void> _check() async {
    if (_checking) return;
    setState(() => _checking = true);
    final inputs = await StatusInputs.gather();
    if (!mounted) return;
    setState(() {
      _freeBytes = inputs.freeBytes;
      _online = inputs.online;
      _checking = false;
    });
  }

  Future<void> _fix(StatusFix fix) async {
    switch (fix) {
      case StatusFix.signIn:
        await Navigator.of(
          context,
        ).push(MaterialPageRoute(builder: (_) => const AppleSignInLayer()));
        await cookie_store.refreshSessionState();
      case StatusFix.setUpLossless:
        await showAnimationDialog(
          context: context,
          child: const LosslessSetup(),
        );
      case StatusFix.startWrapper:
        await wrapperService.start();
      case StatusFix.chooseFolder:
        await showDownloadFolderDialog(context);
        await _check();
      case StatusFix.recheck:
        await Future.wait([refreshPipelineCapabilities(), _check()]);
    }
  }

  @override
  Widget build(BuildContext context) {
    final checks = buildStatusChecks(
      StatusInputs.live(freeBytes: _freeBytes, online: _online),
    );
    final problems = checks.where((c) => c.level == StatusLevel.problem).length;
    final warnings = checks.where((c) => c.level == StatusLevel.warning).length;
    final open = _open || problems > 0;
    final summary = problems > 0
        ? problems == 1
              ? '1 problem stops downloads'
              : '$problems problems stop downloads'
        : warnings > 0
        ? 'Ready to archive, ${warnings == 1 ? '1 thing' : '$warnings things'} to look at'
        : 'Ready to archive';

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // Wraps rather than squeezing: at large text sizes the buttons move
        // under the summary.
        Wrap(
          alignment: WrapAlignment.spaceBetween,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Semantics(
              liveRegion: true,
              child: Text(
                _checking ? '$summary (checking…)' : summary,
                style: TextStyle(
                  fontSize: 13.5,
                  color: highlightTextColor.value,
                ),
              ),
            ),
            Wrap(
              children: [
                if (problems == 0)
                  TextButton(
                    onPressed: () => setState(() => _open = !_open),
                    child: Text(open ? 'Hide details' : 'Details'),
                  ),
                TextButton(
                  onPressed: _checking ? null : () => _fix(StatusFix.recheck),
                  child: const Text('Check again'),
                ),
              ],
            ),
          ],
        ),
        if (open)
          for (final check in checks) ...[
            const SizedBox(height: 10),
            _row(check),
          ],
      ],
    );
  }

  Widget _row(StatusCheck check) {
    final (icon, word) = switch (check.level) {
      StatusLevel.ok => (Icons.check_circle_outline, 'OK'),
      StatusLevel.warning => (Icons.warning_amber_rounded, 'Warning'),
      StatusLevel.problem => (Icons.error_outline, 'Problem'),
      StatusLevel.info => (Icons.info_outline, 'Note'),
    };
    final fix = check.fix;
    return Row(
      children: [
        Expanded(
          // Read as one item: "Problem. Apple Music account. Not signed in."
          child: MergeSemantics(
            child: Row(
              children: [
                Icon(
                  icon,
                  size: 18,
                  semanticLabel: word,
                  color: check.level == StatusLevel.ok
                      ? seekBarColor.value
                      : highlightTextColor.value,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        check.label,
                        style: TextStyle(
                          fontSize: 13.5,
                          color: highlightTextColor.value,
                        ),
                      ),
                      Text(
                        check.detail,
                        style: TextStyle(
                          fontSize: 11.5,
                          color: textColor.value,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        if (fix != null)
          TextButton(
            onPressed: () => _fix(fix),
            child: Text(
              statusFixLabel(fix),
              semanticsLabel: '${statusFixLabel(fix)}: ${check.label}',
            ),
          ),
      ],
    );
  }
}
