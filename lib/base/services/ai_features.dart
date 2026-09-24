/// The two things AI does in Soiboi: build a playlist from the library, and
/// recommend albums to archive next.
///
/// Only song tags leave the device (title, artist, album, genre, year) plus,
/// for recommendations, the names of the artists played most. No audio, no
/// file paths, nothing else.
library;

import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/ai_service.dart';
import 'package:soiboi/base/services/listenbrainz_service.dart';

/// Tracks sent per request. A line is ~15 tokens, so this stays well inside
/// the smallest free-tier context windows.
const _maxTracks = 3000;

String _line(int i, MyAudioMetadata s) {
  final parts = [
    '$i',
    s.title ?? '',
    s.artist ?? '',
    s.album ?? '',
    s.genre ?? '',
    s.year?.toString() ?? '',
  ];
  return parts.map((p) => p.replaceAll('|', '/').trim()).join(' | ');
}

class AiPlaylist {
  AiPlaylist(this.name, this.songs);
  final String name;
  final List<MyAudioMetadata> songs;
}

/// Asks for a playlist matching [request] from the local library, then saves
/// it. Returns what was saved.
Future<AiPlaylist> aiMakePlaylist(String request) async {
  final config = aiConfigNotifier.value;
  if (config == null) throw AiException('AI is not set up yet');
  final songs = library.songList.take(_maxTracks).toList();
  if (songs.isEmpty) throw AiException('Your library is empty');

  final catalogue = [
    for (var i = 0; i < songs.length; i++) _line(i, songs[i]),
  ].join('\n');

  final answer = await aiComplete(
    config,
    system:
        'You build playlists from a personal music library. You may only '
        'choose tracks from the numbered list given. Reply with JSON only, '
        'no prose, in exactly this shape: '
        '{"name": "short playlist title", "tracks": [track numbers in play '
        'order]}. Pick between 10 and 40 tracks unless the request asks for '
        'a number. Order them so the playlist flows.',
    prompt:
        'Request: $request\n\n'
        'Library (number | title | artist | album | genre | year):\n'
        '$catalogue',
  );

  final json = extractJsonObject(answer);
  final picked = <MyAudioMetadata>[];
  final seen = <int>{};
  for (final n in (json['tracks'] as List? ?? const [])) {
    final i = n is int ? n : int.tryParse('$n');
    if (i == null || i < 0 || i >= songs.length || !seen.add(i)) continue;
    picked.add(songs[i]);
  }
  if (picked.isEmpty) {
    throw AiException('No tracks in your library matched that');
  }

  final name = await _freeName(
    (json['name'] as String?)?.trim().isNotEmpty == true
        ? json['name'] as String
        : request,
  );
  await playlistManager.createPlaylist(name);
  final playlist = playlistManager.getPlaylistByName(name);
  if (playlist == null) throw AiException('Could not save the playlist');
  await playlist.add(picked);
  return AiPlaylist(name, picked);
}

/// [name], or "name (2)" and so on if a playlist already has it.
Future<String> _freeName(String name) async {
  final base = name.length > 60 ? name.substring(0, 60) : name;
  var candidate = base;
  var n = 2;
  while (playlistManager.getPlaylistByName(candidate) != null) {
    candidate = '$base ($n)';
    n++;
  }
  return candidate;
}

class AiAlbumPick {
  AiAlbumPick({required this.artist, required this.album, required this.why});
  final String artist;
  final String album;
  final String why;

  Map<String, String> toJson() => {
    'artist': artist,
    'album': album,
    'why': why,
  };
  factory AiAlbumPick.fromJson(Map j) => AiAlbumPick(
    artist: j['artist'] as String? ?? '',
    album: j['album'] as String? ?? '',
    why: j['why'] as String? ?? '',
  );
}

/// Home's AI recommendations shelf. Kept for a day so opening Home does not
/// spend the person's key every time; the shelf's refresh button forces it.
final aiRecsNotifier = ValueNotifier<List<AiAlbumPick>>(const []);
final aiRecsBusyNotifier = ValueNotifier(false);
final aiRecsErrorNotifier = ValueNotifier<String?>(null);
const _recsMaxAge = Duration(hours: 24);

