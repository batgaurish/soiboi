/// Home: the landing screen.
///
/// Upstream has no home in the standard views — the sidebar drops you straight
/// into Artists or Songs — so this is new, and it is the startup layer.
///
/// Layout follows the pattern the large streaming apps converged on: familiar
/// things first (what you were listening to, what just arrived), discovery
/// below. Every shelf is conditional, so a fresh install with no server and no
/// play history shows one empty state rather than a wall of placeholders.
///
/// Rankings have two sources. Without a ListenBrainz username they come from
/// local play counts, which needs no network and no account. With one, they
/// come from ListenBrainz and reflect everything you listen to rather than just
/// what this device played — and entries you don't own locally are surfaced as
/// gaps the archival pipeline can fill.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/history.dart';
import 'package:soiboi/base/data/home_shelves.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/discovery_service.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/listenbrainz_service.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:soiboi/base/widgets/cover_art_widget.dart';
import 'package:soiboi/base/widgets/quality_badge.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/layer/downloads_layer.dart';
import 'package:soiboi/layer/catalog_sheet.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/portrait_view/custom_appbar_leading.dart';
import 'package:smooth_corner/smooth_corner.dart';

class HomeLayer extends StatefulWidget {
  const HomeLayer({super.key});

  @override
  State<HomeLayer> createState() => _HomeLayerState();
}

class _HomeLayerState extends State<HomeLayer> {
  List<LbPlaylist> _discover = const [];
  List<LbEntry>? _lbArtists;
  List<LbEntry>? _lbAlbums;

  @override
  void initState() {
    super.initState();
    _loadRemote();
    listenBrainzUserNotifier.addListener(_loadRemote);
  }

  @override
  void dispose() {
    listenBrainzUserNotifier.removeListener(_loadRemote);
    super.dispose();
  }

  Future<void> _loadRemote() async {
    // Discovery playlists come straight from ListenBrainz now. They are public
    // and need only a username, so no server is involved and this works on a
    // device with nothing else configured.
    final playlists = await discoveryPlaylists();
    if (mounted) setState(() => _discover = playlists);

    if (!listenBrainzConnected) {
      if (mounted) {
        setState(() {
          _lbArtists = null;
          _lbAlbums = null;
        });
      }
      return;
    }
    final artists = await topArtistsFromListenBrainz();
    final albums = await topAlbumsFromListenBrainz();
    if (mounted) {
      setState(() {
        _lbArtists = artists;
        _lbAlbums = albums;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);

    // The Scaffold is deliberately built *outside* the ListenableBuilder.
    // Returning either a bare scroll view or a Scaffold from inside the
    // builder changed the tree's shape on every notification, which tripped
    // Flutter's '_dependents.isEmpty' assertion when an element with
    // registered dependents was torn down mid-rebuild. Keeping the shape
    // fixed and rebuilding only the content avoids that entirely.
    final body = ListenableBuilder(
      listenable: Listenable.merge([
        history.recentlyChangeNotifier,
        history.rankingChangeNotifier,
        artistAlbumManager.updateNotifier,
        currentSongNotifier,
      ]),
      builder: (context, _) {
        final upNext = playNextSongs();
        final recent = history.recentlySongList.take(shelfLimit).toList();
        final added = recentlyAddedSongs();
        final addedAlbums = recentlyAddedAlbums();
        final favourites = favouriteSongs();
        final most = history.rankingSongList.take(shelfLimit).toList();

        // ListenBrainz wins when connected and reachable; local rankings are
        // the fallback, never a blank shelf.
        final artistEntries = _lbArtists;
        final albumEntries = _lbAlbums;
        final localArtists = topArtists();
        final localAlbums = topAlbums();

        final anything = upNext.isNotEmpty ||
            recent.isNotEmpty ||
            added.isNotEmpty ||
            _discover.isNotEmpty ||
            favourites.isNotEmpty ||
            most.isNotEmpty ||
            localArtists.isNotEmpty ||
            (artistEntries?.isNotEmpty ?? false);

        return CustomScrollView(
          slivers: [
            const SliverToBoxAdapter(child: SizedBox(height: 18)),

            if (upNext.isNotEmpty)
              _sliver(_songShelf('Up next', upNext)),

            if (recent.isNotEmpty)
              _sliver(_songShelf(l10n.recently, recent)),

            if (added.isNotEmpty)
              _sliver(_songShelf('Recently added', added)),

            if (_discover.isNotEmpty) _sliver(_discoverShelf()),

            if (favourites.isNotEmpty)
              _sliver(_songShelf(l10n.favorites, favourites)),

            if (artistEntries != null && artistEntries.isNotEmpty)
              _sliver(_lbShelf('Top artists', artistEntries, circular: true))
            else if (localArtists.isNotEmpty)
              _sliver(_collectionShelf(
                'Top artists',
                localArtists,
                circular: true,
              )),

            if (albumEntries != null && albumEntries.isNotEmpty)
              _sliver(_lbShelf('Top albums', albumEntries, circular: false))
            else if (localAlbums.isNotEmpty)
              _sliver(_collectionShelf('Top albums', localAlbums)),

            if (addedAlbums.isNotEmpty)
              _sliver(_collectionShelf('New albums', addedAlbums)),

            if (most.isNotEmpty) _sliver(_songShelf(l10n.ranking, most)),

            if (!anything) SliverFillRemaining(child: _emptyState()),
            const SliverToBoxAdapter(child: SizedBox(height: 90)),
          ],
        );
      },
    );

    // On a narrow layout the drawer is the only navigation, and every other
    // page gets its menu button from its own portrait wrapper. Without one
    // here, Home was a dead end with no way to reach anything else.
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
        title: Text(l10n.home),
        centerTitle: true,
      ),
      body: body,
    );
  }

