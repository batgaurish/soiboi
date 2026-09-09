/// Just enough Markdown to render a GitHub release body.
///
/// The update sheet used to print `release.notes` into a plain [Text], so
/// every `**bold**` and `## heading` showed up as literal punctuation, and the
/// hard wrapping GitHub bodies are written with turned into ragged short lines
/// — the notes were legible only if you mentally stripped the syntax.
///
/// A full CommonMark implementation is not worth a dependency here. Release
/// bodies use a small, stable subset — headings, bold, italics, inline code,
/// fenced code, bullet and numbered lists, block quotes, rules and links — so
/// that subset is what this handles. Anything it does not recognise falls
/// through as literal text, which is exactly the old behaviour: unknown syntax
/// can look wrong, never blank.
///
/// Parsing is kept as pure functions over strings, with no Flutter types, so
/// the awkward parts are unit-testable without pumping a widget.
library;

import 'package:material_ui/material_ui.dart';

// ---------------------------------------------------------------------------
// Inline
// ---------------------------------------------------------------------------

/// A run of text sharing one set of marks.
class InlineSegment {
  const InlineSegment(
    this.text, {
    this.bold = false,
    this.italic = false,
    this.code = false,
    this.link,
  });

  final String text;
  final bool bold;
  final bool italic;
  final bool code;

  /// Destination when this run came from `[label](url)`, else null.
  final String? link;

  @override
  bool operator ==(Object other) =>
      other is InlineSegment &&
      other.text == text &&
      other.bold == bold &&
      other.italic == italic &&
      other.code == code &&
      other.link == link;

  @override
  int get hashCode => Object.hash(text, bold, italic, code, link);

  @override
  String toString() {
    final marks = [
      if (bold) 'b',
      if (italic) 'i',
      if (code) 'code',
      if (link != null) 'link:$link',
    ];
    return 'InlineSegment(${jsonish(text)}${marks.isEmpty ? '' : ' ${marks.join(",")}'})';
  }

  static String jsonish(String s) => '"${s.replaceAll('\n', r'\n')}"';
}

/// Splits [line] into formatted runs.
///
/// Code spans are matched before anything else, so `` `a**b**c` `` stays
/// literal — that is what backticks are for, and release notes lean on them
/// for things like `lua/ytdl_hook` that are full of punctuation.
///
/// A marker with no partner is not an error: `2 * 3 * 4` and a stray `_` are
/// far more common in prose than a typo'd emphasis, so an unclosed marker is
/// emitted as the character it is.
List<InlineSegment> parseInline(String line) {
  final out = <InlineSegment>[];
  final buffer = StringBuffer();

  void flush() {
    if (buffer.isEmpty) return;
    out.add(InlineSegment(buffer.toString()));
    buffer.clear();
  }

  var i = 0;
  while (i < line.length) {
    final rest = line.substring(i);

    // `code`
    if (rest.startsWith('`')) {
      final end = line.indexOf('`', i + 1);
      if (end > i + 1) {
        flush();
        out.add(InlineSegment(line.substring(i + 1, end), code: true));
        i = end + 1;
        continue;
      }
    }

    // [label](url)
    if (rest.startsWith('[')) {
      final close = line.indexOf(']', i + 1);
      if (close > i && close + 1 < line.length && line[close + 1] == '(') {
        final urlEnd = line.indexOf(')', close + 2);
        if (urlEnd > close + 1) {
          flush();
          out.add(
            InlineSegment(
              line.substring(i + 1, close),
              link: line.substring(close + 2, urlEnd),
            ),
          );
          i = urlEnd + 1;
          continue;
        }
      }
    }

    // **bold** / __bold__, then *italic* / _italic_. Longest marker first, so
    // `**x**` is one bold run rather than two empty italics.
    var matched = false;
    for (final (marker, bold) in const [
      ('**', true),
      ('__', true),
      ('*', false),
      ('_', false),
    ]) {
      if (!rest.startsWith(marker)) continue;
      final from = i + marker.length;
      // A marker with no partner is not an error: `2 * 3 * 4` and a stray `_`
      // are far more common in prose than a typo'd emphasis, so an unclosed
      // marker falls through and is emitted as the character it is.
      final end = from < line.length ? line.indexOf(marker, from) : -1;
      if (end > from) {
        flush();
        out.add(
          InlineSegment(line.substring(from, end), bold: bold, italic: !bold),
        );
        i = end + marker.length;
        matched = true;
      }
      break;
    }
    if (matched) continue;

    buffer.write(line[i]);
    i++;
  }

  flush();
  return out;
}

