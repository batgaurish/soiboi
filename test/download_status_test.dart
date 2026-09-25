import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/download_status.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';
import 'package:soiboi/base/services/wrapper_service.dart';

const _gb = 1024 * 1024 * 1024;
final _now = DateTime(2026, 9, 25, 12);

PipelineCapabilities _engine({bool ok = true}) => PipelineCapabilities(
  canDownload: ok,
  pythonVersion: '3.14.0',
  missing: ok ? const [] : const ['gamdl'],
  nativeMuxer: true,
);

/// Everything fine with cookies and AAC; each test changes one thing.
StatusInputs _inputs({
  String codec = 'aac',
  bool cookies = true,
  DateTime? expiry,
  bool wrapperSignedIn = false,
  WrapperState wrapper = const WrapperState(WrapperStage.stopped),
  PipelineCapabilities? capabilities,
  bool noEngineYet = false,
  String chosenFolder = '',
  String saveFolder = '/data/Downloads',
  int? freeBytes = 50 * _gb,
  bool? online = true,
}) => StatusInputs(
  now: _now,
  codec: codec,
  cookiesSignedIn: cookies,
  cookieExpiry: expiry,
  wrapperSignedIn: wrapperSignedIn,
  wrapper: wrapper,
  capabilities: noEngineYet ? null : (capabilities ?? _engine()),
  chosenFolder: chosenFolder,
  saveFolder: saveFolder,
  freeBytes: freeBytes,
  online: online,
);

StatusCheck _check(StatusInputs inputs, String label) =>
    buildStatusChecks(inputs).singleWhere((c) => c.label == label);

void main() {
  test('all fine: five checks, no problems or warnings', () {
    final checks = buildStatusChecks(_inputs());
    expect(checks.map((c) => c.label), [
      'Apple Music account',
      'Lossless wrapper',
      'Download engine',
      'Download folder',
      'Network',
    ]);
    expect(
      checks.where((c) => c.level == StatusLevel.problem),
      isEmpty,
      reason: '$checks',
    );
    expect(
      checks.where((c) => c.level == StatusLevel.warning),
      isEmpty,
      reason: '$checks',
    );
    // Lossless is optional here, but still offered.
    expect(checks[1].level, StatusLevel.info);
    expect(checks[1].fix, StatusFix.setUpLossless);
  });

  group('Apple Music account', () {
    const label = 'Apple Music account';

    test('not signed in is a problem with Sign in', () {
      final c = _check(_inputs(cookies: false), label);
      expect(c.level, StatusLevel.problem);
      expect(c.fix, StatusFix.signIn);
    });

    test('an expired browser session is a problem and says when', () {
      final c = _check(_inputs(expiry: DateTime(2026, 9, 20)), label);
      expect(c.level, StatusLevel.problem);
      expect(c.detail, contains('expired on 2026-09-20'));
      expect(c.fix, StatusFix.signIn);
    });

    test('a session expiring within a week is a warning', () {
      final c = _check(_inputs(expiry: DateTime(2026, 9, 28)), label);
      expect(c.level, StatusLevel.warning);
      expect(c.detail, contains('expires on 2026-09-28'));
    });

    test('a later expiry is fine and shown', () {
      final c = _check(_inputs(expiry: DateTime(2027, 1, 1)), label);
      expect(c.level, StatusLevel.ok);
      expect(c.detail, contains('2027-01-01'));
    });

    test('lossless sign-in names the account', () {
      final c = _check(
        _inputs(
          cookies: false,
          wrapperSignedIn: true,
          wrapper: const WrapperState(
            WrapperStage.ready,
            account: 'me@example.com',
          ),
        ),
        label,
      );
      expect(c.level, StatusLevel.ok);
      expect(c.detail, 'Lossless sign-in as me@example.com');
    });
  });

  group('Lossless wrapper', () {
    const label = 'Lossless wrapper';

    test('ALAC without the Apple Music file is a problem with Set up', () {
      final c = _check(
        _inputs(
          codec: 'alac',
          wrapper: const WrapperState(WrapperStage.needsLibraries),
        ),
        label,
      );
      expect(c.level, StatusLevel.problem);
      expect(c.fix, StatusFix.setUpLossless);
    });

    test(
      'with AAC and cookies, a wrapper that is not set up is only a note',
      () {
        final c = _check(
          _inputs(wrapper: const WrapperState(WrapperStage.needsLibraries)),
          label,
        );
        expect(c.level, StatusLevel.info);
        expect(c.detail, startsWith('Only needed for ALAC.'));
      },
    );

    test('a wrapper that failed to start offers Start', () {
      final c = _check(
        _inputs(
          codec: 'alac',
          wrapperSignedIn: true,
          wrapper: const WrapperState(
            WrapperStage.failed,
            message: 'port in use',
          ),
        ),
        label,
      );
      expect(c.level, StatusLevel.problem);
      expect(c.detail, 'Did not start: port in use');
      expect(c.fix, StatusFix.startWrapper);
    });

    test('it matters for AAC too when it holds the only sign-in', () {
      final c = _check(
        _inputs(
          cookies: false,
          wrapperSignedIn: true,
          wrapper: const WrapperState(WrapperStage.failed),
        ),
        label,
      );
      expect(c.level, StatusLevel.problem);
    });

    test('signed in and stopped is fine: it starts on demand', () {
      final c = _check(_inputs(codec: 'alac', wrapperSignedIn: true), label);
      expect(c.level, StatusLevel.ok);
    });

    test('a build without the wrapper says AAC still works', () {
      final c = _check(
        _inputs(
          codec: 'alac',
          wrapper: const WrapperState(WrapperStage.unsupported),
        ),
        label,
      );
      expect(c.level, StatusLevel.info);
      expect(c.detail, contains('AAC downloads work'));
    });
  });

  group('Download engine', () {
    const label = 'Download engine';

    test('missing pieces are named, with Check again', () {
      final c = _check(_inputs(capabilities: _engine(ok: false)), label);
      expect(c.level, StatusLevel.problem);
      expect(c.detail, 'Missing: gamdl');
      expect(c.fix, StatusFix.recheck);
    });

    test('still probing is not a problem yet', () {
      final c = _check(_inputs(noEngineYet: true), label);
      expect(c.level, StatusLevel.info);
    });
  });

  group('Download folder', () {
    const label = 'Download folder';

    test('an unwritable chosen folder falls back, with Choose folder', () {
      final c = _check(
        _inputs(chosenFolder: '/sdcard/Music', saveFolder: '/data/Downloads'),
        label,
      );
      expect(c.level, StatusLevel.warning);
      expect(c.detail, contains("/sdcard/Music can't be written to"));
      expect(c.fix, StatusFix.chooseFolder);
    });

    test('almost no space is a problem', () {
      final c = _check(_inputs(freeBytes: 50 * 1024 * 1024), label);
      expect(c.level, StatusLevel.problem);
      expect(c.detail, contains('only 50 MB free'));
      expect(c.fix, StatusFix.chooseFolder);
    });

    test('under a gigabyte is a warning', () {
      final c = _check(_inputs(freeBytes: 600 * 1024 * 1024), label);
      expect(c.level, StatusLevel.warning);
    });

    test('plenty of space shows how much', () {
      final c = _check(
        _inputs(chosenFolder: '/music', saveFolder: '/music'),
        label,
      );
      expect(c.level, StatusLevel.ok);
      expect(c.detail, '/music: 50.0 GB free');
    });
  });

  test('offline is a problem with Check again', () {
    final c = _check(_inputs(online: false), 'Network');
    expect(c.level, StatusLevel.problem);
    expect(c.fix, StatusFix.recheck);
  });
}
