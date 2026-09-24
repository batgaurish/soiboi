/// One song, as a screen reader should meet it: a single item that says
/// "One More Time, Daft Punk, Discovery, 5:20", and plays when activated.
///
/// Rows draw the title, artist, album and duration as separate texts, and a
/// screen reader would otherwise stop on each one. A custom row wraps its
/// texts in [ExcludeSemantics] and itself in [SongSemantics.new], which then
/// owns the row's actions. A [ListTile] already makes its own item with its
/// own tap, so it takes [SongSemantics.title] as its title instead, and the
/// label lands on the tile's item. Either way, buttons inside the row
/// (favorite, more options) stay their own items.
///
/// The keyboard gets the same two actions: with the row focused, Enter plays
/// it and the Menu key (or Shift+F10) opens its options. Space plays or
/// pauses, as it does everywhere else, rather than selecting the row; with
/// nothing queued yet, it plays the row.
library;

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';
import 'package:soiboi/base/utils/semantics_labels.dart';

class SongSemantics extends StatelessWidget {
  const SongSemantics({
    super.key,
    required this.song,
    required this.child,
    this.onTap,
    this.onLongPress,
    this.includeAlbum = true,
    this.extra,
  }) : asTitle = false;

  /// The label alone, for a [ListTile]'s title slot. The tile supplies the
  /// actions; [child] is still drawn but not read.
  const SongSemantics.title({
    super.key,
    required this.song,
    required this.child,
    this.includeAlbum = true,
    this.extra,
  }) : asTitle = true,
       onTap = null,
       onLongPress = null;

  final bool asTitle;

  final MyAudioMetadata song;
  final Widget child;

  /// What activating the row does. Usually plays the song.
  final VoidCallback? onTap;

  /// Opens the row's menu, where it has one.
  final VoidCallback? onLongPress;

  final bool includeAlbum;

  /// Anything else the row shows that is worth saying, such as a play count.
  final String? extra;

  static String labelFor(
    MyAudioMetadata song, {
    bool includeAlbum = true,
    bool isCurrent = false,
    bool isPlaying = false,
    String? extra,
  }) {
    final album = includeAlbum ? getAlbum(song) : '';
    return songLabel(
      title: getTitle(song),
      artist: getArtist(song),
      duration: getDuration(song),
      extra: [
        if (album.trim().isNotEmpty) 'album $album',
        if (isCurrent) isPlaying ? 'now playing' : 'paused',
        if (song.isFavoriteNotifier.value) 'favorite',
        ?extra,
      ].join(', '),
    );
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: Listenable.merge([
        currentSongNotifier,
        isPlayingNotifier,
        song.isFavoriteNotifier,
        song.updateNotifier,
      ]),
      builder: (context, child) {
        final isCurrent = currentSongNotifier.value == song;
        final label = labelFor(
          song,
          includeAlbum: includeAlbum,
          isCurrent: isCurrent,
          isPlaying: isPlayingNotifier.value,
          extra: extra,
        );
        if (asTitle) {
          return Semantics(label: label, excludeSemantics: true, child: child);
        }
        return Semantics(
          container: true,
          button: onTap != null,
          selected: isCurrent,
          label: label,
          onTap: onTap,
          onLongPress: onLongPress,
          onLongPressHint: onLongPress == null ? null : 'More options',
          child: child,
        );
      },
      child: asTitle ? child : _keys(child),
    );
  }

  /// Enter and Menu for the row's own focus only: a button inside the row,
  /// such as favorite, is a focus child of the row and keeps its keys.
  Widget _keys(Widget child) {
    return Focus(
      canRequestFocus: false,
      skipTraversal: true,
      includeSemantics: false,
      onKeyEvent: (node, event) {
        final row = FocusManager.instance.primaryFocus?.parent == node;
        if (!row || event is! KeyDownEvent) return KeyEventResult.ignored;
        final key = event.logicalKey;
        final shift = HardwareKeyboard.instance.isShiftPressed;
        final VoidCallback? action =
            key == LogicalKeyboardKey.space && playQueue.isNotEmpty
            ? audioHandler.togglePlay
            : key == LogicalKeyboardKey.enter ||
                  key == LogicalKeyboardKey.numpadEnter ||
                  key == LogicalKeyboardKey.space
            ? onTap
            : key == LogicalKeyboardKey.contextMenu ||
                  (key == LogicalKeyboardKey.f10 && shift)
            ? onLongPress
            : null;
        if (action == null) return KeyEventResult.ignored;
        action();
        return KeyEventResult.handled;
      },
      child: child,
    );
  }
}
