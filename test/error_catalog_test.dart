import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/archive_service.dart';
import 'package:soiboi/base/services/download_queue_manager.dart';
import 'package:soiboi/base/services/error_catalog.dart';

/// Failures as they actually arrive: the pipeline's code, and the message
/// after the stream tap has stripped gamdl's log furniture. The texts come
/// from gamdl 3.8/3.9's own exceptions and from the pipeline.
const _cases = <(String code, String message, String id)>[
  ('cancelled', 'Download stopped', 'cancelled'),
  (
    'wrapper_unconfirmed',
    'Apple did not confirm the lossless sign-in. Check the connection.',
    'sign_in_unconfirmed',
  ),
  (
    'no_cookies',
    'No Apple Music cookies. Sign in from Settings.',
    'not_signed_in',
  ),
  (
    'gamdl_reported_error',
    "No active Apple Music subscription found, you won't be able to download "
        'anything',
    'no_subscription',
  ),
  ('no_runtime', 'Bundled Python runtime not found', 'downloader_missing'),
  (
    'no_gamdl',
    "Downloader unavailable: No module named 'gamdl'",
    'downloader_missing',
  ),
  (
    'no_multiprocessing',
    'This device cannot run the downloader: it has no working multiprocessing',
    'device_unsupported',
  ),
  ('bad_request', 'Missing: url', 'app_bug'),
  (
    'gamdl_reported_error',
    'Error: Unsupported wrapper-v2 API version. gamdl requires wrapper-v2 2: '
        '{"detected_version": 1}',
    'wrapper_outdated',
  ),
  (
    'gamdl_reported_error',
    'Error: Error fetching wrapper account info (Status code: 500)',
    'wrapper_down',
  ),
  ('', 'The wrapper did not come up within 30 seconds.', 'wrapper_down'),
  (
    'gamdl_error',
    'Error fetching account info (Status code: 401)',
    'signed_out',
  ),
  (
    'gamdl_reported_error',
    'Error: Wrapper is not authenticated. Provide get_credentials_func or log '
        'in via the wrapper.',
    'signed_out',
  ),
  (
    'gamdl_reported_error',
    'Error downloading "One More Time": Error fetching from AMP API '
        '(Status code: 429): Too Many Requests',
    'rate_limited',
  ),
  (
    'gamdl_reported_error',
    'Error downloading "Aerodynamic": Error fetching from AMP API '
        '(Status code: 503)',
    'apple_unavailable',
  ),
  (
    'gamdl_reported_error',
    "Error downloading \"Digital Love\": [Errno 28] No space left on device: "
        "'/storage/emulated/0/Music/Daft Punk'",
    'disk_full',
  ),
  (
    'gamdl_error',
    "[Errno 13] Permission denied: '/storage/emulated/0/Music/.temp'",
    'folder_not_writable',
  ),
  (
    'gamdl_error',
    '[Errno 30] Read-only file system: /media/sd',
    'folder_not_writable',
  ),
  (
    'gamdl_reported_error',
    'Requested format is not available (media ID: 1440818839): alac',
    'quality_unavailable',
  ),
  (
    'gamdl_reported_error',
    'Decryption is not available for media ID: 1440818839',
    'needs_wrapper',
  ),
  (
    'gamdl_reported_error',
    'Media is not streamable: 1440818839',
    'not_streamable',
  ),
  (
    'gamdl_reported_error',
    'URL is not valid or supported: https://example.com/album/1',
    'unsupported_link',
  ),
  (
    'gamdl_reported_error',
    'Media type is disallowed (media ID: 1): music-videos',
    'unsupported_media',
  ),
  (
    'gamdl_reported_error',
    'Error processing "https://music.apple.com/us/album/x/1": Error fetching '
        'from AMP API (Status code: 404): [{"status": "404"}]',
    'not_found',
  ),
  ('gamdl_error', 'Error finding token in index.js page', 'apple_changed'),
  ('gamdl_error', 'Error fetching Apple Music homepage', 'network'),
  (
    'gamdl_reported_error',
    'Error processing "https://music.apple.com/us/album/x/1": '
        '[Errno -3] Temporary failure in name resolution',
    'network',
  ),
  (
    'gamdl_reported_error',
    'Error downloading "Veridis Quo": yt-dlp HLS download failed',
    'network',
  ),
  ('io', 'SocketException: Connection reset by peer', 'network'),
  ('gamdl_error', 'Widevine CDM is not configured', 'widevine_rejected'),
  (
    'gamdl_reported_error',
    'Required dependency not found: N_m3u8DL-RE',
    'tool_missing',
  ),
  (
    'no_result',
    'The downloader stopped without finishing. See the log for details.',
    'downloader_crashed',
  ),
  ('gamdl_failed', 'Downloader exited with status 2', 'downloader_crashed'),
  // The most frequent failures in a real desktop log (2026-09-25).
  (
    'gamdl_reported_error',
    'Error downloading "Iktara": Error fetching wrapper playback',
    'wrapper_down',
  ),
  (
    'gamdl_reported_error',
    'Error downloading "Iktara": Server disconnected without sending a '
        'response.',
    'network',
  ),
  (
    'gamdl_reported_error',
    'Error downloading "Low": [Errno 13] Permission denied: '
        "'/home/me/Music/The Driver Era/X/06 Low.lrc'",
    'folder_not_writable',
  ),
  (
    'gamdl_reported_error',
    "Requested format is not available (media ID: 1440904109): ['alac']",
    'quality_unavailable',
  ),
];

