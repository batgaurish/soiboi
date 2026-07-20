import 'package:flutter/material.dart';
import 'package:sylvakru/base/data/artist_album.dart';
import 'package:sylvakru/base/utils/my_gird_delegate.dart';
import 'package:sylvakru/base/utils/zoom_page_route.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';
import 'package:sylvakru/base/widgets/scale_widget.dart';
import 'package:sylvakru/big_picture_view/panels/big_single_artist_panel.dart';

class BigArtistsPanel extends StatefulWidget {
  const BigArtistsPanel({super.key});

  @override
  State<StatefulWidget> createState() => _BigArtistsPanelState();
}

class _BigArtistsPanelState extends State<BigArtistsPanel> {
  late final ValueNotifier<List<Artist>> currentArtistListNotifier;

  final textController = TextEditingController();

  final ScrollController scrollController = ScrollController();

  late ValueNotifier<bool> randomizeNotifier;
  late ValueNotifier<bool> isAscendingNotifier;

  final ValueNotifier<bool> isSearchNotifier = ValueNotifier(false);

  void updateCurrentList() {
    final value = textController.text;
    currentArtistListNotifier.value = artistAlbumManager.artistList
        .where((e) => (e.name.toLowerCase().contains(value.toLowerCase())))
        .toList();
    if (randomizeNotifier.value) {
      currentArtistListNotifier.value.shuffle();
    }
  }

  @override
  void initState() {
    super.initState();
    currentArtistListNotifier = ValueNotifier(artistAlbumManager.artistList);

    randomizeNotifier = artistAlbumManager.getIsRandomizeNotifier(false);

    isAscendingNotifier = artistAlbumManager.getIsAscendingNotifier(false);

    updateCurrentList();
    textController.addListener(updateCurrentList);
    artistAlbumManager.updateNotifier.addListener(updateCurrentList);
  }

  @override
  void dispose() {
    textController.dispose();
    artistAlbumManager.updateNotifier.removeListener(updateCurrentList);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: currentArtistListNotifier,
      builder: (context, list, child) {
        return GridView.builder(
          padding: const EdgeInsets.symmetric(horizontal: 40.0, vertical: 75),
          gridDelegate: MyGirdDelegate(
            maxCrossAxisExtent: 200,
            crossAxisSpacing: 30,
            mainAxisSpacing: 10,
            textExtent: 30,
          ),
          itemCount: list.length,
          itemBuilder: (context, index) {
            return ValueListenableBuilder(
              valueListenable: list[index].songListManager.sourceTypeNotifier,
              builder: (context, value, child) {
                final coverSong = list[index].getCoverSong();
                return ValueListenableBuilder(
                  valueListenable: coverSong.updateNotifier,
                  builder: (_, _, _) {
                    return ScaleWidget(
                      onTap: () {
                        Navigator.of(context).push(
                          ZoomPageRoute(
                            builder: (context) {
                              return BigSingleArtistPanel(artist: list[index]);
                            },
                          ),
                        );
                      },
                      child: LayoutBuilder(
                        builder: (context, constraint) {
                          return Column(
                            children: [
                              CoverArtWidget(
                                size: constraint.maxWidth,
                                borderRadius: constraint.maxWidth / 10,
                                song: coverSong,
                              ),
                              Transform.translate(
                                offset: Offset(0, 5),
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 10,
                                  ),
                                  child: Align(
                                    alignment: .centerLeft,
                                    child: Text(
                                      list[index].name,
                                      style: TextStyle(
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ),
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
