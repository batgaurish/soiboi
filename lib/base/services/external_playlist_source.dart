/// Where a playlist can come from.
///
/// Discovery started as one hard-coded platform: `home_layer.dart` and
/// `downloads_layer.dart` both assumed ListenBrainz, and the resolution cache
/// was keyed by a bare MusicBrainz id. Adding a second platform to that shape
/// would have meant a second copy of the Apple-resolution engine.
///
/// So the engine stays exactly where it is and only the *supply* is abstracted.
/// A source answers two questions — what playlists do you have, and what is in
/// one — and everything downstream (Apple resolution, caching, the sheet, the
/// download queue) is shared.
///
/// Two shapes of source exist and the interface admits both honestly:
/// ListenBrainz *offers* playlists it built for you, while YouTube Music has
/// nothing to offer until you hand it a link. A source that cannot browse
/// returns an empty list from [playlists] and recognises URLs instead.
library;

/// A playlist as the source describes it, before anything is resolved.
class ExternalPlaylist {
  const ExternalPlaylist({
    required this.sourceId,
    required this.id,
    required this.title,
    this.lastModified,
    this.trackCount,
  });

  final String sourceId;
  final String id;
  final String title;
  final String? lastModified;

  /// Known up front by some sources, and only after fetching by others.
  final int? trackCount;

  /// The cache key for this playlist across every source.
  ///
  /// Two platforms can and do use the same id space (a bare hex string is a
  /// MusicBrainz id and a plausible YouTube id), so the source has to be part
  /// of the key or one platform's cache would answer for another's.
  String get key => '$sourceId:$id';
}

/// One track, as the source knows it — a name and an artist, nothing more.
///
/// Deliberately not a `DiscoveryTrack`: that type carries Apple artwork,
/// preview and catalog URL, which is the *output* of resolution. Keeping the
/// input this thin is what lets a new source be a few dozen lines.
class ExternalTrack {
  const ExternalTrack({required this.title, required this.artist});

  final String title;
  final String artist;
}

abstract class ExternalPlaylistSource {
  /// Stable, storable identifier — `listenbrainz`, `youtube`.
  String get id;

  String get displayName;

  /// Playlists this source offers unprompted. Empty is a valid answer: it
  /// means this source can only be given a specific link.
  Future<List<ExternalPlaylist>> playlists();

  /// The tracks of one playlist.
  Future<List<ExternalTrack>> tracks(String playlistId);

  /// Whether this source can be handed a link.
  ///
  /// Stated rather than inferred from [playlistIdFromUrl] returning null for
  /// some sample URL — that would only tell you the sample did not match.
  bool get acceptsLinks => false;

  /// The playlist id inside [url], if this source recognises it.
  ///
  /// Null by default, so a source that only browses does not have to think
  /// about links at all.
  String? playlistIdFromUrl(String url) => null;

  /// A human title for a playlist known only by id — used after an import by
  /// link, where there was no listing to take a title from.
  Future<String?> titleFor(String playlistId) async => null;
}

/// Every source the app knows about.
///
/// Registered rather than hard-coded at each call site, so a screen iterates
/// sources instead of naming them and a third platform is one `add` away.
final List<ExternalPlaylistSource> playlistSources = [];

void registerPlaylistSource(ExternalPlaylistSource source) {
  playlistSources.removeWhere((existing) => existing.id == source.id);
  playlistSources.add(source);
}

ExternalPlaylistSource? sourceById(String id) =>
    playlistSources.where((source) => source.id == id).firstOrNull;

/// The source that recognises [url], with the playlist id it found.
///
/// Asking the sources rather than pattern-matching here keeps every platform's
/// URL knowledge inside that platform's own file.
({ExternalPlaylistSource source, String playlistId})? sourceForUrl(String url) {
  for (final source in playlistSources) {
    final id = source.playlistIdFromUrl(url);
    if (id != null) return (source: source, playlistId: id);
  }
  return null;
}
