import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/widgets/dialogs.dart';
import 'package:soiboi/base/widgets/context_menu.dart';
import 'package:soiboi/base/services/center_toast.dart';
import 'package:soiboi/base/widgets/collection_list.dart';
import 'package:soiboi/base/widgets/playlist_widgets.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/base/asset_images.dart';

final GlobalKey<NavigatorState> playlistsKey = GlobalKey();
final playlistsVisibleNotifier = ValueNotifier(true);

class PlaylistsLayer extends CollectionList {
  const PlaylistsLayer({super.key});

  @override
  State<StatefulWidget> createState() => _PlaylistsLayerState();
}

class _PlaylistsLayerState extends CollectionListState {
  @override
  GlobalKey<NavigatorState> get globalKey => playlistsKey;

  @override
  ValueNotifier<bool> get visibleNotifier => playlistsVisibleNotifier;

  @override
  ValueNotifier<bool> get useLargePictureNotifier =>
      playlistsUseLargePictureNotifier;

  @override
  AssetImage get image => playlistsImage;

  @override
  String Function(int) get countFunction =>
      AppLocalizations.of(context).playlistCount;

  @override
  bool get reachEnd => true;

  @override
  void updateCurrentList() {
    preparing = false;
    final value = textController.text;
    final list = playlistManager.playlists.where((playlist) {
      return playlist.name.toLowerCase().contains(value.toLowerCase());
    }).toList();

    currentItems = list
        .map(
          (e) => CollectionItem(
            picture: e.coverPicture,
            text: e.name,
            subCount: e.totalCount,
            onTap: () {
              if (e.coverPicture == null || e.coverPicture!.isLoaded) {
                layersManager.pushDetail('playlists', e);
              }
            },
            onMenu: (context, position) =>
                _showPlaylistMenu(context, e, position),
          ),
        )
        .toList();
    changeNotifier.value++;
  }

  void _showPlaylistMenu(
    BuildContext context,
    Playlist playlist,
    Offset position,
  ) {
    tryVibrate();
    final l10n = AppLocalizations.of(context);
    showContextMenu(context, [
      ...playlistOptionItems(playlist),
      if (playlist.isNotFavorite)
        MenuItem(
          iconData: Icons.delete,
          text: l10n.delete,
          callback: () async {
            if (await showConfirmDialog(
              context,
              '${l10n.delete} ${playlist.name}',
            )) {
              layersManager.removeLayerIfNeed(playlist);
              playlistManager.deletePlaylist(playlist);
            }
          },
        ),
    ], position);
  }

  @override
  void initState() {
    super.initState();
    isListViewNotifier = ValueNotifier(true);
    updateCurrentList();
    playlistManager.updateNotifier.addListener(updateCurrentList);
  }

  @override
  void dispose() {
    playlistManager.updateNotifier.removeListener(updateCurrentList);
    super.dispose();
  }

  @override
  Widget? floatingAction(BuildContext context) => FloatingActionButton.extended(
    heroTag: 'newPlaylist',
    onPressed: () => showCreatePlaylistDialog(context),
    icon: const Icon(Icons.add_rounded),
    label: const Text('New playlist'),
  );

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    title = l10n.playlists;
    searchHint = l10n.searchPlaylists;
    if (currentItems.isNotEmpty) {
      currentItems[0] = currentItems[0].copyWith(text: l10n.favorites);
    }
    return super.build(context);
  }
}
