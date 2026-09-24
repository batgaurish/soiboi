import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/asset_images.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/utils/dynamic_lyrics_page_route.dart';
import 'package:soiboi/base/widgets/buttons.dart';
import 'package:soiboi/base/widgets/cover_art_widget.dart';
import 'package:soiboi/base/widgets/my_divider.dart';
import 'package:soiboi/base/widgets/playlist_widgets.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/widgets/song_info.dart';
import 'package:soiboi/portrait_view/sleep_timer.dart';
import 'package:soiboi/base/widgets/my_sheet.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/base/widgets/lyric_list_view.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/widgets/seekbar.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/widgets/app_icon.dart';
import 'package:soiboi/base/utils/semantics_labels.dart';
import 'package:soiboi/base/widgets/icon_label.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:soiboi/base/widgets/marquee_text.dart';

class PortraitLyricsPage extends StatefulWidget {
  const PortraitLyricsPage({super.key});

  @override
  State<PortraitLyricsPage> createState() => _PortraitLyricsPageState();
}

class _PortraitLyricsPageState extends State<PortraitLyricsPage> {
  final dragOffsetNotifier = ValueNotifier(0.0);

  final canDragNotifier = ValueNotifier(false);

  final draggingNotifier = ValueNotifier(false);

  int _animationDuration = 0;

  Timer? concealRouteTimer;

  final enableAllNotifier = ValueNotifier(Platform.isAndroid ? false : true);

  /// Art only, art with lyrics, or lyrics only. Kept for the session, so the
  /// player opens the way it was left.
  static int _lastMode = 1;
  late final _modes = PageController(initialPage: _lastMode);
  late final _modeNotifier = ValueNotifier(_lastMode);

