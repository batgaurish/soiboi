/// Codec and quality badges.
///
/// In an archival library, "is this the lossless copy or the 320k one?" is a
/// question the interface should answer without being asked — the whole point
/// of the download pipeline is getting the good version. So this is a core
/// component shown across every flavour, not a single-flavour flourish.
///
/// The badge distinguishes three tiers rather than printing raw numbers:
/// lossless (ALAC/FLAC/WAV/APE), lossy with its bitrate, and unknown. Only
/// lossless gets chromatic treatment, so a library that is mostly lossless
/// stays calm and the lossy outliers are what catch the eye.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/theme/flavour.dart';
import 'package:soiboi/base/utils/quality_info.dart';
export 'package:soiboi/base/utils/quality_info.dart';

/// Signal's lossy readout. Fixed rather than themed: it has to read as a
/// warning light against any palette.
const _amber = Color(0xFFF2B84C);

/// Compact badge for list rows.
class QualityBadge extends StatelessWidget {
  const QualityBadge(this.song, {super.key, this.showDetail = false});

  final MyAudioMetadata song;
  final bool showDetail;

  @override
  Widget build(BuildContext context) {
    final info = QualityInfo.of(song);
    if (info.tier == QualityTier.unknown) return const SizedBox.shrink();

    // Lossless is the only tier that earns colour.
    final accent = seekBarColor.value;
    final muted = textColor.value;
    final isLossless = info.tier == QualityTier.lossless;
    final fg = isLossless ? accent : muted;

    final text = showDetail && info.detail != null
        ? '${info.label} · ${info.detail}'
        : info.label;
    final style = activeFlavour.badgeStyle;

    // Each flavour draws the badge its own way, but lossless is always the
    // filled or lit one and lossy the quiet one.
    final (
      Color? fill,
      Color border,
      Color ink,
      double radius,
    ) = switch (style) {
      BadgeStyle.outline => (
        isLossless ? fg.withValues(alpha: 0.10) : null,
        fg.withValues(alpha: 0.45),
        fg,
        3.0 * activeFlavour.cornerScale,
      ),
      BadgeStyle.label => (
        isLossless ? highlightTextColor.value : null,
        isLossless ? highlightTextColor.value : muted.withValues(alpha: 0.6),
        isLossless ? pageBackgroundColor.value : muted,
        2.0,
      ),
      BadgeStyle.led => (
        isLossless ? accent : null,
        isLossless ? accent : _amber,
        isLossless ? pageBackgroundColor.value : _amber,
        1.0,
      ),
      BadgeStyle.sticker => (
        isLossless ? highlightTextColor.value : accent.withValues(alpha: 0.18),
        isLossless ? highlightTextColor.value : muted,
        isLossless ? pageBackgroundColor.value : highlightTextColor.value,
        99.0,
      ),
    };

    final badge = Container(
      padding: EdgeInsets.symmetric(
        horizontal: style == BadgeStyle.sticker ? 8 : 6,
        vertical: 1,
      ),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: border, width: 1),
        color: fill,
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 10,
          height: 1.35,
          fontWeight: isLossless ? FontWeight.w700 : FontWeight.w600,
          letterSpacing: style == BadgeStyle.outline ? 0.3 : 0.8,
          color: ink,
          fontFeatures: const [FontFeature.tabularFigures()],
        ),
      ),
    );
    // Stickers go on slightly crooked, only the lossless one, so a lossy
    // outlier still reads as the odd one out.
    if (style == BadgeStyle.sticker && isLossless) {
      return Transform.rotate(angle: -0.05, child: badge);
    }
    return badge;
  }
}
