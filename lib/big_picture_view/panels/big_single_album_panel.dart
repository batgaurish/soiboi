import 'dart:math';
import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter_gamepads/flutter_gamepads.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:rive_animated_icon/rive_animated_icon.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:sylvakru/base/app.dart';
import 'package:sylvakru/base/asset_images.dart';
import 'package:sylvakru/base/audio_handler.dart';
import 'package:sylvakru/base/data/artist_album.dart';
import 'package:sylvakru/base/services/color_manager.dart';
import 'package:sylvakru/base/services/interaction.dart';
import 'package:sylvakru/base/utils/dynamic_lyrics_page_route.dart';
import 'package:sylvakru/base/utils/format_duration.dart';
import 'package:sylvakru/base/utils/media_query.dart';
import 'package:sylvakru/base/utils/metadata_utils.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';
import 'package:sylvakru/l10n/generated/app_localizations.dart';
import 'package:sylvakru/layer/lyrics_page_layer.dart';

bool useCurrentSongForBg = true;

class BigSingleAlbumPanel extends StatefulWidget {
  final Album album;
  final Color baseColor;
  const BigSingleAlbumPanel({
    super.key,
    required this.album,
    required this.baseColor,
  });

  @override
  State<StatefulWidget> createState() => _BigSingleAlbumPanelState();
}

class _BigSingleAlbumPanelState extends State<BigSingleAlbumPanel> {
  FocusNode currentSongNode = FocusNode();

