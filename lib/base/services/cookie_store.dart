/// Apple Music credentials, stored on-device.
///
/// The downloader needs cookies from a signed-in Apple Music session. They
/// arrive by two routes, both ending in the same Netscape-format file that
/// gamdl reads:
///
///   * **Android** harvests them from an in-app WebView after the user signs in
///     on Apple's own page. Apple handles the password, 2FA and passkeys; the
///     app never sees a credential, only the resulting session cookies. That is
///     a materially smaller thing to ask someone to trust than a password box.
///   * **Desktop** imports a cookies.txt exported from a browser, since no
///     maintained Flutter WebView supports Linux.
///
/// Cookies live in the app's private directory, never the music library, so
/// they are not swept up by a folder sync or a library backup.
library;

import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:path/path.dart' as p;
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/logger.dart';

/// Cookies Apple Music actually needs, and why both matter.
///
/// `media-user-token` is the Apple Music *subscription* entitlement and is
/// long-lived — typically months. `myacinfo` is the Apple ID *account* session
/// and is usually a session cookie, so it dies when the browser session that
/// produced it ends.
///
/// Checking only the first is a trap worth avoiding: a months-old export will
/// still carry a valid media-user-token while myacinfo is long dead, so the app
/// reports "Signed in" and every download fails with an opaque "Error fetching
/// account info (500)". Observed on a real four-month-old export.
const _requiredCookies = {'media-user-token', 'myacinfo'};

/// Domains worth keeping. A browser export contains cookies for everything the
/// user has ever visited, and shipping unrelated sites' cookies into a file the
/// downloader reads would be careless.
const _relevantDomains = {
  'apple.com',
  '.apple.com',
  'music.apple.com',
  '.music.apple.com',
  'itunes.apple.com',
  '.itunes.apple.com',
};

class Cookie {
  Cookie({
    required this.domain,
    required this.name,
    required this.value,
    this.path = '/',
    this.secure = true,
    this.expires,
    this.httpOnly = false,
    this.includeSubdomains = true,
  });

  final String domain;
  final String name;
  final String value;
  final String path;
  final bool secure;
  final DateTime? expires;
  final bool httpOnly;
  final bool includeSubdomains;

  /// One Netscape cookie-file line.
  ///
  /// The `#HttpOnly_` prefix is a curl convention that the format's own comment
  /// syntax would otherwise swallow -- readers strip it rather than treating
  /// the line as a comment.
  String toNetscapeLine() {
    final prefix = httpOnly ? '#HttpOnly_' : '';
    final epoch = expires == null
        ? 0
        : expires!.millisecondsSinceEpoch ~/ 1000;
    return [
      '$prefix$domain',
      includeSubdomains ? 'TRUE' : 'FALSE',
      path,
      secure ? 'TRUE' : 'FALSE',
      '$epoch',
      name,
      value,
    ].join('\t');
  }

  static Cookie? fromNetscapeLine(String line) {
    var raw = line.trimRight();
    if (raw.isEmpty) return null;

    var httpOnly = false;
    if (raw.startsWith('#HttpOnly_')) {
      httpOnly = true;
      raw = raw.substring('#HttpOnly_'.length);
    } else if (raw.startsWith('#')) {
      return null; // genuine comment
    }

    final parts = raw.split('\t');
    if (parts.length < 7) return null;

    final epoch = int.tryParse(parts[4]) ?? 0;
    return Cookie(
      domain: parts[0],
      includeSubdomains: parts[1].toUpperCase() == 'TRUE',
      path: parts[2],
      secure: parts[3].toUpperCase() == 'TRUE',
      expires: epoch > 0
          ? DateTime.fromMillisecondsSinceEpoch(epoch * 1000)
          : null,
      name: parts[5],
      value: parts.sublist(6).join('\t'),
      httpOnly: httpOnly,
    );
  }

  bool get isExpired =>
      expires != null && expires!.isBefore(DateTime.now());
}

/// Whether a usable Apple Music session is stored.
final signedInNotifier = ValueNotifier<bool>(false);

/// When the session's earliest important cookie expires, if known.
final sessionExpiryNotifier = ValueNotifier<DateTime?>(null);

String get cookiesPath => p.join(appSupportDir.path, 'apple_music_cookies.txt');

bool _isRelevant(Cookie cookie) {
  final domain = cookie.domain.toLowerCase();
  return _relevantDomains.any(
    (allowed) => domain == allowed || domain.endsWith(allowed),
  );
}

/// Writes [cookies] in Netscape format. Returns false when the set lacks what
/// Apple Music needs, rather than writing a file that will fail later.
Future<bool> saveCookies(List<Cookie> cookies) async {
  final relevant = cookies.where(_isRelevant).toList();
  final names = relevant.map((c) => c.name).toSet();
  if (!_requiredCookies.every(names.contains)) {
    logger.output(
      'cookies: rejected, missing ${_requiredCookies.difference(names)}',
    );
    return false;
  }

  final buffer = StringBuffer()
    ..writeln('# Netscape HTTP Cookie File')
    ..writeln('# Written by Soiboi. Do not edit.');
  for (final cookie in relevant) {
    buffer.writeln(cookie.toNetscapeLine());
  }

  try {
    final file = File(cookiesPath);
    await file.parent.create(recursive: true);
    await file.writeAsString(buffer.toString());
    // Owner-only: this file grants access to the account.
    if (!Platform.isWindows) {
      await Process.run('chmod', ['600', cookiesPath]);
    }
  } catch (e) {
    logger.output('cookies: write failed: $e');
    return false;
  }

  await refreshSessionState();
  return signedInNotifier.value;
}

/// Imports a browser-exported cookies.txt. Filters to Apple domains, so an
/// unrelated site's cookies in the export are dropped rather than copied.
Future<bool> importCookieFile(String sourcePath) async {
  try {
    final lines = await File(sourcePath).readAsLines();
    final cookies = lines
        .map(Cookie.fromNetscapeLine)
        .whereType<Cookie>()
        .toList();
    return await saveCookies(cookies);
  } catch (e) {
    logger.output('cookies: import failed: $e');
    return false;
  }
}

Future<List<Cookie>> loadCookies() async {
  final file = File(cookiesPath);
  if (!await file.exists()) return const [];
  try {
    final lines = await file.readAsLines();
    return lines.map(Cookie.fromNetscapeLine).whereType<Cookie>().toList();
  } catch (e) {
    logger.output('cookies: read failed: $e');
    return const [];
  }
}

/// Re-reads the stored session and updates the notifiers.
///
/// A present file is not the same as a valid session. Every required cookie
/// must be present and unexpired, so a stale export prompts a fresh sign-in
/// instead of letting downloads fail with an opaque server error.
Future<void> refreshSessionState() async {
  final cookies = await loadCookies();
  final required = cookies
      .where((c) => _requiredCookies.contains(c.name))
      .toList();

  final present = required.map((c) => c.name).toSet();
  final complete = _requiredCookies.every(present.contains);
  final valid = complete && !required.any((c) => c.isExpired);
  signedInNotifier.value = valid;

  DateTime? soonest;
  for (final cookie in required) {
    final expires = cookie.expires;
    if (expires == null) continue;
    if (soonest == null || expires.isBefore(soonest)) soonest = expires;
  }
  sessionExpiryNotifier.value = soonest;
}

Future<void> signOut() async {
  try {
    final file = File(cookiesPath);
    if (await file.exists()) await file.delete();
  } catch (e) {
    logger.output('cookies: delete failed: $e');
  }
  signedInNotifier.value = false;
  sessionExpiryNotifier.value = null;
}
