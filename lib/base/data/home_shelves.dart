/// Derives the Home screen's shelves from the library.
///
/// Kept out of the widget so the selection rules can be read (and tested)
/// without wading through layout code.
///
/// A note on "favourites": upstream only has a favourite *flag on songs*, via a
/// built-in playlist named Favorite. There is no favourite-artist or
/// favourite-album anywhere in the data model. Rather than invent a flag the
/// app has no UI to set, top artists and albums are derived from play counts —
/// which is what the big streaming apps actually show under those headings, and
/// is honest about where the ranking comes from.
library;

import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

/// How many items a shelf shows. Enough to be worth scrolling, few enough that
/// building them all on a large library stays cheap.
const shelfLimit = 12;

/// Tracks queued after the current one — "what's coming up".
List<MyAudioMetadata> playNextSongs() {
  final index = audioHandler.currentIndex;
  if (index < 0) return const [];
  final start = index + 1;
  if (start >= playQueue.length) return const [];
  return playQueue.sublist(
    start,
    (start + shelfLimit).clamp(start, playQueue.length),
  );
}

/// Newest files in the library, by filesystem modification time.
///
/// This is "recently added" in the only sense a local library can know: when
/// the file arrived. For a Syncthing-mirrored archive that is exactly right —
/// it surfaces whatever the pipeline downloaded most recently.
List<MyAudioMetadata> recentlyAddedSongs() {
  final songs = library.songList.where((s) => s.modified != null).toList();
  songs.sort((a, b) => b.modified!.compareTo(a.modified!));
  return songs.take(shelfLimit).toList();
}

/// Albums whose newest track arrived most recently.
List<Album> recentlyAddedAlbums() {
  final albums = artistAlbumManager.albumList.where((album) {
    return album.songList.any((s) => s.modified != null);
  }).toList();

  DateTime newest(Album album) {
    DateTime? best;
    for (final song in album.songList) {
      final m = song.modified;
      if (m != null && (best == null || m.isAfter(best))) best = m;
    }
    return best ?? DateTime.fromMillisecondsSinceEpoch(0);
  }

  albums.sort((a, b) => newest(b).compareTo(newest(a)));
  return albums.take(shelfLimit).toList();
}

int _totalPlays(List<MyAudioMetadata> songs) =>
    songs.fold(0, (sum, s) => sum + s.playCount);

/// Artists ranked by total plays across their tracks.
List<Artist> topArtists() {
  final artists = artistAlbumManager.artistList
      .where((a) => _totalPlays(a.songList) > 0)
      .toList();
  artists.sort((a, b) => _totalPlays(b.songList).compareTo(_totalPlays(a.songList)));
  return artists.take(shelfLimit).toList();
}

/// Albums ranked by total plays across their tracks.
List<Album> topAlbums() {
  final albums = artistAlbumManager.albumList
      .where((a) => _totalPlays(a.songList) > 0)
      .toList();
  albums.sort((a, b) => _totalPlays(b.songList).compareTo(_totalPlays(a.songList)));
  return albums.take(shelfLimit).toList();
}

/// The built-in Favorite playlist, if it has anything in it.
List<MyAudioMetadata> favouriteSongs() {
  final playlist = playlistManager.getPlaylistByName('Favorite');
  final songs = playlist?.songList ?? const <MyAudioMetadata>[];
  return songs.take(shelfLimit).toList();
}
