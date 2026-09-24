/// Local playlists that mirror an imported playlist's tracklist.
///
/// Importing a playlist downloads its songs, but the songs used to land in the
/// library loose, so the playlist itself was lost. A linked playlist keeps the
/// source tracklist (artist and title per track) and fills a normal local
/// playlist from whatever of it is in the library. Songs still downloading are
/// added when they arrive, because [refreshAll] runs after every library sync.
///
/// The same path handles a playlist whose songs are already downloaded:
/// linking it without queueing anything fills it straight away.
library;

import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/library_match_service.dart';
import 'package:soiboi/base/services/logger.dart';

class LinkedTrack {
  const LinkedTrack(this.artist, this.title);

  final String artist;
  final String title;

  Map<String, String> toJson() => {'artist': artist, 'title': title};

  factory LinkedTrack.fromJson(Map<String, dynamic> json) => LinkedTrack(
    json['artist'] as String? ?? '',
    json['title'] as String? ?? '',
  );
}

class LinkResult {
  const LinkResult(this.playlistName, this.matched, this.total);

  final String playlistName;
  final int matched;
  final int total;
}

final linkedPlaylists = LinkedPlaylists();

class LinkedPlaylists {
  final Map<String, List<LinkedTrack>> _links = {};
  bool _loaded = false;

  File get _file => File(p.join(appSupportDir.path, 'linked_playlists.json'));

  void _load() {
    if (_loaded) return;
    _loaded = true;
    try {
      if (!_file.existsSync()) return;
      final raw = jsonDecode(_file.readAsStringSync()) as Map<String, dynamic>;
      for (final entry in raw.entries) {
        _links[entry.key] = [
          for (final track in (entry.value as List).whereType<Map>())
            LinkedTrack.fromJson(Map<String, dynamic>.from(track)),
        ];
      }
    } catch (e) {
      logger.output('linked playlists: read failed: $e');
    }
  }

  void _save() {
    try {
      _file.writeAsStringSync(
        jsonEncode({
          for (final entry in _links.entries)
            entry.key: entry.value.map((t) => t.toJson()).toList(),
        }),
      );
    } catch (e) {
      logger.output('linked playlists: write failed: $e');
    }
  }

  /// Creates (or reuses) the local playlist [name] for [tracks] and fills it
  /// from the library now. Songs not in the library yet are added by later
  /// [refreshAll] calls.
  ///
  /// [artworkUrl], the playlist's cover on its platform, becomes the local
  /// playlist's cover unless the user has already set one.
  Future<LinkResult?> link(
    String name,
    List<LinkedTrack> tracks, {
    String? artworkUrl,
  }) async {
    if (isStreamSource || tracks.isEmpty) return null;
    _load();
    final playlistName = _safeName(name);
    _links[playlistName] = tracks;
    _save();

    var playlist = playlistManager.getPlaylistByName(playlistName);
    if (playlist == null) {
      playlist = Playlist(name: playlistName);
      playlistManager.addPlaylist(playlist);
      playlistManager.update();
    }
    final matched = await _fill(playlist, tracks, _Index());
    if (artworkUrl != null && playlist.customCover == null) {
      await _importCover(playlist, artworkUrl);
    }
    return LinkResult(playlistName, matched, tracks.length);
  }

  /// Adds newly downloaded songs to every linked playlist. Run after a
  /// library sync; cheap when nothing changed.
  Future<void> refreshAll() async {
    if (isStreamSource) return;
    _load();
    if (_links.isEmpty) return;
    final index = _Index();
    for (final entry in _links.entries) {
      final playlist = playlistManager.getPlaylistByName(entry.key);
      // Deleted by the user: respect that rather than bring it back.
      if (playlist == null) continue;
      await _fill(playlist, entry.value, index);
    }
  }

  /// Puts the matched songs in source order, followed by anything the user
  /// added by hand. Writes only when the list actually changes.
  Future<int> _fill(
    Playlist playlist,
    List<LinkedTrack> tracks,
    _Index index,
  ) async {
    final ordered = <MyAudioMetadata>[];
    for (final track in tracks) {
      final song = index.find(track);
      if (song != null && !ordered.contains(song)) ordered.add(song);
    }
    final extras = playlist.songList.where((s) => !ordered.contains(s));
    final next = [...ordered, ...extras];

    final unchanged =
        next.length == playlist.songList.length &&
        Iterable.generate(next.length).every(
          (i) => identical(next[i], playlist.songList[i]),
        );
    if (!unchanged && playlist.canModify) {
      playlist.songList
        ..clear()
        ..addAll(next);
      await playlist.update();
    }
    return ordered.length;
  }

  Future<void> _importCover(Playlist playlist, String url) async {
    final temp = File(p.join(appSupportDir.path, 'playlist-cover-download'));
    try {
      final response = await http
          .get(Uri.parse(url))
          .timeout(const Duration(seconds: 20));
      if (response.statusCode != 200 || response.bodyBytes.isEmpty) return;
      await temp.writeAsBytes(response.bodyBytes);
      await playlist.setCover(temp.path);
    } catch (e) {
      // A missing cover is cosmetic; the playlist itself is already saved.
      logger.output('linked playlists: cover download failed: $e');
    } finally {
      if (temp.existsSync()) temp.deleteSync();
    }
  }

  /// Playlist names double as file names, so a slash would point elsewhere.
  static String _safeName(String name) {
    final cleaned = name.replaceAll(RegExp(r'[/\\]'), '-').trim();
    if (cleaned.isEmpty) return 'Imported playlist';
    // Never the built-in favourites list, which this would overwrite.
    return cleaned == 'Favorite' ? 'Favorite (imported)' : cleaned;
  }
}

/// Library lookups by artist and title, built once per refresh.
class _Index {
  _Index() {
    for (final song in library.songList) {
      final title = song.title ?? '';
      if (title.isEmpty) continue;
      final artist = song.artist ?? '';
      _exact.putIfAbsent(ownedSongKey(artist, title), () => song);
      _loose.putIfAbsent(_looseKey(artist, title), () => song);
    }
  }

  final _exact = <String, MyAudioMetadata>{};
  final _loose = <String, MyAudioMetadata>{};

  MyAudioMetadata? find(LinkedTrack track) =>
      _exact[ownedSongKey(track.artist, track.title)] ??
      _loose[_looseKey(track.artist, track.title)];

  /// Main artist and bare title. Sources disagree on "(feat. X)", "[From
  /// 'Film']" and "- Remastered" suffixes, and on how co-artists are listed.
  static String _looseKey(String artist, String title) {
    final mainArtist = artist
        .split(RegExp(r',|&|\bfeat\.?|\bft\.?|\bx\b', caseSensitive: false))
        .first;
    final bareTitle = title
        .replaceAll(RegExp(r'\s*[\(\[][^\)\]]*[\)\]]'), '')
        .replaceAll(RegExp(r'\s+-\s+.*$'), '');
    return ownedSongKey(mainArtist, bareTitle);
  }
}
