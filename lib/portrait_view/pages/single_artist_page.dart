import 'package:flutter/material.dart';
import 'package:particle_music/base/data/artist_album.dart';
import 'package:particle_music/base/widgets/local_navidrome_base.dart';

class SingleArtistPage extends StatelessWidget {
  final Artist artist;
  const SingleArtistPage({super.key, required this.artist});
  @override
  Widget build(BuildContext context) {
    return LocalNavidromeBase(
      displayNavidromeNotifier: artist.displayNavidromeNotifier,
      localSongList: artist.songList,
      navidromeSongList: artist.navidromeSongList,
      artist: artist,
      isPanel: false,
    );
  }
}
