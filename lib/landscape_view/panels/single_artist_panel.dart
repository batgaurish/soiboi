import 'package:flutter/material.dart';
import 'package:particle_music/common/data/artist_album.dart';
import 'package:particle_music/common/widgets/local_navidrome_base.dart';

class SingleArtistPanel extends StatelessWidget {
  final Artist artist;
  const SingleArtistPanel({super.key, required this.artist});

  @override
  Widget build(BuildContext context) {
    return LocalNavidromeBase(
      displayNavidromeNotifier: artist.displayNavidromeNotifier,
      localSongList: artist.songList,
      navidromeSongList: artist.navidromeSongList,
      artist: artist,
      isPanel: true,
    );
  }
}
