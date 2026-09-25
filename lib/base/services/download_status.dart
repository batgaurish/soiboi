/// "Why can't I download?", answered one line at a time.
///
/// The Downloads status panel shows these checks: the Apple Music account,
/// the lossless wrapper, the download engine, the download folder and the
/// network. Each problem carries the one action that fixes it.
///
/// [buildStatusChecks] is pure, so every case the app can detect is tested
/// without a device; [StatusInputs.gather] reads the live state.
library;

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/cookie_store.dart' as cookie_store;
import 'package:soiboi/base/services/pipeline_runner.dart';
import 'package:soiboi/base/services/wrapper_service.dart';

enum StatusLevel {
  ok,

  /// Works now, but needs attention soon (a session about to expire).
  warning,

  /// Stops downloads until fixed.
  problem,

  /// Not needed for how downloads are set up, or still being checked.
  info,
}

/// What a check's button does.
enum StatusFix { signIn, setUpLossless, startWrapper, chooseFolder, recheck }

/// Button text for [fix].
String statusFixLabel(StatusFix fix) => switch (fix) {
  StatusFix.signIn => 'Sign in',
  StatusFix.setUpLossless => 'Set up',
  StatusFix.startWrapper => 'Start',
  StatusFix.chooseFolder => 'Choose folder',
  StatusFix.recheck => 'Check again',
};

class StatusCheck {
  const StatusCheck({
    required this.label,
    required this.level,
    required this.detail,
    this.fix,
  });

  final String label;
  final StatusLevel level;
  final String detail;
  final StatusFix? fix;

  @override
  String toString() => '$label: ${level.name}, $detail';
}

/// Everything the checks look at, read once so they agree with each other.
class StatusInputs {
  const StatusInputs({
    required this.now,
    required this.codec,
    required this.cookiesSignedIn,
    required this.cookieExpiry,
    required this.wrapperSignedIn,
    required this.wrapper,
    required this.capabilities,
    required this.chosenFolder,
    required this.saveFolder,
    required this.freeBytes,
    required this.online,
  });

  final DateTime now;
  final String codec;
  final bool cookiesSignedIn;
  final DateTime? cookieExpiry;
  final bool wrapperSignedIn;
  final WrapperState wrapper;

  /// Null while the pipeline is being probed.
  final PipelineCapabilities? capabilities;

  /// The folder picked in settings; empty for the app's own folder.
  final String chosenFolder;

  /// Where downloads will actually go (the app's own folder when the chosen
  /// one cannot be written to).
  final String saveFolder;

  /// Null when unknown.
  final int? freeBytes;

  /// Null while checking.
  final bool? online;

  /// The app's state now, with the disk and network answers passed in:
  /// those take a moment, so a sign-in or settings change reuses the last
  /// ones instead of asking again.
  factory StatusInputs.live({int? freeBytes, bool? online}) => StatusInputs(
    now: DateTime.now(),
    codec: downloadCodecNotifier.value,
    cookiesSignedIn: cookie_store.signedInNotifier.value,
    cookieExpiry: cookie_store.sessionExpiryNotifier.value,
    wrapperSignedIn: wrapperService.signedIn.value,
    wrapper: wrapperService.state.value,
    capabilities: pipelineCapabilitiesNotifier.value,
    chosenFolder: downloadFolderNotifier.value.trim(),
    saveFolder: downloadOutputDir,
    freeBytes: freeBytes,
    online: online,
  );

  /// Everything, including free space and whether Apple Music answers.
  static Future<StatusInputs> gather() async {
    final results = await Future.wait<Object?>([
      pipelineRunner.diskUsage(downloadOutputDir),
      _reachesApple(),
    ]);
    final usage = results[0] as ({int free, int total})?;
    return StatusInputs.live(
      freeBytes: usage?.free,
      online: results[1] as bool,
    );
  }

  /// Every notifier [StatusInputs.live] reads, to rebuild when one changes.
  static final Listenable changes = Listenable.merge([
    cookie_store.signedInNotifier,
    cookie_store.sessionExpiryNotifier,
    wrapperService.signedIn,
    wrapperService.state,
    pipelineCapabilitiesNotifier,
    downloadCodecNotifier,
    downloadFolderNotifier,
  ]);

  /// Any answer at all from Apple Music's host counts: the question is
  /// whether the network gets there, not what the page says.
  static Future<bool> _reachesApple() async {
    try {
      await http
          .head(Uri.parse('https://music.apple.com/'))
          .timeout(const Duration(seconds: 8));
      return true;
    } catch (_) {
      return false;
    }
  }
}

/// Below this, a download can fail partway: an ALAC album is about 1 GB.
const _lowSpace = 1024 * 1024 * 1024;

/// Below this, a single ALAC track may not fit.
const _outOfSpace = 200 * 1024 * 1024;

/// A browser session this close to expiring gets a warning.
const _expirySoon = Duration(days: 7);

List<StatusCheck> buildStatusChecks(StatusInputs i) => [
  _account(i),
  _wrapper(i),
  _engine(i),
  _folder(i),
  _network(i),
];

String _date(DateTime d) => d.toLocal().toString().split(' ').first;

