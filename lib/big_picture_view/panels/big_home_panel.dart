import 'package:flutter/material.dart';
import 'package:sylvakru/base/data/artist_album.dart';
import 'package:sylvakru/base/data/history.dart';
import 'package:sylvakru/base/data/library.dart';
import 'package:sylvakru/base/data/playlist.dart';
import 'package:sylvakru/base/my_audio_metadata.dart';
import 'package:sylvakru/base/services/metadata_service.dart';
import 'package:sylvakru/base/utils/metadata_utils.dart';
import 'package:sylvakru/base/utils/zoom_page_route.dart';
import 'package:sylvakru/base/widgets/cover_art_widget.dart';
import 'package:sylvakru/base/widgets/scale_widget.dart';
import 'package:sylvakru/big_picture_view/panels/big_single_album_panel.dart';
import 'package:sylvakru/l10n/generated/app_localizations.dart';

class BigHomePanel extends StatefulWidget {
  const BigHomePanel({super.key});

  @override
  State<StatefulWidget> createState() => _BigHomePanelState();
}

class _BigHomePanelState extends State<BigHomePanel> {
  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListView(
      padding: const EdgeInsets.symmetric(vertical: 75),
      children: [
        _listView(
          title: l10n.artists,
          getCoverSong: (index) {
            return artistAlbumManager.artistList[index].getCoverSong();
          },
          count: artistAlbumManager.artistList.length,
          onTap: (index) {},
        ),

        _listView(
          title: l10n.albums,
          getCoverSong: (index) =>
              artistAlbumManager.albumList[index].getCoverSong(),
          count: artistAlbumManager.albumList.length,
          onTap: (index) async {
            final baseColor = await computeCoverArtColor(
              artistAlbumManager.albumList[index].getCoverSong(),
            );
            if (!context.mounted) {
              return;
            }
            Navigator.of(context).push(
              ZoomPageRoute(
                builder: (context) {
                  return BigSingleAlbumPanel(
                    album: artistAlbumManager.albumList[index],
                    baseColor: baseColor,
                  );
                },
              ),
            );
          },
          tag: (index) =>
              'big${artistAlbumManager.albumList[index].getCoverSong().id}${artistAlbumManager.albumList[index].name}',
        ),

        _listView(
          title: l10n.folders,
          getCoverSong: (index) =>
              getFirstSong(library.localFolderList[index].songList),
          count: library.localFolderList.length,
          onTap: (index) {},
        ),

        _listView(
          title: l10n.ranking,
          getCoverSong: (index) =>
              history.rankingSongListManager.getSongList()[index],
          count: history.rankingSongListManager.getSongList().length,
          onTap: (index) {},
        ),

        _listView(
          title: l10n.recently,
          getCoverSong: (index) =>
              history.recentlySongListManager.getSongList()[index],
          count: history.recentlySongListManager.getSongList().length,
          onTap: (index) {},
        ),

        _listView(
          title: l10n.playlists,
          getCoverSong: (index) =>
              playlistManager.playlists[index].getCoverSong(),
          count: playlistManager.playlists.length,
          onTap: (index) {},
        ),
      ],
    );
  }

  Widget _listView({
    required String title,
    required MyAudioMetadata? Function(int) getCoverSong,
    required int count,
    required void Function(int) onTap,
    String Function(int)? tag,
  }) {
    if (count == 0) {
      return SizedBox.shrink();
    }
    return Column(
      children: [
        Row(
          children: [
            SizedBox(width: 40),
            Text(title, style: .new(fontSize: 24, fontWeight: .bold)),
            IconButton(
              onPressed: () {},
              icon: Icon(Icons.arrow_forward_ios_rounded),
            ),
          ],
        ),
        SizedBox(
          height: 280,
          child: ListView.separated(
            padding: EdgeInsets.symmetric(horizontal: 40),
            scrollDirection: .horizontal,
            itemCount: count,
            separatorBuilder: (context, index) {
              return const SizedBox(width: 30);
            },
            itemBuilder: (context, index) {
              final song = getCoverSong(index);
              return ScaleWidget(
                onTap: () {
                  onTap.call(index);
                },
                child: Column(
                  children: [
                    SizedBox(height: 15),
                    tag != null
                        ? Hero(
                            tag: tag(index),
                            child: CoverArtWidget(
                              size: 200,
                              borderRadius: 20,
                              song: song,
                            ),
                          )
                        : CoverArtWidget(
                            size: 200,
                            borderRadius: 20,
                            song: song,
                          ),
                    SizedBox(
                      width: 180,
                      child: ListTile(
                        contentPadding: .zero,
                        mouseCursor: SystemMouseCursors.click,
                        title: Text(
                          getTitle(song),
                          style: .new(overflow: .ellipsis),
                        ),
                        subtitle: Text(
                          '${getArtist(song)} - ${getAlbum(song)}',
                          style: .new(overflow: .ellipsis),
                        ),
                        visualDensity: .new(vertical: -4),
                      ),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}
