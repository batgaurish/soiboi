import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:sylvakru/base/app.dart';
import 'package:sylvakru/base/data/artist_album.dart';
import 'package:sylvakru/base/services/color_manager.dart';
import 'package:sylvakru/base/utils/format_duration.dart';
import 'package:sylvakru/base/utils/metadata_utils.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';

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
  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.widthOf(context);
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
              song: widget.album.songListManager.getSongList().first,
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
          body: Column(
            children: [
              SizedBox(height: 10),
              Row(
                children: [
                  SizedBox(width: 20),

                  GlassContainer(
                    settings: LiquidGlassSettings(
                      glassColor: playBarColor.value,
                    ),
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
                ],
              ),
              SizedBox(height: 10),

              Expanded(
                child: Row(
                  children: [
                    Expanded(
                      child: ListView.builder(
                        padding: EdgeInsets.symmetric(horizontal: 30),
                        itemExtent: 50,
                        itemCount: widget.album.songListManager
                            .getSongList()
                            .length,
                        itemBuilder: (_, index) {
                          final song = widget.album.songListManager
                              .getSongList()[index];
                          return InkWell(
                            mouseCursor: SystemMouseCursors.click,
                            customBorder: SmoothRectangleBorder(
                              smoothness: 1,
                              borderRadius: .circular(15),
                            ),
                            onTap: () {},
                            child: Padding(
                              padding: EdgeInsets.only(
                                right: isMobile ? 0 : 20.0,
                              ),
                              child: Row(
                                children: [
                                  SizedBox(
                                    width: 50,
                                    child: Center(child: Text('${index + 1}')),
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
                          );
                        },
                      ),
                    ),
                    SizedBox(width: screenWidth * 0.02),
                    SizedBox(
                      width: screenWidth * 0.25,
                      child: Column(
                        mainAxisAlignment: .center,
                        children: [
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
                                ) => FittedBox(
                                  child: flightDirection == .pop
                                      ? toHeroContext.widget
                                      : fromHeroContext.widget,
                                ),
                            child: CoverArtWidget(
                              size: screenWidth * 0.25,
                              borderRadius: screenWidth * 0.025,
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
                          TextButton(onPressed: () {}, child: Text('play all')),
                          TextButton(onPressed: () {}, child: Text('play all')),
                          TextButton(onPressed: () {}, child: Text('play all')),
                          TextButton(onPressed: () {}, child: Text('play all')),
                          TextButton(onPressed: () {}, child: Text('play all')),
                          TextButton(onPressed: () {}, child: Text('play all')),
                        ],
                      ),
                    ),
                    SizedBox(width: screenWidth * 0.05),
                  ],
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
