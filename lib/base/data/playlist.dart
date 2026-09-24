import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/picture_service.dart';
import 'package:crypto/crypto.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/stream_client.dart';
import 'package:soiboi/base/utils/path.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

final playlistManager = PlaylistManager();

class PlaylistManager {
  late File _playlistsFile;

  List<Playlist> playlists = [];
  Map<String, Playlist> playlistMap = {};
  ValueNotifier<int> updateNotifier = ValueNotifier(0);

  /// Playlists shown in the sidebar. Every playlist is on the Playlists tab;
  /// only pinned ones (and Favorite, always) also get a sidebar entry, so a
  /// library with dozens of imported playlists keeps a usable sidebar.
  final Set<String> _pinned = {};
  late File _pinnedFile;

  bool isPinned(Playlist playlist) =>
      playlist.isFavorite || _pinned.contains(playlist.name);

  /// Favorite first, then pinned playlists in the user's order.
  List<Playlist> get sidebarPlaylists => playlists.where(isPinned).toList();

  void setPinned(Playlist playlist, bool pinned) {
    if (playlist.isFavorite) return;
    pinned ? _pinned.add(playlist.name) : _pinned.remove(playlist.name);
    _savePinned();
    updateNotifier.value++;
  }

  void _savePinned() {
    _pinnedFile.writeAsStringSync(jsonEncode(_pinned.toList()));
  }

  PlaylistManager() {
    addPlaylist(Playlist(name: 'Favorite'));
  }

  Future<void> _prepare() async {
    playlists.clear();
    playlistMap.clear();

    addPlaylist(Playlist(name: 'Favorite'));
    updateNotifier.value++;

    _playlistsFile = File(
      "${getPlaylistConfigPath(sourceType)}/soiboi_playlists.json",
    );
    initFile(_playlistsFile, true);

    final playlistNames = await readJsonListFile(_playlistsFile);
    for (final name in playlistNames) {
      final playlist = Playlist(name: name);
      addPlaylist(playlist);
    }

    _pinnedFile = File(
      "${getPlaylistConfigPath(sourceType)}/soiboi_pinned_playlists.json",
    );
    _pinned.clear();
    if (_pinnedFile.existsSync()) {
      _pinned.addAll((await readJsonListFile(_pinnedFile)).cast<String>());
    } else {
      // First run with pinning: everything already in the sidebar stays
      // there. Playlists made from now on start unpinned.
      _pinned.addAll(playlistNames.cast<String>());
      _savePinned();
    }
    if (isStreamSource) {
      final tmpPlaylist = await streamClient?.getPlaylists();
      for (final playlist in tmpPlaylist ?? <Playlist>[]) {
        if (playlist.name == playQueueForStreamName) {
          continue;
        }
        if (playlistMap[playlist.name] == null) {
          addPlaylist(playlist);
        }
        playlistMap[playlist.name]!.id = playlist.id;
      }
      playlists.removeWhere((e) => e.isNotFavorite && e.id == null);
      playlistMap.removeWhere((k, v) => v.isNotFavorite && v.id == null);
    }

    update();
  }

  Future<void> load() async {
    await _prepare();
    for (final playlist in playlists) {
      await playlist.load();
    }
  }

  Playlist getPlaylistByIndex(int index) {
    assert(index >= 0 && index < playlists.length);
    return playlists[index];
  }

  Playlist? getPlaylistByName(String name) {
    return playlistMap[name];
  }

  void addPlaylist(Playlist playlist) {
    playlists.add(playlist);
    playlistMap[playlist.name] = playlist;
  }

  Future<void> createPlaylist(String name) async {
    for (Playlist playlist in playlists) {
      // check whether the name exists
      if (name == playlist.name) {
        showCenterMessage('Playlist exists');
        return;
      }
    }

    final playlist = Playlist(name: name);
    if (isStreamSource) {
      playlist.id = await streamClient?.createPlaylist(name);

      // failed
      if (playlist.id == null) {
        showCenterMessage('Create playlist failed');
        return;
      }
    }
    addPlaylist(playlist);

    update();
  }

  Future<void> deletePlaylist(Playlist playlist) async {
    // Remote first: if the server refuses, the playlist must survive intact
    // rather than lose its local song list while staying on screen.
    if (playlist.id != null && streamClient != null) {
      if (!await streamClient!.deletePlaylist(playlist.id!)) {
        showCenterMessage('Delete playlist failed');
        return;
      }
    }
    playlist.songListFile?.deleteSync();
    playlist.removeCover();
    if (_pinned.remove(playlist.name)) _savePinned();

    playlists.remove(playlist);
    playlistMap.remove(playlist.name);

    update();
  }

  void update() {
    _playlistsFile.writeAsStringSync(
      jsonEncode(playlists.map((pl) => pl.name).skip(1).toList()),
    );

    updateNotifier.value++;
  }

  void clear() {
    playlists.clear();
    playlistMap.clear();
  }
}

class Playlist {
  String name;

  String? id;

  File? songListFile;

  List<MyAudioMetadata> songList = [];

  late bool isFavorite;
  late bool isNotFavorite;

  final changeNotifier = ValueNotifier(0);
  final sortTypeNotifier = ValueNotifier(0);

  bool canModify = true;

