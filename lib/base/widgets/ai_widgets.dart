/// "Ask AI": playlists from your library and albums to archive next, on a key
/// you bring yourself.
///
/// Three surfaces share these pieces: the global Ask AI button (a sheet with
/// [AskAiPanel]), the AI picks section on Home, and the provider setup in
/// Settings ([AiSetupPanel]). Asking before setup opens setup instead, with a
/// one-tap route to a free key.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/ai_features.dart';
import 'package:soiboi/base/services/ai_service.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/layer/catalog_sheet.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:soiboi/base/widgets/icon_label.dart';

class AskAiPanel extends StatefulWidget {
  const AskAiPanel({super.key, this.compact = false});

  /// Home shows fewer idea chips.
  final bool compact;

  @override
  State<AskAiPanel> createState() => _AskAiPanelState();
}

class _AskAiPanelState extends State<AskAiPanel> {
  final _prompt = TextEditingController();
  bool _busy = false;
  String? _error;
  AiPlaylist? _playlist;
  List<AiAlbumPick>? _picks;

  static const _ideas = [
    'Late night drive, nothing too loud',
    'Songs to focus and study to',
    'Upbeat Hindi for a road trip',
    'Rainy evening, slow and warm',
  ];

  @override
  void dispose() {
    _prompt.dispose();
    super.dispose();
  }

  Future<void> _run(Future<void> Function() job) async {
    FocusScope.of(context).unfocus();
    setState(() {
      _busy = true;
      _error = null;
      _playlist = null;
      _picks = null;
    });
    try {
      await job();
    } on AiException catch (e) {
      _error = e.message;
    } catch (e) {
      _error = 'Something went wrong: $e';
    }
    if (mounted) setState(() => _busy = false);
  }

  void _makePlaylist() {
    final request = _prompt.text.trim();
    if (request.isEmpty) {
      setState(() => _error = 'Describe the playlist you want first');
      return;
    }
    _run(() async => _playlist = await aiMakePlaylist(request));
  }

  void _recommend() =>
      _run(() async => _picks = await aiRecommendAlbums(_prompt.text));

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: aiConfigNotifier,
      builder: (context, config, _) {
        if (config == null) return _notSetUp(context);
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: _main(config),
        );
      },
    );
  }

  Widget _notSetUp(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          'Make playlists from your library and find albums to archive, '
          'using your own AI key. Gemini and OpenRouter keys are free.',
          style: TextStyle(fontSize: 13, color: textColor.value),
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: () => openAiSetup(context),
          icon: const Icon(Icons.key_rounded),
          label: const Text('Set up AI'),
        ),
      ],
    );
  }

  List<Widget> _main(AiConfig config) {
    return [
      Text(
        'Using ${config.info.label} · ${config.effectiveModel}',
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      const SizedBox(height: 12),
      TextField(
        controller: _prompt,
        minLines: 2,
        maxLines: 4,
        decoration: const InputDecoration(
          hintText: 'Describe a mood, a moment or a sound',
          border: OutlineInputBorder(),
        ),
      ),
      const SizedBox(height: 10),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final idea in widget.compact ? _ideas.take(2) : _ideas)
            ActionChip(
              label: Text(idea),
              onPressed: () => setState(() => _prompt.text = idea),
            ),
        ],
      ),
      const SizedBox(height: 16),
      Row(
        children: [
          Expanded(
            child: FilledButton.icon(
              onPressed: _busy ? null : _makePlaylist,
              icon: const Icon(Icons.queue_music_rounded),
              label: const Text('Make a playlist'),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: OutlinedButton.icon(
              onPressed: _busy ? null : _recommend,
              icon: const Icon(Icons.explore_outlined),
              label: const Text('Discover'),
            ),
          ),
        ],
      ),
      const SizedBox(height: 8),
      Text(
        'Playlists use songs you already have. Discover finds new albums '
        'to archive. Only song tags and artist names are sent.',
        style: TextStyle(fontSize: 11, color: textColor.value),
      ),
      const SizedBox(height: 20),
      if (_busy)
        const Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: CircularProgressIndicator(),
          ),
        ),
      if (_error != null)
        Text(_error!, style: const TextStyle(color: Colors.redAccent)),
      if (_playlist != null) _playlistResult(_playlist!),
      if (_picks != null) ..._pickResults(_picks!),
    ];
  }

  Widget _playlistResult(AiPlaylist p) {
    return Card(
      child: ListTile(
        leading: const Icon(Icons.playlist_add_check_rounded),
        title: Text(p.name),
        subtitle: Text('${p.songs.length} songs saved as a playlist'),
        trailing: const Icon(Icons.chevron_right_rounded),
        onTap: () {
          Navigator.of(context).maybePop();
          layersManager.switchRootLayer('_${p.name}');
        },
      ),
    );
  }

  List<Widget> _pickResults(List<AiAlbumPick> picks) {
    return [
      Text(
        activeFlavour.heading('Worth archiving'),
        style: activeFlavour.headingStyle(
          TextStyle(fontSize: 17, color: highlightTextColor.value),
        ),
      ),
      const SizedBox(height: 4),
      Text(
        'Tap one to see its tracks and archive it.',
        style: TextStyle(fontSize: 12, color: textColor.value),
      ),
      const SizedBox(height: 8),
      for (final p in picks)
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.album_outlined),
          title: Text(p.album),
          subtitle: Text(
            p.why.isEmpty ? p.artist : '${p.artist} · ${p.why}',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
          onTap: () => showCatalogAlbumSheet(context, p.artist, p.album),
        ),
    ];
  }
}

