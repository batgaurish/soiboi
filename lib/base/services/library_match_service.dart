/// Matching external catalog/ranking data (ListenBrainz entries, Apple
/// catalog tracks) against what is already in the local library.
///
/// Pulled out of `listenbrainz_service.dart`, which originally had its own
/// private artist/album matchers, because more than one feature now needs
/// "is this already local, and if not, is it archivable" as a first-class
/// query: the catalog browse sheets need it per-track (not just per-album),
/// and future features (search, auto-generated playlists surfacing catalog
/// gaps) need the same answer. One implementation, not three.
library;

import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

/// Case- and punctuation-insensitive comparison, because tags and catalog
/// metadata disagree constantly about "&" vs "and", accents, and trailing
/// articles.
String normaliseForMatch(String value) => value
    .toLowerCase()
    .replaceAll('&', 'and')
    .replaceAll(RegExp(r'[^a-z0-9]+'), '');

Artist? matchArtist(String name) {
  final target = normaliseForMatch(name);
  for (final artist in artistAlbumManager.artistList) {
    if (normaliseForMatch(artist.name) == target) return artist;
  }
  return null;
}

Album? matchAlbum(String name, {String? artist}) {
  final target = normaliseForMatch(name);
  final artistTarget = artist == null ? null : normaliseForMatch(artist);
  for (final album in artistAlbumManager.albumList) {
    if (normaliseForMatch(album.name) != target) continue;
    // When an artist is given, a bare name match is not enough: album
    // titles collide across artists constantly ("Greatest Hits"), so only
    // accept the album if that artist actually has tracks on it.
    if (artistTarget != null &&
        !album.artist2SongList.keys.any(
          (a) => normaliseForMatch(a) == artistTarget,
        )) {
      continue;
    }
    return album;
  }
  return null;
}

/// A local song matching [title], preferring one already known to be in
/// [album] (when given) and falling back to a flat scan by artist + title.
///
/// The album-scoped lookup is what makes this useful for cross-referencing a
/// catalog tracklist: two different artists can each have a song called
/// "Intro", so matching within the already-matched local album first avoids
/// crediting the wrong artist's track as owned.
MyAudioMetadata? matchSong(String title, {Album? album, String? artist}) {
  final target = normaliseForMatch(title);

  if (album != null) {
    for (final song in album.songList) {
      if (normaliseForMatch(song.title ?? '') == target) return song;
    }
    return null;
  }

  if (artist == null) return null;
  final localArtist = matchArtist(artist);
  if (localArtist == null) return null;
  for (final song in localArtist.songList) {
    if (normaliseForMatch(song.title ?? '') == target) return song;
  }
  return null;
}
