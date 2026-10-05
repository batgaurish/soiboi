import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/widgets/song_list/song_list.dart';
import 'package:soiboi/layer/artists_layer.dart';
import 'package:soiboi/layer/layers_manager.dart';

class SingleArtistLayer extends StatelessWidget {
  final Artist artist;
  const SingleArtistLayer({super.key, required this.artist});

  @override
  Widget build(BuildContext context) {
    return SongList(
      artist: artist,
      isRoot: false,
      rootLabel: 'artists',
      rootVisibleNotifier: artistsVisibleNotifier,
      onBackToRoot: () => layersManager.popDetail('artists'),
    );
  }
}
