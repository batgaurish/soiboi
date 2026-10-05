import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/widgets/song_list/song_list.dart';
import 'package:soiboi/layer/albums_layer.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/layer/ranking_layer.dart';
import 'package:soiboi/layer/recently_layer.dart';

class SingleAlbumLayer extends StatelessWidget {
  final Album album;
  final String rootLabel;
  final ValueNotifier<bool>? rootVisibleNotifier;

  const SingleAlbumLayer({
    super.key,
    required this.album,
    this.rootLabel = 'albums',
    this.rootVisibleNotifier,
  });

  ValueNotifier<bool> get _effectiveRootVisibleNotifier {
    if (rootVisibleNotifier != null) return rootVisibleNotifier!;
    if (rootLabel == 'ranking') return rankingVisibleNotifier;
    if (rootLabel == 'recently') return recentlyVisibleNotifier;
    return albumsVisibleNotifier;
  }

  @override
  Widget build(BuildContext context) {
    return SongList(
      album: album,
      isRoot: false,
      rootLabel: rootLabel,
      rootVisibleNotifier: _effectiveRootVisibleNotifier,
      onBackToRoot: () => layersManager.popDetail(rootLabel),
    );
  }
}
