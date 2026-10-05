import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/picture_service.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';
import 'package:soiboi/base/utils/zoom_page_route.dart';
import 'package:soiboi/base/widgets/cover_art_widget.dart';
import 'package:soiboi/big_picture_view/panels/big_single_album_panel.dart';
import 'package:soiboi/big_picture_view/panels/big_single_artist_panel.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/base/utils/media_query.dart';

import 'package:soiboi/base/widgets/dialogs.dart';
import 'package:soiboi/base/services/center_toast.dart';

Future<String?> _selectArtist(
  BuildContext context,
  List<String> artists, {
  String? excludedArtist,
}) async {
  artists.removeWhere((e) => e == excludedArtist);
  if (artists.isEmpty) {
    return null;
  }
  if (artists.length == 1 && excludedArtist == null) {
    return artists.first;
  }

  return showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 300,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(10, 20, 10, 20),
        child: ListView.builder(
          shrinkWrap: true,
          itemCount: artists.length,
          itemExtent: scaledExtent(context, 60),
          itemBuilder: (context, index) {
            String name = artists[index];

            return Center(
              child: ListTile(
                leading: CoverArtWidget(
                  size: 50,
                  borderRadius: 5,
                  picture: artistAlbumManager.artistMap[name]!.picture,
                ),
                title: Text(name, style: .new(overflow: .ellipsis)),
                onTap: () {
                  Navigator.pop(context, name);
                },
              ),
            );
          },
        ),
      ),
    ),
  );
}

void goToArtist(
  MyAudioMetadata song,
  BuildContext context, {
  bool bigPictureMode = false,
  String? excludedArtist,
}) async {
  Artist? artist;
  if (isNotStreamSource) {
    final artistName = await _selectArtist(
      context,
      getArtists(getArtist(song)),
      excludedArtist: excludedArtist,
    );
    if (artistName == null) {
      return;
    }
    artist = await artistAlbumManager.artistFor(artistName);
    if (artist == null) {
      return;
    }
  } else {
    showCenterLoading();
    artist = await artistAlbumManager.artistFor(song.artist);
    removeCenterLoading();
    if (artist == null) {
      showCenterMessage('Get artist failed');
      return;
    }
  }

  if (bigPictureMode) {
    if (context.mounted) {
      Navigator.of(context).push(
        ZoomPageRoute(
          builder: (context) {
            return BigSingleArtistPanel(artist: artist!);
          },
        ),
      );
    }
  } else {
    showCenterLoading();
    await Future.delayed(Duration(milliseconds: 250));
    layersManager.switchRootLayer('artists');
    await layersManager.pushDetailIfNeed(artist);
    removeCenterLoading();
  }
}

void goToAlbum(
  MyAudioMetadata song, {
  bool bigPictureMode = false,
  BuildContext? context,
}) async {
  await Future.delayed(Duration(milliseconds: 250));

  if (isStreamSource && song.albumId == null) {
    showCenterMessage('Can not get this album');
    return;
  }

  Album? album;
  if (isStreamSource) {
    showCenterLoading();
    album = await artistAlbumManager.albumFor(song);
    removeCenterLoading();
  } else {
    album = await artistAlbumManager.albumFor(song);
  }
  if (album == null) {
    showCenterMessage('Get album failed');
    return;
  }

  if (bigPictureMode) {
    showCenterLoading();
    final baseColor = await computeColor(album.picture);
    if (!context!.mounted) {
      return;
    }
    removeCenterLoading();

    Navigator.of(context).push(
      ZoomPageRoute(
        builder: (context) {
          return BigSingleAlbumPanel(album: album!, baseColor: baseColor);
        },
      ),
    );
    return;
  }

  layersManager.switchRootLayer('albums');

  showCenterLoading();
  await layersManager.pushDetailIfNeed(album);
  removeCenterLoading();
}
