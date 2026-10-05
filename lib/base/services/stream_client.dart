import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

const String playQueueForStreamName = '_soiboi_play_queue_';

StreamClient? streamClient;

abstract class StreamClient {
  final String baseUrl;
  final String username;
  final String password;

  String? playQueuePlaylistId;

  @protected
  late final Dio dio;

  StreamClient({
    required this.baseUrl,
    required this.username,
    required this.password,
  });

  @protected
  List<Map<String, dynamic>>? normalize(dynamic data) {
    if (data == null) {
      return null;
    }
    return List<Map<String, dynamic>>.from(data);
  }

  Future<bool> ping();

  Future<List<MyAudioMetadata>?> searchSongs(
    String query,
    int size,
    int offset,
  );

  Future<List<MyAudioMetadata>?> getSongs(int size, int offset);

  Future<List<Artist>?> getArtistList();

  Future<List<Album>?> getArtistAlbumList(String id);

  Future<List<MyAudioMetadata>?> getArtistSongs(String id);

  Future<List<Album>?> getAlbumList(int offset, {String type});

  Future<Album?> getAlbum(String id);

  Future<List<MyAudioMetadata>?> getAlbumSongs(String id);

  // use playlist to save playqueue(no limit)
  Future<List<MyAudioMetadata>?> getPlayQueue() async {
    final ids = <String>[];
    for (Playlist pl in await getPlaylists() ?? []) {
      if (pl.name == playQueueForStreamName) {
        // if mutiple instance, chose the latest one
        playQueuePlaylistId = pl.id;
        ids.add(pl.id!);
      }
    }
    if (ids.isNotEmpty) {
      ids.removeLast();
    }
    for (final id in ids) {
      await deletePlaylist(id);
    }

    if (playQueuePlaylistId == null) {
      return null;
    }

    return getPlaylistSongs(playQueuePlaylistId!);
  }

  Timer? _savePlayQueueTimer;
  bool _saving = false;
  Future<bool> savePlayQueue(List<String> songIds) async {
    if (_saving) {
      return false;
    }
    _savePlayQueueTimer?.cancel();
    _savePlayQueueTimer = Timer(Duration(seconds: 5), () async {
      _saving = true;

      if (playQueuePlaylistId != null) {
        await deletePlaylist(playQueuePlaylistId!);
        playQueuePlaylistId = null;
      }

      playQueuePlaylistId ??= await createPlaylist(playQueueForStreamName);
      if (playQueuePlaylistId == null) {
        _saving = false;
        return;
      }
      await updatePlaylistSongs(playQueuePlaylistId!, songIds);
      _saving = false;
    });

    return true;
  }

  Future<List<MyAudioMetadata>?> getStarredSongs();

  Future<bool> updateStarredSongs(List<String> songIds);

  Future<List<Playlist>?> getPlaylists();

  Future<String?> createPlaylist(String name);

  Future<bool> deletePlaylist(String playlistId);

  Future<List<MyAudioMetadata>?> getPlaylistSongs(String playlistId);

  Future<bool> updatePlaylistSongs(String playlistId, List<String> songIds);

  String getStreamUrl(String id);

  Future<Uint8List?> getPictureBytes(String songId);

  Future<String> getLyricsById(String songId);

  Future<bool> downloadSong(String songId, String savePath);

  Future<bool> scrobble(String songId);
}
