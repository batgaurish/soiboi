import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/my_window_listener.dart';
import 'package:soiboi/layer/lyrics_page_layer.dart';
import 'package:window_manager/window_manager.dart';

bool isTyping = false;

bool shiftIsPressed = false;
bool ctrlIsPressed = false;

/// Whether a text field has the keyboard. [isTyping] is only set by the
/// app's own search fields; this catches every other one, such as the link
/// box in Downloads, where Space used to pause the music instead of typing.
bool get _typing {
  if (isTyping) return true;
  final context = FocusManager.instance.primaryFocus?.context;
  return context != null &&
      (context.widget is EditableText ||
          context.findAncestorWidgetOfExactType<EditableText>() != null);
}

void keyboardInit() {
  HardwareKeyboard.instance.addHandler((event) {
    if (event is KeyDownEvent) {
      switch (event.logicalKey) {
        case LogicalKeyboardKey.shiftLeft:
        case LogicalKeyboardKey.shiftRight:
          shiftIsPressed = true;
          break;
        case LogicalKeyboardKey.controlLeft:
        case LogicalKeyboardKey.controlRight:
          ctrlIsPressed = true;
          break;
        case LogicalKeyboardKey.space:
          if (!_typing && playQueue.isNotEmpty) {
            audioHandler.togglePlay();
          }
          break;
        case LogicalKeyboardKey.escape:
          if (displayLyricsPage && isFullScreenNotifier.value) {
            windowManager.setFullScreen(false);
            isFullScreenNotifier.value = false;
          }
          break;
        case LogicalKeyboardKey.f11:
          if (displayLyricsPage && !isMaximizedNotifier.value) {
            windowManager.setFullScreen(true);
            isFullScreenNotifier.value = true;
          }
          break;
      }
    } else if (event is KeyUpEvent) {
      switch (event.logicalKey) {
        case LogicalKeyboardKey.shiftLeft:
        case LogicalKeyboardKey.shiftRight:
          shiftIsPressed = false;
          break;
        case LogicalKeyboardKey.controlLeft:
        case LogicalKeyboardKey.controlRight:
          ctrlIsPressed = false;
          break;
      }
    }
    return false;
  });
}