  @override
  void initState() {
    useCurrentSongForBg = false;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      colorManager.updateBigPictureRelatedColors(widget.album.getCoverSong());
    });
    super.initState();
  }

  @override
  void dispose() {
    currentSongNode.dispose();
    useCurrentSongForBg = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      colorManager.updateBigPictureRelatedColors(currentSongNotifier.value);
    });
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final appWidth = MediaQuery.widthOf(context);
    final currentSongList = widget.album.songListManager.getSongList();
    return Stack(
      fit: .expand,
      children: [
        ListenableBuilder(
          listenable: Listenable.merge([]),
          builder: (context, _) {
            if (mainPageThemeNotifier.value != .vivid) {
              return SizedBox.shrink();
            }
            return CoverArtWidget(
              song: currentSongList.first,
              color: widget.baseColor,
            );
          },
        ),
        ListenableBuilder(
          listenable: Listenable.merge([]),
          builder: (context, child) {
            if (mainPageThemeNotifier.value != .vivid) {
              return SizedBox.shrink();
            }
            final pageWidth = MediaQuery.widthOf(context);
            final pageHight = MediaQuery.heightOf(context);

            return RepaintBoundary(
              child: BackdropFilter(
                filter: ImageFilter.blur(
                  sigmaX: pageWidth * 0.03,
                  sigmaY: pageHight * 0.03,
                ),
                child: AnimatedContainer(
                  duration: Duration(milliseconds: 500),
                  curve: Curves.easeInOutCubic,
                  color: widget.baseColor.withAlpha(180),
                ),
              ),
            );
          },
        ),

        Scaffold(
          backgroundColor: panelColor.value,
          extendBodyBehindAppBar: true,
          resizeToAvoidBottomInset: false,
          body: Row(
            children: [
              Expanded(
                child: Column(
                  children: [
                    SizedBox(height: 75),
                    Expanded(
                      child: ValueListenableBuilder(
                        valueListenable: currentSongNotifier,
                        builder: (context, currentSong, child) {
                          return ListView.builder(
                            padding: EdgeInsets.symmetric(horizontal: 30),
                            itemExtent: 50,
                            itemCount: currentSongList.length,
                            itemBuilder: (_, index) {
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
                                    showOptions(context: context, song: song);
                                  },
                                  child: Padding(
                                    padding: EdgeInsets.only(right: 20.0),
                                    child: Row(
                                      children: [
                                        SizedBox(
                                          width: 50,
                                          child: Center(
                                            child: currentSong == song
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
                                                            child:
                                                                RiveAnimatedIcon(
                                                                  key: ValueKey(
                                                                    value,
                                                                  ),
                                                                  riveIcon:
                                                                      .sound,
                                                                  width: 35,
                                                                  height: 35,
                                                                  loopAnimation:
                                                                      value,
                                                                ),
                                                          );
                                                        },
                                                  )
                                                : Text(
                                                    song.track != null
                                                        ? song.track.toString()
                                                        : '#',
                                                  ),
                                          ),
                                        ),
                                        Expanded(
                                          child: Align(
                                            alignment: .centerLeft,
                                            child: Text(
                                              getTitle(song),
                                              style: .new(overflow: .ellipsis),
                                            ),
                                          ),
                                        ),

                                        Expanded(
                                          child: Text(
                                            getArtist(song),
                                            style: .new(overflow: .ellipsis),
                                          ),
                                        ),
                                        Text(formatDuration(getDuration(song))),
                                      ],
                                    ),
                                  ),
                                ),
                              );
                            },
                          );
                        },
                      ),
                    ),
                  ],
                ),
              ),
              SizedBox(width: appWidth * 0.02),
              SizedBox(
                width: appWidth * 0.25,
                child: ListView(
                  children: [
                    SizedBox(height: 120),
                    Hero(
                      tag:
                          'big${widget.album.getCoverSong().id}${widget.album.name}',
                      flightShuttleBuilder:
                          (
                            flightContext,
                            animation,
                            flightDirection,
                            fromHeroContext,
                            toHeroContext,
                          ) => FittedBox(child: toHeroContext.widget),
                      child: CoverArtWidget(
                        size: appWidth * 0.25,
                        borderRadius: appWidth * 0.0125,
                        song: widget.album.getCoverSong(),
                      ),
                    ),
                    SizedBox(height: 10),
                    Text(
                      widget.album.name,
                      textAlign: .center,
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 18,
                      ),
                    ),
                    GamepadInterceptor(
                      onBeforeIntent: (activator, intent) {
                        if (intent is DirectionalFocusIntent) {
                          if (intent.direction == .up) {
                            currentSongNode.requestFocus();
                            return false;
                          }
                        }
                        return true;
                      },
                      child: Row(
                        mainAxisAlignment: .center,
                        children: [
                          IconButton(
                            onPressed: () async {
                              audioHandler.currentIndex = Random().nextInt(
                                currentSongList.length,
                              );
                              playModeNotifier.value = 1;
                              await audioHandler.setPlayQueue(currentSongList);
                              await audioHandler.load();
                              audioHandler.play();
                            },
                            icon: ImageIcon(shuffleImage),
                            iconSize: 30,
                          ),
                          IconButton(
                            onPressed: () async {
                              audioHandler.currentIndex = 0;
                              playModeNotifier.value = 0;
                              await audioHandler.setPlayQueue(currentSongList);
                              await audioHandler.load();
                              audioHandler.play();
                            },
                            icon: Icon(Icons.play_circle_fill_rounded),
                            iconSize: 50,
                          ),
                          IconButton(
                            onPressed: () {},
                            icon: Transform.scale(
                              scale: 0.95,
                              child: ImageIcon(selectImage),
                            ),
                            iconSize: 30,
                          ),
                        ],
                      ),
                    ),
                    ValueListenableBuilder(
                      valueListenable: buttonColor.valueNotifier,
                      builder: (context, value, child) {
                        return ElevatedButton(
                          onPressed: () async {},
                          style: ElevatedButton.styleFrom(
                            backgroundColor: buttonColor.value,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(10),
                            ),
                            padding: EdgeInsets.all(10),
                          ),
                          child: Text(AppLocalizations.of(context).switch_),
                        );
                      },
                    ),
                  ],
                ),
              ),

              SizedBox(width: appWidth * 0.05),
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
