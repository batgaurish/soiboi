/// One line of text that scrolls sideways when it is too long to fit, or,
/// with motion reduced, simply ends in an ellipsis.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/theme/motion.dart';
import 'package:text_scroll/text_scroll.dart';

class MarqueeText extends StatelessWidget {
  const MarqueeText(
    this.text, {
    super.key,
    this.style,
    this.textAlign,
    this.velocity = const Velocity(pixelsPerSecond: Offset(40, 0)),
    this.intervalSpaces = 10,
    this.pauseBetween = const Duration(seconds: 2),
  });

  final String text;
  final TextStyle? style;
  final TextAlign? textAlign;
  final Velocity velocity;
  final int intervalSpaces;
  final Duration pauseBetween;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: reduceMotionNotifier,
      builder: (context, reduce, _) => reduce
          ? Text(
              text,
              style: style,
              textAlign: textAlign,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            )
          : TextScroll(
              text,
              style: style,
              textAlign: textAlign,
              velocity: velocity,
              intervalSpaces: intervalSpaces,
              pauseBetween: pauseBetween,
            ),
    );
  }
}
