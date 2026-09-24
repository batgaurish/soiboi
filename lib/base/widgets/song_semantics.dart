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
library;

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
      child: child,
    );
  }
}
