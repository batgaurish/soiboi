import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/widgets/song_list/song_list.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/layer/playlists_layer.dart';

class SinglePlaylistLayer extends StatelessWidget {
  final Playlist playlist;
  final bool isRoot;

  const SinglePlaylistLayer({
    super.key,
    required this.playlist,
    required this.isRoot,
  });

  @override
  Widget build(BuildContext context) {
    return SongList(
      playlist: playlist,
      isRoot: isRoot,
      rootLabel: isRoot ? '' : 'playlists',
      rootVisibleNotifier: isRoot ? null : playlistsVisibleNotifier,
      onBackToRoot: isRoot ? null : () => layersManager.popDetail('playlists'),
    );
  }
}
