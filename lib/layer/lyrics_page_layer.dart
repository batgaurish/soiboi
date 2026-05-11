import 'package:flutter/material.dart';
import 'package:particle_music/common/app.dart';
import 'package:particle_music/landscape_view/bottom_control.dart';
import 'package:particle_music/landscape_view/pages/landscape_lyrics_page.dart';
import 'package:particle_music/portrait_view/pages/portrait_lyrics_page.dart';

bool displayLyricsPage = false;

class LyricsPageLayer extends StatefulWidget {
  const LyricsPageLayer({super.key});

  @override
  State<StatefulWidget> createState() => _LyricsPageLayerState();
}

class _LyricsPageLayerState extends State<LyricsPageLayer> {
  @override
  void initState() {
    super.initState();
    displayLyricsPage = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!isTV) {
        return;
      }
      playControlScopeNode.requestFocus();
    });
  }

  @override
  void dispose() {
    displayLyricsPage = false;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!isTV) {
        return;
      }
      currentSongTileNode.requestFocus();
    });
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return OrientationBuilder(
      builder: (context, orientation) {
        if (isMobile && orientation == Orientation.portrait) {
          return PortraitLyricsPage();
        } else {
          return LandscapeLyricsPage();
        }
      },
    );
  }
}
