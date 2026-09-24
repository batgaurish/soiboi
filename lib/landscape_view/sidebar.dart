import 'dart:io';
import 'dart:math';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/asset_images.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/base/widgets/cover_art_widget.dart';
import 'package:soiboi/base/widgets/my_divider.dart';
import 'package:soiboi/base/widgets/playlist_widgets.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:window_manager/window_manager.dart';
import 'package:soiboi/base/widgets/app_icon.dart';
import 'package:soiboi/base/widgets/icon_label.dart';

final ValueNotifier<String> sidebarHighlighLabel = ValueNotifier('');

/// The sidebar's width: 220, a little wider with large text so the labels
/// still mostly fit.
double sidebarWidth(BuildContext context) =>
    scaledExtent(context, 220, textShare: 0.35);

class Sidebar extends StatelessWidget {
  final ScrollController _scrollController = ScrollController();
  final void Function()? closeDrawer;
  Sidebar({super.key, this.closeDrawer});

  Widget sidebarItem({
    required String label,
    required Widget leading,
    required String content,
    Widget? trailing,
    EdgeInsetsGeometry? contentPadding,
    required Function() onTap,
  }) {
    return Padding(
      padding: EdgeInsets.symmetric(horizontal: 10),
      child: ValueListenableBuilder(
        valueListenable: sidebarHighlighLabel,
        builder: (context, highlightLabel, child) {
          return ValueListenableBuilder(
            valueListenable: selectedItemColor.valueNotifier,
            builder: (context, value, _) {
              return Material(
                color: highlightLabel == label ? value : Colors.transparent,
                shape: SmoothRectangleBorder(
                  smoothness: 1,
                  borderRadius: BorderRadius.circular(10),
                ),
                clipBehavior: .antiAlias,
                child: child,
              );
            },
          );
        },
        child: InkWell(
          mouseCursor: SystemMouseCursors.click,
          // Grows with large text instead of clipping it, and the label
          // shortens with an ellipsis before it pushes anything off.
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: 40),
            child: Row(
              children: [
                SizedBox(width: 20),
                leading,
                SizedBox(width: 10),

                Expanded(
                  child: Text(
                    content,
                    maxLines: 1,
                    style: TextStyle(
                      fontSize: 15,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ),

                if (trailing != null) ...[trailing, SizedBox(width: 5)],
              ],
            ),
          ),

          onTap: () async {
            if (closeDrawer != null) {
              closeDrawer!.call();
              await Future.delayed(Duration(milliseconds: 250));
            }
            onTap();
          },
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);

    return ValueListenableBuilder(
      valueListenable: sidebarColor.valueNotifier,
      builder: (context, value, child) {
        return Material(color: value, child: child);
      },
      child: SizedBox(
        width: sidebarWidth(context),
        child: Column(
          children: [
            GestureDetector(
              behavior: HitTestBehavior.translucent,
              onPanStart: (details) {
                if (isMobile) {
                  return;
                }
                windowManager.startDragging();
              },
              onDoubleTap: () async {
                if (isMobile) {
                  return;
                }
                await windowManager.isMaximized()
                    ? windowManager.unmaximize()
                    : windowManager.maximize();
              },
              child: SizedBox(
                height: 75,
                child: ValueListenableBuilder(
                  valueListenable: highlightTextColor.valueNotifier,
                  builder: (context, value, child) {
                    return Transform.translate(
                      offset: Offset(-10, 0),
                      child: Row(
                        mainAxisAlignment: .center,
                        children: [
                          Transform.translate(
                            offset: Offset(0, 2),
                            child: AppIcon(iconImage, size: 28),
                          ),
                          SizedBox(width: 5),
                          // The wordmark is set in the flavour's display face,
                          // in its accent, like the mockups' masthead.
                          Text(
                            activeFlavour.heading(l10n.soiboi),
                            style: activeFlavour.headingStyle(
                              TextStyle(fontSize: 22, color: _wordmarkColor()),
                              userFont: fontFamilyNotifier.value,
                            ),
                          ),
                        ],
                      ),
                    );
                  },
                ),
              ),
            ),

            Expanded(
              child: Scrollbar(
                thickness: isMobile ? 0 : 5,
                controller: _scrollController,

                child: CustomScrollView(
                  primary: false,
                  controller: _scrollController,
                  scrollBehavior: ScrollConfiguration.of(
                    context,
                  ).copyWith(scrollbars: false),
                  slivers: [
                    // First, and above Home: the sidebar is on every screen,
                    // which is what makes this the app's one persistent search
                    // entry point without wrapping a new shell around the
                    // layer system.
                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'search',

                        leading: Icon(Icons.search_rounded, size: 30),
                        content: 'Search',

                        onTap: () {
                          layersManager.switchRootLayer('search');
                        },
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'home',

                        leading: Icon(Icons.home_outlined, size: 30),
                        content: l10n.home,

                        onTap: () {
                          layersManager.switchRootLayer('home');
                        },
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'artists',
                        leading: AppIcon(artistImage, size: 30),
                        content: l10n.artists,

                        onTap: () {
                          layersManager.switchRootLayer('artists');
                        },
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'albums',

                        leading: AppIcon(albumImage, size: 30),
                        content: l10n.albums,

                        onTap: () {
                          layersManager.switchRootLayer('albums');
                        },
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'folders',

                        leading: AppIcon(folderImage, size: 30),
                        content: l10n.folders,

                        onTap: () {
                          layersManager.switchRootLayer('folders');
                        },
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'songs',

                        leading: AppIcon(songsImage, size: 30),
                        content: l10n.songs,

                        onTap: () {
                          layersManager.switchRootLayer('songs');
                        },
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'downloads',

                        leading: Icon(Icons.cloud_download_outlined, size: 30),
                        content: l10n.downloads,

                        onTap: () {
                          layersManager.switchRootLayer('downloads');
                        },
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'smart',

                        leading: Icon(Icons.auto_awesome_outlined, size: 30),
                        content: 'Smart playlists',

                        onTap: () {
                          layersManager.switchRootLayer('smart');
                        },
                      ),
                    ),

                    SliverToBoxAdapter(child: SizedBox(height: 5)),
                    SliverToBoxAdapter(
                      child: MyDivider(
                        thickness: 0.5,
                        height: 1,
                        indent: 20,
                        endIndent: 20,
                        color: dividerColor,
                      ),
                    ),
                    SliverToBoxAdapter(child: SizedBox(height: 5)),

                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'ranking',

                        leading: AppIcon(rankingImage, size: 30),
                        content: l10n.ranking,

                        onTap: () {
                          layersManager.switchRootLayer('ranking');
                        },
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: sidebarItem(
                        label: 'recently',

                        leading: AppIcon(recentlyImage, size: 30),
                        content: l10n.recently,

                        onTap: () {
                          layersManager.switchRootLayer('recently');
                        },
                      ),
                    ),
                    SliverToBoxAdapter(child: SizedBox(height: 5)),
                    SliverToBoxAdapter(
                      child: MyDivider(
                        thickness: 0.5,
                        height: 1,
                        indent: 20,
                        endIndent: 20,
                        color: dividerColor,
                      ),
                    ),
                    SliverToBoxAdapter(child: SizedBox(height: 5)),

                    SliverToBoxAdapter(
                      child: Builder(
                        builder: (context) {
                          return GestureDetector(
                            child: sidebarItem(
                              label: 'playlists',
                              leading: AppIcon(playlistsImage, size: 30),
                              content: l10n.playlists,
                              contentPadding: EdgeInsets.fromLTRB(16, 0, 8, 0),

                              trailing: IconButton(
                                tooltip: AppLocalizations.of(
                                  context,
                                ).createPlaylist,
                                onPressed: () {
                                  showCreatePlaylistDialog(context);
                                },
                                icon: labelIcon(
                                  AppLocalizations.of(context).createPlaylist,
                                  AppIcon(addImage, size: 20),
                                ),
                              ),

                              onTap: () {
                                layersManager.switchRootLayer('playlists');
                              },
                            ),
                            onTapDown: (details) {
                              if (Platform.isIOS) {
                                showContextMenu(context, [
                                  MenuItem(
                                    text: l10n.reorder,
                                    iconData: Icons.reorder_rounded,
                                    callback: () async {
                                      showAnimationDialog(
                                        context: context,

                                        child: OrientationBuilder(
                                          builder: (context, orientation) {
                                            final size = MediaQuery.of(
                                              context,
                                            ).size;
                                            final shortSide = size.shortestSide;

                                            bool isPhone = shortSide < 600;
                                            return SizedBox(
                                              height: max(
                                                350,
                                                size.height * 0.7,
                                              ),
                                              width: isPhone ? 300 : 400,
                                              child: Padding(
                                                padding:
                                                    const EdgeInsets.fromLTRB(
                                                      10,
                                                      10,
                                                      10,
                                                      0,
                                                    ),
                                                child: reorderablePlaylistsView(
                                                  context,
                                                ),
                                              ),
                                            );
                                          },
                                        ),
                                      );
                                    },
                                  ),
                                ], details.globalPosition);
                              }
                            },
                            onLongPressStart: (details) {
                              if (Platform.isAndroid) {
                                tryVibrate();
                                showContextMenu(context, [
                                  MenuItem(
                                    text: l10n.reorder,
                                    iconData: Icons.reorder_rounded,
                                    callback: () async {
                                      showAnimationDialog(
                                        context: context,

                                        child: OrientationBuilder(
                                          builder: (context, orientation) {
                                            final size = MediaQuery.of(
                                              context,
                                            ).size;
                                            final shortSide = size.shortestSide;

                                            bool isPhone = shortSide < 600;
                                            return SizedBox(
                                              height: max(
                                                350,
                                                size.height * 0.7,
                                              ),
                                              width: isPhone ? 300 : 400,
                                              child: Padding(
                                                padding:
                                                    const EdgeInsets.fromLTRB(
                                                      10,
                                                      10,
                                                      10,
                                                      0,
                                                    ),
                                                child: reorderablePlaylistsView(
                                                  context,
                                                ),
                                              ),
                                            );
                                          },
                                        ),
                                      );
                                    },
                                  ),
                                ], details.globalPosition);
                              }
                            },
                          );
                        },
                      ),
                    ),
                    SliverToBoxAdapter(child: SizedBox(height: 5)),

                    // keep Favorite at top
                    ValueListenableBuilder(
                      valueListenable: playlistManager.updateNotifier,
                      builder: (context, value, child) {
                        return SliverToBoxAdapter(child: playlistItem(0));
                      },
                    ),

                    ValueListenableBuilder(
                      valueListenable: playlistManager.updateNotifier,
                      builder: (context, _, _) {
                        // Pinned playlists only; the Playlists tab has all.
                        final pinned = playlistManager.sidebarPlaylists
                            .skip(1)
                            .toList();
                        return SliverReorderableList(
                          onReorderItem: (oldIndex, newIndex) {
                            // Reorder the pinned ones among themselves, back
                            // into the same slots of the full list, so
                            // unpinned playlists keep their places.
                            final all = playlistManager.playlists;
                            final slots = [
                              for (final p in pinned) all.indexOf(p),
                            ];
                            final item = pinned.removeAt(oldIndex);
                            pinned.insert(
                              newIndex.clamp(0, pinned.length),
                              item,
                            );
                            for (var i = 0; i < slots.length; i++) {
                              all[slots[i]] = pinned[i];
                            }
                            playlistManager.update();
                          },
                          itemCount: pinned.length,
                          itemBuilder: (_, index) {
                            return ReorderableDragStartListener(
                              enabled: !isMobile,
                              index: index,
                              key: ValueKey(pinned[index].name),
                              child: playlistItem(
                                playlistManager.playlists.indexOf(
                                  pinned[index],
                                ),
                              ),
                            );
                          },
                        );
                      },
                    ),
                  ],
                ),
              ),
            ),
            if (isTooNarrow(context)) ...[
              sidebarItem(
                label: 'settings',
                leading: AppIcon(settingImage, size: 30),
                content: l10n.settings,
                onTap: () {
                  layersManager.switchRootLayer('settings');
                },
              ),
              SizedBox(height: 40),
            ],
          ],
        ),
      ),
    );
  }

  Widget playlistItem(int index) {
    final playlist = playlistManager.getPlaylistByIndex(index);

    return Builder(
      builder: (context) {
        final l10n = AppLocalizations.of(context);

        return GestureDetector(
          child: sidebarItem(
            label: '_${playlist.name}',
            leading: ValueListenableBuilder(
              valueListenable: playlist.changeNotifier,
              builder: (context, value, child) {
                final cover = playlist.coverPicture;
                return ListenableBuilder(
                  listenable: Listenable.merge([cover?.changeNotifier]),
                  builder: (_, _) {
                    return CoverArtWidget(
                      size: 30,
                      borderRadius: 3,
                      picture: cover,
                    );
                  },
                );
              },
            ),
            content: index == 0 ? l10n.favorites : playlist.name,

            onTap: () {
              layersManager.switchRootLayer('_${playlist.name}');
            },
          ),
          onSecondaryTapUp: (details) {
            if (index == 0) {
              return;
            }
            final menuItems = <MenuItem>[...playlistOptionItems(playlist)];

            menuItems.add(
              MenuItem(
                iconData: Icons.delete,
                text: l10n.delete,
                callback: () async {
                  if (await showConfirmDialog(
                    context,
                    "${l10n.delete} ${playlist.name}",
                  )) {
                    if (closeDrawer != null) {
                      closeDrawer!.call();
                      await Future.delayed(Duration(milliseconds: 250));
                    }
                    layersManager.removeLayerIfNeed(playlist);
                    playlistManager.deletePlaylist(playlist);
                  }
                },
              ),
            );

            showContextMenu(context, menuItems, details.globalPosition);
          },
          onTapDown: (details) {
            if (Platform.isIOS && index > 0) {
              final menuItems = <MenuItem>[...playlistOptionItems(playlist)];

              menuItems.add(
                MenuItem(
                  iconData: Icons.delete,
                  text: l10n.delete,
                  callback: () async {
                    if (await showConfirmDialog(
                      context,
                      "${l10n.delete} ${playlist.name}",
                    )) {
                      if (closeDrawer != null) {
                        closeDrawer!.call();
                        await Future.delayed(Duration(milliseconds: 250));
                      }
                      layersManager.removeLayerIfNeed(playlist);
                      playlistManager.deletePlaylist(playlist);
                    }
                  },
                ),
              );

              showContextMenu(context, menuItems, details.globalPosition);
            }
          },
          onLongPressStart: (details) {
            if (Platform.isAndroid && index > 0) {
              tryVibrate();

              final menuItems = <MenuItem>[...playlistOptionItems(playlist)];

              menuItems.add(
                MenuItem(
                  iconData: Icons.delete,
                  text: l10n.delete,
                  callback: () async {
                    if (await showConfirmDialog(
                      context,
                      "${l10n.delete} ${playlist.name}",
                    )) {
                      if (closeDrawer != null) {
                        closeDrawer!.call();
                        await Future.delayed(Duration(milliseconds: 250));
                      }
                      layersManager.removeLayerIfNeed(playlist);
                      playlistManager.deletePlaylist(playlist);
                    }
                  },
                ),
              );

              showContextMenu(context, menuItems, details.globalPosition);
            }
          },
        );
      },
    );
  }
}

/// The accent, unless it would be hard to read on the sidebar's own colour.
Color _wordmarkColor() => readableOr(
  seekBarColor.value.withAlpha(255),
  sidebarColor.value,
  highlightTextColor.value,
);
