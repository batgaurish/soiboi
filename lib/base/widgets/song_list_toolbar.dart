/// Sort, Filter and Select for the Songs page, in plain sight rather than
/// behind the page's more menu.
library;

import 'dart:math';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/song_filter.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/widgets/quality_badge.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';

/// Every order the Songs page offers, by the sort type [sortSongList] takes.
List<(int, String)> songSortOptions(AppLocalizations l10n) => [
  (0, l10n.defaultText),
  (1, l10n.titleAscending),
  (2, l10n.titleDescending),
  (3, l10n.artistAscending),
  (4, l10n.artistDescending),
  (5, l10n.albumAscending),
  (6, l10n.albumDescending),
  (10, 'Recently added'),
  (9, 'Oldest added'),
  (13, 'Year, newest first'),
  (14, 'Year, oldest first'),
  (15, 'Most played'),
  (7, l10n.durationAscending),
  (8, l10n.durationDescending),
  (11, l10n.randomizeTemp),
];

class SongListToolbar extends StatelessWidget {
  const SongListToolbar({
    super.key,
    required this.sortTypeNotifier,
    required this.songs,
    required this.selectLabel,
    required this.onSelect,
  });

  final ValueNotifier<int> sortTypeNotifier;

  /// Every song the page could show, before filtering: the filter offers
  /// only codecs and genres that are actually there.
  final List<MyAudioMetadata> songs;

  final String selectLabel;
  final VoidCallback onSelect;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final options = songSortOptions(l10n);
    return Wrap(
      spacing: 8,
      runSpacing: 4,
      children: [
        ValueListenableBuilder(
          valueListenable: sortTypeNotifier,
          builder: (context, sortType, _) {
            final label = options
                .firstWhere(
                  (o) => o.$1 == sortType,
                  orElse: () => options.first,
                )
                .$2;
            return Builder(
              builder: (buttonContext) => _ToolbarButton(
                icon: Icons.sort_rounded,
                label: 'Sort: $label',
                onPressed: () => showContextMenu(context, [
                  for (final (type, text) in options)
                    MenuItem(
                      iconData: type == sortType ? Icons.check_rounded : null,
                      text: text,
                      callback: () => sortTypeNotifier.value = type,
                    ),
                ], _below(buttonContext)),
              ),
            );
          },
        ),
        ValueListenableBuilder(
          valueListenable: songFilterNotifier,
          builder: (context, filter, _) => _ToolbarButton(
            icon: Icons.filter_list_rounded,
            label: filter.isEmpty ? 'Filter' : 'Filter · ${filter.activeCount}',
            selected: !filter.isEmpty,
            onPressed: () => showSongFilter(context, songs),
          ),
        ),
        _ToolbarButton(
          icon: Icons.checklist_rounded,
          label: selectLabel,
          onPressed: onSelect,
        ),
      ],
    );
  }

  static Offset _below(BuildContext context) {
    final box = context.findRenderObject() as RenderBox?;
    if (box == null) return Offset.zero;
    return box.localToGlobal(Offset(0, box.size.height));
  }
}

class _ToolbarButton extends StatelessWidget {
  const _ToolbarButton({
    required this.icon,
    required this.label,
    required this.onPressed,
    this.selected = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback onPressed;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final color = colorManager.getSpecificTextColor();
    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 18, color: color),
      label: Text(label, style: TextStyle(color: color)),
      style: OutlinedButton.styleFrom(
        visualDensity: VisualDensity.compact,
        backgroundColor: selected
            ? colorManager.getSpecificButtonColor()
            : null,
        side: BorderSide(color: color.withAlpha(selected ? 200 : 90)),
      ),
    );
  }
}

