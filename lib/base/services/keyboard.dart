/// App-wide keyboard shortcuts.
///
/// Handled ahead of the focus system, so each shortcut first checks it would
/// not take a key from something that needs it: a text field being typed
/// in, a focused button (Space presses it), or a dialog, sheet or menu,
/// which arrows move through. The TV layout keeps its arrows for moving
/// focus, and a focused slider keeps them for itself. Pressing `?` shows
/// the full list ([shortcutList]).
library;

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/my_window_listener.dart';
import 'package:soiboi/base/utils/dynamic_lyrics_page_route.dart';
import 'package:soiboi/layer/global_search_layer.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/layer/lyrics_page_layer.dart';
import 'package:window_manager/window_manager.dart';

bool isTyping = false;

bool shiftIsPressed = false;
bool ctrlIsPressed = false;

/// Every shortcut, as the `?` list shows it.
const shortcutList = <(String, String)>[
  ('Space', 'Play or pause'),
  ('← / →', 'Back or forward 5 seconds'),
  ('Shift + ← / →', 'Previous or next song'),
  ('↑ / ↓', 'Volume up or down'),
  ('Ctrl + F  or  /', 'Search'),
  ('Ctrl + L', 'Open or close the lyrics'),
  ('Ctrl + D', 'Downloads'),
  ('Tab / Shift + Tab', 'Move between controls'),
  ('Enter', 'Press the focused control, or play the focused song'),
  ('Menu  or  Shift + F10', 'Options for the focused song'),
  ('Esc', 'Close the lyrics, a dialog or a menu'),
  ('F11', 'Full screen lyrics (desktop)'),
  ('?', 'This list'),
];

/// Whether a text field has the keyboard.
bool get _typing {
  if (isTyping) return true;
  final context = FocusManager.instance.primaryFocus?.context;
  return context != null &&
      (context.widget is EditableText ||
          context.findAncestorWidgetOfExactType<EditableText>() != null);
}

/// Whether the keyboard is on a control that Space or Enter presses.
bool get _onControl {
  final context = FocusManager.instance.primaryFocus?.context;
  return context != null && Actions.maybeFind<ActivateIntent>(context) != null;
}

/// Whether focus is inside a dialog, sheet or menu, which own their keys:
/// a popup route, or any route a tap outside (or Esc) dismisses, like the
/// context menus.
bool get _inPopup {
  final context = FocusManager.instance.primaryFocus?.context;
  final route = context == null ? null : ModalRoute.of(context);
  return route is PopupRoute || (route?.barrierDismissible ?? false);
}

/// Whether a slider (equalizer, font weight) has the keyboard: its arrows
/// move it, not the song or the volume.
bool get _onSlider {
  final context = FocusManager.instance.primaryFocus?.context;
  return context != null &&
      (context.findAncestorWidgetOfExactType<Slider>() != null ||
          context.findAncestorWidgetOfExactType<RangeSlider>() != null);
}

/// Whether the player shortcuts (arrows) apply at all right now.
bool get _playerKeys =>
    !_typing &&
    !_inPopup &&
    !_onSlider &&
    !isTV &&
    viewModeNotifier.value != .bigPicture;

void keyboardInit() {
  HardwareKeyboard.instance
    ..removeHandler(_handle)
    ..addHandler(_handle);
}

