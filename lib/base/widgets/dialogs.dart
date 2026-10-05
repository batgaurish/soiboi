import 'dart:ui';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/widgets/custom_text_field.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/theme/motion.dart';

/// Asks before [action]. [message] replaces the generic "continue?" line,
/// for when the consequence needs spelling out; [confirmText] replaces
/// "Confirm".
Future<bool> showConfirmDialog(
  BuildContext context,
  String action, {
  String? message,
  String? confirmText,
}) async {
  final l10n = AppLocalizations.of(context);

  final result = await showAnimationDialog<bool>(
    context: context,
    child: Builder(
      builder: (context) {
        return SizedBox(
          width: 300,
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: ListenableBuilder(
              listenable: Listenable.merge([
                buttonColor.valueNotifier,
                lyricsPageForegroundColor.valueNotifier,
                lyricsPageButtonColor.valueNotifier,
                miniViewForegroundColor.valueNotifier,
              ]),
              builder: (context, _) {
                return Column(
                  mainAxisSize: .min,
                  children: [
                    Align(
                      alignment: .centerLeft,
                      child: Text(
                        action,
                        maxLines: 3,
                        style: TextStyle(
                          fontSize: 24,
                          fontWeight: .bold,
                          color: colorManager.getSpecificTextColor(),
                          overflow: .ellipsis,
                        ),
                      ),
                    ),
                    SizedBox(height: 15),
                    Align(
                      alignment: .centerLeft,
                      child: Text(
                        message ?? l10n.continueMsg,
                        style: TextStyle(
                          fontSize: 14,
                          color: colorManager.getSpecificTextColor(),
                        ),
                      ),
                    ),
                    SizedBox(height: 20),

                    Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        ElevatedButton(
                          autofocus: viewModeNotifier.value == .bigPicture,
                          onPressed: () => Navigator.pop(context, false),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: colorManager
                                .getSpecificButtonColor(),
                            foregroundColor: colorManager
                                .getSpecificTextColor(),
                          ),
                          child: Text(l10n.cancel),
                        ),
                        const SizedBox(width: 20),
                        ElevatedButton(
                          onPressed: () => Navigator.pop(context, true),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: colorManager
                                .getSpecificButtonColor(),
                            foregroundColor: Colors.red,
                          ),
                          child: Text(confirmText ?? l10n.confirm),
                        ),
                      ],
                    ),
                  ],
                );
              },
            ),
          ),
        );
      },
    ),
  );
  return result ?? false;
}

Future<String> getInputTextDialog(
  BuildContext context,
  String title, {
  bool needConfirm = true,
}) async {
  final l10n = AppLocalizations.of(context);

  final controller = TextEditingController();

  final result = await showAnimationDialog<String>(
    context: context,
    child: SizedBox(
      width: 300,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(30, 20, 30, 20),
        child: ValueListenableBuilder(
          valueListenable: lyricsPageForegroundColor.valueNotifier,
          builder: (context, value, child) {
            final specificTextcolor = colorManager.getSpecificTextColor();
            return Column(
              mainAxisSize: .min,
              children: [
                Center(
                  child: Text(
                    title,
                    style: TextStyle(fontSize: 25, color: specificTextcolor),
                  ),
                ),
                SizedBox(height: 20),
                CustomTextField(
                  null,
                  controller,
                  compact: false,
                  autoFocus: true,
                ),
                SizedBox(height: 30),
                if (needConfirm)
                  Center(
                    child: ListenableBuilder(
                      listenable: Listenable.merge([
                        buttonColor.valueNotifier,
                        lyricsPageButtonColor.valueNotifier,
                      ]),
                      builder: (context, _) {
                        return ElevatedButton(
                          onPressed: () =>
                              Navigator.pop(context, controller.text),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: colorManager
                                .getSpecificButtonColor(),
                            foregroundColor: specificTextcolor,
                          ),
                          child: Text(l10n.confirm),
                        );
                      },
                    ),
                  ),
              ],
            );
          },
        ),
      ),
    ),
  );
  if (needConfirm) {
    return result ?? '';
  } else {
    return controller.text;
  }
}

