import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/app.dart';

DateTime? _lastShowTime;

void showCenterMessage(String message, {int duration = 2000}) {
  final now = DateTime.now();
  if (_lastShowTime != null &&
      now.difference(_lastShowTime!) < const Duration(seconds: 2)) {
    return;
  }
  _lastShowTime = now;

  final overlay = globalNavigatorKey.currentState?.overlay;
  if (overlay == null) return;
  final overlayEntry = OverlayEntry(
    builder: (context) => Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 300),
        child: Material(
          color: Colors.black,
          shape: SmoothRectangleBorder(
            smoothness: 1,
            borderRadius: BorderRadius.circular(8),
          ),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Text(
              message,
              style: const TextStyle(color: Colors.white, fontSize: 16),
            ),
          ),
        ),
      ),
    ),
  );

  overlay.insert(overlayEntry);

  Future.delayed(Duration(milliseconds: duration), () {
    overlayEntry.remove();
  });
}

OverlayEntry? _centerOverlayEntry;

Future<void> showCenterLoading({Color? color}) async {
  final overlay = globalNavigatorKey.currentState?.overlay;
  if (overlay == null) return;
  _centerOverlayEntry = OverlayEntry(
    builder: (context) => Stack(
      children: [
        const ModalBarrier(dismissible: false, color: Colors.transparent),
        Center(
          child: CircularProgressIndicator(
            color: color ?? Theme.of(context).colorScheme.primary,
          ),
        ),
      ],
    ),
  );

  overlay.insert(_centerOverlayEntry!);
}

void removeCenterLoading() {
  _centerOverlayEntry?.remove();
  _centerOverlayEntry = null;
}

ValueNotifier<bool> vibrationOnNoitifier = ValueNotifier(true);
void tryVibrate() {
  if (vibrationOnNoitifier.value) {
    HapticFeedback.heavyImpact();
  }
}