File get _recsFile => File('${appSupportDir.path}/ai_recommendations.json');

/// Shows the saved shelf, then asks for a new one when it is older than a
/// day, empty, or [force]d. Does nothing without an AI key.
Future<void> loadAiRecommendations({bool force = false}) async {
  if (aiConfigNotifier.value == null || aiRecsBusyNotifier.value) return;
  DateTime? savedAt;
  try {
    final json = jsonDecode(await _recsFile.readAsString()) as Map;
    savedAt = DateTime.tryParse(json['at'] as String? ?? '');
    aiRecsNotifier.value = [
      for (final p in (json['picks'] as List? ?? const []))
        if (p is Map) AiAlbumPick.fromJson(p),
    ];
  } catch (_) {}
  final fresh = savedAt != null &&
      DateTime.now().difference(savedAt) < _recsMaxAge &&
      aiRecsNotifier.value.isNotEmpty;
  if (fresh && !force) return;

  aiRecsBusyNotifier.value = true;
  aiRecsErrorNotifier.value = null;
  try {
    final picks = await aiRecommendAlbums('');
    aiRecsNotifier.value = picks;
    await _recsFile.writeAsString(
      jsonEncode({
        'at': DateTime.now().toIso8601String(),
        'picks': [for (final p in picks) p.toJson()],
      }),
    );
  } on AiException catch (e) {
    aiRecsErrorNotifier.value = e.message;
  } catch (e) {
    aiRecsErrorNotifier.value = '$e';
  } finally {
    aiRecsBusyNotifier.value = false;
  }
}

/// Albums worth archiving next, based on the library and listening history.
/// [request] narrows it ("more like this but Hindi", "90s") and may be empty.
Future<List<AiAlbumPick>> aiRecommendAlbums(String request) async {
  final config = aiConfigNotifier.value;
  if (config == null) throw AiException('AI is not set up yet');

  // Most-owned artists locally, then what is actually played most.
  final byCount = [...artistAlbumManager.artistList]
    ..sort((a, b) => b.songList.length.compareTo(a.songList.length));
  final owned = byCount.take(40).map((a) => a.name).toList();
  final played =
      (await topArtistsFromListenBrainz(range: LbRange.year))
          ?.take(25)
          .map((e) => e.name)
          .toList() ??
      const <String>[];
  final ownedAlbums = artistAlbumManager.albumList
      .take(400)
      .map((a) => a.name)
      .join('; ');

  final answer = await aiComplete(
    config,
    system:
        'You recommend albums to someone building an offline music archive. '
        'Recommend real albums that exist on Apple Music and that they do '
        'not already own. Reply with JSON only, no prose, in exactly this '
        'shape: {"albums": [{"artist": "...", "album": "exact album title", '
        '"why": "one short sentence"}]}. Give 12 albums.',
    prompt:
        '${request.trim().isEmpty ? 'Recommend what they would love next.' : 'Request: $request'}\n\n'
        'Artists they own most: ${owned.join(', ')}\n'
        '${played.isEmpty ? '' : 'Artists they play most: ${played.join(', ')}\n'}'
        'Albums already owned (do not repeat): $ownedAlbums',
  );

  final json = extractJsonObject(answer);
  final picks = <AiAlbumPick>[];
  for (final a in (json['albums'] as List? ?? const [])) {
    if (a is! Map) continue;
    final artist = (a['artist'] as String? ?? '').trim();
    final album = (a['album'] as String? ?? '').trim();
    if (artist.isEmpty || album.isEmpty) continue;
    picks.add(
      AiAlbumPick(
        artist: artist,
        album: album,
        why: (a['why'] as String? ?? '').trim(),
      ),
    );
  }
  if (picks.isEmpty) throw AiException('No recommendations came back');
  return picks;
}