void main() {
  for (final (code, message, id) in _cases) {
    test('$id: $message', () {
      final explained = explainFailure(message, code: code);
      expect(explained, isNotNull);
      expect(explained!.id, id);
      expect(explained.raw, message);
      expect(explained.title, isNotEmpty);
      expect(explained.detail, isNotEmpty);
    });
  }

  test('every catalog entry is reached by a real message', () {
    final reached = {
      for (final (code, message, _) in _cases)
        explainFailure(message, code: code)!.id,
    };
    expect(reached, containsAll(catalogIds));
  });

  test('matches whatever the case', () {
    expect(explainFailure('no space left on device')!.id, 'disk_full');
  });

  test('only passing trouble is marked temporary', () {
    final temporary = {
      for (final (code, message, _) in _cases)
        if (explainFailure(message, code: code)!.temporary)
          explainFailure(message, code: code)!.id,
    };
    expect(temporary, {
      'sign_in_unconfirmed',
      'wrapper_down',
      'rate_limited',
      'apple_unavailable',
      'network',
    });
  });

  test('an unknown failure keeps its own words and points at the log', () {
    expect(explainFailure("KeyError: 'attributes'"), isNull);
    final described = describeFailure("KeyError: 'attributes'");
    expect(described.known, isFalse);
    expect(described.title, 'Download failed');
    expect(described.detail, "KeyError: 'attributes'");
    expect(described.fix, FailureFix.openLog);
    expect(describeFailure('  ').detail, 'No reason was given.');
  });

  test('a failed job is explained from its code, not only its words', () async {
    final manager = DownloadQueueManager()
      ..sync = (() async {})
      ..stopActive = (() async {});
    manager.archive =
        (
          url, {
          bool redownload = false,
          void Function(int, String)? onProgress,
          void Function(String)? onLog,
          String? logPath,
        }) async => const DownloadFailure('Sign in first', code: 'no_cookies');

    final batch = manager.enqueue([
      const DownloadRequest(url: 'u', label: 'Discovery'),
    ]);
    await batch.done;

    final job = batch.jobs.single;
    expect(job.error, 'Sign in first');
    expect(job.failure!.id, 'not_signed_in');
    expect(batch.failures.single.fix, FailureFix.signIn);
    expect(job.log.join('\n'), contains('Catalog: not_signed_in'));

    manager.retry(job);
    expect(job.failure, isNull);
    expect(job.errorCode, isEmpty);
  });
}
