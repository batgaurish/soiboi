import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/widgets/quality_badge.dart';

/// The ALAC-vs-AAC branch cannot be verified by eye without a lossless file in
/// the library, and it is the distinction the whole archival pipeline exists to
/// produce — so it gets tested directly.
///
/// [QualityInfo.of] takes a MyAudioMetadata, which is expensive to construct in
/// a unit test (it wraps an FFI-backed tag object). These tests exercise the
/// classification rules through the same public surface by way of a stub.
void main() {
  group('QualityInfo classification', () {
    test('flac is lossless regardless of bitrate', () {
      final info = QualityInfo.classify(format: 'FLAC', bitrate: 900);
      expect(info.tier, QualityTier.lossless);
      expect(info.label, 'FLAC');
    });

    test('mp4 container above the floor is ALAC, not AAC', () {
      // gamdl writes ALAC into an .m4a; lofty reports the container as "MP4".
      final info = QualityInfo.classify(format: 'MP4', bitrate: 950);
      expect(info.tier, QualityTier.lossless);
      expect(info.label, 'ALAC');
    });

    test('mp4 container below the floor is AAC with its bitrate', () {
      final info = QualityInfo.classify(format: 'MP4', bitrate: 323);
      expect(info.tier, QualityTier.lossy);
      expect(info.label, 'AAC 323');
    });

    test('bitrate reported in bit/s is normalised to kbit/s', () {
      final info = QualityInfo.classify(format: 'MP4', bitrate: 320000);
      expect(info.label, 'AAC 320');
      expect(info.tier, QualityTier.lossy);
    });

    test('a bit-per-second ALAC stream still reads as lossless', () {
      final info = QualityInfo.classify(format: 'MP4', bitrate: 1_010_000);
      expect(info.tier, QualityTier.lossless);
      expect(info.label, 'ALAC');
    });

    test('mp3 keeps its own codec name', () {
      final info = QualityInfo.classify(format: 'MP3', bitrate: 256);
      expect(info.tier, QualityTier.lossy);
      expect(info.label, 'MP3 256');
    });

    test('missing format is unknown, not a guess', () {
      expect(QualityInfo.classify(format: null, bitrate: 900).tier, QualityTier.unknown);
      expect(QualityInfo.classify(format: '', bitrate: 900).tier, QualityTier.unknown);
    });

    test('lossy with no bitrate degrades to the bare codec', () {
      final info = QualityInfo.classify(format: 'MP3', bitrate: null);
      expect(info.tier, QualityTier.lossy);
      expect(info.label, 'MP3');
    });

    test('sample rate becomes the detail line', () {
      final info = QualityInfo.classify(format: 'FLAC', bitrate: 900, sampleRate: 96000);
      expect(info.detail, '96.0 kHz');
    });

    test('boundary: exactly at the floor counts as lossless', () {
      expect(QualityInfo.classify(format: 'MP4', bitrate: 500).tier, QualityTier.lossless);
      expect(QualityInfo.classify(format: 'MP4', bitrate: 499).tier, QualityTier.lossy);
    });
  });
}
