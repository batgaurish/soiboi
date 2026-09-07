/// The smart playlists screen: the list, and the editor that defines one.
///
/// Opening a playlist hands it to the ordinary playlist screen, so playback,
/// queueing and sorting all behave exactly as they do everywhere else — the
/// only thing that is different about a smart playlist is where its songs come
/// from.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/smart_playlist.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/base/widgets/song_list.dart';
import 'package:soiboi/portrait_view/custom_appbar_leading.dart';

class SmartPlaylistsLayer extends StatefulWidget {
  const SmartPlaylistsLayer({super.key});

  @override
  State<SmartPlaylistsLayer> createState() => _SmartPlaylistsLayerState();
}

class _SmartPlaylistsLayerState extends State<SmartPlaylistsLayer> {
  @override
  Widget build(BuildContext context) {
    final playlists = smartPlaylists.playlists;

    return Scaffold(
      extendBodyBehindAppBar: true,
      backgroundColor: Colors.transparent,
      resizeToAvoidBottomInset: false,
      appBar: AppBar(
        automaticallyImplyLeading: false,
        // Same reason as Downloads: on a narrow layout the drawer is the only
        // way out, and this screen has no portrait wrapper to supply one.
        leading: isTooNarrow(context) ? customAppBarLeading(context) : null,
        title: const Text('Smart playlists'),
        centerTitle: true,
        backgroundColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        actions: [
          IconButton(
            icon: const Icon(Icons.add_rounded),
            tooltip: 'New smart playlist',
            onPressed: () => _edit(null),
          ),
        ],
      ),
      body: playlists.isEmpty
          ? _empty()
          : ListView.builder(
              // The body extends behind the transparent app bar, so the list
              // has to start below it — otherwise the first row renders under
              // the title and the status bar.
              padding: EdgeInsets.only(
                top: MediaQuery.paddingOf(context).top + kToolbarHeight + 8,
                bottom: 90,
              ),
              itemCount: playlists.length,
              itemBuilder: (context, i) => _row(playlists[i]),
            ),
    );
  }

  Widget _empty() => Center(
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 40),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.auto_awesome_outlined, size: 42, color: textColor.value),
          const SizedBox(height: 14),
          Text(
            'Nothing here yet',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: highlightTextColor.value,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            'A smart playlist stores a question rather than a list of songs, '
            'and answers it against your library every time you open it — so '
            '"lossless tracks I have not played this year" stays true as the '
            'library grows.',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 12.5, color: textColor.value),
          ),
          const SizedBox(height: 18),
          FilledButton.icon(
            onPressed: () => _edit(null),
            icon: const Icon(Icons.add_rounded, size: 18),
            label: const Text('New smart playlist'),
          ),
        ],
      ),
    ),
  );

  Widget _row(SmartPlaylist playlist) {
    // Counted now rather than stored: the answer changes with the library, and
    // a stale number beside a live playlist is worse than no number.
    final count = playlist.evaluate(library.songList).length;

    return ListTile(
      leading: Icon(Icons.auto_awesome_outlined, color: textColor.value),
      title: Text(
        playlist.name,
        style: TextStyle(fontSize: 14.5, color: highlightTextColor.value),
      ),
      subtitle: Text(
        _describe(playlist, count),
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 11.5, color: textColor.value),
      ),
      trailing: IconButton(
        icon: const Icon(Icons.edit_outlined, size: 20),
        tooltip: 'Edit rules',
        onPressed: () => _edit(playlist),
      ),
      onTap: () {
        tryVibrate();
        // Pushed on the root navigator: the layer system nests one per root
        // layer, and a route pushed into this screen's own navigator would be
        // hidden behind the sidebar on a wide layout.
        Navigator.of(context, rootNavigator: true).push(
          MaterialPageRoute(
            builder: (_) => SongList(
              playlist: SmartPlaylistView(playlist),
              isRoot: false,
            ),
          ),
        );
      },
    );
  }

  /// A one-line reading of the rules, so the list says what each playlist
  /// means without opening the editor.
  String _describe(SmartPlaylist playlist, int count) {
    final joiner = playlist.matchAll ? ' and ' : ' or ';
    final rules = playlist.rules
        .map((r) => '${r.field.label} ${r.operator.label} ${r.value}')
        .join(joiner);
    return [
      count == 1 ? '1 track' : '$count tracks',
      if (rules.isNotEmpty) rules,
      playlist.sort.label,
    ].join(' · ');
  }

  Future<void> _edit(SmartPlaylist? existing) async {
    final saved = await showAnimationDialog<bool>(
      context: context,
      child: SizedBox(
        width: 480,
        height: 560,
        child: _SmartPlaylistEditor(existing: existing),
      ),
    );
    if (saved == true && mounted) setState(() {});
  }
}

