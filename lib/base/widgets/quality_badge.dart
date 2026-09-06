/// Codec and quality badges.
///
/// In an archival library, "is this the lossless copy or the 320k one?" is a
/// question the interface should answer without being asked — the whole point
/// of the download pipeline is getting the good version. So this is a core
/// component shown across every flavour, not a Console-only flourish.
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

enum QualityTier { lossless, lossy, unknown }

/// Container formats that carry lossless audio.
const _losslessFormats = {
  'alac',
  'flac',
  'wav',
  'wave',
  'ape',
  'wavpack',
  'wv',
  'aiff',
  'aif',
  'tta',
};

class QualityInfo {
  const QualityInfo(this.tier, this.label, {this.detail});

  final QualityTier tier;

  /// Short form for inline rows: "ALAC", "AAC 320".
  final String label;

  /// Long form for detail views: "24-bit · 96 kHz".
  final String? detail;

  /// Above this, an MP4/M4A stream is ALAC rather than AAC.
  ///
  /// The tag library reports the *container* ("MP4") for local files, so ALAC
  /// and AAC are indistinguishable by name — which is unfortunate, because that
  /// is precisely the distinction this library exists to make. Bitrate settles
  /// it cleanly: ALAC at 16-bit/44.1 kHz runs roughly 700-1100 kbit/s, while
  /// AAC tops out near 400 even at its most generous VBR. Nothing lands in
  /// between, so 500 separates them with room to spare.
  static const _alacBitrateFloor = 500;

  static QualityInfo of(MyAudioMetadata song) => classify(
    format: song.format,
    bitrate: song.bitrate,
    sampleRate: song.samplerate,
  );

  /// Pure classification, kept separate from [MyAudioMetadata] so the rules can
  /// be tested without constructing an FFI-backed tag object.
  static QualityInfo classify({
    required String? format,
    required int? bitrate,
    int? sampleRate,
  }) {
    final trimmed = format?.trim();
    if (trimmed == null || trimmed.isEmpty) {
      return const QualityInfo(QualityTier.unknown, '—');
    }

    final normalised = trimmed.toLowerCase();
    final detail = sampleRate == null || sampleRate <= 0
        ? null
        : '${(sampleRate / 1000).toStringAsFixed(1)} kHz';

    // Bitrate arrives in bit/s from some sources and kbit/s from others.
    final kbps = bitrate == null || bitrate <= 0
        ? null
        : (bitrate > 10000 ? (bitrate / 1000).round() : bitrate);

    if (_losslessFormats.any(normalised.contains)) {
      return QualityInfo(
        QualityTier.lossless,
        _codecName(normalised),
        detail: detail,
      );
    }

    // An MP4 container holds either ALAC or AAC; only the bitrate tells us.
    if (_isMp4Container(normalised)) {
      if (kbps != null && kbps >= _alacBitrateFloor) {
        return QualityInfo(QualityTier.lossless, 'ALAC', detail: detail);
      }
      return QualityInfo(
        QualityTier.lossy,
        kbps == null ? 'AAC' : 'AAC $kbps',
        detail: detail,
      );
    }

    final codec = _codecName(normalised);
    if (kbps == null) {
      return QualityInfo(QualityTier.lossy, codec, detail: detail);
    }
    return QualityInfo(QualityTier.lossy, '$codec $kbps', detail: detail);
  }

  static bool _isMp4Container(String format) =>
      format.contains('mp4') ||
      format.contains('m4a') ||
      format.contains('mpeg-4') ||
      format.contains('mp4a');

  static String _codecName(String format) {
    if (format.contains('alac')) return 'ALAC';
    if (format.contains('flac')) return 'FLAC';
    if (format.contains('aac') || format.contains('m4a')) return 'AAC';
    if (format.contains('mp3') || format.contains('mpeg')) return 'MP3';
    if (format.contains('opus')) return 'Opus';
    if (format.contains('vorbis') || format.contains('ogg')) return 'Vorbis';
    if (format.contains('wavpack') || format.contains('wv')) return 'WavPack';
    if (format.contains('wav')) return 'WAV';
    if (format.contains('aiff') || format.contains('aif')) return 'AIFF';
    if (format.contains('ape')) return 'APE';
    return format.toUpperCase();
  }
}

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

    final radius = 3.0 * activeFlavour.cornerScale;
    final text = showDetail && info.detail != null
        ? '${info.label} · ${info.detail}'
        : info.label;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: fg.withValues(alpha: 0.45), width: 1),
        color: isLossless ? fg.withValues(alpha: 0.10) : null,
      ),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 10,
          height: 1.35,
          fontWeight: isLossless ? FontWeight.w600 : FontWeight.w500,
          letterSpacing: 0.3,
          color: fg,
          fontFeatures: const [FontFeature.tabularFigures()],
        ),
      ),
    );
  }
}