  /// [fileBacked] false keeps the playlist entirely in memory.
  ///
  /// Smart playlists are defined by rules, not by a stored list of songs, so
  /// giving them a file would leave an empty json on disk per playlist and a
  /// second source of truth to drift from the rules.
  Playlist({required this.name, this.id, bool fileBacked = true}) {
    if (isNotStreamSource && fileBacked) {
      songListFile = File("${getPlaylistConfigPath(sourceType)}/$name.json");
      initFile(songListFile!, true);
    }

    isFavorite = name == 'Favorite';
    isNotFavorite = !isFavorite;
    if (isNotStreamSource) _loadCover();
  }

  MyAudioMetadata? getCoverSong() {
    return getFirstSong(songList);
  }

  /// An image the user chose (or an import brought), shown instead of the
  /// first song's artwork.
  ///
  /// Stored with the app's other pictures under a name that changes on every
  /// replacement: image caches key on the path, so reusing one path would
  /// keep showing the old cover.
  MyPicture? customCover;

  MyPicture? get coverPicture => customCover ?? getCoverSong()?.picture;

  String get _coverPrefix =>
      'playlist-cover-${md5.convert(utf8.encode(name))}-';

  Iterable<File> _coverFiles() {
    final dir = Directory(getPicturesPath(sourceType));
    if (!dir.existsSync()) return const [];
    return dir.listSync().whereType<File>().where(
      (f) => f.uri.pathSegments.last.startsWith(_coverPrefix),
    );
  }

  void _loadCover() {
    final files = _coverFiles().toList()
      ..sort((a, b) => a.path.compareTo(b.path));
    customCover = files.isEmpty
        ? null
        : MyPicture(name, md5Hash: files.last.uri.pathSegments.last);
  }

  /// Copies the image at [imagePath] in as this playlist's cover.
  Future<void> setCover(String imagePath) async {
    final hash = '$_coverPrefix${DateTime.now().millisecondsSinceEpoch}';
    final target = File('${getPicturesPath(sourceType)}/$hash');
    await target.parent.create(recursive: true);
    await File(imagePath).copy(target.path);
    for (final old in _coverFiles()) {
      if (old.path != target.path) old.deleteSync();
    }
    customCover = MyPicture(name, md5Hash: hash);
    _coverChanged();
  }

  void removeCover() {
    for (final file in _coverFiles()) {
      file.deleteSync();
    }
    if (customCover == null) return;
    customCover = null;
    _coverChanged();
  }

  void _coverChanged() {
    changeNotifier.value++;
    playlistManager.updateNotifier.value++;
    layersManager.updateBackground();
  }

  int get totalCount => songList.length;

  Future<void> load() async {
    canModify = false;
    changeNotifier.value++;
    if (isNotStreamSource) {
      final decoded = await readJsonListFile(songListFile!);
      for (String id in decoded) {
        MyAudioMetadata? song = library.id2Song[id];
        if (song == null) {
          continue;
        }
        songList.add(song);
        if (isFavorite) {
          song.isFavoriteNotifier.value = true;
        }
      }
      await songListFile!.writeAsString(
        jsonEncode(songList.map((e) => e.id).toList()),
      );
    } else {
      List<MyAudioMetadata>? tmpSongs;
      if (isFavorite) {
        tmpSongs = (await streamClient?.getStarredSongs());
      } else {
        tmpSongs = (await streamClient?.getPlaylistSongs(id!));
      }
      for (final song in tmpSongs ?? []) {
        songList.add(song);
        if (isFavorite) {
          song.isFavoriteNotifier.value = true;
        }
      }
    }

    canModify = true;
    changeNotifier.value++;
    layersManager.updateBackground();
  }

  Future<void> reload() async {
    songList.clear();
    await load();
  }

  Future<void> add(List<MyAudioMetadata> songList) async {
    if (!canModify) {
      showCenterMessage('Can not modify, it\'s updating');
      return;
    }
    for (MyAudioMetadata song in songList) {
      final targetSongList = this.songList;
      if (targetSongList.contains(song)) {
        continue;
      }
      targetSongList.insert(0, song);

      if (isFavorite) {
        song.isFavoriteNotifier.value = true;
      }
    }
    await update();
  }

  Future<void> remove(List<MyAudioMetadata> songList) async {
    if (!canModify) {
      showCenterMessage('Can not modify, it\'s updating');
      return;
    }
    for (MyAudioMetadata song in songList) {
      final targetSongList = this.songList;
      targetSongList.remove(song);

      if (isFavorite) {
        song.isFavoriteNotifier.value = false;
      }
    }
    await update();
  }

  Future<void> update() async {
    if (!canModify) {
      showCenterMessage('Can not modify, it\'s updating');
      return;
    }
    canModify = false;
    changeNotifier.value++;
    playlistManager.updateNotifier.value++;
    layersManager.updateBackground();

    final songIds = songList.map((e) => e.id).toList();
    await songListFile?.writeAsString(jsonEncode(songIds));
    if (isStreamSource) {
      late bool success;
      if (isFavorite) {
        success = await streamClient?.updateStarredSongs(songIds) ?? false;
      } else {
        success =
            await streamClient?.updatePlaylistSongs(id!, songIds) ?? false;
      }
      if (!success) {
        showCenterMessage('Update playlist failed');
      }
    }
    canModify = true;
    changeNotifier.value++;
  }
}

void toggleFavoriteState(MyAudioMetadata song) {
  final favorite = playlistManager.playlists.first;
  if (!favorite.canModify) {
    return;
  }
  final isFavorite = song.isFavoriteNotifier;
  if (isFavorite.value) {
    favorite.remove([song]);
  } else {
    favorite.add([song]);
  }
}
