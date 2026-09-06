import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/lyric.dart';

/// Regression: a track whose file carried plain, untimed lyrics rendered
/// "Lyrics parsing failed" while the words sat visibly in Song Info. Untimed
/// lyrics are the common case for embedded tags and many LRCLIB entries, and
/// showing them is far better than reporting a failure.
void main() {
  ParsedLyrics parse(List<String> lines) {
    final result = ParsedLyrics();
    applyLrcParsing(
      result,
      lines,
      noLyricsMessage: 'NO_LYRICS',
      parseFailedMessage: 'PARSE_FAILED',
    );
    return result;
  }

  test('untimed lyrics render as unsynced text, not an error', () {
    final result = parse([
      'Think I only want one number in my phone',
      'I might change your contact',
      'You said you like my eyes',
    ]);
    expect(result.lines.map((l) => l.text), isNot(contains('PARSE_FAILED')));
    expect(result.lines, hasLength(3));
    expect(result.isSynced, isFalse);
    expect(result.lines.first.text, startsWith('Think I only want'));
  });

  test('LRC metadata headers are not shown as lyrics', () {
    final result = parse(['[ar:Sabrina Carpenter]', '[by:someone]', 'Real line']);
    expect(result.lines, hasLength(1));
    expect(result.lines.single.text, 'Real line');
  });

  test('timestamped lyrics still parse as synced', () {
    final result = parse(['[00:12.34]First line', '[00:15.00]Second line']);
    expect(result.isSynced, isTrue);
    expect(result.lines, hasLength(2));
    expect(result.lines.first.start, const Duration(seconds: 12, milliseconds: 340));
  });

  test('genuinely empty input still reports no lyrics', () {
    expect(parse([]).lines.single.text, 'NO_LYRICS');
  });

  test('whitespace-only input is not mistaken for lyrics', () {
    expect(parse(['', '   ', '']).lines.single.text, anyOf('NO_LYRICS', 'PARSE_FAILED'));
  });
}
