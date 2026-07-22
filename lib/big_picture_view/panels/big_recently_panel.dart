import 'package:flutter/material.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:sylvakru/base/data/history.dart';
import 'package:sylvakru/base/services/interaction.dart';
import 'package:sylvakru/base/utils/format_duration.dart';
import 'package:sylvakru/base/utils/metadata_utils.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';

class BigRecentlyPanel extends StatefulWidget {
  const BigRecentlyPanel({super.key});

  @override
  State<StatefulWidget> createState() => _BigRecentlyPanelState();
}

class _BigRecentlyPanelState extends State<BigRecentlyPanel> {
  final songListManager = history.recentlySongListManager;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: songListManager.changeNotifier,
      builder: (context, value, child) {
        final currentSongList = songListManager.getSongList();

        return ListView.builder(
          padding: EdgeInsets.symmetric(horizontal: 40, vertical: 75),
          itemExtent: 80,
          itemCount: currentSongList.length,
          itemBuilder: (context, index) {
            final song = currentSongList[index];
            return Material(
              color: Colors.transparent,
              shape: SmoothRectangleBorder(
                smoothness: 1,
                borderRadius: .circular(15),
              ),
              clipBehavior: .antiAlias,
              child: InkWell(
                mouseCursor: SystemMouseCursors.click,
                onTap: () {
                  showOptions(
                    context: context,
                    song: song,
                    includeGoToArtist: true,
                    includeGoToAlbum: true,
                  );
                },
                child: Row(
                  children: [
                    SizedBox(
                      width: 60,
                      child: Center(child: Text('${index + 1}')),
                    ),
                    CoverArtWidget(song: song, size: 60, borderRadius: 10),
                    SizedBox(width: 10),

                    Expanded(
                      child: Text(
                        getTitle(song),
                        style: .new(overflow: .ellipsis),
                      ),
                    ),
                    SizedBox(width: 10),

                    Expanded(
                      child: Text(
                        getArtist(song),
                        style: .new(overflow: .ellipsis),
                      ),
                    ),
                    SizedBox(width: 10),

                    Expanded(
                      child: Text(
                        getAlbum(song),
                        style: .new(overflow: .ellipsis),
                      ),
                    ),
                    SizedBox(width: 10),

                    Text(formatDuration(getDuration(song))),
                    SizedBox(width: 20),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }
}
