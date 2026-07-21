import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_gamepads/flutter_gamepads.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:sylvakru/base/app.dart';
import 'package:sylvakru/base/asset_images.dart';
import 'package:sylvakru/base/audio_handler.dart';
import 'package:sylvakru/base/services/color_manager.dart';
import 'package:sylvakru/base/services/interaction.dart';
import 'package:sylvakru/base/services/my_window_listener.dart';
import 'package:sylvakru/base/utils/dynamic_lyrics_page_route.dart';
import 'package:sylvakru/base/utils/media_query.dart';
import 'package:sylvakru/base/utils/metadata_utils.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';
import 'package:sylvakru/big_picture_view/panels/big_albums_panel.dart';
import 'package:sylvakru/big_picture_view/panels/big_artists_panel.dart';
import 'package:sylvakru/big_picture_view/panels/big_home_panel.dart';
import 'package:sylvakru/big_picture_view/panels/big_songs_panel.dart';
import 'package:sylvakru/l10n/generated/app_localizations.dart';
import 'package:sylvakru/layer/layers_manager.dart';
import 'package:sylvakru/layer/lyrics_page_layer.dart';
import 'package:window_manager/window_manager.dart';

class BigPictureView extends StatefulWidget {
  const BigPictureView({super.key});

  @override
  State<StatefulWidget> createState() => _BigPictureViewState();
}

class _BigPictureViewState extends State<BigPictureView> {
  final _pageController = PageController();
  final _currentIndexNotifier = ValueNotifier(0);

  final pages = const [
    BigHomePanel(),
    BigSongsPanel(),
    BigArtistsPanel(),
    BigAlbumsPanel(),
  ];

  final topNode = FocusScopeNode();
  final pageViewNode = FocusScopeNode();
  final bottomNode = FocusScopeNode();

