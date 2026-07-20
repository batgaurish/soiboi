import 'package:flutter/material.dart';
import 'package:sylvakru/base/data/artist_album.dart';
import 'package:sylvakru/base/services/metadata_service.dart';
import 'package:sylvakru/base/utils/my_gird_delegate.dart';
import 'package:sylvakru/base/utils/zoom_page_route.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';
import 'package:sylvakru/base/widgets/scale_widget.dart';
import 'package:sylvakru/big_picture_view/panels/big_single_album_panel.dart';

class BigAlbumsPanel extends StatefulWidget {
  const BigAlbumsPanel({super.key});

  @override
  State<StatefulWidget> createState() => _BigAlbumsPanelState();
}

class _BigAlbumsPanelState extends State<BigAlbumsPanel> {
  late final ValueNotifier<List<Album>> currentAlbumListNotifier;

  final textController = TextEditingController();

  final ScrollController scrollController = ScrollController();

  late ValueNotifier<bool> randomizeNotifier;
  late ValueNotifier<bool> isAscendingNotifier;

  final ValueNotifier<bool> isSearchNotifier = ValueNotifier(false);

  void updateCurrentList() {
    final value = textController.text;
    currentAlbumListNotifier.value = artistAlbumManager.albumList
        .where((e) => (e.name.toLowerCase().contains(value.toLowerCase())))
        .toList();
    if (randomizeNotifier.value) {
      currentAlbumListNotifier.value.shuffle();
    }
  }

  @override
  void initState() {
    super.initState();
    currentAlbumListNotifier = ValueNotifier(artistAlbumManager.albumList);

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
      valueListenable: currentAlbumListNotifier,
      builder: (context, list, child) {
        return GridView.builder(
          padding: const EdgeInsets.symmetric(horizontal: 40, vertical: 75),
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
                      child: LayoutBuilder(
                        builder: (context, constraints) {
                          return Column(
                            children: [
                              Hero(
                                tag: 'big${coverSong.id}${list[index].name}',
                                child: CoverArtWidget(
                                  size: constraints.maxWidth,
                                  borderRadius: constraints.maxWidth * 0.1,
                                  song: coverSong,
                                ),
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

                      onTap: () async {
                        final baseColor = await computeCoverArtColor(
                          list[index].getCoverSong(),
                        );
                        if (!context.mounted) {
                          return;
                        }
                        Navigator.of(context).push(
                          ZoomPageRoute(
                            builder: (context) {
                              return BigSingleAlbumPanel(
                                album: list[index],
                                baseColor: baseColor,
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
      },
    );
  }
}
