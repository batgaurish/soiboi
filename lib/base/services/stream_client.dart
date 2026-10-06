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

  /// Ping the remote streaming server to check connectivity and credentials.
  /// Returns `true` if server responded with an OK status, `false` otherwise.
  Future<bool> ping();

  /// Searches for songs matching [query].
  ///
  /// Paging uses positional [size] (maximum songs) and [offset] (starting index).
  /// Returns `null` on network or protocol error, or an empty list if no results match.
  Future<List<MyAudioMetadata>?> searchSongs(
    String query,
    int size,
    int offset,
  );

  /// Retrieves a paginated list of songs using positional [size] and [offset].
  ///
  /// Returns `null` on error, or an empty list if no songs exist in the range.
  Future<List<MyAudioMetadata>?> getSongs(int size, int offset);

  /// Retrieves all artists. Returns `null` on error.
  Future<List<Artist>?> getArtistList();

  /// Retrieves albums for the artist specified by [id].
  ///
  /// Returns `null` if the request fails or if the backend does not support
  /// an artist-to-album query (e.g. [EmbyClient], where artist songs are queried directly).
  Future<List<Album>?> getArtistAlbumList(String id);

  /// Retrieves all songs belonging to the artist specified by [id].
  ///
  /// Returns `null` on error.
  Future<List<MyAudioMetadata>?> getArtistSongs(String id);

  /// Retrieves albums starting at [offset] with an optional sort [type].
  ///
  /// Page size is determined by backend limit defaults (500 items).
  /// Returns `null` on error.
  Future<List<Album>?> getAlbumList(int offset, {String type});

  /// Retrieves album details for [id]. Returns `null` on error or if unsupported.
  Future<Album?> getAlbum(String id);

  /// Retrieves songs within the album specified by [id]. Returns `null` on error.
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