/// Provider, key, model. Checks the key with a real request before saving.
class AiSetupPanel extends StatefulWidget {
  const AiSetupPanel({super.key, required this.initial, required this.onSaved});

  final AiConfig? initial;
  final VoidCallback onSaved;

  @override
  State<AiSetupPanel> createState() => _AiSetupState();
}

class _AiSetupState extends State<AiSetupPanel> {
  late AiProvider _provider = widget.initial?.provider ?? AiProvider.gemini;
  late final _key = TextEditingController(text: widget.initial?.key ?? '');
  late final _model = TextEditingController(text: widget.initial?.model ?? '');
  late final _baseUrl = TextEditingController(
    text: widget.initial?.baseUrl ?? '',
  );
  List<String> _models = const [];
  bool _checking = false;
  String? _status;
  bool _ok = false;

  AiProviderInfo get _info => aiProviders[_provider]!;

  @override
  void dispose() {
    _key.dispose();
    _model.dispose();
    _baseUrl.dispose();
    super.dispose();
  }

  AiConfig get _config => AiConfig(
    provider: _provider,
    key: _key.text,
    model: _model.text,
    baseUrl: _baseUrl.text,
  );

  Future<void> _check() async {
    setState(() {
      _checking = true;
      _status = null;
      _ok = false;
    });
    var config = _config;
    final models = await aiListModels(config);
    if (_model.text.trim().isEmpty) {
      final pick =
          config.info.defaultModel != null &&
              (models.isEmpty || models.contains(config.info.defaultModel))
          ? config.info.defaultModel
          : pickDefaultModel(_provider, models);
      if (pick != null) _model.text = pick;
      config = _config;
    }
    try {
      final reply = await aiComplete(
        config,
        system: 'Reply with the single word OK.',
        prompt: 'Say OK.',
      );
      _ok = reply.trim().isNotEmpty;
      _status = 'Working. ${config.effectiveModel} answered.';
      await saveAiConfig(config);
    } on AiException catch (e) {
      _status = e.message;
    } catch (e) {
      _status = 'Could not reach ${_info.label}: $e';
    }
    if (!mounted) return;
    setState(() {
      _models = models;
      _checking = false;
    });
    if (_ok) widget.onSaved();
  }