Future<T?> showAnimationDialog<T>({
  required BuildContext context,
  bool barrierDismissible = true,
  required Widget child,
}) async {
  Offset offset = Offset.zero;

  final GlobalKey childKey = GlobalKey();
  double childHeight = 0;
  void measureChild() {
    final renderBox = childKey.currentContext?.findRenderObject() as RenderBox?;
    if (renderBox != null) {
      final newHeight = renderBox.size.height;
      if (newHeight != childHeight) {
        childHeight = newHeight;
      }
    }
  }

  return await showGeneralDialog<T>(
    context: context,
    // The barrier below draws the blur and takes taps; this one is what
    // lets Esc close the dialog, which only a dismissible route allows.
    barrierDismissible: barrierDismissible,
    barrierLabel: MaterialLocalizations.of(context).modalBarrierDismissLabel,
    barrierColor: Colors.transparent,
    transitionDuration: const Duration(milliseconds: 300),
    pageBuilder: (context, animation, _) {
      return StatefulBuilder(
        builder: (context, setState) {
          final mediaQuery = MediaQuery.of(context);
          final screenHeight = mediaQuery.size.height;
          final keyboardHeight = mediaQuery.viewInsets.bottom;
          final isKeyboardOpen = keyboardHeight > 0;
          double getMinOffset() {
            if (childHeight == 0) return double.negativeInfinity;
            return screenHeight / 2 - keyboardHeight - childHeight / 2 - 30;
          }

          WidgetsBinding.instance.addPostFrameCallback((_) {
            measureChild();
            if (!isKeyboardOpen && offset != .zero) {
              setState(() {
                offset = .zero;
              });
            }
          });

          return Stack(
            children: [
              AnimatedBuilder(
                animation: animation,
                builder: (_, _) {
                  return BackdropFilter(
                    filter: ImageFilter.blur(
                      sigmaX: 5 * animation.value,
                      sigmaY: 5 * animation.value,
                    ),
                    child: Container(
                      color: Colors.black.withValues(
                        alpha: 0.3 * animation.value,
                      ),
                    ),
                  );
                },
              ),

              ModalBarrier(
                dismissible: barrierDismissible,
                color: Colors.transparent,
                onDismiss: () {
                  Navigator.pop(context);
                },
              ),

              Center(
                child: AnimatedContainer(
                  duration: motionDuration(Duration(milliseconds: 250)),
                  curve: Curves.easeOutCubic,
                  transform: Matrix4.translationValues(0, offset.dy, 0),
                  child: GestureDetector(
                    onVerticalDragUpdate: (details) {
                      if (!isKeyboardOpen) return;

                      setState(() {
                        if (offset.dy < getMinOffset() || offset.dy > 0) {
                          offset += Offset(0, details.delta.dy * 0.15);
                        } else {
                          offset += Offset(0, details.delta.dy);
                        }
                      });
                    },

                    onVerticalDragEnd: (_) {
                      if (!isKeyboardOpen) return;

                      final minOffset = getMinOffset();
                      setState(() {
                        if (offset.dy < minOffset) {
                          offset = Offset(0, minOffset);
                        } else if (offset.dy > 0) {
                          offset = .zero;
                        }
                      });
                    },

                    child: SlideTransition(
                      // A plain fade, below, when motion is reduced.
                      position:
                          Tween<Offset>(
                            begin: reduceMotion
                                ? Offset.zero
                                : const Offset(0, 1),
                            end: Offset.zero,
                          ).animate(
                            CurvedAnimation(
                              parent: animation,
                              curve: Curves.easeInOutCubic,
                            ),
                          ),
                      child: FadeTransition(
                        opacity: animation,
                        child: ListenableBuilder(
                          listenable: Listenable.merge([
                            layersManager.backgroundChangeNotifier,
                            currentSongNotifier,
                            pageBackgroundColor.valueNotifier,
                            panelColor.valueNotifier,
                          ]),
                          builder: (context, _) {
                            return Material(
                              key: childKey,
                              shape: SmoothRectangleBorder(
                                smoothness: 1,
                                borderRadius: BorderRadius.circular(10),
                              ),
                              color: firstLaunch
                                  ? null
                                  : Color.alphaBlend(
                                      colorManager.getSpecificBgColor(),
                                      colorManager.getSpecificBgBaseColor(),
                                    ),
                              clipBehavior: Clip.antiAliasWithSaveLayer,
                              child: MediaQuery.removePadding(
                                context: context,
                                removeLeft: true,
                                removeRight: true,
                                removeTop: true,
                                removeBottom: true,
                                child: child,
                              ),
                            );
                          },
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          );
        },
      );
    },
  );
}

Future<void> showPremiumDialog(BuildContext context) async {
  final l10n = AppLocalizations.of(context);

  await showAnimationDialog(
    context: context,
    child: Builder(
      builder: (context) {
        return SizedBox(
          width: 300,
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: ListenableBuilder(
              listenable: Listenable.merge([
                buttonColor.valueNotifier,
                lyricsPageForegroundColor.valueNotifier,
                lyricsPageButtonColor.valueNotifier,
              ]),
              builder: (context, _) {
                return Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      l10n.premiumFeatures,
                      style: TextStyle(
                        fontSize: 25,
                        fontWeight: FontWeight.bold,
                        color: colorManager.getSpecificTextColor(),
                      ),
                    ),

                    const SizedBox(height: 16),

                    Text(
                      l10n.premiumRequiredMessage,
                      style: TextStyle(
                        fontSize: 15,
                        color: colorManager.getSpecificTextColor(),
                      ),
                    ),

                    const SizedBox(height: 8),

                    Text(
                      l10n.premiumUnlockHint,
                      style: TextStyle(
                        fontSize: 15,
                        color: colorManager.getSpecificTextColor(),
                      ),
                    ),

                    const SizedBox(height: 24),

                    Align(
                      alignment: Alignment.centerRight,
                      child: ElevatedButton(
                        onPressed: () => Navigator.pop(context),
                        style: ElevatedButton.styleFrom(
                          backgroundColor: colorManager
                              .getSpecificButtonColor(),
                          foregroundColor: colorManager.getSpecificTextColor(),
                        ),
                        child: Text(l10n.confirm),
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        );
      },
    ),
  );
}
