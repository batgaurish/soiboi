/// Motion tokens.
///
/// Defined up front because retrofitting is the expensive path: once curves and
/// durations are hardcoded across dozens of widgets, making motion themeable
/// means touching every one of them. Widgets should read from [activeMotion]
/// rather than naming a `Duration` or `Curve` directly.
///
/// Motion is a real axis of the flavour system, not decoration. Console's near
/// absence of movement is as deliberate as Expressive's spring.
library;

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/theme/flavour.dart';

class MotionSpec {
  const MotionSpec({
    required this.short,
    required this.medium,
    required this.long,
    required this.standard,
    required this.emphasized,
    required this.enterExit,
    required this.useContainerTransform,
    required this.staggerStep,
  });

  /// Hovers, ripples, icon state changes.
  final Duration short;

  /// Panel and sheet transitions.
  final Duration medium;

  /// Full-screen transitions, artwork cross-fades.
  final Duration long;

  /// Default curve for position and size.
  final Curve standard;

  /// For movements that should feel driven — the transport button, the
  /// now-playing expansion.
  final Curve emphasized;

  /// Entering and leaving the tree.
  final Curve enterExit;

  /// Whether an album tile grows into the now-playing screen rather than
  /// pushing a route. The single highest-impact motion decision in the app.
  final bool useContainerTransform;

  /// Delay between successive list or grid items on load. [Duration.zero]
  /// disables staggering entirely.
  final Duration staggerStep;
}

const _expressiveMotion = MotionSpec(
  short: Duration(milliseconds: 180),
  medium: Duration(milliseconds: 320),
  long: Duration(milliseconds: 480),
  standard: Curves.easeOutCubic,
  emphasized: Curves.easeOutBack,
  enterExit: Curves.easeOutQuart,
  useContainerTransform: true,
  staggerStep: Duration(milliseconds: 38),
);

const _glasshouseMotion = MotionSpec(
  short: Duration(milliseconds: 220),
  medium: Duration(milliseconds: 380),
  long: Duration(milliseconds: 620),
  standard: Curves.easeInOutSine,
  emphasized: Curves.easeInOutCubic,
  enterExit: Curves.easeOutSine,
  useContainerTransform: true,
  // Glasshouse drifts rather than cascades — a slower, softer stagger.
  staggerStep: Duration(milliseconds: 55),
);

const _consoleMotion = MotionSpec(
  short: Duration(milliseconds: 90),
  medium: Duration(milliseconds: 130),
  long: Duration(milliseconds: 170),
  standard: Curves.linear,
  emphasized: Curves.easeOut,
  enterExit: Curves.easeOut,
  // A dense table should not animate its rows into place; it should be there.
  useContainerTransform: false,
  staggerStep: Duration.zero,
);

const Map<Flavour, MotionSpec> motionSpecs = {
  Flavour.expressive: _expressiveMotion,
  Flavour.glasshouse: _glasshouseMotion,
  Flavour.console: _consoleMotion,
};

MotionSpec get activeMotion =>
    motionSpecs[flavourNotifier.value] ?? _expressiveMotion;

/// Honours the platform's reduced-motion setting by collapsing every duration.
/// Call with the ambient [MediaQuery] where one is available.
MotionSpec motionFor(BuildContext context) {
  final reduce = MediaQuery.maybeOf(context)?.disableAnimations ?? false;
  if (!reduce) return activeMotion;
  final base = activeMotion;
  return MotionSpec(
    short: Duration.zero,
    medium: Duration.zero,
    long: Duration.zero,
    standard: base.standard,
    emphasized: base.emphasized,
    enterExit: base.enterExit,
    useContainerTransform: false,
    staggerStep: Duration.zero,
  );
}
