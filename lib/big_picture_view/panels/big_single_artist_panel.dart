import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:rive_animated_icon/rive_animated_icon.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:sylvakru/base/app.dart';
import 'package:sylvakru/base/audio_handler.dart';
import 'package:sylvakru/base/data/artist_album.dart';
import 'package:sylvakru/base/services/color_manager.dart';
import 'package:sylvakru/base/services/interaction.dart';
import 'package:sylvakru/base/utils/dynamic_lyrics_page_route.dart';
import 'package:sylvakru/base/utils/format_duration.dart';
import 'package:sylvakru/base/utils/media_query.dart';
import 'package:sylvakru/base/utils/metadata_utils.dart';
import 'package:sylvakru/base/utils/source_type.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';
import 'package:sylvakru/base/widgets/my_divider.dart';
import 'package:sylvakru/big_picture_view/panels/big_single_album_panel.dart';
import 'package:sylvakru/l10n/generated/app_localizations.dart';
import 'package:sylvakru/layer/lyrics_page_layer.dart';

class BigSingleArtistPanel extends StatefulWidget {
  final Artist artist;
  const BigSingleArtistPanel({super.key, required this.artist});

  @override
  State<StatefulWidget> createState() => _BigSingleArtistPanelState();
}

class _BigSingleArtistPanelState extends State<BigSingleArtistPanel> {
  FocusNode currentSongNode = FocusNode();
  late final bool useCurrentSongForBgTmp;
  @override
  void initState() {
    useCurrentSongForBgTmp = useCurrentSongForBg;
    useCurrentSongForBg = true;
    super.initState();
  }

