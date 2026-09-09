/// `compareVersion` used to `int.parse` every dot-split part directly, which
/// throws on any version carrying a suffix. That was not hypothetical: the
/// app's own versionNumber went from `4.2.3` to `1.0b`, and every launch
/// after that crashed inside `Loader._handleLegacyVersionData` before the
/// window painted anything -- on screen it looked like a black window, not
/// a crash, because the exception fired ahead of `runApp`.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/utils/common_utils.dart';

void main() {
  test('a suffixed version does not throw', () {
    // The exact case that crashed every launch: '1.0b'.split('.') is
    // ['1', '0b'], and int.parse('0b') throws.
    expect(() => compareVersion('4.0.1', '1.0b'), returnsNormally);
    expect(() => compareVersion('1.0b', '4.0.1'), returnsNormally);
  });

  test('plain numeric versions still compare correctly', () {
    expect(compareVersion('4.2.0', '4.1.9'), greaterThan(0));
    expect(compareVersion('4.1.9', '4.2.0'), lessThan(0));
    expect(compareVersion('4.2.0', '4.2.0'), 0);
    // Missing components count as zero.
    expect(compareVersion('4.2', '4.2.0'), 0);
  });

  test('a suffix is ignored for the comparison, not treated as zero', () {
    // '1.0b' compares as 1.0, matching a real 1.0 release rather than
    // silently sorting as older or newer because of the trailing letter.
    expect(compareVersion('1.0b', '1.0'), 0);
    expect(compareVersion('1.0b', '1.0.1'), lessThan(0));
    expect(compareVersion('1.0b', '0.9'), greaterThan(0));
  });

  test('garbage input degrades to zero rather than throwing', () {
    expect(() => compareVersion('unknown', '4.0.1'), returnsNormally);
    expect(compareVersion('', '4.0.1'), lessThan(0));
  });
}