  Widget _sliver(Widget child) => SliverToBoxAdapter(child: child);

  Widget _emptyState() {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.library_music_outlined, size: 42, color: textColor.value),
            const SizedBox(height: 12),
            Text(
              'Nothing here yet',
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.w600,
                color: highlightTextColor.value,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              'Play something and it will show up here.',
              style: TextStyle(fontSize: 13, color: textColor.value),
            ),
          ],
        ),
      ),
    );
  }

  Widget _sectionHeader(String title, {String? trailing}) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 20, 24, 12),
      child: Row(
        children: [
          Text(
            title,
            style: TextStyle(
              fontSize: 19,
              fontWeight: FontWeight.w600,
              letterSpacing: -0.3,
              color: highlightTextColor.value,
            ),
          ),
          const Spacer(),
          if (trailing != null)
            Text(
              trailing,
              style: TextStyle(fontSize: 11.5, color: textColor.value),
            ),
        ],
      ),
    );
  }

  Widget _shelf({
    required String title,
    String? trailing,
    required double height,
    required int count,
    required Widget Function(BuildContext, int) builder,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _sectionHeader(title, trailing: trailing),
        SizedBox(
          height: height,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 24),
            itemCount: count,
            separatorBuilder: (_, _) => const SizedBox(width: 14),
            itemBuilder: builder,
          ),
        ),
      ],
    );
  }

  Widget _discoverShelf() => _shelf(
    title: 'Weekly discoveries',
    trailing: 'From your listening',
    height: 96,
    count: _discover.length,
    builder: (context, i) => _DiscoverCard(
      playlist: _discover[i],
      index: i,
      onTap: () => showDiscoverPlaylistSheet(context, _discover[i]),
    ),
  );

  /// Height for a shelf of 124px artwork plus [textLines] worth of labels.
  ///
  /// Computed rather than a constant. A fixed height overflowed by two pixels
  /// at the default font size, and the part that would be clipped is the
  /// quality badge — the codec and bitrate at a glance, which is the whole
  /// point of the card. Scaling with the system font size keeps it visible for
  /// anyone who has turned text up.
  double _shelfHeight(BuildContext context, double textBlock) =>
      124 + MediaQuery.textScalerOf(context).scale(textBlock);

  Widget _songShelf(String title, List<MyAudioMetadata> songs) => _shelf(
    title: title,
    // Title, artist, quality badge, and the gaps between them.
    height: _shelfHeight(context, 66),
    count: songs.length,
    builder: (context, i) => _SongCard(
      song: songs[i],
      index: i,
      onTap: () => audioHandler.singlePlay(songs[i]),
    ),
  );

  /// Local artists or albums, ranked by plays on this device.
  Widget _collectionShelf(
    String title,
    List<ArtistAlbumBase> items, {
    bool circular = false,
  }) => _shelf(
    title: title,
    height: _shelfHeight(context, circular ? 46 : 52),
    count: items.length,
    builder: (context, i) => _CollectionCard(
      item: items[i],
      index: i,
      circular: circular,
    ),
  );

  /// ListenBrainz rankings. Entries missing from the library are dimmed and
  /// marked, because "you listen to this and don't own it" is exactly the gap
  /// the archival pipeline exists to close.
  Widget _lbShelf(String title, List<LbEntry> entries, {required bool circular}) {
    final missing = entries.where((e) => !e.isInLibrary).length;
    // The range is named whenever it is not the default month, because these
    // shelves widen to a year or all time when the month has no stats — and a
    // decade of listening presented as "this month" would be wrong.
    final range = lastUsedRange;
    final source = range == null || range == LbRange.month
        ? 'From ListenBrainz'
        : 'ListenBrainz · ${range.label.toLowerCase()}';
    return _shelf(
      title: title,
      trailing: missing == 0 ? source : '$missing not in your library',
      height: _shelfHeight(context, circular ? 46 : 52),
      count: entries.length,
      builder: (context, i) => _LbCard(
        entry: entries[i],
        index: i,
        circular: circular,
      ),
    );
  }
}

