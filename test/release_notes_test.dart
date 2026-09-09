/// The update sheet printed release bodies into a plain Text, so `**bold**`
/// and `## heading` reached the user as literal punctuation and GitHub's hard
/// wrapping came out as a ragged column. These cover the parsing behind the
/// fix — the marker handling and the line joining, which are where a small
/// Markdown reader actually goes wrong.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/widgets/release_notes.dart';

void main() {
  group('inline', () {
    test('bold, italic and code become marked runs', () {
      expect(parseInline('a **b** c'), const [
        InlineSegment('a '),
        InlineSegment('b', bold: true),
        InlineSegment(' c'),
      ]);
      expect(parseInline('_soft_'), const [InlineSegment('soft', italic: true)]);
      expect(parseInline('use `git push`'), const [
        InlineSegment('use '),
        InlineSegment('git push', code: true),
      ]);
    });

    test('code spans win over the markers inside them', () {
      // Release notes lean on backticks for punctuation-heavy names; treating
      // the contents as emphasis would mangle exactly those.
      expect(parseInline('`lua/**hook**`'), const [
        InlineSegment('lua/**hook**', code: true),
      ]);
    });

    test('links keep their label and destination', () {
      expect(parseInline('see [the notes](https://x.dev/a)'), const [
        InlineSegment('see '),
        InlineSegment('the notes', link: 'https://x.dev/a'),
      ]);
    });

    test('an unpaired marker stays literal', () {
      // Prose has far more stray asterisks and underscores than typo'd
      // emphasis, so dropping them would corrupt ordinary text.
      expect(parseInline('2 * 3 = 6'), const [InlineSegment('2 * 3 = 6')]);
      expect(parseInline('a _ b'), const [InlineSegment('a _ b')]);
      expect(parseInline('**unclosed'), const [InlineSegment('**unclosed')]);
    });

    test('bold is not mistaken for two italics', () {
      expect(parseInline('**x**'), const [InlineSegment('x', bold: true)]);
    });
  });

  group('blocks', () {
    test('hard-wrapped lines join into one paragraph', () {
      // The whole reason the old rendering looked ragged: GitHub bodies wrap
      // at ~80 columns, and the sheet then wrapped them again.
      final blocks = parseMarkdownBlocks(
        'Every previous build segfaulted,\nbefore the window was usable.',
      );
      expect(blocks.length, 1);
      expect(blocks.single.kind, BlockKind.paragraph);
      expect(
        blocks.single.text,
        'Every previous build segfaulted, before the window was usable.',
      );
    });

    test('a blank line starts a new paragraph', () {
      final blocks = parseMarkdownBlocks('one\n\ntwo');
      expect(blocks.map((b) => b.text), ['one', 'two']);
    });

    test('headings carry their depth and drop the hashes', () {
      final blocks = parseMarkdownBlocks('# Big\n## The fix\n#### Small');
      expect(blocks.map((b) => b.kind), everyElement(BlockKind.heading));
      expect(blocks.map((b) => b.text), ['Big', 'The fix', 'Small']);
      expect(blocks.map((b) => b.level), [1, 2, 4]);
    });

    test('lists keep their markers', () {
      final blocks = parseMarkdownBlocks('- one\n* two\n1. three\n2) four');
      expect(blocks.map((b) => b.kind), [
        BlockKind.bullet,
        BlockKind.bullet,
        BlockKind.numbered,
        BlockKind.numbered,
      ]);
      expect(blocks.map((b) => b.text), ['one', 'two', 'three', 'four']);
      expect(blocks.map((b) => b.marker), ['•', '•', '1.', '2.']);
    });

    test('fenced code is kept verbatim', () {
      final blocks = parseMarkdownBlocks('```\na **b**\n  indented\n```');
      expect(blocks.single.kind, BlockKind.code);
      expect(blocks.single.text, 'a **b**\n  indented');
    });

    test('an unclosed fence runs to the end rather than swallowing nothing', () {
      final blocks = parseMarkdownBlocks('```\nstill code');
      expect(blocks.single.kind, BlockKind.code);
      expect(blocks.single.text, 'still code');
    });

    test('rules and quotes are recognised', () {
      final blocks = parseMarkdownBlocks('---\n> quoted');
      expect(blocks.map((b) => b.kind), [BlockKind.rule, BlockKind.quote]);
      expect(blocks.last.text, 'quoted');
    });
  });

  test('the real v4.2.1 body renders as structure, not punctuation', () {
    // Trimmed from the release the update check actually returns.
    const body = '''
**The Linux build works.** That is the whole point of this release.

## The fix

Every previous build segfaulted a few seconds after startup on Linux,
before the window was usable.

The cause is libmpv's built-in Lua scripts — `lua/ytdl_hook` and
`lua/stats` were both seen.
''';
    final blocks = parseMarkdownBlocks(body);

    expect(blocks.any((b) => b.kind == BlockKind.heading), isTrue);
    // No block still carries raw syntax the user would have to read past.
    for (final b in blocks.where((b) => b.kind != BlockKind.code)) {
      expect(b.text, isNot(startsWith('#')));
    }
    final first = parseInline(blocks.first.text);
    expect(first.first.bold, isTrue);
    expect(first.first.text, 'The Linux build works.');

    // The paragraph that was hard-wrapped is one line again.
    expect(
      blocks.any(
        (b) =>
            b.kind == BlockKind.paragraph &&
            b.text.contains('startup on Linux, before the window'),
      ),
      isTrue,
    );
  });
}