  @override
  void dispose() {
    topNode.dispose();
    pageViewNode.dispose();
    bottomNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
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
                  color: currentCoverArtColor.withAlpha(180),
                ),
              ),
            );
          },
        ),

        Material(
          color: panelColor.value,
          child: GamepadInterceptor(
            onBeforeIntent: (activator, intent) {
              if (intent is DismissIntent) {
                topNode.requestFocus();
              }
              return true;
            },
            child: KeyboardListener(
              focusNode: pageViewNode,
              onKeyEvent: (value) {
                if (value is KeyUpEvent) {
                  return;
                }

                if (value.logicalKey == .goBack) {
                  topNode.requestFocus();
                }
              },
              child: PageView(
                controller: _pageController,
                onPageChanged: (value) {
                  _currentIndexNotifier.value = value;
                },
                children: pages,
              ),
            ),
          ),
        ),

        topBar(context),
        bottomBar(context),
      ],
    );
  }

  Widget topBar(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final tabs = [
      l10n.home,
      l10n.songs,
      l10n.artists,
      l10n.albums,
      l10n.folders,
      l10n.ranking,
      l10n.recently,
      l10n.playlists,
      l10n.settings,
    ];

    return Positioned(
      top: isTooNarrow(context) ? 40 : 0,
      left: 0,
      right: 0,

      child: KeyboardListener(
        focusNode: topNode,
        onKeyEvent: (value) {
          if (value is KeyUpEvent) {
            return;
          }
          if (value.logicalKey == .arrowDown) {
            pageViewNode.requestFocus();
          } else if (value.logicalKey == .arrowUp) {
            bottomNode.requestFocus();
          }
        },
        child: GamepadInterceptor(
          onBeforeIntent: (activator, intent) {
            if (intent is DirectionalFocusIntent) {
              if (intent.direction == .down) {
                pageViewNode.requestFocus();
              } else if (intent.direction == .up) {
                bottomNode.requestFocus();
              }
            }
            return true;
          },
          child: SizedBox(
            height: 75,
            child: Row(
              children: [
                Expanded(
                  flex: 1,
                  child: Material(
                    color: Colors.transparent,
                    child: Row(
                      children: [
                        SizedBox(width: 30),

                        // Expanded(
                        //   child: GlassContainer(
                        //     settings: LiquidGlassSettings(
                        //       glassColor: glassColor.value,
                        //     ),
                        //     shape: const LiquidRoundedSuperellipse(
                        //       borderRadius: 30,
                        //     ),
                        //     child: TextField(
                        //       decoration: InputDecoration(
                        //         prefixIcon: Icon(Icons.search),
                        //         suffixIcon: IconButton(
                        //           onPressed: () {},
                        //           icon: const Icon(Icons.clear),
                        //           padding: EdgeInsets.zero,
                        //         ),
                        //         filled: true,
                        //         fillColor: Colors.transparent,
                        //         contentPadding: EdgeInsets.zero,
                        //         isDense: true,
                        //         border: OutlineInputBorder(
                        //           borderSide: BorderSide.none,
                        //         ),
                        //       ),
                        //     ),
                        //   ),
                        // ),
                        SizedBox(width: 30),
                      ],
                    ),
                  ),
                ),
                Expanded(
                  flex: 2,
                  child: LayoutBuilder(
                    builder: (context, constraints) {
                      return GlassContainer(
                        settings: LiquidGlassSettings(
                          glassColor: glassColor.value,
                        ),
                        shape: const LiquidRoundedSuperellipse(
                          borderRadius: 30,
                        ),
                        clipBehavior: .antiAlias,
                        child: SingleChildScrollView(
                          scrollDirection: Axis.horizontal,
                          child: ConstrainedBox(
                            constraints: BoxConstraints(
                              minWidth: constraints.maxWidth,
                            ),
                            child: Padding(
                              padding: EdgeInsets.symmetric(
                                horizontal: 15,
                                vertical: 7.5,
                              ),
                              child: Row(
                                mainAxisAlignment: .center,
                                children: List.generate(tabs.length, (index) {
                                  bool focus = false;

                                  return ValueListenableBuilder(
                                    valueListenable: _currentIndexNotifier,
                                    builder: (context, value, child) {
                                      final selected = index == value;
                                      return StatefulBuilder(
                                        builder: (context, thisSetState) {
                                          return Material(
                                            shape: SmoothRectangleBorder(
                                              smoothness: 1,
                                              borderRadius: .circular(25),
                                            ),
                                            color: selected
                                                ? focus
                                                      ? selectedItemColor.value
                                                            .withAlpha(75)
                                                      : selectedItemColor.value
                                                            .withAlpha(50)
                                                : Colors.transparent,
                                            clipBehavior: .antiAlias,
                                            child: InkWell(
                                              autofocus: index == 0,
                                              mouseCursor:
                                                  SystemMouseCursors.click,
                                              onTap: () {
                                                _pageController.animateToPage(
                                                  index,
                                                  duration: const Duration(
                                                    milliseconds: 300,
                                                  ),
                                                  curve: Curves.easeOut,
                                                );
                                              },
                                              onFocusChange: (value) {
                                                thisSetState(() {
                                                  focus = value;
                                                });
                                              },
                                              child: Padding(
                                                padding:
                                                    const EdgeInsets.symmetric(
                                                      horizontal: 10,
                                                    ),
                                                child: Text(
                                                  tabs[index],
                                                  style: TextStyle(
                                                    fontSize: 20,
                                                    fontWeight: FontWeight.bold,
                                                    color: selected
                                                        ? textColor.value
                                                        : textColor.value
                                                              .withAlpha(128),
                                                  ),
                                                ),
                                              ),
                                            ),
                                          );
                                        },
                                      );
                                    },
                                  );
                                }),
                              ),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),

                Expanded(
                  flex: 1,
                  child: isTV
                      ? SizedBox.shrink()
                      : Row(
                          mainAxisAlignment: .end,

                          children: [
                            GlassContainer(
                              settings: LiquidGlassSettings(
                                glassColor: glassColor.value,
                              ),
                              shape: const LiquidRoundedSuperellipse(
                                borderRadius: 30,
                              ),
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 10.0,
                                ),
                                child: Row(
                                  children: [
                                    if (!isFullScreenNotifier.value)
                                      IconButton(
                                        onPressed: () async {
                                          if (!await showConfirmDialog(
                                            context,
                                            l10n.switchMode,
                                          )) {
                                            return;
                                          }
                                          await Future.delayed(
                                            Duration(milliseconds: 250),
                                          );
                                          layersManager.updateBackground();
                                          viewModeNotifier.value = .normal;
                                        },
                                        icon: ImageIcon(bigPictueModeImage),
                                      ),
                                    if (!isMobile && !isMaximizedNotifier.value)
                                      IconButton(
                                        onPressed: () async {
                                          if (isFullScreenNotifier.value) {
                                            isFullScreenNotifier.value = false;
                                            await windowManager.setFullScreen(
                                              false,
                                            );
                                          } else {
                                            isFullScreenNotifier.value = true;
                                            await windowManager.setFullScreen(
                                              true,
                                            );
                                          }
                                        },
                                        icon: ImageIcon(
                                          isFullScreenNotifier.value
                                              ? fullscreenExitImage
                                              : fullscreenImage,
                                        ),
                                      ),
                                    if (!isMobile &&
                                        !isFullScreenNotifier.value) ...[
                                      IconButton(
                                        onPressed: () {
                                          windowManager.minimize();
                                        },
                                        icon: ImageIcon(minimizeImage),
                                      ),
                                      IconButton(
                                        onPressed: () async {
                                          isMaximizedNotifier.value
                                              ? windowManager.unmaximize()
                                              : windowManager.maximize();
                                        },
                                        icon: ImageIcon(
                                          isMaximizedNotifier.value
                                              ? unmaximizeImage
                                              : maximizeImage,
                                        ),
                                      ),
                                      IconButton(
                                        onPressed: () {
                                          windowManager.close();
                                        },
                                        icon: ImageIcon(closeImage),
                                      ),
                                    ],
                                  ],
                                ),
                              ),
                            ),
                            SizedBox(width: isMobile ? 10 : 20),
                          ],
                        ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget bottomBar(BuildContext context) {
    return Positioned(
      bottom: 20,
      left: 0,
      right: 0,
      child: KeyboardListener(
        focusNode: bottomNode,
        onKeyEvent: (value) {
          if (value is KeyUpEvent) {
            return;
          }

          if (value.logicalKey == .arrowDown) {
            topNode.requestFocus();
          } else if (value.logicalKey == .arrowUp) {
            pageViewNode.requestFocus();
          }
        },
        child: GamepadInterceptor(
          onBeforeIntent: (activator, intent) {
            if (intent is DirectionalFocusIntent) {
              if (intent.direction == .down) {
                topNode.requestFocus();
              } else if (intent.direction == .up) {
                pageViewNode.requestFocus();
              }
            }
            return true;
          },
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
                                settings: LiquidGlassSettings(
                                  glassColor: glassColor.value,
                                ),
                                shape: LiquidRoundedSuperellipse(
                                  borderRadius: 25,
                                ),
                                child: InkWell(
                                  autofocus: true,
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
      ),
    );
  }
}
