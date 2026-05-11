import 'package:flutter/material.dart';
import 'package:particle_music/base/widgets/local_navidrome_base.dart';
import 'package:particle_music/base/data/library.dart';

class SongsPage extends StatelessWidget {
  const SongsPage({super.key});

  @override
  Widget build(BuildContext _) {
    return LocalNavidromeBase(
      displayNavidromeNotifier: library.displayNavidromeNotifier,
      localSongList: library.songList,
      navidromeSongList: library.navidromeSongList,
      isPanel: false,
    );
  }
}
