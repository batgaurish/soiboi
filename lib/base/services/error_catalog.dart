/// What a failed download means, in plain words, and what fixes it.
///
/// The pipeline, the wrapper and the downloader's own dependencies report
/// failures as codes and exception text: "Error fetching account info
/// (Status code: 401)", "[Errno 28] No space left on device". Those stay in
/// the job's log, where they help find a bug. Everywhere a person reads them
/// (queue rows, the failure banner, notifications) the entry here is shown
/// instead: what happened, and the one thing to do about it.
///
/// New entries come from the per-download logs (Settings > Download logs):
/// find the failure's last line there and add a pattern for it below, above
/// any broader entry that would otherwise catch it first.
library;

/// The one thing that fixes a failure. Screens turn it into a button.
enum FailureFix {
  /// Sign in to Apple Music again.
  signIn('Sign in'),

  /// Queue the same download again.
  retry('Retry'),

  /// Nothing will make this download work; leave it out.
  skip('Skip'),

  /// Read the downloader's log for this job.
  openLog('Open log'),

  /// Pick a download folder that has room and can be written to.
  chooseFolder('Choose folder'),

  /// Set up, or restart, the lossless wrapper.
  setUpWrapper('Set up lossless'),

  /// Switch download quality to AAC.
  changeQuality('Use AAC'),

  /// Get a newer Soiboi.
  update('Check for updates');

  const FailureFix(this.label);

  final String label;
}

/// A failure, explained.
class FailureExplanation {
  const FailureExplanation({
    required this.id,
    required this.title,
    required this.detail,
    required this.fix,
    this.temporary = false,
    this.raw = '',
  });

  /// Stable name of the catalog entry, for logs and tests.
  final String id;

  /// What happened, short enough for one line of a queue row.
  final String title;

  /// Why, and what to do, in a sentence or two.
  final String detail;

  final FailureFix fix;

  /// Whether the same download is likely to work if simply tried again later
  /// (a dropped connection, Apple rate-limiting). Automatic retries only
  /// ever happen for these.
  final bool temporary;

  /// The message as the pipeline reported it.
  final String raw;

  /// Whether this came from the catalog rather than the unknown-failure
  /// fallback.
  bool get known => id != _unknownId;

  /// Title and detail as one line of text, for places with room for only
  /// one.
  String get sentence => '$title. $detail';
}

const _unknownId = 'unknown';

class _Entry {
  const _Entry({
    required this.id,
    this.codes = const {},
    this.patterns = const [],
    required this.title,
    required this.detail,
    required this.fix,
    this.temporary = false,
  });

  final String id;

  /// Pipeline error codes that always mean this entry.
  final Set<String> codes;

  /// Case-insensitive regular expressions matched against the message.
  final List<String> patterns;

  final String title;
  final String detail;
  final FailureFix fix;
  final bool temporary;

  bool matches(String code, String message) {
    if (codes.contains(code)) return true;
    for (final pattern in patterns) {
      if (RegExp(pattern, caseSensitive: false).hasMatch(message)) return true;
    }
    return false;
  }
}

