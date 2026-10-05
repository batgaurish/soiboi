import 'dart:math';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/asset_images.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/song_deletion.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';
import 'package:soiboi/base/utils/zoom_page_route.dart';
import 'package:soiboi/base/widgets/cover_art_widget.dart';
import 'package:soiboi/base/widgets/my_divider.dart';
import 'package:soiboi/base/widgets/playlist_widgets.dart';
import 'package:soiboi/base/widgets/selectable_song_list_page.dart';
import 'package:soiboi/base/widgets/song_info.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/base/widgets/app_icon.dart';

import 'package:soiboi/base/widgets/dialogs.dart';
import 'package:soiboi/base/utils/library_navigation.dart';

void showSongOptions({
  required BuildContext context,
  required MyAudioMetadata song,
  void Function()? moveToTop,
  bool includeGoToArtist = false,
  String? excludedArtist,
  bool includeGoToAlbum = false,
  Playlist? playlist,
}) {
  final l10n = AppLocalizations.of(context);
  showAnimationDialog(
    context: context,
    child: Builder(
      builder: (context) {
        return ConstrainedBox(
          constraints: BoxConstraints(
            maxWidth: max(320, min(MediaQuery.widthOf(context) / 3, 400)),
            maxHeight: MediaQuery.sizeOf(context).height * 0.8,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(height: 5),

              ListTile(
                leading: CoverArtWidget(
                  size: 50,
                  borderRadius: 5,
                  picture: song.picture,
                ),
                title: Text(getTitle(song), overflow: TextOverflow.ellipsis),
                subtitle: Text(
                  "${getArtist(song)} - ${getAlbum(song)}",
                  overflow: TextOverflow.ellipsis,
                ),
              ),

              SizedBox(height: 5),
              MyDivider(color: dividerColor, thickness: 0.5, height: 1),
              SizedBox(height: 5),

              Flexible(
                child: ListView(
                  physics: const ClampingScrollPhysics(),
                  shrinkWrap: true,
                  children: [
                    if (moveToTop != null)
                      ListTile(
                        leading: Icon(Icons.vertical_align_top_rounded),
                        title: Text(
                          l10n.move2Top,
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        visualDensity: const VisualDensity(
                          horizontal: 0,
                          vertical: -4,
                        ),
                        onTap: () {
                          Navigator.pop(context);
                          moveToTop.call();
                        },
                      ),
                    ListTile(
                      leading: Icon(Icons.play_arrow_rounded),
                      title: Text(
                        l10n.playNow,
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      visualDensity: const VisualDensity(
                        horizontal: 0,
                        vertical: -4,
                      ),
                      onTap: () {
                        audioHandler.singlePlay(song);
                        Navigator.pop(context);
                        audioHandler.saveAllStates();
                      },
                    ),
                    ListTile(
                      leading: Icon(Icons.navigate_next_rounded),
                      title: Text(
                        l10n.playNext,
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      visualDensity: const VisualDensity(
                        horizontal: 0,
                        vertical: -4,
                      ),
                      onTap: () {
                        if (playQueue.isEmpty) {
                          audioHandler.singlePlay(song);
                        } else {
                          audioHandler.insert2Next(song);
                        }
                        Navigator.pop(context);
                        audioHandler.saveAllStates();
                      },
                    ),
                    ListTile(
                      leading: Icon(Icons.playlist_add_rounded),
                      title: Text(
                        l10n.add2Queue,
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      visualDensity: const VisualDensity(
                        horizontal: 0,
                        vertical: -4,
                      ),
                      onTap: () {
                        if (playQueue.isEmpty) {
                          audioHandler.singlePlay(song);
                        } else {
                          audioHandler.add2Last(song);
                        }
                        Navigator.pop(context);
                        audioHandler.saveAllStates();
                      },
                    ),
                    ListTile(
                      leading: Icon(Icons.add_rounded),
                      title: Text(
                        l10n.add2Playlist,
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      visualDensity: const VisualDensity(
                        horizontal: 0,
                        vertical: -4,
                      ),
                      onTap: () {
                        Navigator.pop(context);

                        showAddPlaylistDialog(context, [song]);
                      },
                    ),

                    if (includeGoToArtist)
                      ListTile(
                        leading: Icon(Icons.people),
                        title: Text(
                          l10n.go2Artist,
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        visualDensity: const VisualDensity(
                          horizontal: 0,
                          vertical: -4,
                        ),
                        onTap: () {
                          goToArtist(
                            song,
                            context,
                            bigPictureMode: true,
                            excludedArtist: excludedArtist,
                          );
                        },
                      ),

                    if (includeGoToAlbum)
                      ListTile(
                        leading: Icon(Icons.album_rounded),
                        title: Text(
                          l10n.go2Album,
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        visualDensity: const VisualDensity(
                          horizontal: 0,
                          vertical: -4,
                        ),
                        onTap: () {
                          Navigator.pop(context);
                          goToAlbum(
                            song,
                            bigPictureMode: true,
                            context: context,
                          );
                        },
                      ),

                    ListTile(
                      leading: Icon(Icons.info_outline_rounded),
                      title: Text(
                        l10n.songInfo,
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      visualDensity: const VisualDensity(
                        horizontal: 0,
                        vertical: -4,
                      ),
                      onTap: () {
                        Navigator.pop(context);
                        showAnimationDialog(
                          context: context,
                          child: SongInfo(song: song),
                        );
                      },
                    ),

                    if (canDeleteFromDevice(song))
                      ListTile(
                        leading: Icon(Icons.delete_forever_rounded),
                        title: Text(
                          'Delete from device',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        visualDensity: const VisualDensity(
                          horizontal: 0,
                          vertical: -4,
                        ),
                        onTap: () async {
                          Navigator.pop(context);
                          await confirmAndDeleteSongs(context, [song]);
                        },
                      ),

                    if (playlist != null)
                      ListTile(
                        leading: Icon(Icons.delete_rounded),
                        title: Text(
                          l10n.delete,
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        visualDensity: const VisualDensity(
                          horizontal: 0,
                          vertical: -4,
                        ),
                        onTap: () async {
                          if (await showConfirmDialog(context, l10n.delete)) {
                            playlist.remove([song]);
                            if (context.mounted) {
                              Navigator.pop(context);
                            }
                          }
                        },
                      ),
                  ],
                ),
              ),

              SizedBox(height: 10),
            ],
          ),
        );
      },
    ),
  );
}

void showPlayQueueItemOptions(
  BuildContext context,
  MyAudioMetadata song, {
  required void Function() playNextCallback,
  required void Function() removeCallback,
}) {
  final l10n = AppLocalizations.of(context);
  showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 300,
      child: ListenableBuilder(
        listenable: Listenable.merge([lyricsPageForegroundColor.valueNotifier]),
        builder: (context, _) {
          final iconColor = colorManager.getSpecificIconColor();
          final textColor = colorManager.getSpecificTextColor();

          return Column(
            mainAxisSize: .min,
            children: [
              SizedBox(height: 10),

              ListTile(
                leading: Icon(Icons.play_arrow_rounded, color: iconColor),
                title: Text(l10n.playNow, style: .new(color: textColor)),
                onTap: () async {
                  Navigator.pop(context);
                  await Future.delayed(Duration(milliseconds: 250));

                  final index = playQueue.indexOf(song);
                  if (index == audioHandler.currentIndex) {
                    audioHandler.play();
                  } else {
                    audioHandler.currentIndex = index;
                    await audioHandler.load();
                    audioHandler.play();
                  }
                },
              ),
              ListTile(
                leading: Icon(Icons.navigate_next_rounded, color: iconColor),
                title: Text(l10n.playNext, style: .new(color: textColor)),
                onTap: () async {
                  Navigator.pop(context);
                  await Future.delayed(Duration(milliseconds: 250));

                  playNextCallback.call();
                },
              ),
              ListTile(
                leading: Icon(Icons.playlist_add_rounded, color: iconColor),
                title: Text(l10n.add2Playlist, style: .new(color: textColor)),
                onTap: () async {
                  Navigator.pop(context);
                  await Future.delayed(Duration(milliseconds: 250));

                  if (context.mounted) {
                    showAddPlaylistDialog(context, [song]);
                  }
                },
              ),

              ListTile(
                leading: Icon(Icons.info_outline_rounded, color: iconColor),
                title: Text(l10n.songInfo, style: .new(color: textColor)),
                onTap: () async {
                  Navigator.pop(context);
                  await Future.delayed(Duration(milliseconds: 250));
                  if (context.mounted) {
                    showAnimationDialog(
                      context: context,
                      child: SongInfo(song: song),
                    );
                  }
                },
              ),

              ListTile(
                leading: Icon(Icons.close_rounded, color: iconColor),
                title: Text(l10n.remove, style: .new(color: textColor)),
                onTap: () async {
                  Navigator.pop(context);
                  await Future.delayed(Duration(milliseconds: 250));

                  removeCallback.call();
                },
              ),
              SizedBox(height: 10),
            ],
          );
        },
      ),
    ),
  );
}

void showSongListOptions(BuildContext context, List<MyAudioMetadata> songList) {
  final l10n = AppLocalizations.of(context);
  showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 300,
      child: Builder(
        builder: (context) {
          return Column(
            mainAxisSize: .min,
            children: [
              SizedBox(height: 10),

              ListTile(
                leading: Icon(Icons.play_arrow_rounded),
                title: Text(l10n.playAll),
                onTap: () async {
                  Navigator.pop(context);
                  await Future.delayed(Duration(milliseconds: 250));
                  audioHandler.setPlayQueue(songList, 0);
                },
              ),
              ListTile(
                leading: AppIcon(shuffleImage),
                title: Text(l10n.shuffle),
                onTap: () async {
                  Navigator.pop(context);
                  await Future.delayed(Duration(milliseconds: 250));

                  audioHandler.setPlayQueue(songList, 1);
                },
              ),

              ListTile(
                leading: AppIcon(selectImage),
                title: Text(l10n.select),
                onTap: () async {
                  Navigator.pop(context);
                  await Future.delayed(Duration(milliseconds: 250));
                  if (context.mounted) {
                    Navigator.of(context).push(
                      ZoomPageRoute(
                        builder: (_) => SelectableSongListPage(
                          songList: songList,
                          reorderable: false,
                          isSelectedNotifierMap: Map.fromEntries(
                            songList.map(
                              (e) => MapEntry(e, ValueNotifier(false)),
                            ),
                          ),
                        ),
                      ),
                    );
                  }
                },
              ),

              SizedBox(height: 10),
            ],
          );
        },
      ),
    ),
  );
}

void showArtistsAlbumsOptions(BuildContext context, bool isArtist) {
  final l10n = AppLocalizations.of(context);
  showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 300,
      child: Builder(
        builder: (context) {
          final isAscending = artistAlbumManager.getIsAscendingNotifier(
            isArtist,
          );
          return Column(
            mainAxisSize: .min,
            children: [
              SizedBox(height: 10),

              ListTile(
                leading: AppIcon(sequenceImage),
                title: Text(
                  isAscending.value ? l10n.descending : l10n.ascending,
                ),
                onTap: () async {
                  Navigator.pop(context);
                  await Future.delayed(Duration(milliseconds: 250));

                  isAscending.value = !isAscending.value;
                },
              ),

              SizedBox(height: 10),
            ],
          );
        },
      ),
    ),
  );
}
