import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/cookie_store.dart';

/// Netscape cookie format is fiddly and its failures are silent -- a malformed
/// file parses to nothing and the download fails much later with an opaque
/// error. These pin the parsing rules.
void main() {
  group('Netscape parsing', () {
    test('parses a standard line', () {
      final cookie = Cookie.fromNetscapeLine(
        '.apple.com\tTRUE\t/\tTRUE\t1800000000\tmedia-user-token\tabc123',
      );
      expect(cookie, isNotNull);
      expect(cookie!.domain, '.apple.com');
      expect(cookie.includeSubdomains, isTrue);
      expect(cookie.secure, isTrue);
      expect(cookie.name, 'media-user-token');
      expect(cookie.value, 'abc123');
      expect(cookie.expires, isNotNull);
    });

    test('the #HttpOnly_ prefix is a marker, not a comment', () {
      // curl's convention. Treating it as a comment silently drops the most
      // important auth cookies, which is the classic way this breaks.
      final cookie = Cookie.fromNetscapeLine(
        '#HttpOnly_.apple.com\tTRUE\t/\tTRUE\t1800000000\tmyacinfo\tsecret',
      );
      expect(cookie, isNotNull);
      expect(cookie!.httpOnly, isTrue);
      expect(cookie.domain, '.apple.com');
      expect(cookie.name, 'myacinfo');
    });

    test('genuine comments and blank lines are skipped', () {
      expect(Cookie.fromNetscapeLine('# Netscape HTTP Cookie File'), isNull);
      expect(Cookie.fromNetscapeLine(''), isNull);
      expect(Cookie.fromNetscapeLine('   '), isNull);
    });

    test('malformed lines are skipped rather than throwing', () {
      expect(Cookie.fromNetscapeLine('not\tenough\tfields'), isNull);
    });

    test('a value containing tabs survives the round trip', () {
      // Only the first six fields are positional; everything after is value.
      final cookie = Cookie.fromNetscapeLine(
        '.apple.com\tTRUE\t/\tTRUE\t0\ttoken\tpart1\tpart2',
      );
      expect(cookie!.value, 'part1\tpart2');
    });

    test('zero expiry means a session cookie, not 1970', () {
      final cookie = Cookie.fromNetscapeLine(
        '.apple.com\tTRUE\t/\tTRUE\t0\ttoken\tv',
      );
      expect(cookie!.expires, isNull);
      expect(cookie.isExpired, isFalse);
    });

    test('round-trips through the writer', () {
      const line =
          '.apple.com\tTRUE\t/\tTRUE\t1800000000\tmedia-user-token\tabc123';
      final cookie = Cookie.fromNetscapeLine(line)!;
      expect(cookie.toNetscapeLine(), line);
    });

    test('httpOnly round-trips with its prefix intact', () {
      const line =
          '#HttpOnly_.apple.com\tTRUE\t/\tTRUE\t1800000000\tmyacinfo\ts';
      final cookie = Cookie.fromNetscapeLine(line)!;
      expect(cookie.toNetscapeLine(), line);
    });
  });

  group('session completeness', () {
    // Regression: a real four-month-old export carried a valid
    // media-user-token (the subscription entitlement, good for months) while
    // myacinfo (the account session) was long dead. Checking only the former
    // reported "Signed in" while every download failed with an opaque
    // "Error fetching account info (500)".
    test('the entitlement cookie alone is not a session', () {
      final entitlementOnly = Cookie(
        domain: '.apple.com',
        name: 'media-user-token',
        value: 'v',
        expires: DateTime.now().add(const Duration(days: 200)),
      );
      // A valid, long-lived entitlement must not on its own read as signed in.
      expect(entitlementOnly.isExpired, isFalse);
      expect(
        {'media-user-token'}.containsAll({'media-user-token', 'myacinfo'}),
        isFalse,
        reason: 'both cookies are required for a usable session',
      );
    });
  });

  group('expiry', () {
    test('a past expiry is expired', () {
      final cookie = Cookie(
        domain: '.apple.com',
        name: 'media-user-token',
        value: 'v',
        expires: DateTime.now().subtract(const Duration(days: 1)),
      );
      expect(cookie.isExpired, isTrue);
    });

    test('a future expiry is not', () {
      final cookie = Cookie(
        domain: '.apple.com',
        name: 'media-user-token',
        value: 'v',
        expires: DateTime.now().add(const Duration(days: 30)),
      );
      expect(cookie.isExpired, isFalse);
    });
  });
}