/// Cards stagger in on first build. The delay comes from the flavour's motion
/// spec, so Console — which sets a zero stagger — gets them all at once.
class _StaggeredIn extends StatelessWidget {
  const _StaggeredIn({required this.index, required this.child});
  final int index;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    // Deliberately does NOT read MediaQuery. motionFor() would register an
    // inherited-widget dependency from every card in every shelf, and those
    // cards are created and destroyed constantly as the shelves rebuild --
    // which is what tripped Flutter's '_dependents.isEmpty' assertion when a
    // ListenBrainz fetch swapped the shelf contents mid-animation.
    //
    // Reduced-motion is honoured through the flavour's own stagger setting
    // instead, which needs no context.
    final motion = activeMotion;
    if (motion.staggerStep == Duration.zero) return child;
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: motion.medium + motion.staggerStep * index,
      curve: Interval((index * 0.06).clamp(0.0, 0.6), 1, curve: motion.enterExit),
      builder: (context, t, child) => Opacity(
        opacity: t,
        child: Transform.translate(
          offset: Offset(0, (1 - t) * 14),
          child: child,
        ),
      ),
      child: child,
    );
  }
}

/// A discovery playlist, with a 2x2 mosaic of its first four tracks' artwork.
///
/// The mosaic needs the resolved track list, which is slow to fetch, so it
/// arrives asynchronously and the card shows a placeholder until then. Results
/// are cached for the session, so this cost is paid once.
class _DiscoverCard extends StatefulWidget {
  const _DiscoverCard({
    required this.playlist,
    required this.index,
    required this.onTap,
  });
  final LbPlaylist playlist;
  final int index;
  final VoidCallback onTap;

  @override
  State<_DiscoverCard> createState() => _DiscoverCardState();
}