// ---------------------------------------------------------------------------
// Block
// ---------------------------------------------------------------------------

enum BlockKind { paragraph, heading, bullet, numbered, code, quote, rule }

class MarkdownBlock {
  const MarkdownBlock(this.kind, this.text, {this.level = 0, this.marker});

  final BlockKind kind;

  /// Raw text, still carrying inline markers — except [BlockKind.code], which
  /// is verbatim by definition.
  final String text;

  /// Heading depth, 1-6. Zero for everything else.
  final int level;

  /// The rendered bullet or number for list items.
  final String? marker;

  @override
  String toString() => 'MarkdownBlock($kind, ${InlineSegment.jsonish(text)}'
      '${level > 0 ? ', level: $level' : ''})';
}

/// Groups [markdown] into blocks.
///
/// Soft line breaks inside a paragraph are joined with a space, which is what
/// Markdown means by them and the thing that most improves these notes: GitHub
/// bodies are hard-wrapped at ~80 columns for the web view, and rendering each
/// line as its own line wrapped them twice into a ragged column.
List<MarkdownBlock> parseMarkdownBlocks(String markdown) {
  final blocks = <MarkdownBlock>[];
  final lines = markdown.replaceAll('\r\n', '\n').split('\n');
  final paragraph = <String>[];

  void flushParagraph() {
    if (paragraph.isEmpty) return;
    blocks.add(MarkdownBlock(BlockKind.paragraph, paragraph.join(' ').trim()));
    paragraph.clear();
  }

  var i = 0;
  while (i < lines.length) {
    final raw = lines[i];
    final line = raw.trimRight();
    final trimmed = line.trimLeft();

    // Fenced code: verbatim until the closing fence, or the end of the notes
    // if the body forgot one.
    if (trimmed.startsWith('```')) {
      flushParagraph();
      final body = <String>[];
      i++;
      while (i < lines.length && !lines[i].trimLeft().startsWith('```')) {
        body.add(lines[i]);
        i++;
      }
      i++; // closing fence
      blocks.add(MarkdownBlock(BlockKind.code, body.join('\n')));
      continue;
    }

    if (trimmed.isEmpty) {
      flushParagraph();
      i++;
      continue;
    }

    if (RegExp(r'^(-{3,}|\*{3,}|_{3,})$').hasMatch(trimmed)) {
      flushParagraph();
      blocks.add(const MarkdownBlock(BlockKind.rule, ''));
      i++;
      continue;
    }

    final heading = RegExp(r'^(#{1,6})\s+(.*)$').firstMatch(trimmed);
    if (heading != null) {
      flushParagraph();
      blocks.add(
        MarkdownBlock(
          BlockKind.heading,
          heading.group(2)!.trim(),
          level: heading.group(1)!.length,
        ),
      );
      i++;
      continue;
    }

    final bullet = RegExp(r'^[-*+]\s+(.*)$').firstMatch(trimmed);
    if (bullet != null) {
      flushParagraph();
      blocks.add(
        MarkdownBlock(BlockKind.bullet, bullet.group(1)!.trim(), marker: '•'),
      );
      i++;
      continue;
    }

    final numbered = RegExp(r'^(\d+)[.)]\s+(.*)$').firstMatch(trimmed);
    if (numbered != null) {
      flushParagraph();
      blocks.add(
        MarkdownBlock(
          BlockKind.numbered,
          numbered.group(2)!.trim(),
          marker: '${numbered.group(1)}.',
        ),
      );
      i++;
      continue;
    }

    if (trimmed.startsWith('>')) {
      flushParagraph();
      blocks.add(
        MarkdownBlock(BlockKind.quote, trimmed.substring(1).trim()),
      );
      i++;
      continue;
    }

    paragraph.add(trimmed);
    i++;
  }

  flushParagraph();
  return blocks;
}

