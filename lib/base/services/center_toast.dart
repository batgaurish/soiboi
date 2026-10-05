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

  if (_lastMessageOverlayEntry?.mounted ?? false) {
    _lastMessageOverlayEntry?.remove();
  }
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
    if (overlayEntry.mounted) {
      overlayEntry.remove();
    }
    if (_lastMessageOverlayEntry == overlayEntry) {
      _lastMessageOverlayEntry = null;
    }
  });
}

OverlayEntry? _centerOverlayEntry;

void showCenterLoading({Color? color}) {
  final overlay = globalNavigatorKey.currentState?.overlay;
  if (overlay == null) return;

  if (_centerOverlayEntry?.mounted ?? false) {
    _centerOverlayEntry?.remove();
  }
  _centerOverlayEntry = null;

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
  if (_centerOverlayEntry?.mounted ?? false) {
    _centerOverlayEntry?.remove();
  }
  _centerOverlayEntry = null;
}

ValueNotifier<bool> vibrationOnNoitifier = ValueNotifier(true);
void tryVibrate() {
  if (vibrationOnNoitifier.value) {
    HapticFeedback.heavyImpact();
  }
}
