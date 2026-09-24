import 'dart:math';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:smooth_corner/smooth_corner.dart';

class MySheet extends StatelessWidget {
  final Widget child;
  final double? height;

  const MySheet(this.child, {super.key, this.height});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: currentSongNotifier,
      builder: (context, _, _) {
        return Material(
          shape: SmoothRectangleBorder(
            smoothness: 1,
            borderRadius: BorderRadius.vertical(top: Radius.circular(10)),
          ),
          color: Color.alphaBlend(
            colorManager.getSpecificBgColor(),
            colorManager.getSpecificBgBaseColor(),
          ),
          clipBehavior: .antiAlias,
          // Taller with large text, whose rows would otherwise run past the
          // sheet's bottom; it may then take more of the screen, too.
          child: SizedBox(
            height: min(
              scaledExtent(context, height ?? 500, textShare: 0.6),
              MediaQuery.heightOf(context) *
                  (textGrowth(context) > 1.2 ? 0.85 : 0.6),
            ),
            child: MediaQuery.removePadding(
              context: context,
              removeLeft: true, // for mobile
              removeRight: true,
              removeBottom: true,
              removeTop: true,
              child: child,
            ),
          ),
        );
      },
    );
  }
}
