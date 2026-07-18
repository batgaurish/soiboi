import 'package:flutter/material.dart';
import 'package:sylvakru/base/audio_handler.dart';
import 'package:sylvakru/base/data/library.dart';
import 'package:sylvakru/base/data/song_list_manager.dart';
import 'package:sylvakru/base/my_audio_metadata.dart';
import 'package:sylvakru/base/utils/metadata_utils.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';

class BigSongsPanel extends StatefulWidget {
  const BigSongsPanel({super.key});

  @override
  State<StatefulWidget> createState() => _BigSongsPanelState();
}

class _BigSongsPanelState extends State<BigSongsPanel> {
  final textController = TextEditingController();

  final ScrollController scrollController = ScrollController();

  final ValueNotifier<bool> isSearchNotifier = ValueNotifier(false);

  late SongListManager songListManager;
  late List<MyAudioMetadata> songList;

  final currentSongListNotifier = ValueNotifier<List<MyAudioMetadata>>([]);

  ValueNotifier<int> sortTypeNotifier = ValueNotifier(0);

  void updateSongList() {
    final value = textController.text;
    final filteredSongList = filterSongList(songList, value);
    sortSongList(sortTypeNotifier.value, filteredSongList);
    currentSongListNotifier.value = filteredSongList;
  }

  @override
  void initState() {
    super.initState();
    songListManager = library.songListManager;
    songList = songListManager.getSongList2(.navidrome);
    updateSongList();
    textController.addListener(updateSongList);
  }

  @override
  void dispose() {
    textController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    int crossAxisCount = (MediaQuery.widthOf(context) / 200).toInt();
    return ValueListenableBuilder(
      valueListenable: currentSongListNotifier,
      builder: (context, currentSongList, child) {
        return GridView.builder(
          padding: const EdgeInsets.symmetric(horizontal: 30.0, vertical: 75),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: crossAxisCount,
            childAspectRatio: 0.85,
          ),
          itemCount: currentSongList.length,
          itemBuilder: (context, index) {
            final song = currentSongList[index];
            return ValueListenableBuilder(
              valueListenable: song.updateNotifier,
              builder: (_, _, _) {
                bool focus = false;
                return StatefulBuilder(
                  builder: (context, setState) {
                    return Transform.scale(
                      scale: focus ? 1.1 : 1,
                      child: LayoutBuilder(
                        builder: (context, constraint) {
                          return Column(
                            children: [
                              InkWell(
                                mouseCursor: SystemMouseCursors.click,
                                focusColor: Colors.transparent,
                                hoverColor: Colors.transparent,
                                splashColor: Colors.transparent,
                                highlightColor: Colors.transparent,
                                onFocusChange: (value) {
                                  setState(() {
                                    focus = value;
                                  });
                                },
                                onTap: () async {
                                  audioHandler.currentIndex = index;
                                  await audioHandler.setPlayQueue(
                                    currentSongList,
                                  );
                                  await audioHandler.load();
                                  audioHandler.play();
                                },
                                child: CoverArtWidget(
                                  size: constraint.maxWidth - 25,
                                  borderRadius: constraint.maxWidth / 10,
                                  song: song,
                                ),
                              ),
                              SizedBox(height: 5),
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 20,
                                ),
                                child: Align(
                                  alignment: .centerLeft,
                                  child: Text(
                                    getTitle(song),
                                    style: .new(overflow: .ellipsis),
                                  ),
                                ),
                              ),
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 20,
                                ),

                                child: Align(
                                  alignment: .centerLeft,
                                  child: Text(
                                    '${getArtist(song)} - ${getAlbum(song)}',
                                    style: .new(overflow: .ellipsis),
                                  ),
                                ),
                              ),
                            ],
                          );
                        },
                      ),
                    );
                  },
                );
              },
            );
          },
        );
      },
    );
  }
}
