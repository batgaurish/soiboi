import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/widgets/song_list.dart';

class SingleAlbumLayer extends StatelessWidget {
  final Album album;
  final String rootLabel;
  const SingleAlbumLayer({
    super.key,
    required this.album,
    this.rootLabel = 'albums',
  });

  @override
  Widget build(BuildContext context) {
    return SongList(album: album, isRoot: false, albumRootLabel: rootLabel);
  }
}
