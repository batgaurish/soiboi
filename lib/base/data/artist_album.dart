import 'dart:async';

import 'package:lpinyin/lpinyin.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/picture_service.dart';
import 'package:soiboi/base/services/stream_client.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';

final ArtistAlbumManager artistAlbumManager = ArtistAlbumManager();

class ArtistAlbumManager {
  static bool Function()? isBusyProvider;
  bool get isBusy => isBusyProvider?.call() ?? false;

  List<Artist> artistList = [];
  Map<String, Artist> artistMap = {};

  List<Album> albumList = [];
  // streamSoure will has duplicate name album
  Map<String, Album> albumMap = {};
  final updateNotifier = ValueNotifier(0);
  static final clearNotifier = ValueNotifier<int>(0);

  ArtistAlbumManager() {
    artistsIsAscendingNotifier.addListener(() {
      sortArtists();
      updateNotifier.value++;
    });
    albumsIsAscendingNotifier.addListener(() {
      sortAlbums();
      updateNotifier.value++;
    });
  }

  List<ArtistAlbumBase> getArtistAlbumList(bool isArtist) {
    return isArtist ? artistList : albumList;
  }

  ValueNotifier<bool> getRandomizeNotifier(bool isArtist) {
    return isArtist ? artistsRandomizeNotifier : albumsRandomizeNotifier;
  }

  ValueNotifier<bool> getIsAscendingNotifier(bool isArtist) {
    return isArtist ? artistsIsAscendingNotifier : albumsIsAscendingNotifier;
  }

  ValueNotifier<bool> getUseLargePictureNotifier(bool isArtist) {
    return isArtist
        ? artistsUseLargePictureNotifier
        : albumsUseLargePictureNotifier;
  }

  void classify() {
    artistList.clear();
    albumList.clear();
    artistMap.clear();
    albumMap.clear();

    for (final song in library.songList) {
      _processSong(song);
    }

    sortArtists();
    sortAlbums();

    for (final album in albumList) {
      album.sort();
    }

    for (final artist in artistList) {
      artist.combineAlbums();
    }

    updateNotifier.value++;
  }

  void clear() {
    clearNotifier.value++;
    artistList.clear();
    albumList.clear();
    artistMap.clear();
    albumMap.clear();
    artistCompleter = null;
    ablumCompleter = null;
    updateNotifier.value++;
  }

  void _processSong(MyAudioMetadata song) {
    final albumName = getAlbum(song);

    Album? album = albumMap[albumName];
    if (album == null) {
      album = Album(albumName);
      albumList.add(album);
      albumMap[albumName] = album;
    }

    if (song.year != null && album.year == null) {
      album.year = song.year;
    }

    album.songList.add(song);

    for (String artistName in getArtists(getArtist(song))) {
      Artist? artist = artistMap[artistName];
      if (artist == null) {
        artist = Artist(artistName);
        artistList.add(artist);
        artistMap[artistName] = artist;
      }
      artist.albumSet.add(album);
    }
  }

  void sortArtists() {
    artistList.sort((a, b) {
      if (artistsIsAscendingNotifier.value) {
        return a.compareName.compareTo(b.compareName);
      } else {
        return b.compareName.compareTo(a.compareName);
      }
    });
  }

  void sortAlbums() {
    albumList.sort((a, b) {
      if (albumsIsAscendingNotifier.value) {
        return a.compareName.compareTo(b.compareName);
      } else {
        return b.compareName.compareTo(a.compareName);
      }
    });
  }

  void updateArtistAlbum() {
    clear();
    classify();
  }

  // use completer to avoid loading same data multiple times
  Completer<void>? artistCompleter;
  Completer<int?>? ablumCompleter;

  /// Ensures albums are loaded for the current source.
  /// For local sources or if already populated, returns false.
  /// For stream sources, loads the initial batch and returns true if end of list is reached.
  Future<bool> ensureAlbums() async {
    if (albumList.isNotEmpty || (isNotStreamSource && !isBusy)) {
      return false;
    }
    if (isStreamSource) {
      final count = await loadAlbums();
      return count == 0;
    }
    return false;
  }

  /// Ensures artists are loaded for the current source.
  /// No-op if already populated or for ready local sources.
  Future<bool> ensureArtists() async {
    if (artistList.isNotEmpty || (isNotStreamSource && !isBusy)) {
      return false;
    }
    if (isStreamSource) {
      await loadArtists();
    }
    return false;
  }

  /// Finds or fetches an album for the given song.
  Future<Album?> albumFor(MyAudioMetadata song) async {
    if (isNotStreamSource) {
      return albumMap[getAlbum(song)];
    }
    if (song.albumId == null) {
      return null;
    }
    if (albumList.isEmpty) {
      await loadAlbums();
    }
    if (albumMap[song.albumId] == null) {
      final album = await streamClient?.getAlbum(song.albumId!);
      if (album != null) {
        albumList.add(album);
        sortAlbums();
        updateNotifier.value++;
      }
    }
    return albumMap[song.albumId];
  }