StatusCheck _account(StatusInputs i) {
  const label = 'Apple Music account';
  if (i.wrapperSignedIn) {
    final account = i.wrapper.account;
    return StatusCheck(
      label: label,
      level: StatusLevel.ok,
      detail:
          'Lossless sign-in${account == null ? '' : ' as $account'}'
          '${i.cookiesSignedIn ? ', browser sign-in as a fallback' : ''}',
    );
  }
  if (!i.cookiesSignedIn) {
    return const StatusCheck(
      label: label,
      level: StatusLevel.problem,
      detail: 'Not signed in. Downloading needs an Apple Music subscription.',
      fix: StatusFix.signIn,
    );
  }
  final expiry = i.cookieExpiry;
  if (expiry == null) {
    return const StatusCheck(
      label: label,
      level: StatusLevel.ok,
      detail: 'Browser sign-in',
    );
  }
  if (!expiry.isAfter(i.now)) {
    return StatusCheck(
      label: label,
      level: StatusLevel.problem,
      detail: 'Browser sign-in expired on ${_date(expiry)}',
      fix: StatusFix.signIn,
    );
  }
  if (expiry.difference(i.now) < _expirySoon) {
    return StatusCheck(
      label: label,
      level: StatusLevel.warning,
      detail: 'Browser sign-in expires on ${_date(expiry)}',
      fix: StatusFix.signIn,
    );
  }
  return StatusCheck(
    label: label,
    level: StatusLevel.ok,
    detail: 'Browser sign-in, expires ${_date(expiry)}',
  );
}

StatusCheck _wrapper(StatusInputs i) {
  const label = 'Lossless wrapper';
  final stage = i.wrapper.stage;
  if (stage == WrapperStage.unsupported) {
    return const StatusCheck(
      label: label,
      level: StatusLevel.info,
      detail: 'Not available in this build. AAC downloads work.',
    );
  }
  // ALAC needs it, and so does every download when it holds the only
  // sign-in; otherwise it is optional.
  final needed = i.codec == 'alac' || (i.wrapperSignedIn && !i.cookiesSignedIn);
  final (level, detail, fix) = switch (stage) {
    WrapperStage.ready => (StatusLevel.ok, 'Running', null),
    WrapperStage.stopped => (
      i.wrapperSignedIn ? StatusLevel.ok : StatusLevel.problem,
      i.wrapperSignedIn ? 'Starts when a download needs it' : 'Not signed in',
      i.wrapperSignedIn ? null : StatusFix.setUpLossless,
    ),
    WrapperStage.starting => (StatusLevel.info, 'Starting…', null),
    WrapperStage.needsLibraries => (
      StatusLevel.problem,
      'Needs the Apple Music app file',
      StatusFix.setUpLossless,
    ),
    WrapperStage.signedOut || WrapperStage.needsCode => (
      StatusLevel.problem,
      'Needs sign-in',
      StatusFix.setUpLossless,
    ),
    WrapperStage.failed => (
      StatusLevel.problem,
      'Did not start${i.wrapper.message == null ? '' : ': ${i.wrapper.message}'}',
      StatusFix.startWrapper,
    ),
    WrapperStage.unsupported => (StatusLevel.info, '', null),
  };
  if (!needed && level == StatusLevel.problem) {
    return StatusCheck(
      label: label,
      level: StatusLevel.info,
      detail: 'Only needed for ALAC. $detail.',
      fix: fix,
    );
  }
  return StatusCheck(label: label, level: level, detail: detail, fix: fix);
}

StatusCheck _engine(StatusInputs i) {
  const label = 'Download engine';
  final caps = i.capabilities;
  if (caps == null) {
    return const StatusCheck(
      label: label,
      level: StatusLevel.info,
      detail: 'Checking…',
    );
  }
  if (caps.canDownload) {
    return StatusCheck(
      label: label,
      level: StatusLevel.ok,
      detail: caps.pythonVersion.isEmpty
          ? 'Ready'
          : 'Ready (Python ${caps.pythonVersion})',
    );
  }
  return StatusCheck(
    label: label,
    level: StatusLevel.problem,
    detail: caps.summary,
    fix: StatusFix.recheck,
  );
}

String _size(int bytes) {
  const gb = 1024 * 1024 * 1024;
  if (bytes >= gb) return '${(bytes / gb).toStringAsFixed(1)} GB';
  return '${(bytes / (1024 * 1024)).round()} MB';
}

StatusCheck _folder(StatusInputs i) {
  const label = 'Download folder';
  final where = i.chosenFolder.isEmpty ? "The app's own folder" : i.saveFolder;
  if (i.chosenFolder.isNotEmpty && i.saveFolder != i.chosenFolder) {
    return StatusCheck(
      label: label,
      level: StatusLevel.warning,
      detail:
          "${i.chosenFolder} can't be written to, so downloads go to the "
          "app's own folder",
      fix: StatusFix.chooseFolder,
    );
  }
  final free = i.freeBytes;
  if (free == null) {
    return StatusCheck(label: label, level: StatusLevel.ok, detail: where);
  }
  if (free < _outOfSpace) {
    return StatusCheck(
      label: label,
      level: StatusLevel.problem,
      detail: '$where: only ${_size(free)} free',
      fix: StatusFix.chooseFolder,
    );
  }
  if (free < _lowSpace) {
    return StatusCheck(
      label: label,
      level: StatusLevel.warning,
      detail: '$where: ${_size(free)} free, enough for a few albums',
      fix: StatusFix.chooseFolder,
    );
  }
  return StatusCheck(
    label: label,
    level: StatusLevel.ok,
    detail: '$where: ${_size(free)} free',
  );
}

StatusCheck _network(StatusInputs i) {
  const label = 'Network';
  return switch (i.online) {
    null => const StatusCheck(
      label: label,
      level: StatusLevel.info,
      detail: 'Checking…',
    ),
    true => const StatusCheck(
      label: label,
      level: StatusLevel.ok,
      detail: 'Apple Music is reachable',
    ),
    false => const StatusCheck(
      label: label,
      level: StatusLevel.problem,
      detail: "Can't reach Apple Music. Check the connection.",
      fix: StatusFix.recheck,
    ),
  };
}