/// The filter dialog. Changes apply as they are made.
Future<void> showSongFilter(
  BuildContext context,
  List<MyAudioMetadata> songs,
) async {
  final available = filterOptions(songs);
  await showAnimationDialog(
    context: context,
    child: ConstrainedBox(
      constraints: BoxConstraints(
        maxWidth: min(420, MediaQuery.widthOf(context) - 32),
        maxHeight: MediaQuery.heightOf(context) * 0.8,
      ),
      child: ValueListenableBuilder(
        valueListenable: songFilterNotifier,
        builder: (context, filter, _) {
          final text = colorManager.getSpecificTextColor();
          void set(SongFilter next) => songFilterNotifier.value = next;
          Widget heading(String label) => Padding(
            padding: const EdgeInsets.only(top: 16, bottom: 8),
            child: Semantics(
              header: true,
              child: Text(
                label,
                style: TextStyle(
                  color: text,
                  fontWeight: FontWeight.bold,
                  fontSize: 15,
                ),
              ),
            ),
          );
          Widget chip(String label, bool on, ValueChanged<bool> onChanged) =>
              FilterChip(
                label: Text(label, style: TextStyle(color: text)),
                selected: on,
                onSelected: onChanged,
                checkmarkColor: text,
                selectedColor: colorManager.getSpecificButtonColor(),
                side: BorderSide(color: text.withAlpha(90)),
                backgroundColor: Colors.transparent,
              );
          Set<String> toggled(Set<String> set, String value, bool on) =>
              on ? {...set, value} : ({...set}..remove(value));

          return Padding(
            padding: const EdgeInsets.fromLTRB(20, 20, 20, 12),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Filter songs',
                  style: TextStyle(
                    color: text,
                    fontSize: 22,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                Flexible(
                  child: SingleChildScrollView(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        heading('Quality'),
                        Wrap(
                          spacing: 8,
                          runSpacing: 4,
                          children: [
                            for (final (tier, label) in [
                              (null, 'Any'),
                              (QualityTier.lossless, 'Lossless'),
                              (QualityTier.lossy, 'Lossy'),
                            ])
                              chip(
                                label,
                                filter.quality == tier,
                                (_) =>
                                    set(filter.copyWith(quality: () => tier)),
                              ),
                          ],
                        ),
                        if (available.codecs.length > 1) ...[
                          heading('Format'),
                          Wrap(
                            spacing: 8,
                            runSpacing: 4,
                            children: [
                              for (final MapEntry(key: codec, value: count)
                                  in available.codecs.entries)
                                chip(
                                  '$codec ($count)',
                                  filter.codecs.contains(codec),
                                  (on) => set(
                                    filter.copyWith(
                                      codecs: toggled(filter.codecs, codec, on),
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ],
                        if (available.genres.length > 1) ...[
                          heading('Genre'),
                          Wrap(
                            spacing: 8,
                            runSpacing: 4,
                            children: [
                              for (final MapEntry(key: genre, value: count)
                                  in available.genres.entries)
                                chip(
                                  '$genre ($count)',
                                  filter.genres.contains(genre),
                                  (on) => set(
                                    filter.copyWith(
                                      genres: toggled(filter.genres, genre, on),
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ],
                        const SizedBox(height: 8),
                        SwitchListTile(
                          contentPadding: EdgeInsets.zero,
                          title: Text(
                            'Favourites only',
                            style: TextStyle(color: text),
                          ),
                          value: filter.favoritesOnly,
                          onChanged: (on) =>
                              set(filter.copyWith(favoritesOnly: on)),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Row(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    TextButton(
                      onPressed: filter.isEmpty
                          ? null
                          : () => set(const SongFilter()),
                      child: Text('Clear all', style: TextStyle(color: text)),
                    ),
                    const SizedBox(width: 8),
                    ElevatedButton(
                      onPressed: () => Navigator.pop(context),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: colorManager.getSpecificButtonColor(),
                        foregroundColor: text,
                      ),
                      child: const Text('Done'),
                    ),
                  ],
                ),
              ],
            ),
          );
        },
      ),
    ),
  );
}