class _SmartPlaylistEditor extends StatefulWidget {
  const _SmartPlaylistEditor({this.existing});
  final SmartPlaylist? existing;

  @override
  State<_SmartPlaylistEditor> createState() => _SmartPlaylistEditorState();
}

class _SmartPlaylistEditorState extends State<_SmartPlaylistEditor> {
  late final TextEditingController _name;
  late final TextEditingController _limit;
  late List<SmartRule> _rules;
  late bool _matchAll;
  late SmartSort _sort;
  late bool _descending;

  @override
  void initState() {
    super.initState();
    final existing = widget.existing;
    _name = TextEditingController(text: existing?.name ?? '');
    _limit = TextEditingController(text: existing?.limit?.toString() ?? '');
    _rules = [...?existing?.rules];
    _matchAll = existing?.matchAll ?? true;
    _sort = existing?.sort ?? SmartSort.added;
    _descending = existing?.descending ?? true;
  }

  @override
  void dispose() {
    _name.dispose();
    _limit.dispose();
    super.dispose();
  }

  SmartPlaylist get _draft => SmartPlaylist(
    name: _name.text.trim(),
    rules: _rules,
    matchAll: _matchAll,
    sort: _sort,
    descending: _descending,
    limit: int.tryParse(_limit.text.trim()),
  );