// ---------------------------------------------------------------------------
// Widget
// ---------------------------------------------------------------------------

/// Renders [notes] as the update sheet shows them.
class ReleaseNotesView extends StatelessWidget {
  const ReleaseNotesView(
    this.notes, {
    super.key,
    required this.textColor,
    required this.headingColor,
    this.fontSize = 12.5,
  });

  final String notes;
  final Color textColor;
  final Color headingColor;
  final double fontSize;

  @override
  Widget build(BuildContext context) {
    final blocks = parseMarkdownBlocks(notes);
    final base = TextStyle(fontSize: fontSize, color: textColor, height: 1.45);

    List<TextSpan> spans(String text, {TextStyle? style}) => [
      for (final seg in parseInline(text))
        TextSpan(
          text: seg.text,
          style: (style ?? base).copyWith(
            fontWeight: seg.bold ? FontWeight.w700 : null,
            fontStyle: seg.italic ? FontStyle.italic : null,
            fontFamily: seg.code ? 'monospace' : null,
            color: seg.link != null ? headingColor : null,
            decoration: seg.link != null ? TextDecoration.underline : null,
          ),
        ),
    ];

    final children = <Widget>[];
    for (final block in blocks) {
      switch (block.kind) {
        case BlockKind.heading:
          children.add(
            Padding(
              // A heading needs air above it, but not when it opens the notes.
              padding: EdgeInsets.only(
                top: children.isEmpty ? 0 : 14,
                bottom: 4,
              ),
              child: RichText(
                text: TextSpan(
                  children: spans(
                    block.text,
                    style: base.copyWith(
                      // h1 and h2 are the same size: release bodies use them
                      // interchangeably for the one level of structure they
                      // actually have.
                      fontSize: fontSize + (block.level <= 2 ? 3 : 1.5),
                      fontWeight: FontWeight.w700,
                      color: headingColor,
                    ),
                  ),
                ),
              ),
            ),
          );
        case BlockKind.paragraph:
          children.add(
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: RichText(text: TextSpan(children: spans(block.text))),
            ),
          );
        case BlockKind.bullet:
        case BlockKind.numbered:
          children.add(
            Padding(
              padding: const EdgeInsets.only(bottom: 5, left: 2),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 18,
                    child: Text(block.marker ?? '•', style: base),
                  ),
                  Expanded(
                    child: RichText(
                      text: TextSpan(children: spans(block.text)),
                    ),
                  ),
                ],
              ),
            ),
          );
        case BlockKind.code:
          children.add(
            Container(
              width: double.infinity,
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: textColor.withValues(alpha: 0.10),
                borderRadius: BorderRadius.circular(6),
              ),
              // Code must not be re-wrapped, so it scrolls instead.
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Text(
                  block.text,
                  style: base.copyWith(fontFamily: 'monospace'),
                ),
              ),
            ),
          );
        case BlockKind.quote:
          children.add(
            Container(
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.only(left: 10),
              decoration: BoxDecoration(
                border: Border(
                  left: BorderSide(
                    color: textColor.withValues(alpha: 0.35),
                    width: 3,
                  ),
                ),
              ),
              child: RichText(
                text: TextSpan(
                  children: spans(
                    block.text,
                    style: base.copyWith(fontStyle: FontStyle.italic),
                  ),
                ),
              ),
            ),
          );
        case BlockKind.rule:
          children.add(
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Divider(
                height: 1,
                color: textColor.withValues(alpha: 0.25),
              ),
            ),
          );
      }
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: children,
    );
  }
}