  /// Finds or fetches an artist by name.
  Future<Artist?> artistFor(String? artistName) async {
    if (artistName == null) {
      return null;
    }
    if (isNotStreamSource) {
      return artistMap[artistName];
    }
    if (artistList.isEmpty) {
      await loadArtists();
    }
    return artistMap[artistName];
  }

  Future<void> loadArtists() async {
    if (artistCompleter == null) {
      artistCompleter = Completer<void>();
      final tmpArtistList = await streamClient?.getArtistList();
      if (tmpArtistList == null) {
        updateNotifier.value++;
        artistCompleter!.complete();
        return;
      }

      for (final artist in tmpArtistList) {
        artistList.add(artist);
        artistMap[artist.name] = artist;
      }
      sortArtists();
      updateNotifier.value++;
      artistCompleter!.complete();
      return;
    }
    updateNotifier.value++;
    return artistCompleter!.future;
  }

  // null: error; 0: end
  Future<int?> loadAlbums() async {
    if (ablumCompleter == null) {
      ablumCompleter = Completer<int?>();
      final loadedAlbums = await streamClient?.getAlbumList(
        albumList.length,
      );
      if (loadedAlbums == null) {
        updateNotifier.value++;
        ablumCompleter!.complete(null);
        ablumCompleter = null;
        return null;
      }

      albumList.addAll(loadedAlbums);
      sortAlbums();
      updateNotifier.value++;

      ablumCompleter!.complete(loadedAlbums.length);
      ablumCompleter = null;
      return loadedAlbums.length;
    }
    return ablumCompleter!.future;
  }
}

abstract class ArtistAlbumBase {
  String? id;
  final String name;
  late final String compareName;

  final List<MyAudioMetadata> songList = [];

  bool get isArtist => this is Artist;

  MyPicture? _picture;
  MyPicture get picture => isStreamSource ? _picture! : getCoverSong().picture;

  ArtistAlbumBase(this.name, {this.id, String? coverArtId}) {
    id ??= name;
    compareName = PinyinHelper.getPinyinE(name);
    if (isStreamSource) {
      _picture = MyPicture.from(coverArtId ?? '');
    }
  }

  bool get isEmpty => songList.isEmpty;

  MyAudioMetadata getCoverSong() {
    return songList.first;
  }

  int get totalCount => songList.length;

  Completer<void>? completer;

  Future<void> load();
}

class Artist extends ArtistAlbumBase {
  Artist(super.name, {super.id, super.coverArtId});

  Set<Album> albumSet = {};

  List<Album> albumList = [];

  final changeNotifier = ValueNotifier(0);

  void combineAlbums() {
    albumSet.removeWhere((album) => album.isEmpty);
    albumList = albumSet.toList();
    albumList.sort((a, b) {
      int aYear = a.year ?? 9999;
      int bYear = b.year ?? 9999;

      return aYear.compareTo(bYear);
    });

    for (final album in albumList) {
      songList.addAll(album.artist2SongList[name]!);
    }
  }

  @override
  Future<void> load() async {
    if (completer == null) {
      completer = Completer<void>();
      if (sourceType == .navidrome) {
        final albums = await streamClient?.getArtistAlbumList(id!);
        if (albums == null) {
          completer!.complete();
          return;
        } else {
          albumList.addAll(albums);
        }

        for (final album in albumList) {
          await album.load();
          songList.addAll(album.songList);
          changeNotifier.value++;
        }
        completer!.complete();
        return;
      } else {
        songList.addAll(await streamClient?.getArtistSongs(id!) ?? []);
        changeNotifier.value++;
        completer!.complete();
        return;
      }
    }
    return completer!.future;
  }
}

class Album extends ArtistAlbumBase {
  Album(super.name, {super.id, super.coverArtId, this.year});

  Map<String, List<MyAudioMetadata>> artist2SongList = {};
  int? year;

  int _sort(MyAudioMetadata a, MyAudioMetadata b) {
    final discA = a.disc ?? 9999;
    final discB = b.disc ?? 9999;

    final discCompare = discA.compareTo(discB);
    if (discCompare != 0) return discCompare;

    final trackA = a.track ?? 9999;
    final trackB = b.track ?? 9999;

    return trackA.compareTo(trackB);
  }

  void sort() {
    songList.sort((a, b) => _sort(a, b));
    for (final song in songList) {
      for (String artistName in getArtists(getArtist(song))) {
        final tmp = artist2SongList.putIfAbsent(artistName, () => []);
        tmp.add(song);
      }
    }
  }

  @override
  Future<void> load() async {
    if (completer == null) {
      // ensure load one time
      completer = Completer<void>();
      songList.addAll(await streamClient?.getAlbumSongs(id!) ?? []);
      completer!.complete();
      return;
    }
    return completer!.future;
  }
}
