/// Connecting a ListenBrainz username, checked against ListenBrainz before it
/// is saved. Shared by the Settings dialog and the setup wizard.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/listenbrainz_service.dart';

class ListenBrainzForm extends StatefulWidget {
  const ListenBrainzForm({super.key, this.onDone, this.autofocus = false});

  /// Called after a username is connected or disconnected.
  final VoidCallback? onDone;
  final bool autofocus;

  @override
  State<ListenBrainzForm> createState() => _ListenBrainzFormState();
}

class _ListenBrainzFormState extends State<ListenBrainzForm> {
  late final _controller = TextEditingController(
    text: listenBrainzUserNotifier.value,
  );
  String? _status;
  bool _checking = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _connect() async {
    final user = _controller.text.trim();
    if (user.isEmpty || _checking) return;
    setState(() {
      _checking = true;
      _status = 'Checking…';
    });
    final ok = await verifyListenBrainzUser(user);
    if (!mounted) return;
    setState(() {
      _checking = false;
      _status = ok ? 'Connected as $user' : 'No such ListenBrainz user';
    });
    if (!ok) return;
    listenBrainzUserNotifier.value = user;
    setting.save();
    widget.onDone?.call();
  }

  void _disconnect() {
    listenBrainzUserNotifier.value = '';
    setting.save();
    _controller.clear();
    setState(() => _status = null);
    widget.onDone?.call();
  }

  @override
  Widget build(BuildContext context) {
    // Stretched, so the buttons' Wrap spans the width and can sit at the end.
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _controller,
          autofocus: widget.autofocus,
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => _connect(),
          decoration: const InputDecoration(
            labelText: 'ListenBrainz username',
            isDense: true,
            border: OutlineInputBorder(),
          ),
        ),
        if (_status != null) ...[
          const SizedBox(height: 10),
          // Read out when it changes, so a screen reader hears the answer
          // to "Connect" without hunting for it.
          Semantics(
            liveRegion: true,
            child: Text(
              _status!,
              style: TextStyle(fontSize: 12, color: textColor.value),
            ),
          ),
        ],
        const SizedBox(height: 12),
        ValueListenableBuilder<String>(
          valueListenable: listenBrainzUserNotifier,
          builder: (context, user, _) => Wrap(
            alignment: WrapAlignment.end,
            spacing: 8,
            children: [
              if (user.isNotEmpty)
                TextButton(
                  onPressed: _disconnect,
                  child: const Text('Disconnect'),
                ),
              FilledButton(
                onPressed: _checking ? null : _connect,
                child: const Text('Connect'),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
