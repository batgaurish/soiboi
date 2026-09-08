/// Search, from anywhere.
///
/// The plan called for a new shell above [layersManager] to hang a persistent
/// search entry point on. There already is one: the sidebar is the drawer on a
/// narrow layout and a permanent rail on a wide one, and it is present on every
/// screen. So search is a root layer like Songs or Downloads — reachable from
/// every tab, with no new navigation concept and no shell wrapped around the
/// existing one.
///
/// Modelled on [DownloadsLayer] rather than the settings detail layers: those
/// are `part` pairs (a portrait page and a landscape panel), and this screen is
/// one scrolling list that reads the same either way.
///
/// Results reuse the existing row widgets' *behaviour* — a song plays through
/// the ordinary play queue, an album or artist opens its existing detail layer
/// — rather than teaching this screen how to be a second music library.
library;

import 'package:material_ui/material_ui.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/smart_playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/global_search_service.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';
import 'package:soiboi/base/widgets/song_list.dart';
import 'package:soiboi/layer/catalog_sheet.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/portrait_view/custom_appbar_leading.dart';

class GlobalSearchLayer extends StatefulWidget {
  const GlobalSearchLayer({super.key});

  @override
  State<GlobalSearchLayer> createState() => _GlobalSearchLayerState();
}

class _GlobalSearchLayerState extends State<GlobalSearchLayer> {
  final _controller = TextEditingController();
  final _focus = FocusNode();
  GlobalSearchResults? _results;

  @override
  void initState() {
    super.initState();
    _controller.addListener(_search);
    // Opening search and having to tap the box first is a wasted step: this
    // screen has exactly one thing to do.
    WidgetsBinding.instance.addPostFrameCallback((_) => _focus.requestFocus());
  }

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  /// Searched synchronously, without a debounce.
  ///
  /// Unlike the per-screen filters this reads only in-memory lists — no
  /// network, no database — so a keystroke costs a few list scans over a
  /// library that already lives in RAM. A debounce here would add latency to
  /// hide a cost that is not there.
  void _search() {
    setState(() {
      _results = _controller.text.trim().isEmpty
          ? null
          : globalSearch(_controller.text);
    });
  }

