/// Guided setup for lossless downloads through the bundled wrapper.
///
/// Four steps, each shown only when it is the one left to do: get the right
/// Apple Music file, install its libraries, start the wrapper, sign in (with a
/// two-factor code when Apple asks). Like a patcher that names the exact APK
/// it needs, the first step links straight to the one build that works.
library;

import 'package:file_picker/file_picker.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/wrapper_service.dart';
import 'package:url_launcher/url_launcher.dart';

class LosslessSetup extends StatefulWidget {
  const LosslessSetup({super.key});

  @override
  State<LosslessSetup> createState() => _LosslessSetupState();
}

class _LosslessSetupState extends State<LosslessSetup> {
  final _appleId = TextEditingController();
  final _password = TextEditingController();
  final _code = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    wrapperService.refresh();
  }

  @override
  void dispose() {
    _appleId.dispose();
    _password.dispose();
    _code.dispose();
    super.dispose();
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pickApk() => _run(() async {
    final picked = await FilePicker.pickFiles(
      dialogTitle: 'Choose the Apple Music .apkm or .apk',
    );
    final path = picked?.files.single.path;
    if (path == null) return;
    _error = await wrapperService.installLibraries(path);
    if (_error == null) await wrapperService.start();
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 420,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: ValueListenableBuilder<WrapperState>(
          valueListenable: wrapperService.state,
          builder: (context, state, _) => Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Lossless (ALAC)',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 12),
              ..._step(state),
              if (_error ?? state.message case final message?) ...[
                const SizedBox(height: 12),
                Text(message, style: const TextStyle(color: Colors.redAccent)),
              ],
              if (_busy) ...[
                const SizedBox(height: 12),
                const LinearProgressIndicator(),
              ],
            ],
          ),
        ),
      ),
    );
  }

  List<Widget> _step(WrapperState state) => switch (state.stage) {
    WrapperStage.unsupported => [
      _text(
        'This build cannot run the lossless wrapper yet. AAC downloads work '
        'as normal.',
      ),
    ],
    WrapperStage.needsLibraries => [
      _text(
        'Apple Music decrypts ALAC with its own libraries, and Soiboi cannot '
        'ship them. Download this exact build, then choose the file here. '
        'Soiboi checks every library against a pinned hash, so another '
        'version will not work.',
      ),
      const SizedBox(height: 12),
      OutlinedButton.icon(
        icon: const Icon(Icons.open_in_new_rounded),
        label: const Text(appleMusicApkLabel),
        onPressed: () => launchUrl(
          Uri.parse(appleMusicApkUrl),
          mode: LaunchMode.externalApplication,
        ),
      ),
      const SizedBox(height: 8),
      FilledButton(
        onPressed: _busy ? null : _pickApk,
        child: const Text('Choose the downloaded file'),
      ),
    ],
    WrapperStage.stopped || WrapperStage.failed => [
      _text('The Apple Music libraries are installed.'),
      const SizedBox(height: 12),
      FilledButton(
        onPressed: _busy ? null : () => _run(wrapperService.start),
        child: const Text('Start the wrapper'),
      ),
      TextButton(
        onPressed: _busy ? null : _pickApk,
        child: const Text('Reinstall from a file'),
      ),
    ],
    WrapperStage.starting => [_text('Starting the wrapper…')],
    WrapperStage.signedOut => [
      _text(
        'Sign in with the Apple ID that has your Apple Music subscription. '
        'The password goes to the wrapper on this device, which passes it to '
        "Apple's own sign-in. Soiboi does not store it.",
      ),
      const SizedBox(height: 12),
      TextField(
        controller: _appleId,
        decoration: const InputDecoration(labelText: 'Apple ID'),
        keyboardType: TextInputType.emailAddress,
        autofillHints: const [AutofillHints.username],
      ),
      TextField(
        controller: _password,
        decoration: const InputDecoration(labelText: 'Password'),
        obscureText: true,
        autofillHints: const [AutofillHints.password],
      ),
      const SizedBox(height: 12),
      FilledButton(
        onPressed: _busy
            ? null
            : () => _run(() async {
                await wrapperService.signIn(_appleId.text.trim(), _password.text);
                _password.clear();
              }),
        child: const Text('Sign in'),
      ),
    ],
    WrapperStage.needsCode => [
      _text('Enter the six-digit code Apple sent to your devices.'),
      TextField(
        controller: _code,
        decoration: const InputDecoration(labelText: 'Verification code'),
        keyboardType: TextInputType.number,
        maxLength: 6,
      ),
      FilledButton(
        onPressed: _busy ? null : () => _run(() => wrapperService.submitCode(_code.text)),
        child: const Text('Verify'),
      ),
    ],
    WrapperStage.ready => [
      _text(
        'Lossless is ready${state.account == null ? '' : ' for ${state.account}'}. '
        'Choose ALAC under Download quality to use it.',
      ),
      const SizedBox(height: 12),
      TextButton(
        onPressed: _busy ? null : () => _run(wrapperService.signOut),
        child: const Text('Sign out'),
      ),
    ],
  };

  Widget _text(String text) =>
      Text(text, style: TextStyle(fontSize: 13, color: textColor.value));
}