  @override
  Widget build(BuildContext context) {
    // Live, because the whole point of rules is hard to picture until you see
    // what they select.
    final matches = _draft.evaluate(library.songList).length;

    return Padding(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            widget.existing == null ? 'New smart playlist' : 'Edit rules',
            style: TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w600,
              color: highlightTextColor.value,
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _name,
            style: const TextStyle(fontSize: 13),
            decoration: const InputDecoration(
              isDense: true,
              border: OutlineInputBorder(),
              labelText: 'Name',
            ),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Text(
                'Match',
                style: TextStyle(fontSize: 12, color: textColor.value),
              ),
              const SizedBox(width: 10),
              SegmentedButton<bool>(
                showSelectedIcon: false,
                style: const ButtonStyle(visualDensity: VisualDensity.compact),
                segments: const [
                  ButtonSegment(value: true, label: Text('All rules')),
                  ButtonSegment(value: false, label: Text('Any rule')),
                ],
                selected: {_matchAll},
                onSelectionChanged: (s) => setState(() => _matchAll = s.first),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Expanded(
            child: _rules.isEmpty
                ? Center(
                    child: Text(
                      'No rules yet — this would select the whole library.',
                      style: TextStyle(fontSize: 12, color: textColor.value),
                    ),
                  )
                : ListView.builder(
                    itemCount: _rules.length,
                    itemBuilder: (context, i) => _ruleRow(i),
                  ),
          ),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton.icon(
              onPressed: () => setState(
                () => _rules.add(
                  const SmartRule(
                    field: SmartField.artist,
                    operator: SmartOperator.contains,
                    value: '',
                  ),
                ),
              ),
              icon: const Icon(Icons.add_rounded, size: 18),
              label: const Text('Add rule'),
            ),
          ),
          const Divider(height: 18),
          Row(
            children: [
              Expanded(
                child: DropdownButton<SmartSort>(
                  isExpanded: true,
                  value: _sort,
                  dropdownColor: menuColor.value,
                  style: TextStyle(fontSize: 12, color: textColor.value),
                  items: [
                    for (final sort in SmartSort.values)
                      DropdownMenuItem(value: sort, child: Text(sort.label)),
                  ],
                  onChanged: (sort) => setState(() => _sort = sort ?? _sort),
                ),
              ),
              // Meaningless for a shuffle, so it is not offered there.
              if (_sort != SmartSort.random)
                IconButton(
                  tooltip: _descending ? 'Descending' : 'Ascending',
                  onPressed: () => setState(() => _descending = !_descending),
                  icon: Icon(
                    _descending
                        ? Icons.arrow_downward_rounded
                        : Icons.arrow_upward_rounded,
                    size: 18,
                  ),
                ),
              const SizedBox(width: 8),
              SizedBox(
                width: 92,
                child: TextField(
                  controller: _limit,
                  keyboardType: TextInputType.number,
                  style: const TextStyle(fontSize: 12),
                  decoration: const InputDecoration(
                    isDense: true,
                    border: OutlineInputBorder(),
                    labelText: 'Limit',
                  ),
                  onChanged: (_) => setState(() {}),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: Text(
                  matches == 1 ? '1 track matches' : '$matches tracks match',
                  style: TextStyle(fontSize: 12, color: textColor.value),
                ),
              ),
              if (widget.existing != null)
                TextButton(
                  onPressed: () async {
                    await smartPlaylists.remove(widget.existing!.name);
                    if (context.mounted) Navigator.of(context).pop(true);
                  },
                  child: const Text('Delete'),
                ),
              const SizedBox(width: 6),
              FilledButton(
                // A playlist with no name cannot be found again, so that is
                // the one thing required.
                onPressed: _name.text.trim().isEmpty ? null : _save,
                child: const Text('Save'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _ruleRow(int index) {
    final rule = _rules[index];
    final operators = SmartOperator.forKind(rule.field.kind);

    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        children: [
          Expanded(
            flex: 4,
            child: DropdownButton<SmartField>(
              isExpanded: true,
              value: rule.field,
              dropdownColor: menuColor.value,
              style: TextStyle(fontSize: 12, color: textColor.value),
              items: [
                for (final field in SmartField.values)
                  DropdownMenuItem(value: field, child: Text(field.label)),
              ],
              onChanged: (field) {
                if (field == null) return;
                setState(() {
                  // The operator has to be re-picked with the field: "contains"
                  // is meaningless on a play count, and keeping it would make a
                  // rule that silently matches nothing.
                  final allowed = SmartOperator.forKind(field.kind);
                  _rules[index] = SmartRule(
                    field: field,
                    operator: allowed.contains(rule.operator)
                        ? rule.operator
                        : allowed.first,
                    value: rule.value,
                  );
                });
              },
            ),
          ),
          const SizedBox(width: 6),
          Expanded(
            flex: 4,
            child: DropdownButton<SmartOperator>(
              isExpanded: true,
              value: operators.contains(rule.operator)
                  ? rule.operator
                  : operators.first,
              dropdownColor: menuColor.value,
              style: TextStyle(fontSize: 12, color: textColor.value),
              items: [
                for (final op in operators)
                  DropdownMenuItem(value: op, child: Text(op.label)),
              ],
              onChanged: (op) => setState(() {
                if (op == null) return;
                _rules[index] = SmartRule(
                  field: rule.field,
                  operator: op,
                  value: rule.value,
                );
              }),
            ),
          ),
          const SizedBox(width: 6),
          Expanded(flex: 3, child: _valueField(index, rule)),
          IconButton(
            visualDensity: VisualDensity.compact,
            icon: const Icon(Icons.close_rounded, size: 18),
            tooltip: 'Remove rule',
            onPressed: () => setState(() => _rules.removeAt(index)),
          ),
        ],
      ),
    );
  }

  Widget _valueField(int index, SmartRule rule) {
    if (rule.field.kind == SmartFieldKind.flag) {
      final isYes = rule.value.toLowerCase() != 'no';
      return DropdownButton<bool>(
        isExpanded: true,
        value: isYes,
        dropdownColor: menuColor.value,
        style: TextStyle(fontSize: 12, color: textColor.value),
        items: const [
          DropdownMenuItem(value: true, child: Text('Yes')),
          DropdownMenuItem(value: false, child: Text('No')),
        ],
        onChanged: (yes) => setState(() {
          _rules[index] = SmartRule(
            field: rule.field,
            operator: rule.operator,
            value: (yes ?? true) ? 'yes' : 'no',
          );
        }),
      );
    }

    return TextField(
      // Keyed by position and field so the box keeps its text while the rule
      // is edited, but resets when the rule type changes under it.
      key: ValueKey('$index-${rule.field.name}'),
      controller: TextEditingController(text: rule.value)
        ..selection = TextSelection.collapsed(offset: rule.value.length),
      keyboardType: rule.field.kind == SmartFieldKind.text
          ? TextInputType.text
          : TextInputType.number,
      style: const TextStyle(fontSize: 12),
      decoration: const InputDecoration(isDense: true, border: UnderlineInputBorder()),
      onChanged: (value) {
        // No setState: rebuilding here would recreate the controller and drop
        // the caret. The match count updates on the next rebuild instead.
        _rules[index] = SmartRule(
          field: rule.field,
          operator: rule.operator,
          value: value,
        );
      },
      onEditingComplete: () => setState(() {}),
    );
  }

  Future<void> _save() async {
    await smartPlaylists.upsert(_draft, replacing: widget.existing?.name);
    if (mounted) Navigator.of(context).pop(true);
  }
}