  @override
  Widget build(BuildContext context) {
    final body = Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 14, 20, 4),
          child: TextField(
            controller: _controller,
            focusNode: _focus,
            style: TextStyle(fontSize: 15, color: textColor.value),
            decoration: InputDecoration(
              hintText: 'Songs, albums, artists, playlists',
              isDense: true,
              filled: true,
              fillColor: searchFieldColor.value,
              prefixIcon: const Icon(Icons.search_rounded, size: 20),
              suffixIcon: _controller.text.isEmpty
                  ? null
                  : IconButton(
                      icon: const Icon(Icons.close_rounded, size: 18),
                      onPressed: _controller.clear,
                    ),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(
                  10 * activeFlavour.cornerScale,
                ),
                borderSide: BorderSide.none,
              ),
            ),
          ),
        ),
        Expanded(child: _body()),
      ],
    );

    if (!isTooNarrow(context)) return body;
    return Scaffold(
      backgroundColor: Colors.transparent,
      resizeToAvoidBottomInset: false,
      appBar: AppBar(
        automaticallyImplyLeading: false,
        leading: customAppBarLeading(context),
        backgroundColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        title: const Text('Search'),
        centerTitle: true,
      ),
      body: body,
    );
  }

  Widget _body() {
    final results = _results;
    if (results == null) {
      return _hint(
        'Search your whole library at once — songs, albums, artists and '
        'playlists, wherever they live.',
      );
    }
    if (results.isEmpty) {
      // Offering the catalog is the point: not owning something is exactly
      // the moment the archive pipeline is useful, and the alternative is a
      // dead end that says "no results" and stops.
      return Column(
        children: [
          _hint('Nothing in your library matches "${results.query}".'),
          const SizedBox(height: 4),
          TextButton.icon(
            onPressed: () =>
                showCatalogArtistSheet(context, results.query.trim()),
            icon: const Icon(Icons.travel_explore_rounded, size: 18),
            label: const Text('Look for it on Apple Music'),
          ),
        ],
      );
    }

    return ListView(
      padding: const EdgeInsets.only(bottom: 100),
      children: [
        _section(
          'Songs',
          results.songs.length,
          [for (final song in results.songs) _songRow(song, results.songs)],
        ),
        _section('Albums', results.albums.length, [
          for (final album in results.albums) _albumRow(album),
        ]),
        _section('Artists', results.artists.length, [
          for (final artist in results.artists) _artistRow(artist),
        ]),
        _section('Playlists', results.playlists.length, [
          for (final hit in results.playlists) _playlistRow(hit),
        ]),
      ],
    );
  }

  Widget _hint(String text) => Padding(
    padding: const EdgeInsets.fromLTRB(24, 40, 24, 0),
    child: Text(
      text,
      textAlign: TextAlign.center,
      style: TextStyle(fontSize: 13, color: textColor.value),
    ),
  );

  Widget _section(String title, int count, List<Widget> rows) {
    if (rows.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
      child: SmoothClipRRect(
        smoothness: 1,
        borderRadius: BorderRadius.circular(12 * activeFlavour.cornerScale),
        child: Container(
          color: menuColor.value,
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '$title ($count)',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: highlightTextColor.value,
                ),
              ),
              const SizedBox(height: 4),
              ...rows,
            ],
          ),
        ),
      ),
    );
  }

  Widget _row({
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return ListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      leading: Icon(icon, size: 20, color: textColor.value),
      title: Text(
        title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 13.5, color: highlightTextColor.value),
      ),
      subtitle: Text(
        subtitle,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 11.5, color: textColor.value),
      ),
      onTap: onTap,
    );
  }

  Widget _songRow(MyAudioMetadata song, List<MyAudioMetadata> siblings) => _row(
    icon: Icons.music_note_rounded,
    title: getTitle(song),
    subtitle: '${getArtist(song)} · ${getAlbum(song)}',
    // The other song hits become the queue, not the one song: a search for an
    // album name should behave like opening that album, not like playing one
    // track and stopping.
    onTap: () => audioHandler.setPlayQueue(
      siblings,
      0,
      targetIndex: siblings.indexOf(song),
    ),
  );

  Widget _albumRow(Album album) => _row(
    icon: Icons.album_rounded,
    title: album.name,
    subtitle:
        '${album.songList.length} '
        '${album.songList.length == 1 ? "track" : "tracks"}',
    onTap: () => layersManager.pushDetail('albums', album),
  );

  Widget _artistRow(Artist artist) => _row(
    icon: Icons.person_rounded,
    title: artist.name,
    subtitle: artistSubtitle(artist),
    onTap: () => layersManager.pushDetail('artists', artist),
  );

  Widget _playlistRow(PlaylistHit hit) {
    final label = switch (hit.kind) {
      PlaylistKind.saved => 'Playlist',
      PlaylistKind.smart => 'Smart playlist',
      PlaylistKind.mood => 'Mood',
    };
    return _row(
      icon: switch (hit.kind) {
        PlaylistKind.saved => Icons.queue_music_rounded,
        PlaylistKind.smart => Icons.auto_awesome_rounded,
        PlaylistKind.mood => hit.mood!.icon,
      },
      title: hit.name,
      subtitle:
          '$label · ${hit.trackCount} '
          '${hit.trackCount == 1 ? "track" : "tracks"}',
      onTap: () {
        // Saved playlists are real navigation destinations and have a layer;
        // smart and mood ones are answers to a question, so they open the
        // same SongList view Home and Smart playlists already use for them.
        if (hit.kind == PlaylistKind.saved) {
          layersManager.pushDetail('playlists', hit.saved);
          return;
        }
        final spec = hit.smart ?? hit.mood!.playlist;
        Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => SongList(playlist: SmartPlaylistView(spec)),
          ),
        );
      },
    );
  }
}