/// First match wins, so specific entries come before broad ones: a wrapper
/// that refuses connections must read as the wrapper, not as the network.
const _catalog = <_Entry>[
  _Entry(
    id: 'cancelled',
    codes: {'cancelled'},
    patterns: [r'^Download stopped$'],
    title: 'Stopped',
    detail: 'This download was stopped before it finished.',
    fix: FailureFix.retry,
  ),
  _Entry(
    id: 'not_signed_in',
    codes: {'no_cookies'},
    patterns: [r'No Apple Music cookies'],
    title: 'Not signed in to Apple Music',
    detail:
        'Sign in to Apple Music, or set up the lossless wrapper, before '
        'downloading.',
    fix: FailureFix.signIn,
  ),
  _Entry(
    id: 'no_subscription',
    codes: {'no_subscription'},
    patterns: [r'No active Apple Music subscription'],
    title: 'No active Apple Music subscription',
    detail:
        'Apple only hands out songs to an account with a subscription. Check '
        'it, or sign in with another account.',
    fix: FailureFix.signIn,
  ),
  _Entry(
    id: 'downloader_missing',
    codes: {'no_runtime', 'no_gamdl', 'spawn_failed'},
    patterns: [
      r'Bundled Python runtime not found',
      r'Downloader unavailable',
    ],
    title: 'The downloader is missing',
    detail:
        'This copy of Soiboi cannot find its built-in downloader. Update or '
        'reinstall the app.',
    fix: FailureFix.update,
  ),
  _Entry(
    id: 'device_unsupported',
    codes: {'no_multiprocessing'},
    title: 'This device cannot run the downloader',
    detail:
        'The downloader needs something this device does not provide. Please '
        'report it with the log.',
    fix: FailureFix.openLog,
  ),
  _Entry(
    id: 'app_bug',
    codes: {
      'bad_request',
      'bad_json',
      'unknown_command',
      'bad_response',
      'channel',
    },
    title: 'Something went wrong inside Soiboi',
    detail: 'This is a bug, not something you did. The log shows the details.',
    fix: FailureFix.openLog,
  ),
  _Entry(
    id: 'wrapper_outdated',
    patterns: [r'Unsupported wrapper-v2 API version'],
    title: 'The lossless wrapper is out of date',
    detail:
        'The downloader needs a newer wrapper than this build has. Update '
        'Soiboi.',
    fix: FailureFix.update,
  ),
  _Entry(
    id: 'wrapper_down',
    patterns: [
      r'Error fetching wrapper',
      r'wrapper did not come up',
      r'wrapper stopped \(exit code',
      r'wrapper.*(Connection refused|ConnectError|All connection attempts failed)',
      r'127\.0\.0\.1.*(Connection refused|ConnectError)',
    ],
    title: 'The lossless wrapper is not running',
    detail:
        'It stopped, or did not start in time. Retrying starts it again; if '
        'that fails too, check it under Settings > Lossless.',
    fix: FailureFix.setUpWrapper,
    temporary: true,
  ),
  _Entry(
    id: 'signed_out',
    codes: {'auth'},
    patterns: [
      r'Status code: 40[13]\b',
      r'\b401 Unauthorized\b',
      r'HTTP Error 401',
      r'Wrapper is not authenticated',
      r'Wrapper account info is missing auth tokens',
      r'Wrapper (2FA )?login failed',
      r'media-user-token',
    ],
    title: 'Apple Music sign-in expired',
    detail: 'Apple no longer accepts this sign-in. Sign in again, then retry.',
    fix: FailureFix.signIn,
  ),
  _Entry(
    id: 'rate_limited',
    patterns: [
      r'Status code: 429',
      r'\b429 Too Many Requests\b',
      r'HTTP Error 429',
      r'Too Many Requests',
    ],
    title: 'Apple is limiting downloads',
    detail:
        'Too many requests in a short time. It clears by itself after a few '
        'minutes.',
    fix: FailureFix.retry,
    temporary: true,
  ),
  _Entry(
    id: 'apple_unavailable',
    patterns: [
      r'Status code: 5\d\d',
      r'HTTP Error 5\d\d',
      r"Server error '5\d\d",
    ],
    title: 'Apple Music had a problem',
    detail: "Apple's servers failed to answer. Try again in a little while.",
    fix: FailureFix.retry,
    temporary: true,
  ),
  _Entry(
    id: 'disk_full',
    patterns: [r'No space left on device', r'Errno 28\b', r'ENOSPC'],
    title: 'Storage is full',
    detail:
        'There is no room left in the download folder. Free some space, or '
        'choose another folder.',
    fix: FailureFix.chooseFolder,
  ),
  _Entry(
    id: 'folder_not_writable',
    patterns: [
      r'Permission denied',
      r'Errno 13\b',
      r'Read-only file system',
      r'Errno 30\b',
      r'Operation not permitted',
      r'is not writable',
    ],
    title: 'Cannot write to the download folder',
    detail:
        'Soiboi is not allowed to save files there. Choose another folder; '
        'on Android, allowing All files access also fixes it.',
    fix: FailureFix.chooseFolder,
  ),
  _Entry(
    id: 'quality_unavailable',
    patterns: [r'Requested format is not available'],
    title: 'Not available in lossless',
    detail:
        'Apple has no ALAC version of this. Switch download quality to AAC '
        'to get it.',
    fix: FailureFix.changeQuality,
  ),
  _Entry(
    id: 'needs_wrapper',
    patterns: [
      r'Decryption is not available',
      r'wrapper_api is required for FairPlay',
    ],
    title: 'Needs the lossless wrapper',
    detail:
        'This can only be decrypted by the lossless wrapper. Set it up, or '
        'switch download quality to AAC.',
    fix: FailureFix.setUpWrapper,
  ),
  _Entry(
    id: 'not_streamable',
    patterns: [r'Media is not streamable'],
    title: 'Not available on your account',
    detail:
        'Apple Music will not play this in your region or on your account, so '
        'it cannot be downloaded.',
    fix: FailureFix.skip,
  ),
  _Entry(
    id: 'unsupported_link',
    patterns: [r'URL is not valid or supported'],
    title: 'Link not supported',
    detail:
        'This is not an Apple Music song, album or playlist link that can be '
        'downloaded.',
    fix: FailureFix.skip,
  ),
  _Entry(
    id: 'unsupported_media',
    patterns: [r'Media type is disallowed', r'Artist has no media of type'],
    title: 'Nothing to download here',
    detail:
        'Soiboi downloads songs, albums and playlists; this link points at '
        'something else.',
    fix: FailureFix.skip,
  ),
  _Entry(
    id: 'not_found',
    patterns: [r'Status code: 404', r'HTTP Error 404', r'\b404 Not Found\b'],
    title: 'Not found on Apple Music',
    detail:
        'Apple Music has nothing at this link any more. It may have been '
        'removed, or be limited to other regions.',
    fix: FailureFix.skip,
  ),
  _Entry(
    id: 'apple_changed',
    patterns: [
      r'Error finding index\.js URI',
      r'Error finding token in index\.js',
    ],
    title: 'Apple Music changed something',
    detail:
        'The downloader no longer understands the Apple Music website. A '
        'newer Soiboi usually fixes this.',
    fix: FailureFix.update,
  ),
  _Entry(
    id: 'network',
    codes: {'io'},
    patterns: [
      r'Error fetching Apple Music homepage',
      r'Error fetching index\.js page',
      r'ConnectError',
      r'ConnectTimeout',
      r'ReadTimeout',
      r'WriteTimeout',
      r'PoolTimeout',
      r'timed out',
      r'Name or service not known',
      r'Temporary failure in name resolution',
      r'No address associated with hostname',
      r'getaddrinfo failed',
      r'Network is unreachable',
      r'Connection reset',
      r'Connection refused',
      r'Connection aborted',
      r'RemoteProtocolError',
      r'Server disconnected',
      r'Failed to establish a new connection',
      r'SSL: ',
      r'EOF occurred in violation of protocol',
      r'IncompleteRead',
      r'yt-dlp (HLS|HTTP) download failed',
    ],
    title: 'Network problem',
    detail:
        'The connection dropped or timed out. Check the connection, then '
        'retry.',
    fix: FailureFix.retry,
    temporary: true,
  ),
  _Entry(
    id: 'widevine_rejected',
    patterns: [
      r'Widevine CDM is not configured',
      r'pywidevine',
      r'InvalidLicense',
      r'SignatureMismatch',
    ],
    title: 'Apple refused the decryption key',
    detail:
        'The built-in Widevine device may have been blocked. Use the lossless '
        'wrapper, or add your own .wvd file in Settings.',
    fix: FailureFix.setUpWrapper,
  ),
  _Entry(
    id: 'tool_missing',
    patterns: [r'Required dependency not found'],
    title: 'A download tool is missing',
    detail: 'This needs a tool the app does not include. Update Soiboi.',
    fix: FailureFix.update,
  ),
  _Entry(
    id: 'downloader_crashed',
    patterns: [
      r'downloader stopped without finishing',
      r'Downloader exited with status',
    ],
    title: 'The downloader stopped unexpectedly',
    detail:
        'It closed without saying why. Retry; if it happens again, the log '
        'shows what went wrong.',
    fix: FailureFix.openLog,
  ),
];

/// The catalog entry for a failure, or null when the catalog does not know
/// it.
FailureExplanation? explainFailure(String message, {String code = ''}) {
  for (final entry in _catalog) {
    if (entry.matches(code, message)) {
      return FailureExplanation(
        id: entry.id,
        title: entry.title,
        detail: entry.detail,
        fix: entry.fix,
        temporary: entry.temporary,
        raw: message,
      );
    }
  }
  return null;
}

/// Like [explainFailure], but always has an answer. An unknown failure keeps
/// the reported message, since it is then the only clue there is.
FailureExplanation describeFailure(String message, {String code = ''}) =>
    explainFailure(message, code: code) ??
    FailureExplanation(
      id: _unknownId,
      title: 'Download failed',
      detail: message.trim().isEmpty ? 'No reason was given.' : message.trim(),
      fix: FailureFix.openLog,
      raw: message,
    );

/// Every entry's id, so a test can check each one is reachable.
List<String> get catalogIds => [for (final entry in _catalog) entry.id];
