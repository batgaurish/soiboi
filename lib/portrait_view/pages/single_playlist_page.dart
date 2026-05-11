import 'package:flutter/material.dart';
import 'package:particle_music/common/widgets/local_navidrome_base.dart';
import 'package:particle_music/common/data/playlist.dart';

class SinglePlaylistPage extends StatelessWidget {
  final Playlist playlist;
  const SinglePlaylistPage({super.key, required this.playlist});
  @override
  Widget build(BuildContext context) {
    return LocalNavidromeBase(
      displayNavidromeNotifier: playlist.displayNavidromeNotifier,
      localSongList: playlist.songList,
      navidromeSongList: playlist.navidromeSongList,
      playlist: playlist,
      isPanel: false,
    );
  }
}
