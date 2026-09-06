/// Home: the landing screen.
///
/// Upstream has no home in the standard views — the sidebar drops you straight
/// into Artists or Songs — so this is new. It exists to put the weekly
/// discoveries where you actually land, rather than buried behind the Downloads
/// tab, and to give recently and most played a shelf each.
///
/// Every section is conditional. With no bridge configured and an unplayed
/// library this screen is empty by design, so it never shows a wall of empty
/// placeholder cards to someone who just installed the app.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/history.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/bridge_client.dart';
import 'package:soiboi/base/services/bridge_service.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:soiboi/base/widgets/cover_art_widget.dart';
import 'package:soiboi/base/widgets/quality_badge.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/layer/downloads_layer.dart';
import 'package:smooth_corner/smooth_corner.dart';

class HomeLayer extends StatefulWidget {
  const HomeLayer({super.key});

  @override
  State<HomeLayer> createState() => _HomeLayerState();
}

class _HomeLayerState extends State<HomeLayer> {
  List<DiscoverPlaylist> _discover = const [];

  @override
  void initState() {
    super.initState();
    _loadDiscover();
    // Refresh when the server address changes, so configuring it in Settings
    // makes the shelf appear without a restart.
    bridgeUrlNotifier.addListener(_loadDiscover);
  }

  @override
  void dispose() {
    bridgeUrlNotifier.removeListener(_loadDiscover);
    super.dispose();
  }

  Future<void> _loadDiscover() async {
    final client = bridgeClient;
    if (client == null) {
      if (mounted) setState(() => _discover = const []);
      return;
    }
    try {
      final playlists = await client.discoverPlaylists();
      if (mounted) setState(() => _discover = playlists);
    } on BridgeException {
      // No server, or no ListenBrainz username configured on it. Both are
      // ordinary states — the shelf simply doesn't appear.
      if (mounted) setState(() => _discover = const []);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ListenableBuilder(
      listenable: Listenable.merge([
        history.recentlyChangeNotifier,
        history.rankingChangeNotifier,
      ]),
      builder: (context, _) {
        final recent = history.recentlySongList.take(12).toList();
        final most = history.rankingSongList.take(12).toList();
        final empty = _discover.isEmpty && recent.isEmpty && most.isEmpty;

        return CustomScrollView(
          slivers: [
            const SliverToBoxAdapter(child: SizedBox(height: 18)),
            if (_discover.isNotEmpty)
              SliverToBoxAdapter(child: _discoverShelf()),
            if (recent.isNotEmpty)
              SliverToBoxAdapter(
                child: _songShelf(l10n.recently, recent),
              ),
            if (most.isNotEmpty)
              SliverToBoxAdapter(
                child: _songShelf(l10n.ranking, most),
              ),
            if (empty) SliverFillRemaining(child: _emptyState()),
            const SliverToBoxAdapter(child: SizedBox(height: 90)),
          ],
        );
      },
    );
  }

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
              style: TextStyle(fontSize: 12, color: textColor.value),
            ),
        ],
      ),
    );
  }

  /// Weekly discovery playlists, straight from the pipeline's discovery engine.
  Widget _discoverShelf() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _sectionHeader('Weekly discoveries', trailing: 'From your library'),
        SizedBox(
          height: 96,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 24),
            itemCount: _discover.length,
            separatorBuilder: (_, _) => const SizedBox(width: 12),
            itemBuilder: (context, i) {
              final playlist = _discover[i];
              return _DiscoverCard(
                playlist: playlist,
                index: i,
                onTap: () => showDiscoverPlaylistSheet(context, playlist),
              );
            },
          ),
        ),
      ],
    );
  }

  Widget _songShelf(String title, List<MyAudioMetadata> songs) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        _sectionHeader(title),
        SizedBox(
          height: 186,
          child: ListView.separated(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 24),
            itemCount: songs.length,
            separatorBuilder: (_, _) => const SizedBox(width: 14),
            itemBuilder: (context, i) => _SongCard(
              song: songs[i],
              index: i,
              onTap: () => audioHandler.singlePlay(songs[i]),
            ),
          ),
        ),
      ],
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
    final motion = motionFor(context);
    if (motion.staggerStep == Duration.zero) return child;
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: motion.medium + motion.staggerStep * index,
      curve: Interval(
        // Later cards start later, which is what reads as a cascade.
        (index * 0.06).clamp(0.0, 0.6),
        1,
        curve: motion.enterExit,
      ),
      builder: (context, t, child) => Opacity(
        opacity: t,
        child: Transform.translate(offset: Offset(0, (1 - t) * 14), child: child),
      ),
      child: child,
    );
  }
}

class _DiscoverCard extends StatelessWidget {
  const _DiscoverCard({
    required this.playlist,
    required this.index,
    required this.onTap,
  });
  final DiscoverPlaylist playlist;
  final int index;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return _StaggeredIn(
      index: index,
      child: SmoothClipRRect(
        smoothness: 1,
        borderRadius: BorderRadius.circular(14 * activeFlavour.cornerScale),
        child: Material(
          color: menuColor.value,
          child: InkWell(
            onTap: onTap,
            child: Container(
              width: 208,
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Row(
                    children: [
                      Icon(
                        Icons.auto_awesome_outlined,
                        size: 15,
                        color: seekBarColor.value,
                      ),
                      const SizedBox(width: 7),
                      Expanded(
                        child: Text(
                          playlist.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: highlightTextColor.value,
                          ),
                        ),
                      ),
                    ],
                  ),
                  if (playlist.lastModified != null) ...[
                    const SizedBox(height: 6),
                    Text(
                      playlist.lastModified!,
                      style: TextStyle(fontSize: 11, color: textColor.value),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
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
              // Quality is visible here too — the point of the archive is
              // knowing which copy you have, wherever a track is shown.
              QualityBadge(song),
            ],
          ),
        ),
      ),
    );
  }
}
