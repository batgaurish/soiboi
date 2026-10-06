import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/app.dart';

String? _lastMessage;
DateTime? _lastShowTime;
OverlayEntry? _lastMessageOverlayEntry;

void showCenterMessage(String message, {int duration = 2000}) {
  final now = DateTime.now();
  if (_lastMessage == message &&
      _lastShowTime != null &&
      now.difference(_lastShowTime!) < const Duration(seconds: 2)) {
    return;
  }
  _lastMessage = message;
  _lastShowTime = now;

  final overlay = globalNavigatorKey.currentState?.overlay;
  if (overlay == null) return;

  // A new message replaces the one on screen.
  _lastMessageOverlayEntry?.remove();
  _lastMessageOverlayEntry = null;

  late final OverlayEntry overlayEntry;
  overlayEntry = OverlayEntry(
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
  _lastMessageOverlayEntry = overlayEntry;

  overlay.insert(overlayEntry);

  Future.delayed(Duration(milliseconds: duration), () {
    // Only if a newer message has not already replaced (and removed) it.
    if (_lastMessageOverlayEntry == overlayEntry) {
      overlayEntry.remove();
      _lastMessageOverlayEntry = null;
    }
  });
}

OverlayEntry? _centerOverlayEntry;

void showCenterLoading({Color? color}) {
  final overlay = globalNavigatorKey.currentState?.overlay;
  if (overlay == null) return;

  removeCenterLoading();

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

/// Takes the loading barrier down. Not guarded on `OverlayEntry.mounted`:
/// that turns true only once the entry has been built, so a load that
/// finishes within the frame it started in would leave the barrier up and
/// swallow every tap. The entry is tracked here instead, so it is removed
/// exactly once.
void removeCenterLoading() {
  final entry = _centerOverlayEntry;
  _centerOverlayEntry = null;
  entry?.remove();
}

ValueNotifier<bool> vibrationOnNoitifier = ValueNotifier(true);
void tryVibrate() {
  if (vibrationOnNoitifier.value) {
    HapticFeedback.heavyImpact();
  }
}
