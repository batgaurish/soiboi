/// One search across everything the app knows about.
///
/// Search here has always been per-screen: Songs filters songs, Albums filters
/// albums, and neither knows the other exists. That is fine when you already
/// know where a thing lives and useless when you don't — which is most of the
/// time, and the whole reason to have a search box at all.
///
/// This is additive, not a replacement. The per-screen filters stay: narrowing
/// a list you are already looking at is a different moment from asking "where
/// is this".
///
/// Nothing here reimplements matching. Songs go through the same
/// [filterSongList] the Songs screen uses, and names go through
/// `library_match_service`'s [normaliseForMatch], so a search agrees with the
/// screens it is searching and with the catalog matcher Phase 4 extracted.
library;

import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/mood_playlists.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/data/smart_playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/library_match_service.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';

/// Where a playlist hit came from, since three different kinds of playlist
/// share one section and behave differently when opened.
enum PlaylistKind { saved, smart, mood }

class PlaylistHit {
  const PlaylistHit({
    required this.kind,
    required this.name,
    required this.trackCount,
    this.saved,
    this.smart,
    this.mood,
  });

  final PlaylistKind kind;
  final String name;
  final int trackCount;

  final Playlist? saved;
  final SmartPlaylist? smart;
  final MoodCardData? mood;
}

class GlobalSearchResults {
  const GlobalSearchResults({
    required this.query,
    required this.songs,
    required this.albums,
    required this.artists,
    required this.playlists,
  });

  final String query;
  final List<MyAudioMetadata> songs;
  final List<Album> albums;
  final List<Artist> artists;
  final List<PlaylistHit> playlists;

  bool get isEmpty =>
      songs.isEmpty && albums.isEmpty && artists.isEmpty && playlists.isEmpty;

  int get total =>
      songs.length + albums.length + artists.length + playlists.length;
}

/// A name match that ignores case, punctuation and "&" vs "and".
///
/// The same normalisation the catalog matcher uses, so "Sigur Ros" finds
/// "Sigur Rós" and searching agrees with what the app calls "already owned".
bool matchesName(String name, String query) {
  final target = normaliseForMatch(query);
  if (target.isEmpty) return false;
  return normaliseForMatch(name).contains(target);
}

/// How many results each section shows before it is cut off.
///
/// A search for "a" matches most of a library, and a screen listing four
/// thousand songs under a heading is not a result, it is a wall.
const _sectionLimit = 12;

GlobalSearchResults globalSearch(
  String rawQuery, {
  int limit = _sectionLimit,

  // Injected so the tests can search a fixture rather than the app's global
  // library, which is not constructible in a unit test.
  List<MyAudioMetadata>? songs,
  List<Album>? albums,
  List<Artist>? artists,
  List<Playlist>? savedPlaylists,
  List<SmartPlaylist>? smartPlaylistList,
  List<MoodCardData>? moodPlaylistList,
}) {
  final query = rawQuery.trim();
  if (query.isEmpty) {
    return GlobalSearchResults(
      query: query,
      songs: const [],
      albums: const [],
      artists: const [],
      playlists: const [],
    );
  }

  final songSource = songs ?? library.songList;
  final albumSource = albums ?? artistAlbumManager.albumList;
  final artistSource = artists ?? artistAlbumManager.artistList;

  final playlistHits = <PlaylistHit>[
    for (final playlist in savedPlaylists ?? playlistManager.playlists)
      if (matchesName(playlist.name, query))
        PlaylistHit(
          kind: PlaylistKind.saved,
          name: playlist.name,
          trackCount: playlist.songList.length,
          saved: playlist,
        ),
    for (final playlist in smartPlaylistList ?? smartPlaylists.playlists)
      if (matchesName(playlist.name, query))
        PlaylistHit(
          kind: PlaylistKind.smart,
          name: playlist.name,
          trackCount: playlist.evaluate(songSource).length,
          smart: playlist,
        ),
    // Mood playlists are ephemeral — regenerated per time of day — but they
    // are named things a user sees on Home, so not finding "Deep Focus" by
    // searching for it would be a hole.
    for (final mood in moodPlaylistList ?? autoMoodPlaylists(songs: songSource))
      if (matchesName(mood.name, query))
        PlaylistHit(
          kind: PlaylistKind.mood,
          name: mood.name,
          trackCount: mood.trackCount,
          mood: mood,
        ),
  ];

  return GlobalSearchResults(
    query: query,
    // The Songs screen's own filter, not a second opinion about what a song
    // match is: two different answers to that question would be a bug report
    // waiting to happen.
    songs: filterSongList(songSource, query).take(limit).toList(),
    albums: [
      for (final album in albumSource)
        if (matchesName(album.name, query)) album,
    ].take(limit).toList(),
    artists: [
      for (final artist in artistSource)
        if (matchesName(artist.name, query)) artist,
    ].take(limit).toList(),
    playlists: playlistHits.take(limit).toList(),
  );
}

/// What a search for an artist you only partly own should say.
///
/// Phase 4 established that a partly-owned artist is the interesting case —
/// the one where the archive pipeline has something to do — so a search hit
/// carries the same fact the catalog sheets show rather than a bare name.
String artistSubtitle(Artist artist) {
  final albums = artist.albumList.length;
  final tracks = artist.songList.length;
  return '$albums ${albums == 1 ? "album" : "albums"} · '
      '$tracks ${tracks == 1 ? "track" : "tracks"}';
}