  @override
  Widget build(BuildContext context) {
    final free = aiProviders.entries.where((e) => e.value.free);
    final paid = aiProviders.entries.where((e) => !e.value.free);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          activeFlavour.heading('Set up AI'),
          style: activeFlavour.headingStyle(
            TextStyle(fontSize: 20, color: highlightTextColor.value),
          ),
        ),
        const SizedBox(height: 6),
        Text(
          'Soiboi uses your own key, so there is no account and nothing to '
          'pay us. Gemini and OpenRouter have free keys that take about a '
          'minute to get.',
          style: TextStyle(fontSize: 13, color: textColor.value),
        ),
        const SizedBox(height: 16),
        _group('Free', free),
        const SizedBox(height: 8),
        _group('Paid or local', paid),
        const SizedBox(height: 16),
        if (_info.keyPage != null) ...[
          FilledButton.tonalIcon(
            onPressed: () => launchUrl(
              Uri.parse(_info.keyPage!),
              mode: LaunchMode.externalApplication,
            ),
            icon: const Icon(Icons.open_in_new_rounded),
            label: Text(
              _info.free ? 'Get a free ${_info.label} key' : 'Get a key',
            ),
          ),
          const SizedBox(height: 8),
          Text(
            _steps(),
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
          const SizedBox(height: 12),
        ],
        if (_info.needsKey || _provider == AiProvider.custom)
          TextField(
            controller: _key,
            obscureText: true,
            decoration: InputDecoration(
              labelText: _info.needsKey ? 'Paste your key' : 'Key (if needed)',
              border: const OutlineInputBorder(),
            ),
          ),
        if (_provider == AiProvider.ollama ||
            _provider == AiProvider.custom) ...[
          const SizedBox(height: 12),
          TextField(
            controller: _baseUrl,
            decoration: InputDecoration(
              labelText: 'Address',
              hintText: _info.baseUrl.isEmpty
                  ? 'https://host/v1'
                  : _info.baseUrl,
              border: const OutlineInputBorder(),
            ),
          ),
        ],
        const SizedBox(height: 12),
        if (_models.isEmpty)
          TextField(
            controller: _model,
            decoration: InputDecoration(
              labelText: 'Model',
              hintText: _info.defaultModel ?? 'Picked for you when you test',
              border: const OutlineInputBorder(),
            ),
          )
        else
          DropdownButtonFormField<String>(
            initialValue: _models.contains(_model.text) ? _model.text : null,
            isExpanded: true,
            decoration: const InputDecoration(
              labelText: 'Model',
              border: OutlineInputBorder(),
            ),
            items: [
              for (final m in _models)
                DropdownMenuItem(value: m, child: Text(m)),
            ],
            onChanged: (m) => setState(() => _model.text = m ?? ''),
          ),
        const SizedBox(height: 16),
        Row(
          children: [
            FilledButton(
              onPressed: _checking ? null : _check,
              child: Text(_checking ? 'Checking' : 'Test and save'),
            ),
            if (widget.initial != null) ...[
              const SizedBox(width: 12),
              TextButton(
                onPressed: () async {
                  await saveAiConfig(null);
                  widget.onSaved();
                },
                child: const Text('Remove key'),
              ),
            ],
          ],
        ),
        if (_status != null) ...[
          const SizedBox(height: 10),
          Text(
            _status!,
            style: TextStyle(
              fontSize: 13,
              color: _ok ? highlightTextColor.value : Colors.redAccent,
            ),
          ),
        ],
      ],
    );
  }

  Widget _group(
    String title,
    Iterable<MapEntry<AiProvider, AiProviderInfo>> e,
  ) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: TextStyle(fontSize: 12, color: textColor.value)),
        const SizedBox(height: 6),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final entry in e)
              ChoiceChip(
                label: Text(entry.value.label),
                selected: _provider == entry.key,
                onSelected: (_) => setState(() {
                  _provider = entry.key;
                  _models = const [];
                  _model.clear();
                  _status = null;
                }),
              ),
          ],
        ),
        if (e.any((x) => x.key == _provider)) ...[
          const SizedBox(height: 6),
          Text(
            _info.blurb,
            style: TextStyle(fontSize: 12, color: textColor.value),
          ),
        ],
      ],
    );
  }

  String _steps() => switch (_provider) {
    AiProvider.gemini =>
      '1. Sign in with Google.  2. Tap "Create API key".  '
          '3. Copy it and paste it below.',
    AiProvider.openRouter =>
      '1. Sign in.  2. Tap "Create key" (no credit needed for free models).  '
          '3. Copy it and paste it below.',
    AiProvider.groq =>
      '1. Sign in.  2. Tap "Create API Key".  3. Copy it and paste it below.',
    _ => 'Create a key on that page, then paste it below.',
  };
}

/// Provider setup as its own page, from Settings or from a not-set-up prompt.
Future<void> openAiSetup(BuildContext context) {
  return Navigator.of(context).push(
    MaterialPageRoute(
      builder: (context) => Scaffold(
        backgroundColor: pageBackgroundColor.value,
        appBar: AppBar(
          backgroundColor: Colors.transparent,
          title: Text(activeFlavour.heading('AI provider and key')),
        ),
        body: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 40),
          children: [
            AiSetupPanel(
              initial: aiConfigNotifier.value,
              onSaved: () => Navigator.of(context).maybePop(),
            ),
          ],
        ),
      ),
    ),
  );
}

/// The Ask AI sheet the global button opens.
Future<void> showAskAiSheet(BuildContext context) {
  return showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: pageBackgroundColor.value,
    builder: (context) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: ConstrainedBox(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * 0.85,
        ),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 32),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                activeFlavour.heading('Ask AI'),
                style: activeFlavour.headingStyle(
                  TextStyle(fontSize: 20, color: highlightTextColor.value),
                ),
              ),
              const SizedBox(height: 12),
              const AskAiPanel(),
            ],
          ),
        ),
      ),
    ),
  );
}

/// The global Ask AI button, shown on every main page.
class AskAiFab extends StatelessWidget {
  const AskAiFab({super.key});

  @override
  Widget build(BuildContext context) {
    return FloatingActionButton(
      heroTag: 'askAi',
      tooltip: 'Ask AI',
      onPressed: () => showAskAiSheet(context),
      child: labelIcon('Ask AI', const Icon(Icons.auto_awesome_rounded)),
    );
  }
}
