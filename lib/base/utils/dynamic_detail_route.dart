import 'package:flutter/material.dart';
import 'package:sylvakru/base/utils/media_query.dart';
import 'package:sylvakru/layer/layers_manager.dart';

class DynamicDetailRoute extends MaterialPageRoute {
  final String label;
  DynamicDetailRoute({required super.builder, required this.label});

  @override
  Color? get barrierColor => Colors.transparent;
  @override
  DelegatedTransitionBuilder? get delegatedTransition =>
      (context, animation, secondaryAnimation, allowSnapshotting, child) {
        if (!isTooNarrow(context)) {
          return child;
        }

        final isGesture = navigator?.userGestureInProgress == true;
        if (isGesture) {
          return SlideTransition(
            position: secondaryAnimation.drive(
              Tween(end: const Offset(-1 / 3, 0), begin: Offset.zero),
            ),
            transformHitTests: false,
            child: child,
          );
        }
        final animation = CurvedAnimation(
          parent: secondaryAnimation,
          curve: Curves.linearToEaseOut,
          reverseCurve: Curves.easeInToLinear,
        );
        final Animation<Offset> delegatedPositionAnimation = animation.drive(
          Tween(end: const Offset(-1 / 3, 0), begin: Offset.zero),
        );
        animation.dispose();

        return SlideTransition(
          position: delegatedPositionAnimation,
          transformHitTests: false,
          child: child,
        );
      };

  @override
  bool didPop(result) {
    if (navigator?.userGestureInProgress == true) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        layersManager.popDetail(label, executePop: false);
      });
    }
    return super.didPop(result);
  }

  @override
  Widget buildTransitions(
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    if (isTooNarrow(context)) {
      return super.buildTransitions(
        context,
        animation,
        secondaryAnimation,
        child,
      );
    }
    return child;
  }
}
