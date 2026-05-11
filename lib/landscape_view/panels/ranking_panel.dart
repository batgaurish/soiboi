import 'package:flutter/material.dart';
import 'package:particle_music/base/widgets/local_navidrome_base.dart';
import 'package:particle_music/base/data/history.dart';
import 'package:particle_music/l10n/generated/app_localizations.dart';

class RankingPanel extends StatelessWidget {
  const RankingPanel({super.key});

  @override
  Widget build(BuildContext context) {
    return LocalNavidromeBase(
      displayNavidromeNotifier: history.displayNavidromeRankingNotifier,
      localSongList: history.rankingSongList,
      navidromeSongList: history.navidromeRankingSongList,
      ranking: AppLocalizations.of(context).ranking,
      isPanel: true,
    );
  }
}