  @override
  void dispose() {
    _modes.dispose();
    _hideControls?.cancel();
    _controlsShown.dispose();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await Future.delayed(Duration(milliseconds: 500));
      if (Platform.isAndroid) {
        enableAllNotifier.value = true;
      }
      canDragNotifier.value = true;
    });
  }

  @override
  Widget build(BuildContext context) {
    final screenHeight = MediaQuery.heightOf(context);

    return ValueListenableBuilder(
      valueListenable: canDragNotifier,
      builder: (context, value, child) {
        return GestureDetector(
          onVerticalDragStart: value
              ? (_) {
                  draggingNotifier.value = true;
                  concealRouteTimer?.cancel();
                  final route = ModalRoute.of(context);
                  if (route is DynamicLyricsPageRoute) {
                    route.revealRoutesBelow();
                  }
                }
              : null,
          onVerticalDragUpdate: value
              ? (details) {
                  _animationDuration = 0;
                  dragOffsetNotifier.value += details.delta.dy;
                  dragOffsetNotifier.value = dragOffsetNotifier.value.clamp(
                    0.0,
                    screenHeight,
                  );
                }
              : null,

          onVerticalDragEnd: value
              ? (details) {
                  double velocity = details.primaryVelocity ?? 0;

                  if (dragOffsetNotifier.value * 3 > screenHeight ||
                      velocity > 500) {
                    Navigator.pop(context);
                  } else {
                    _animationDuration = 250;
                    dragOffsetNotifier.value = 0.0;
                    concealRouteTimer = Timer(Duration(milliseconds: 250), () {
                      draggingNotifier.value = false;
                      final route = ModalRoute.of(context);
                      if (route is DynamicLyricsPageRoute) {
                        route.concealRoutesBelow();
                      }
                    });
                  }
                }
              : null,
          onVerticalDragCancel: value
              ? () {
                  _animationDuration = 250;
                  dragOffsetNotifier.value = 0.0;
                  concealRouteTimer = Timer(Duration(milliseconds: 250), () {
                    draggingNotifier.value = false;
                    final route = ModalRoute.of(context);
                    if (route is DynamicLyricsPageRoute) {
                      route.concealRoutesBelow();
                    }
                  });
                }
              : null,
          child: child,
        );
      },
      child: ValueListenableBuilder(
        valueListenable: dragOffsetNotifier,
        builder: (context, value, child) {
          return AnimatedContainer(
            duration: motionDuration(Duration(milliseconds: _animationDuration)),
            curve: Curves.easeOutCubic,
            transform: Matrix4.translationValues(0, value, 0),
            child: child,
          );
        },
        child: content(),
      ),
    );
  }

  Widget content() {
    return ValueListenableBuilder(
      valueListenable: currentSongNotifier,
      builder: (context, currentSong, child) {
        return AnnotatedRegion(
          value: lyricsPageForegroundColor.value.computeLuminance() > 0.5
              ? SystemUiOverlayStyle.light
              : SystemUiOverlayStyle.dark,
          child: ValueListenableBuilder(
            valueListenable: draggingNotifier,
            builder: (context, value, child) {
              return Material(
                color: Colors.transparent,
                shape: SmoothRectangleBorder(
                  smoothness: 1,
                  borderRadius: .circular(
                    value ? screenRadius?.topLeft ?? 0 : 0,
                  ),
                ),
                clipBehavior: value ? .antiAliasWithSaveLayer : .antiAlias,
                child: child,
              );
            },
            child: Stack(
              fit: StackFit.expand,
              children: [
                // Blurred album art background — always shown, not just in vivid.
                // The artwork is dimmed by an overlay so lyrics stay legible.
                CoverArtWidget(
                  picture: currentSong?.picture,
                  color: colorManager.getSpecificLyricsPageCoverArtBaseColor(),
                ),
                RepaintBoundary(
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 30, sigmaY: 30),
                    child: AnimatedContainer(
                      duration: motionDuration(Duration(milliseconds: 300)),
                      curve: Curves.easeInOutCubic,
                      // In vivid mode, the overlay uses the cover art colour;
                      // otherwise it uses the lyrics page background colour,
                      // so the tint follows the active theme.
                      color: lyricsPageThemeNotifier.value == .vivid
                          ? currentCoverArtColor.withAlpha(180)
                          : lyricsPageBackgroundColor.value.withAlpha(200),
                    ),
                  ),
                ),
                Column(
                  children: [
                    SizedBox(height: 60),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 30),
                      child: SizedBox(
                        height: scaledExtent(context, 36, textShare: 0.8),
                        child: ValueListenableBuilder(
                          valueListenable: enableAllNotifier,
                          builder: (context, value, child) {
                            final data = getTitle(currentSong);
                            final textStyle = TextStyle(
                              fontWeight: FontWeight.bold,
                              fontSize: 20,
                              color: lyricsPageHighlightTextColor.value,
                              overflow: .ellipsis,
                            );
                            if (!value) {
                              return Text(data, style: textStyle);
                            }
                            return MarqueeText(
                              textAlign: .center,
                              getTitle(currentSong),
                              velocity: const Velocity(
                                pixelsPerSecond: Offset(40, 0),
                              ),
                              style: textStyle,
                              intervalSpaces: 10,
                              pauseBetween: Duration(seconds: 2),
                            );
                          },
                        ),
                      ),
                    ),

                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 30),
                      child: SizedBox(
                        height: scaledExtent(context, 28, textShare: 0.8),
                        child: ValueListenableBuilder(
                          valueListenable: enableAllNotifier,
                          builder: (context, value, child) {
                            final data =
                                '${getArtist(currentSong)} - ${getAlbum(currentSong)}';
                            final textStyle = TextStyle(
                              fontSize: 14,
                              color: lyricsPageForegroundColor.value,
                              overflow: .ellipsis,
                            );
                            if (!value) {
                              return Text(data, style: textStyle);
                            }
                            return MarqueeText(
                              textAlign: .center,
                              data,
                              velocity: const Velocity(
                                pixelsPerSecond: Offset(40, 0),
                              ),
                              style: textStyle,
                              intervalSpaces: 10,
                              pauseBetween: Duration(seconds: 2),
                            );
                          },
                        ),
                      ),
                    ),
                    SizedBox(height: 10),

                    _modeSwitcher(),
                    Expanded(
                      // Art and Lyrics have no controls of their own, so a
                      // tap or a scroll brings them up over the page.
                      child: NotificationListener<ScrollNotification>(
                        onNotification: (n) {
                          // Only a finger counts: synced lyrics scroll
                          // themselves, which would keep the controls up.
                          if (n is ScrollUpdateNotification &&
                              n.dragDetails != null &&
                              n.metrics.axis == Axis.vertical) {
                            _showControls();
                          }
                          return false;
                        },
                        child: GestureDetector(
                          behavior: HitTestBehavior.translucent,
                          onTap: _showControls,
                          child: Stack(
                            children: [
                              PageView(
                                controller: _modes,
                                onPageChanged: (page) {
                                  _lastMode = page;
                                  _modeNotifier.value = page;
                                  _controlsShown.value = false;
                                },
                                children: [
                                  artOnlyPage(context, currentSong),
                                  artPage(context, currentSong),
                                  ValueListenableBuilder(
                                    valueListenable: enableAllNotifier,
                                    builder: (context, value, child) {
                                      if (!value) {
                                        return SizedBox.shrink();
                                      }
                                      return expandedLyricsPage(
                                        context,
                                        currentSong,
                                      );
                                    },
                                  ),
                                ],
                              ),
                              _overlayControls(),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  final _controlsShown = ValueNotifier(false);
  Timer? _hideControls;

  void _showControls() {
    if (_modeNotifier.value == 1) return; // Both has its own controls
    _controlsShown.value = true;
    _hideControls?.cancel();
    _hideControls = Timer(
      const Duration(seconds: 4),
      () => _controlsShown.value = false,
    );
  }

  Widget _overlayControls() {
    return Positioned(
      left: 0,
      right: 0,
      bottom: 0,
      child: ValueListenableBuilder(
        valueListenable: _controlsShown,
        builder: (context, shown, child) => IgnorePointer(
          ignoring: !shown,
          child: AnimatedOpacity(
            opacity: shown ? 1 : 0,
            duration: const Duration(milliseconds: 200),
            child: child,
          ),
        ),
        child: DecoratedBox(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [
                Colors.black.withValues(alpha: 0),
                Colors.black.withValues(alpha: 0.7),
              ],
            ),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 30),
                child: ValueListenableBuilder(
                  valueListenable: lyricsPageForegroundColor.valueNotifier,
                  builder: (context, value, child) => SeekBar(
                    color: value,
                    widgetHeight: 60,
                    seekBarHeight: 40,
                  ),
                ),
              ),
              playControls(),
              const SizedBox(height: 30),
            ],
          ),
        ),
      ),
    );
  }

  /// Art, Both, Lyrics: the same three pages a swipe reaches, made visible.
  Widget _modeSwitcher() {
    const modes = [
      (Icons.image_rounded, 'Art'),
      (Icons.vertical_split_rounded, 'Both'),
      (Icons.lyrics_rounded, 'Lyrics'),
    ];
    return ValueListenableBuilder<int>(
      valueListenable: _modeNotifier,
      builder: (context, current, _) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            for (final (index, (icon, label)) in modes.indexed)
              TextButton.icon(
                onPressed: () => _modes.glideToPage(
                  index,
                  duration: const Duration(milliseconds: 280),
                  curve: Curves.easeOutCubic,
                ),
                icon: Icon(icon, size: 18),
                label: Text(label),
                style: TextButton.styleFrom(
                  foregroundColor: lyricsPageForegroundColor.value.withValues(
                    alpha: index == current ? 1 : 0.45,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  /// The cover alone, as large as the screen allows.
  Widget artOnlyPage(BuildContext context, MyAudioMetadata? currentSong) {
    final size = MediaQuery.widthOf(context) * 0.9;
    return Center(
      child: CoverArtWidget(
        size: size,
        borderRadius: size * 0.04,
        picture: currentSong?.picture,
        elevation: 15,
        color: colorManager.getSpecificLyricsPageCoverArtBaseColor(),
      ),
    );
  }

  Widget artPage(BuildContext context, MyAudioMetadata? currentSong) {
    return LayoutBuilder(
      builder: (context, constraints) =>
          _artPageColumn(context, currentSong, constraints.maxHeight),
    );
  }

  Widget _artPageColumn(
    BuildContext context,
    MyAudioMetadata? currentSong,
    double height,
  ) {
    final mobileWidth = MediaQuery.widthOf(context);
    // The cover gives way to the controls below it: with large text, or on
    // a short screen, a full-width cover would push them off the bottom.
    // Room is kept for the gap, the button row, seek bar, transport and, with
    // large text, a line of lyrics.
    final reserved =
        30 +
        scaledExtent(context, 48, textShare: 0.3) +
        scaledExtent(context, 60, textShare: 0.5) +
        64 +
        40 +
        (textGrowth(context) > 1 ? 40 : 0);
    final coverSize = (height - reserved).clamp(120.0, mobileWidth * 0.84);

    return Column(
      children: [
        Hero(
          tag: 'cover',
          flightShuttleBuilder:
              (
                flightContext,
                animation,
                flightDirection,
                fromHeroContext,
                toHeroContext,
              ) => FittedBox(child: toHeroContext.widget),
          child: CoverArtWidget(
            size: coverSize,
            borderRadius: coverSize * 0.05,
            picture: currentSong?.picture,
            elevation: 15,
            color: colorManager.getSpecificLyricsPageCoverArtBaseColor(),
          ),
        ),

        const SizedBox(height: 30),

        Expanded(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 40),
            child: ShaderMask(
              shaderCallback: (rect) {
                return LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.transparent, // fade out at top
                    Colors.grey.shade50, // fully visible
                    Colors.grey.shade50, // fully visible
                    Colors.transparent, // fade out at bottom
                  ],
                  stops: [0.0, 0.1, 0.8, 1.0], // adjust fade height
                ).createShader(rect);
              },
              blendMode: BlendMode.dstIn,
              // use key to force update
              child: currentSong == null
                  ? SizedBox()
                  : ValueListenableBuilder(
                      valueListenable: enableAllNotifier,
                      builder: (context, value, child) {
                        if (!value) {
                          return SizedBox.shrink();
                        }
                        return LyricsListView(
                          key: ValueKey(currentSong),
                          expanded: false,
                          lines: currentSong.parsedLyrics!.lines,
                          isKaraoke: currentSong.parsedLyrics!.isKaraoke,
                          isSynced: currentSong.parsedLyrics!.isSynced,
                        );
                      },
                    ),
            ),
          ),
        ),

        Row(
          children: [
            SizedBox(width: 25),
            FavoriteButton(),
            IconButton(
              tooltip: AppLocalizations.of(context).sleepTimer,
              color: lyricsPageForegroundColor.value,
              onPressed: () {
                displayTimedPauseSetting(context);
              },
              icon: labelIcon(
                AppLocalizations.of(context).sleepTimer,
                AppIcon(timerImage, size: 25),
              ),
            ),
            remainTimesText(textColor: lyricsPageForegroundColor.value),
            Spacer(),
            IconButton(
              tooltip: 'Larger lyrics',
              color: lyricsPageForegroundColor.value,
              onPressed: () {
                lyricsFontSizeOffsetNotifier.value += 2;
                setting.save();
              },
              icon: labelIcon(
                'Larger lyrics',
                Icon(Icons.text_increase_rounded),
              ),
            ),
            IconButton(
              tooltip: 'Smaller lyrics',
              color: lyricsPageForegroundColor.value,
              onPressed: () {
                if (lyricsFontSizeOffsetNotifier.value < -2) {
                  return;
                }
                lyricsFontSizeOffsetNotifier.value -= 2;
                setting.save();
              },
              icon: labelIcon(
                'Smaller lyrics',
                Icon(Icons.text_decrease_rounded),
              ),
            ),

            moreButton(currentSong),

            SizedBox(width: 25),
          ],
        ),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 30),
          child: ValueListenableBuilder(
            valueListenable: lyricsPageForegroundColor.valueNotifier,
            builder: (context, value, child) {
              return SeekBar(color: value, widgetHeight: 60, seekBarHeight: 40);
            },
          ),
        ),

        playControls(),

        SizedBox(height: 40),
      ],
    );
  }

  Widget moreButton(MyAudioMetadata? currentSong) {
    final l10n = AppLocalizations.of(context);
    return IconButton(
      tooltip: AppLocalizations.of(context).more,
      onPressed: () {
        tryVibrate();
        showModalBottomSheet(
          context: context,
          isScrollControlled: true,
          builder: (context) {
            return MySheet(
              height: 250,
              ValueListenableBuilder(
                valueListenable: lyricsPageForegroundColor.valueNotifier,
                builder: (context, value, child) {
                  return Column(
                    children: [
                      SizedBox(height: 5),

                      ListTile(
                        leading: CoverArtWidget(
                          size: 50,
                          borderRadius: 5,
                          picture: currentSong?.picture,
                        ),
                        title: Text(
                          getTitle(currentSong),
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(color: value),
                        ),
                        subtitle: Text(
                          "${getArtist(currentSong)} - ${getAlbum(currentSong)}",
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(color: value),
                        ),
                      ),

                      SizedBox(height: 5),
                      MyDivider(
                        color: lyricsPageDividerColor,
                        thickness: 0.5,
                        height: 1,
                      ),
                      SizedBox(height: 5),

                      Expanded(
                        child: ListView(
                          physics: const ClampingScrollPhysics(),
                          children: [
                            ListTile(
                              leading: Icon(Icons.add_rounded, color: value),
                              title: Text(
                                l10n.add2Playlist,
                                style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  color: value,
                                ),
                              ),
                              visualDensity: const VisualDensity(
                                horizontal: 0,
                                vertical: -4,
                              ),
                              onTap: () {
                                Navigator.pop(context);

                                showAddPlaylistDialog(context, [currentSong!]);
                              },
                            ),

                            ListTile(
                              leading: Transform.scale(
                                scale: 0.85,
                                child: Icon(
                                  Icons.info_outline_rounded,
                                  color: value,
                                ),
                              ),
                              title: Text(
                                l10n.songInfo,
                                style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  color: value,
                                ),
                              ),
                              visualDensity: const VisualDensity(
                                horizontal: 0,
                                vertical: -4,
                              ),
                              onTap: () {
                                Navigator.pop(context);
                                showAnimationDialog(
                                  context: context,
                                  child: SongInfo(song: currentSong!),
                                );
                              },
                            ),

                            ListTile(
                              leading: AppIcon(
                                desktopLyricsImage,
                                color: value,
                              ),
                              title: Text(
                                l10n.adjustLyrics,
                                style: TextStyle(
                                  fontWeight: FontWeight.bold,
                                  color: value,
                                ),
                              ),
                              visualDensity: const VisualDensity(
                                horizontal: 0,
                                vertical: -4,
                              ),
                              onTap: () {
                                Navigator.pop(context);
                                showAdjustLyrics(context);
                              },
                            ),
                          ],
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
      icon: labelIcon(
        AppLocalizations.of(context).more,
        Icon(Icons.more_vert, color: lyricsPageForegroundColor.value),
      ),
    );
  }

  void showAdjustLyrics(BuildContext context) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (context) {
        final l10n = AppLocalizations.of(context);

        return MySheet(
          height: 200,
          ValueListenableBuilder(
            valueListenable: lyricsPageForegroundColor.valueNotifier,
            builder: (context, value, child) {
              return Column(
                children: [
                  SizedBox(height: 10),
                  Row(
                    children: [
                      SizedBox(width: 20),
                      Text(
                        l10n.fontSize,
                        style: .new(fontWeight: .bold, color: value),
                      ),
                      Spacer(),

                      IconButton(
                        tooltip: 'Smaller lyrics',
                        color: value,
                        onPressed: () {
                          if (lyricsFontSizeOffsetNotifier.value < -2) {
                            return;
                          }
                          lyricsFontSizeOffsetNotifier.value -= 2;
                          setting.save();
                        },
                        icon: labelIcon(
                          'Smaller lyrics',
                          AppIcon(minimizeImage),
                        ),
                      ),
                      ValueListenableBuilder(
                        valueListenable: lyricsFontSizeOffsetNotifier,
                        builder: (context, fontSizeOffset, child) {
                          return SizedBox(
                            width: 40,
                            child: Text(
                              textAlign: .center,
                              fontSizeOffset.toString(),
                              style: .new(fontWeight: .bold, color: value),
                            ),
                          );
                        },
                      ),
                      IconButton(
                        tooltip: 'Larger lyrics',
                        color: value,
                        onPressed: () {
                          lyricsFontSizeOffsetNotifier.value += 2;
                          setting.save();
                        },
                        icon: labelIcon('Larger lyrics', Icon(Icons.add)),
                      ),
                      SizedBox(width: 20),
                    ],
                  ),

                  Row(
                    children: [
                      SizedBox(width: 20),
                      Text(
                        l10n.offset,
                        style: .new(fontWeight: .bold, color: value),
                      ),
                      Spacer(),

                      IconButton(
                        tooltip: 'Offset minus 0.1 seconds',
                        color: value,
                        onPressed: () {
                          lyricsTimeOffsetNotifier.value -= 100;
                        },
                        icon: labelIcon(
                          'Offset minus 0.1 seconds',
                          AppIcon(minimizeImage),
                        ),
                      ),
                      ValueListenableBuilder(
                        valueListenable: lyricsTimeOffsetNotifier,
                        builder: (context, timeOffset, child) {
                          return SizedBox(
                            width: 40,
                            child: Text(
                              textAlign: .center,
                              '${timeOffset / 1000} s',
                              style: .new(fontWeight: .bold, color: value),
                            ),
                          );
                        },
                      ),
                      IconButton(
                        tooltip: 'Offset plus 0.1 seconds',
                        color: value,
                        onPressed: () {
                          lyricsTimeOffsetNotifier.value += 100;
                        },
                        icon: labelIcon(
                          'Offset plus 0.1 seconds',
                          Icon(Icons.add),
                        ),
                      ),

                      SizedBox(width: 20),
                    ],
                  ),

                  Row(
                    children: [
                      SizedBox(width: 20),
                      Text(
                        l10n.fontWeight,
                        style: .new(fontWeight: .bold, color: value),
                      ),
                      Expanded(
                        child: ValueListenableBuilder<FontWeight>(
                          valueListenable: lyricsFontWeightNotifier,
                          builder: (context, weight, _) {
                            final fontWeights = [
                              FontWeight.w100,
                              FontWeight.w200,
                              FontWeight.w300,
                              FontWeight.w400,
                              FontWeight.w500,
                              FontWeight.w600,
                              FontWeight.w700,
                              FontWeight.w800,
                              FontWeight.w900,
                            ];
                            final index = fontWeights.indexOf(weight);

                            return SliderTheme(
                              data: SliderTheme.of(context).copyWith(
                                trackHeight: 3,

                                activeTrackColor: value,
                                inactiveTrackColor: value,

                                thumbColor: value,

                                overlayColor: Colors.transparent,

                                tickMarkShape: const RoundSliderTickMarkShape(
                                  tickMarkRadius: 1.5,
                                ),
                                activeTickMarkColor:
                                    value.computeLuminance() > 0.5
                                    ? Colors.black
                                    : Colors.white,
                                inactiveTickMarkColor:
                                    value.computeLuminance() > 0.5
                                    ? Colors.black
                                    : Colors.white,

                                thumbShape: const RoundSliderThumbShape(
                                  enabledThumbRadius: 4,
                                ),
                              ),
                              child: MergeSemantics(
                                child: Semantics(
                                  label: nameWithValue(
                                    AppLocalizations.of(context).fontWeight,
                                    '${index + 1} of ${fontWeights.length}',
                                  ),
                                  child: Slider(
                                    value: index.toDouble(),
                                    min: 0,
                                    max: (fontWeights.length - 1).toDouble(),
                                    divisions: fontWeights.length - 1,
                                    semanticFormatterCallback: (value) =>
                                        '${value.round() + 1} of ${fontWeights.length}',
                                    onChanged: (value) {
                                      lyricsFontWeightNotifier.value =
                                          fontWeights[value.round()];
                                    },
                                  ),
                                ),
                              ),
                            );
                          },
                        ),
                      ),

                      SizedBox(width: 5),
                    ],
                  ),
                ],
              );
            },
          ),
        );
      },
    );
  }

  Widget playControls() {
    final value = lyricsPageForegroundColor.value;
    return Row(
      children: [
        SizedBox(width: 25),

        playModeButton(32, iconColor: value),

        Spacer(),

        skip2PreviousButton(32, iconColor: value),

        Spacer(),

        playOrPauseButton(50, iconColor: value),

        Spacer(),

        skip2NextButton(32, iconColor: value),

        Spacer(),

        showPlayQueueButton(32, iconColor: value),

        SizedBox(width: 25),
      ],
    );
  }

  Widget expandedLyricsPage(
    BuildContext context,
    MyAudioMetadata? currentSong,
  ) {
    return Stack(
      children: [
        Column(
          children: [
            Expanded(
              child: ShaderMask(
                shaderCallback: (rect) {
                  return LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [
                      Colors.transparent, // fade out at top
                      Colors.grey.shade50, // fully visible
                      Colors.grey.shade50, // fully visible
                      Colors.transparent, // fade out at bottom
                    ],
                    stops: [0.0, 0.1, 0.7, 1.0], // adjust fade height
                  ).createShader(rect);
                },
                blendMode: BlendMode.dstIn,
                child: currentSong == null
                    ? SizedBox()
                    : LyricsListView(
                        key: ValueKey(currentSong),
                        expanded: true,
                        lines: currentSong.parsedLyrics!.lines,
                        isKaraoke: currentSong.parsedLyrics!.isKaraoke,
                        isSynced: currentSong.parsedLyrics!.isSynced,
                      ),
              ),
            ),
            SizedBox(height: 50),
          ],
        ),

        Positioned(
          right: 25,
          bottom: 40,
          // The tap-up controls carry their own play button.
          child: ValueListenableBuilder(
            valueListenable: _controlsShown,
            builder: (context, shown, child) =>
                shown ? const SizedBox() : child!,
            child: ValueListenableBuilder(
              valueListenable: lyricsPageForegroundColor.valueNotifier,
              builder: (context, value, child) {
                return IconButton(
                  color: value,
                  icon: ValueListenableBuilder(
                    valueListenable: isPlayingNotifier,
                    builder: (_, isPlaying, _) {
                      return Icon(
                        isPlaying
                            ? Icons.pause_circle_rounded
                            : Icons.play_circle_rounded,
                        size: 48,
                      );
                    },
                  ),
                  onPressed: () => audioHandler.togglePlay(),
                );
              },
            ),
          ),
        ),
      ],
    );
  }
}

class FavoriteButton extends StatelessWidget {
  const FavoriteButton({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: currentSongNotifier,
      builder: (_, currentSong, _) {
        if (currentSong == null) return SizedBox();
        return ValueListenableBuilder(
          valueListenable: currentSong.isFavoriteNotifier,
          builder: (_, value, _) {
            return IconButton(
              tooltip: currentSong.isFavoriteNotifier.value
                  ? 'Remove from favorites'
                  : 'Add to favorites',
              color: lyricsPageForegroundColor.value,

              onPressed: () {
                tryVibrate();
                toggleFavoriteState(currentSong);
              },
              icon: labelIcon(
                currentSong.isFavoriteNotifier.value
                    ? 'Remove from favorites'
                    : 'Add to favorites',
                Transform.scale(
                  scale: 1.1,
                  child: Icon(
                    value ? Icons.star_rounded : Icons.star_outline_rounded,
                    color: value ? Colors.red : null,
                    size: 25,
                  ),
                ),
              ),
            );
          },
        );
      },
    );
  }
}