  @override
  void dispose() {
    useCurrentSongForBg = useCurrentSongForBgTmp;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final panelWidth = MediaQuery.widthOf(context);
    final panelHeight = MediaQuery.heightOf(context);
    final l10n = AppLocalizations.of(context);
    return Stack(
      fit: .expand,
      children: [
        ListenableBuilder(
          listenable: Listenable.merge([
            currentSongNotifier,
            mainPageThemeNotifier,
          ]),
          builder: (context, _) {
            if (mainPageThemeNotifier.value != .vivid) {
              return SizedBox.shrink();
            }
            return CoverArtWidget(
              song: currentSongNotifier.value,
              color: currentCoverArtColor,
            );
          },
        ),
        ListenableBuilder(
          listenable: Listenable.merge([
            currentSongNotifier,
            mainPageThemeNotifier,
          ]),
          builder: (context, child) {
            if (mainPageThemeNotifier.value != .vivid) {
              return SizedBox.shrink();
            }

            return RepaintBoundary(
              child: BackdropFilter(
                filter: ImageFilter.blur(
                  sigmaX: panelWidth * 0.03,
                  sigmaY: panelHeight * 0.03,
                ),
                child: AnimatedContainer(
                  duration: Duration(milliseconds: 500),
                  curve: Curves.easeInOutCubic,
                  color: currentCoverArtColor.withAlpha(180),
                ),
              ),
            );
          },
        ),

        Scaffold(
          backgroundColor: panelColor.value,
          extendBodyBehindAppBar: true,
          resizeToAvoidBottomInset: false,
          body: Column(
            children: [
              SizedBox(height: 100),
              Row(
                children: [
                  SizedBox(width: 40),
                  Text(
                    widget.artist.name,
                    style: .new(
                      fontWeight: .bold,
                      fontSize: 24,
                      overflow: .ellipsis,
                    ),
                  ),
                  Spacer(),
                  IconButton(
                    onPressed: () {},
                    icon: Icon(Icons.play_arrow_rounded),
                  ),
                  SizedBox(width: 40),
                ],
              ),
              MyDivider(color: dividerColor, indent: 40, endIndent: 40),
              Row(
                children: [
                  SizedBox(width: 40),

                  Text(
                    '${getSourceTypeName(l10n, widget.artist.songListManager.sourceTypeNotifier.value)}: ${widget.artist.albumList.length} ${l10n.albums}, ${l10n.songCount(widget.artist.songListManager.getSongList().length)}',
                  ),
                ],
              ),
              SizedBox(height: 10),
              Expanded(
                child: CustomScrollView(
                  slivers: [
                    const SliverToBoxAdapter(child: SizedBox(height: 20)),

                    for (final album in widget.artist.albumList)
                      SliverMainAxisGroup(
                        slivers: [
                          SliverCrossAxisGroup(
                            slivers: [
                              SliverConstrainedCrossAxis(
                                maxExtent: 40,
                                sliver: SliverToBoxAdapter(child: SizedBox()),
                              ),
                              SliverConstrainedCrossAxis(
                                maxExtent: panelWidth * 0.2,
                                sliver: SliverToBoxAdapter(
                                  child: CoverArtWidget(
                                    song: album.getCoverSong(),
                                    size: panelWidth * 0.2,
                                    borderRadius: panelWidth * 0.01,
                                  ),
                                ),
                              ),
                              SliverConstrainedCrossAxis(
                                maxExtent: panelWidth * 0.02,
                                sliver: SliverToBoxAdapter(child: SizedBox()),
                              ),
                              SliverMainAxisGroup(
                                slivers: [
                                  SliverToBoxAdapter(
                                    child: Row(
                                      children: [
                                        SizedBox(width: 20),
                                        Column(
                                          crossAxisAlignment: .start,
                                          children: [
                                            Text(
                                              album.name,
                                              style: .new(
                                                fontWeight: .bold,
                                                fontSize: 20,
                                                overflow: .ellipsis,
                                              ),
                                            ),
                                            if (album.year != null)
                                              Text(
                                                album.year.toString(),
                                                style: .new(
                                                  overflow: .ellipsis,
                                                ),
                                              ),
                                          ],
                                        ),
                                        Spacer(),
                                        IconButton(
                                          onPressed: () {},
                                          icon: Icon(Icons.play_arrow_rounded),
                                        ),
                                      ],
                                    ),
                                  ),
                                  SliverToBoxAdapter(
                                    child: MyDivider(
                                      color: dividerColor,
                                      indent: 10,
                                    ),
                                  ),
                                  SliverList.builder(
                                    itemCount: album.songListManager
                                        .getSongList()
                                        .length,
                                    itemBuilder: (context, index) {
                                      final song = album.songListManager
                                          .getSongList()[index];
                                      final artist = getArtist(song);

                                      if (!artist.contains(
                                        widget.artist.name,
                                      )) {
                                        return SizedBox.shrink();
                                      }
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

                                          child: Padding(
                                            padding: EdgeInsets.only(
                                              right: 20.0,
                                            ),
                                            child: Row(
                                              children: [
                                                SizedBox(
                                                  height: 50,
                                                  width: 50,
                                                  child: ValueListenableBuilder(
                                                    valueListenable:
                                                        currentSongNotifier,
                                                    builder: (context, currentSong, child) {
                                                      return Center(
                                                        child:
                                                            currentSong == song
                                                            ? ValueListenableBuilder(
                                                                valueListenable:
                                                                    isPlayingNotifier,
                                                                builder:
                                                                    (
                                                                      context,
                                                                      value,
                                                                      child,
                                                                    ) {
                                                                      return ExcludeFocus(
                                                                        child: RiveAnimatedIcon(
                                                                          key: ValueKey(
                                                                            value,
                                                                          ),
                                                                          riveIcon:
                                                                              .sound,
                                                                          width:
                                                                              35,
                                                                          height:
                                                                              35,
                                                                          loopAnimation:
                                                                              value,
                                                                        ),
                                                                      );
                                                                    },
                                                              )
                                                            : Text(
                                                                song.track !=
                                                                        null
                                                                    ? song.track
                                                                          .toString()
                                                                    : '#',
                                                              ),
                                                      );
                                                    },
                                                  ),
                                                ),
                                                Expanded(
                                                  child: Text(
                                                    getTitle(song),
                                                    overflow:
                                                        TextOverflow.ellipsis,
                                                  ),
                                                ),
                                                SizedBox(width: 15),
                                                Expanded(
                                                  child: Text(
                                                    getArtist(song),
                                                    overflow:
                                                        TextOverflow.ellipsis,
                                                  ),
                                                ),
                                                SizedBox(width: 15),
                                                Text(
                                                  formatDuration(
                                                    getDuration(song),
                                                  ),

                                                  overflow:
                                                      TextOverflow.ellipsis,
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                      );
                                    },
                                  ),
                                ],
                              ),

                              SliverConstrainedCrossAxis(
                                maxExtent: 40,
                                sliver: SliverToBoxAdapter(child: SizedBox()),
                              ),
                            ],
                          ),
                          SliverToBoxAdapter(child: SizedBox(height: 50)),
                        ],
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),

        Positioned(
          top: isTooNarrow(context) ? 50 : 25,
          left: 20,
          child: GlassContainer(
            shape: LiquidRoundedSuperellipse(borderRadius: 30),
            clipBehavior: .antiAlias,
            child: IconButton(
              autofocus: true,
              onPressed: () {
                Navigator.of(context).pop();
              },
              icon: Icon(Icons.arrow_back_ios_rounded),
            ),
          ),
        ),

        Positioned(
          top: isTooNarrow(context) ? 50 : 20,
          left: 0,
          right: 0,
          child: Row(
            mainAxisAlignment: .center,
            children: [
              Expanded(flex: 1, child: SizedBox.shrink()),
              Expanded(
                flex: 3,
                child: Center(
                  child: ConstrainedBox(
                    constraints: BoxConstraints(maxWidth: 700),
                    child: ValueListenableBuilder(
                      valueListenable: currentSongNotifier,
                      builder: (_, currentSong, _) {
                        return Material(
                          color: Colors.transparent,
                          child: ListenableBuilder(
                            listenable: Listenable.merge([
                              currentSong?.updateNotifier,
                            ]),
                            builder: (context, _) {
                              return GlassContainer(
                                height: 50,

                                shape: LiquidRoundedSuperellipse(
                                  borderRadius: 25,
                                ),
                                child: InkWell(
                                  focusNode: currentSongNode,
                                  customBorder: SmoothRectangleBorder(
                                    smoothness: 1,
                                    borderRadius: .circular(25),
                                  ),
                                  onTap: () {
                                    if (playQueue.isEmpty) {
                                      return;
                                    }
                                    Navigator.of(
                                      context,
                                      rootNavigator: true,
                                    ).push(
                                      DynamicLyricsPageRoute(
                                        pageBuilder: (_, _, _) =>
                                            LyricsPageLayer(),
                                      ),
                                    );
                                  },

                                  child: Row(
                                    children: [
                                      SizedBox(width: 25),
                                      Hero(
                                        tag: 'cover',
                                        child: CoverArtWidget(
                                          size: 40,
                                          borderRadius: 4,
                                          song: currentSong,
                                        ),
                                      ),
                                      SizedBox(width: 10),
                                      Expanded(
                                        child: Column(
                                          mainAxisAlignment: .center,
                                          crossAxisAlignment: .start,
                                          children: [
                                            Text(
                                              getTitle(currentSong),
                                              overflow: TextOverflow.ellipsis,
                                            ),
                                            Text(
                                              "${getArtist(currentSong)} - ${getAlbum(currentSong)}",
                                              overflow: TextOverflow.ellipsis,
                                              style: TextStyle(fontSize: 13),
                                            ),
                                          ],
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            },
                          ),
                        );
                      },
                    ),
                  ),
                ),
              ),
              Expanded(flex: 1, child: SizedBox.shrink()),
            ],
          ),
        ),
      ],
    );
  }
}
