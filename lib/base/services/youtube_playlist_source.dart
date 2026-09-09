/// YouTube Music playlists, read through the yt-dlp already in the app.
///
/// Chosen over Spotify after spiking both (see `pipeline/playlist.py` for the
/// full reasoning): Spotify needs a bearer token even for a public playlist,
/// which means shipping a client secret and contradicting the "nothing
/// configured, no accounts" shape the rest of discovery has. YouTube Music
/// needs neither, and yt-dlp is already bundled and already working on Android
/// because gamdl depends on it.
///
/// The hard part is not fetching. It is that YouTube entries are *videos*, not
/// tracks: the artist and title arrive fused into one human-written string
/// with promotional noise attached. [parseYouTubeTrack] is where that is
/// unpicked, and it is deliberately a pure function — it is the part most
/// likely to be wrong, and the only part worth testing exhaustively.
library;

import 'package:soiboi/base/services/external_playlist_source.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';

/// Trailing decoration that is never part of a song's name.
///
/// Matched anywhere, repeatedly, because uploads stack them:
/// "… (Official Video) [HD] (Lyrics)".
final _noise = RegExp(
  r'[\(\[]\s*(?:'
  r'official\s*(?:music\s*)?(?:video|audio|visualizer|lyric[s]?\s*video)?|'
  r'lyric[s]?(?:\s*video)?|audio|visuali[sz]er|'
  r'hd|hq|4k|full\s*hd|remaster(?:ed)?(?:\s*\d{4})?|'
  r'explicit|clean|radio\s*edit|music\s*video|'
  r'with\s*lyrics|letra|legendado'
  r')\s*[\)\]]',
  caseSensitive: false,
);

/// A featured-artist suffix outside brackets: "Instant Crush ft. Julian …".
///
/// Dropped rather than folded into the artist: Apple's catalog lists the track
/// under the primary artist, and searching for "Daft Punk ft. Julian
/// Casablancas" reliably matches nothing.
final _featuring = RegExp(
  r'\s+(?:ft\.?|feat\.?|featuring)\s+.*$',
  caseSensitive: false,
);

/// "Various Artists - Topic", the auto-generated channels YouTube Music uses
/// for real catalog uploads. The suffix is machinery, not part of the name.
final _topicSuffix = RegExp(r'\s*-\s*Topic\s*$', caseSensitive: false);

/// Separators that split "Artist - Title" in an upload's name.
///
/// Hyphen, en dash and em dash all appear in the wild; the spaces around them
/// are required so a hyphenated title ("Sun-El") is not torn in half.
final _separator = RegExp(r'\s+[-–—]\s+');

String _clean(String value) => value
    .replaceAll(_noise, ' ')
    .replaceAll(RegExp(r'\s{2,}'), ' ')
    // Left behind when the trailing bracket group was the whole tail.
    .replaceAll(RegExp(r'[\s\-–—|]+$'), '')
    .replaceAll(RegExp(r'^[\s\-–—|]+'), '')
    .trim();

/// Turns a YouTube entry into an artist and a title.
///
/// Three shapes, in the order they are worth trusting:
///
/// 1. A "- Topic" channel is YouTube Music's own catalog upload: the uploader
///    is exactly the artist and the title is exactly the track. Nothing to
///    guess.
/// 2. "Artist - Title" in the video name — by far the most common, and the
///    uploader is often a label or a compilation channel, so the name wins.
/// 3. Neither: the title is the track and the uploader is the best guess at
///    an artist, which is right for an artist's own channel and wrong for a
///    compilation. Wrong here costs a failed Apple match, which the sheet
///    already shows as an unmatched row.
ExternalTrack parseYouTubeTrack(String rawTitle, String rawUploader, {String? isrc}) {
  final uploader = rawUploader.trim();
  final topic = _topicSuffix.hasMatch(uploader);
  final artistFromUploader = _clean(uploader.replaceAll(_topicSuffix, ''));

  if (topic) {
    return ExternalTrack(
      title: _clean(rawTitle).replaceAll(_featuring, '').trim(),
      artist: artistFromUploader,
      isrc: isrc,
    );
  }

  final cleaned = _clean(rawTitle);
  final match = _separator.firstMatch(cleaned);
  if (match != null) {
    final artist = cleaned.substring(0, match.start).trim();
    final title = cleaned.substring(match.end).trim();
    // Only when both halves survive: "- Anthem" or "Artist -" would otherwise
    // produce an empty field that matches everything or nothing.
    if (artist.isNotEmpty && title.isNotEmpty) {
      return ExternalTrack(
        title: title.replaceAll(_featuring, '').trim(),
        artist: artist.replaceAll(_featuring, '').trim(),
        isrc: isrc,
      );
    }
  }

  return ExternalTrack(
    title: cleaned.replaceAll(_featuring, '').trim(),
    artist: artistFromUploader,
    isrc: isrc,
  );
}

/// The `list=` id in a YouTube or YouTube Music URL, if there is one.
///
/// Accepts a bare id too, so pasting the id alone works — people copy those
/// out of a URL bar as often as they copy the whole link.
String? youTubePlaylistId(String input) {
  final trimmed = input.trim();
  if (trimmed.isEmpty) return null;

  if (RegExp(r'^(?:PL|OLAK5uy_|RD|UU|LL|FL)[A-Za-z0-9_-]{10,}$')
      .hasMatch(trimmed)) {
    return trimmed;
  }

  final uri = Uri.tryParse(trimmed);
  if (uri == null) return null;
  final host = uri.host.toLowerCase();
  if (!host.endsWith('youtube.com') && !host.endsWith('youtu.be')) return null;
  final id = uri.queryParameters['list'];
  return (id != null && id.isNotEmpty) ? id : null;
}

class YouTubePlaylistSource extends ExternalPlaylistSource {
  @override
  String get id => 'youtube';

  @override
  String get displayName => 'YouTube Music';

  /// Nothing to offer unprompted.
  ///
  /// There is no "playlists made for you" to read without an account, and
  /// pretending otherwise would mean either scraping a logged-out home page or
  /// asking for credentials. An import by link is the honest whole feature.
  @override
  Future<List<ExternalPlaylist>> playlists() async => const [];

  @override
  bool get acceptsLinks => true;

  @override
  String? playlistIdFromUrl(String url) => youTubePlaylistId(url);

  @override
  Future<String?> titleFor(String playlistId) async {
    final result = await _fetch(playlistId, limit: 1);
    return result?['title'] as String?;
  }

  @override
  Future<List<ExternalTrack>> tracks(String playlistId) async {
    final result = await _fetch(playlistId);
    final entries = result?['entries'] as List?;
    if (entries == null) return const [];
    return [
      for (final entry in entries.whereType<Map>())
        parseYouTubeTrack(
          entry['title'] as String? ?? '',
          entry['uploader'] as String? ?? '',
          isrc: entry['isrc'] as String?,
        ),
    ].where((track) => track.title.isNotEmpty).toList();
  }

  /// The last failure, so the sheet can say what went wrong rather than
  /// showing an empty playlist and letting the user guess.
  String? lastError;

  Future<Map<String, dynamic>?> _fetch(String playlistId, {int? limit}) async {
    lastError = null;
    try {
      await for (final event in pipelineRunner.run('playlist', {
        'url': 'https://music.youtube.com/playlist?list=$playlistId',
        'limit': ?limit,
      })) {
        if (event.isDone) return event.raw;
        if (event.isError) {
          lastError = event.message;
          return null;
        }
      }
    } catch (e) {
      lastError = '$e';
    }
    return null;
  }
}
