import 'package:flutter/material.dart';
import 'package:particle_music/base/widgets/local_navidrome_base.dart';
import 'package:particle_music/base/data/library.dart';

class SongsPanel extends StatelessWidget {
  const SongsPanel({super.key});

  @override
  Widget build(BuildContext context) {
    return LocalNavidromeBase(
      displayNavidromeNotifier: library.displayNavidromeNotifier,
      localSongList: library.songList,
      navidromeSongList: library.navidromeSongList,
      isPanel: true,
    );
  }
}