bool _handle(KeyEvent event) {
  if (event is KeyUpEvent) {
    switch (event.logicalKey) {
      case LogicalKeyboardKey.shiftLeft:
      case LogicalKeyboardKey.shiftRight:
        shiftIsPressed = false;
      case LogicalKeyboardKey.controlLeft:
      case LogicalKeyboardKey.controlRight:
        ctrlIsPressed = false;
    }
    return false;
  }

  final repeat = event is KeyRepeatEvent;
  if (event is! KeyDownEvent && !repeat) return false;
  final keyboard = HardwareKeyboard.instance;
  final key = event.logicalKey;

  switch (key) {
    case LogicalKeyboardKey.shiftLeft:
    case LogicalKeyboardKey.shiftRight:
      shiftIsPressed = true;
      return false;
    case LogicalKeyboardKey.controlLeft:
    case LogicalKeyboardKey.controlRight:
      ctrlIsPressed = true;
      return false;
  }

  // Ctrl shortcuts work from anywhere but a text field, which has its own
  // (Ctrl+L, Ctrl+D) or wants the key for itself.
  if (keyboard.isControlPressed && !keyboard.isAltPressed && !repeat) {
    if (key == LogicalKeyboardKey.keyF) return _openSearch();
    if (_typing) return false;
    if (key == LogicalKeyboardKey.keyL) return _toggleLyrics();
    if (key == LogicalKeyboardKey.keyD) return _openRoot('downloads');
    return false;
  }
  if (keyboard.isControlPressed ||
      keyboard.isAltPressed ||
      keyboard.isMetaPressed) {
    return false;
  }

  // `?` by the character it types: it is Shift and / on some layouts and a
  // key of its own on others.
  if (event.character == '?' && !repeat && !_typing && !_inPopup) {
    _showShortcutList();
    return true;
  }

  switch (key) {
    case LogicalKeyboardKey.space:
      // A focused button or row takes Space itself; so does typing.
      if (repeat || _typing || _onControl || _inPopup) return false;
      if (playQueue.isNotEmpty) audioHandler.togglePlay();
      return true;
    case LogicalKeyboardKey.arrowLeft:
    case LogicalKeyboardKey.arrowRight:
      if (!_playerKeys || playQueue.isEmpty) return false;
      final forward = key == LogicalKeyboardKey.arrowRight;
      if (keyboard.isShiftPressed) {
        if (repeat) return true;
        forward ? audioHandler.skipToNext() : audioHandler.skipToPrevious();
      } else {
        _seekBy(Duration(seconds: forward ? 5 : -5));
      }
      return true;
    case LogicalKeyboardKey.arrowUp:
    case LogicalKeyboardKey.arrowDown:
      if (!_playerKeys || keyboard.isShiftPressed) return false;
      _changeVolume(key == LogicalKeyboardKey.arrowUp ? 0.05 : -0.05);
      return true;
    case LogicalKeyboardKey.slash:
      if (repeat || _typing || _inPopup || keyboard.isShiftPressed) {
        return false;
      }
      return _openSearch();
    case LogicalKeyboardKey.escape:
      if (repeat) return false;
      if (displayLyricsPage && isFullScreenNotifier.value && !isMobile) {
        windowManager.setFullScreen(false);
        isFullScreenNotifier.value = false;
        return true;
      }
      if (displayLyricsPage && !_inPopup) return _toggleLyrics();
      return false;
    case LogicalKeyboardKey.f11:
      if (displayLyricsPage && !isMaximizedNotifier.value && !isMobile) {
        windowManager.setFullScreen(true);
        isFullScreenNotifier.value = true;
        return true;
      }
      return false;
  }
  return false;
}

void _seekBy(Duration step) {
  final duration = currentSongNotifier.value?.duration ?? Duration.zero;
  var target = audioHandler.getPosition() + step;
  if (target < Duration.zero) target = Duration.zero;
  if (duration > Duration.zero && target > duration) target = duration;
  audioHandler.seek(target);
}

void _changeVolume(double delta) {
  final volume = (volumeNotifier.value + delta).clamp(0.0, 1.0);
  volumeNotifier.value = volume;
  audioHandler.setVolume(volume);
  audioHandler.savePlayState();
}

bool _openRoot(String label) {
  if (displayLyricsPage) _toggleLyrics();
  layersManager.switchRootLayer(label);
  return true;
}

bool _openSearch() {
  _openRoot('search');
  // The search page may already exist, so it is asked for focus rather than
  // left to take it when first built.
  focusSearchNotifier.value++;
  return true;
}

bool _toggleLyrics() {
  final navigator = globalNavigatorKey.currentState;
  if (navigator == null) return false;
  if (displayLyricsPage) {
    displayLyricsPage = false;
    navigator.pop();
  } else if (currentSongNotifier.value != null) {
    navigator.push(
      DynamicLyricsPageRoute(pageBuilder: (_, _, _) => LyricsPageLayer()),
    );
  }
  return true;
}

void _showShortcutList() {
  final context = globalNavigatorKey.currentContext;
  if (context == null) return;
  showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 460,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Semantics(
              header: true,
              child: const Text(
                'Keyboard shortcuts',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
            ),
            const SizedBox(height: 10),
            Flexible(
              child: SingleChildScrollView(
                child: Column(
                  children: [
                    for (final (keys, action) in shortcutList)
                      MergeSemantics(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(vertical: 4),
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              SizedBox(
                                width: 170,
                                child: Text(
                                  keys,
                                  style: const TextStyle(
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ),
                              Expanded(child: Text(action)),
                            ],
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
