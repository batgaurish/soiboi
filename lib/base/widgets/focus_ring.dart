/// Keyboard access for controls drawn by hand rather than by a Material
/// button, and the ring every focused control shows.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/color_manager.dart';

/// The accent, kept at 3:1 against the page: the contrast WCAG asks of a
/// focus indicator. Themed buttons draw it as an outline too.
Color focusRingColor() => ensureContrast(
  seekBarColor.value,
  pageBackgroundColor.value.withAlpha(255),
  minRatio: kLargeContrast,
);

/// Puts [child] in the Tab order: Enter or Space runs [onActivate], and a
/// 2 px ring in [focusRingColor] shows while it has keyboard focus. The ring
/// is painted just outside [child], so the layout does not move.
class FocusRing extends StatefulWidget {
  const FocusRing({
    super.key,
    required this.onActivate,
    required this.child,
    this.radius = 8,
  });

  final VoidCallback? onActivate;
  final Widget child;

  /// Corner radius of the ring.
  final double radius;

  @override
  State<FocusRing> createState() => _FocusRingState();
}

class _FocusRingState extends State<FocusRing> {
  bool _showRing = false;

  @override
  Widget build(BuildContext context) {
    return FocusableActionDetector(
      enabled: widget.onActivate != null,
      actions: {
        ActivateIntent: CallbackAction<ActivateIntent>(
          onInvoke: (_) {
            widget.onActivate?.call();
            return null;
          },
        ),
      },
      onShowFocusHighlight: (show) {
        if (show != _showRing) setState(() => _showRing = show);
      },
      child: CustomPaint(
        foregroundPainter: _showRing
            ? _RingPainter(focusRingColor(), widget.radius)
            : null,
        child: widget.child,
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  const _RingPainter(this.color, this.radius);

  final Color color;
  final double radius;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        (Offset.zero & size).inflate(3),
        Radius.circular(radius + 3),
      ),
      Paint()
        ..color = color
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2,
    );
  }

  @override
  bool shouldRepaint(_RingPainter old) =>
      old.color != color || old.radius != radius;
}