class _DiscoverCardState extends State<_DiscoverCard> {
  List<DiscoveryTrack>? _tracks;
  int? _total;

  @override
  void initState() {
    super.initState();
    _tracks = cachedDiscoveryTracks(widget.playlist.mbid);
    _prefetch();
  }

  Future<void> _prefetch() async {
    // Only the first four covers are needed for the mosaic, and each costs an
    // Apple lookup -- resolving all fifty here would be fifty requests per card.
    final total = await discoveryTrackCount(widget.playlist.mbid);
    if (mounted) setState(() => _total = total);
    final tracks = await resolveDiscoveryTracks(widget.playlist.mbid, limit: 4);
    if (mounted && tracks != null) setState(() => _tracks = tracks);
  }

  @override
  Widget build(BuildContext context) {
    final radius = 14 * activeFlavour.cornerScale;
    return _StaggeredIn(
      index: widget.index,
      child: SmoothClipRRect(
        smoothness: 1,
        borderRadius: BorderRadius.circular(radius),
        child: Material(
          color: menuColor.value,
          child: InkWell(
            onTap: widget.onTap,
            child: SizedBox(
              width: 230,
              child: Row(
                children: [
                  _mosaic(),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 10,
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Row(
                            children: [
                              Icon(
                                Icons.auto_awesome_outlined,
                                size: 13,
                                color: seekBarColor.value,
                              ),
                              const SizedBox(width: 5),
                              Expanded(
                                child: Text(
                                  widget.playlist.title,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: TextStyle(
                                    fontSize: 13,
                                    fontWeight: FontWeight.w600,
                                    height: 1.2,
                                    color: highlightTextColor.value,
                                  ),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 5),
                          Text(
                            _total == null
                                ? 'Loading…'
                                : '$_total tracks',
                            style: TextStyle(
                              fontSize: 11,
                              color: textColor.value,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _mosaic() {
    const side = 92.0;
    final tracks = _tracks;
    final art = tracks
            ?.map((t) => t.artwork)
            .where((a) => a != null && a.isNotEmpty)
            .take(4)
            .toList() ??
        const <String?>[];

    if (art.isEmpty) {
      return Container(
        width: side,
        height: side,
        color: buttonColor.value,
        child: Icon(
          Icons.queue_music_rounded,
          size: 26,
          color: textColor.value,
        ),
      );
    }

    // Fewer than four covers: fill the square with what we have rather than
    // leaving holes in the grid.
    if (art.length < 4) {
      return _tile(art.first, side);
    }
    return SizedBox(
      width: side,
      height: side,
      child: Column(
        children: [
          Row(children: [_tile(art[0], side / 2), _tile(art[1], side / 2)]),
          Row(children: [_tile(art[2], side / 2), _tile(art[3], side / 2)]),
        ],
      ),
    );
  }

  Widget _tile(String? url, double size) {
    return SizedBox(
      width: size,
      height: size,
      child: url == null
          ? Container(color: buttonColor.value)
          : Image.network(
              url,
              fit: BoxFit.cover,
              errorBuilder: (_, _, _) => Container(color: buttonColor.value),
            ),
    );
  }
}

class _SongCard extends StatelessWidget {
  const _SongCard({
    required this.song,
    required this.index,
    required this.onTap,
  });
  final MyAudioMetadata song;
  final int index;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return _StaggeredIn(
      index: index,
      child: SizedBox(
        width: 124,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(10 * activeFlavour.cornerScale),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              CoverArtWidget(
                size: 124,
                borderRadius: 12 * activeFlavour.cornerScale,
                picture: song.picture,
                elevation: 3,
              ),
              const SizedBox(height: 8),
              Text(
                song.title ?? '',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w500,
                  color: highlightTextColor.value,
                ),
              ),
              Text(
                song.artist ?? '',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 11, color: textColor.value),
              ),
              const SizedBox(height: 4),
              QualityBadge(song),
            ],
          ),
        ),
      ),
    );
  }
}

/// A local artist or album.
class _CollectionCard extends StatelessWidget {
  const _CollectionCard({
    required this.item,
    required this.index,
    this.circular = false,
  });
  final ArtistAlbumBase item;
  final int index;
  final bool circular;

  @override
  Widget build(BuildContext context) {
    return _StaggeredIn(
      index: index,
      child: SizedBox(
        width: 124,
        child: InkWell(
          onTap: () {
            layersManager.switchRootLayer(item.isArtist ? 'artists' : 'albums');
          },
          borderRadius: BorderRadius.circular(
            circular ? 62 : 10 * activeFlavour.cornerScale,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              CoverArtWidget(
                size: 124,
                // Artists read as circles by long-standing convention; albums
                // stay square because that is what a sleeve is.
                borderRadius: circular ? 62 : 12 * activeFlavour.cornerScale,
                picture: item.picture,
                elevation: 3,
              ),
              const SizedBox(height: 8),
              Text(
                item.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: circular ? TextAlign.center : TextAlign.start,
                style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w500,
                  color: highlightTextColor.value,
                ),
              ),
              Text(
                '${item.songList.length} tracks',
                maxLines: 1,
                style: TextStyle(fontSize: 11, color: textColor.value),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A ListenBrainz-ranked artist or album, which may or may not be in the
/// library.
class _LbCard extends StatelessWidget {
  const _LbCard({
    required this.entry,
    required this.index,
    required this.circular,
  });
  final LbEntry entry;
  final int index;
  final bool circular;

  @override
  Widget build(BuildContext context) {
    final owned = entry.isInLibrary;
    final radius = circular ? 62.0 : 12 * activeFlavour.cornerScale;

    return _StaggeredIn(
      index: index,
      child: Opacity(
        // Not owning something is information, not an error — dim rather than
        // hide, so the gap stays visible.
        opacity: owned ? 1 : 0.55,
        child: SizedBox(
          width: 124,
          child: InkWell(
            // Owned or not, every card opens the catalog sheet. A bare name
            // match says nothing about how much of the release is actually
            // local, and jumping to the local tab sent a partially-owned
            // album down the "fully owned" path. The sheets render
            // per-track/per-album ownership themselves and degrade
            // gracefully (no archive footer) when nothing is missing, and
            // browsing owned music stays on the collection shelves.
            onTap: () => circular
                ? showCatalogArtistSheet(context, entry.name)
                : showCatalogAlbumSheet(
                    context,
                    entry.artistName ?? '',
                    entry.name,
                  ),
            borderRadius: BorderRadius.circular(radius),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _artwork(radius, owned),
                const SizedBox(height: 8),
                Text(
                  entry.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w500,
                    color: highlightTextColor.value,
                  ),
                ),
                Text(
                  owned
                      ? '${entry.listenCount} plays'
                      // Says what tapping does, rather than only what is
                      // missing: the card is not a dead end any more.
                      : 'Not in library · browse',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(fontSize: 11, color: textColor.value),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _artwork(double radius, bool owned) {
    final localPicture = entry.localArtist?.picture ?? entry.localAlbum?.picture;
    if (localPicture != null) {
      return CoverArtWidget(
        size: 124,
        borderRadius: radius,
        picture: localPicture,
        elevation: 3,
      );
    }
    // Fall back to Cover Art Archive when we don't own it locally.
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: Container(
        width: 124,
        height: 124,
        color: buttonColor.value,
        child: entry.artworkUrl == null
            ? Icon(
                circular ? Icons.person_outline : Icons.album_outlined,
                color: textColor.value,
              )
            : Image.network(
                entry.artworkUrl!,
                fit: BoxFit.cover,
                errorBuilder: (_, _, _) => Icon(
                  circular ? Icons.person_outline : Icons.album_outlined,
                  color: textColor.value,
                ),
              ),
      ),
    );
  }
}
