import 'dart:io';

import 'package:flutter/material.dart';
import 'package:sylvakru/base/app.dart';

Widget myNavigator({
  required Key key,
  required ValueNotifier visibleNotifier,
  required Widget pageView,
  required Widget panelView,
  bool needAnimation = true,
}) {
  return Navigator(
    key: key,
    observers: [HeroController()],
    pages: [
      if (Platform.isAndroid) MaterialPage(child: SizedBox.shrink()),
      MaterialPage(
        child: OrientationBuilder(
          builder: (context, orientation) {
            if (isMobile && orientation == Orientation.portrait) {
              return pageView;
            } else {
              return ValueListenableBuilder(
                valueListenable: visibleNotifier,
                builder: (context, value, child) {
                  return AnimatedOpacity(
                    duration: Duration(milliseconds: needAnimation ? 100 : 0),
                    opacity: value ? 1 : 0,
                    child: panelView,
                  );
                },
              );
            }
          },
        ),
      ),
    ],
    onDidRemovePage: (page) {},
  );
}
