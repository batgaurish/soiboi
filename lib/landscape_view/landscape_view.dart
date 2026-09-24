import 'dart:ui';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/widgets/ai_widgets.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/widgets/cover_art_widget.dart';
import 'package:soiboi/landscape_view/bottom_control.dart';
import 'package:soiboi/landscape_view/sidebar.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/base/theme/motion.dart';

class LandscapeView extends StatelessWidget {
  const LandscapeView({super.key});

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,

      children: [
        ValueListenableBuilder(
          valueListenable: mainPageThemeNotifier,
          builder: (context, value, child) {
            if (value != .vivid) {
              return SizedBox.shrink();
            }
            return ValueListenableBuilder(
              valueListenable: layersManager.backgroundChangeNotifier,
              builder: (context, value, child) {
                return CoverArtWidget(
                  picture: backgroundPicture,
                  color: colorManager.getSpecificBgBaseColor(),
                );
              },
            );
          },
        ),
        ValueListenableBuilder(
          valueListenable: mainPageThemeNotifier,
          builder: (context, value, child) {
            if (value != .vivid) {
              return SizedBox.shrink();
            }
            final pageWidth = MediaQuery.widthOf(context);
            final pageHight = MediaQuery.heightOf(context);

            // Without this, this always-visible background blur has no
            // layer of its own, so every resize (e.g. un-maximizing) forces
            // the whole tree behind it to repaint in the same frame as the
            // blur recompute - unlike the identical effect on the lyrics
            // pages, which already isolates it this way.
            return RepaintBoundary(
              child: BackdropFilter(
                filter: ImageFilter.blur(
                  sigmaX: pageWidth * 0.03,
                  sigmaY: pageHight * 0.03,
                ),
                child: ValueListenableBuilder(
                  valueListenable: layersManager.backgroundChangeNotifier,
                  builder: (context, value, child) {
                    return AnimatedContainer(
                      duration: motionDuration(Duration(milliseconds: 500)),
                      curve: Curves.easeInOutCubic,
                      color: backgroundCoverArtColor.withAlpha(180),
                    );
                  },
                ),
              ),
            );
          },
        ),
        // Tab finishes one region before the next: the sidebar, then the
        // page (its title bar first), then the player bar. Left to reading
        // order alone, it zigzagged between the sidebar and the page.
        FocusTraversalGroup(
          policy: OrderedTraversalPolicy(),
          child: Column(
            children: [
              Expanded(
                child: Row(
                  children: [
                    _region(1, Sidebar()),

                    Expanded(
                      child: _region(
                        2,
                        ValueListenableBuilder(
                          valueListenable: panelColor.valueNotifier,
                          builder: (context, value, child) {
                            return Material(color: value, child: child);
                          },
                          child: ValueListenableBuilder(
                            valueListenable: layersManager.switchNotifier,
                            builder: (context, value, child) {
                              return Stack(
                                children: [
                                  ...layersManager.rootLayerMap.values.map((
                                    layer,
                                  ) {
                                    // One group per page, so Tab finishes
                                    // the page before the floating Ask AI
                                    // button, not whenever a scrolled row
                                    // lines up with it.
                                    return FocusTraversalGroup(
                                      child: Visibility(
                                        visible:
                                            layer == layersManager.topRootLayer,
                                        maintainState: true,
                                        child: layer,
                                      ),
                                    );
                                  }),
                                  const Positioned(
                                    right: 32,
                                    bottom: 32,
                                    child: AskAiFab(),
                                  ),
                                ],
                              );
                            },
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              _region(3, BottomControl()),
            ],
          ),
        ),
      ],
    );
  }

  static Widget _region(double order, Widget child) => FocusTraversalOrder(
    order: NumericFocusOrder(order),
    child: FocusTraversalGroup(child: child),
  );
}
