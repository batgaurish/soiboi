/// Motion tokens.
///
/// Defined up front because retrofitting is the expensive path: once curves and
/// durations are hardcoded across dozens of widgets, making motion themeable
/// means touching every one of them. Widgets should read from [activeMotion]
/// rather than naming a `Duration` or `Curve` directly.
///
/// Motion is a real axis of the flavour system, not decoration. Signal's near
/// absence of movement is as deliberate as Zine's spring.
library;

import 'dart:ui' show PlatformDispatcher;

import 'package:flutter/foundation.dart' show ValueListenable;
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

const _zineMotion = MotionSpec(
  short: Duration(milliseconds: 180),
  medium: Duration(milliseconds: 320),
  long: Duration(milliseconds: 480),
  standard: Curves.easeOutCubic,
  emphasized: Curves.easeOutBack,
  enterExit: Curves.easeOutQuart,
  useContainerTransform: true,
  staggerStep: Duration(milliseconds: 38),
);

const _linerNotesMotion = MotionSpec(
  short: Duration(milliseconds: 220),
  medium: Duration(milliseconds: 380),
  long: Duration(milliseconds: 620),
  standard: Curves.easeInOutSine,
  emphasized: Curves.easeInOutCubic,
  enterExit: Curves.easeOutSine,
  useContainerTransform: true,
  // Liner Notes drifts rather than cascades: a slower, softer stagger.
  staggerStep: Duration(milliseconds: 55),
);

const _signalMotion = MotionSpec(
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
  Flavour.linerNotes: _linerNotesMotion,
  Flavour.signal: _signalMotion,
  Flavour.zine: _zineMotion,
};

MotionSpec get activeMotion =>
    motionSpecs[flavourNotifier.value] ?? _zineMotion;

/// Collapses every duration of [base]: what the app looks like with motion
/// reduced.
MotionSpec reducedMotion(MotionSpec base) => MotionSpec(
  short: Duration.zero,
  medium: Duration.zero,
  long: Duration.zero,
  standard: base.standard,
  emphasized: base.emphasized,
  enterExit: base.enterExit,
  useContainerTransform: false,
  staggerStep: Duration.zero,
);

/// The active flavour's motion, collapsed when motion is reduced.
///
/// Reads the system setting through [MediaQuery], so a widget that calls
/// this rebuilds when the system setting changes. Widgets that must not
/// depend on MediaQuery can use [activeMotion] with [reduceMotion] instead.
MotionSpec motionFor(BuildContext context) {
  final system =
      MediaQuery.maybeDisableAnimationsOf(context) ??
      systemReducesMotionNotifier.value;
  final reduce = resolveReduceMotion(
    motionPreferenceNotifier.value,
    systemReduces: system,
  );
  return reduce ? reducedMotion(activeMotion) : activeMotion;
}

// ---------------------------------------------------------------------------
// Reduced motion
// ---------------------------------------------------------------------------

/// How much the app may move.
enum MotionPreference {
  /// Follow the system: Android's "Remove animations", or animations turned
  /// off in the desktop's settings (GTK's `gtk-enable-animations`).
  system,

  /// No non-essential motion, whatever the system says.
  reduced,

  /// All motion, whatever the system says.
  full,
}

/// The user's choice. Persisted by `setting.dart`.
final motionPreferenceNotifier = ValueNotifier(MotionPreference.system);

/// Whether the platform currently asks apps to drop animations. Kept up to
/// date by [watchSystemMotionSetting].
final systemReducesMotionNotifier = ValueNotifier(
  PlatformDispatcher.instance.accessibilityFeatures.disableAnimations,
);

bool resolveReduceMotion(
  MotionPreference preference, {
  required bool systemReduces,
}) => switch (preference) {
  MotionPreference.system => systemReduces,
  MotionPreference.reduced => true,
  MotionPreference.full => false,
};

/// Whether to drop non-essential motion right now: the one answer every
/// animated widget should use. Listen to it to rebuild when it changes.
final ValueListenable<bool> reduceMotionNotifier = _ReduceMotion();

bool get reduceMotion => reduceMotionNotifier.value;

/// [duration], or none when motion is reduced.
Duration motionDuration(Duration duration) =>
    reduceMotion ? Duration.zero : duration;

class _ReduceMotion extends ChangeNotifier implements ValueListenable<bool> {
  _ReduceMotion() {
    motionPreferenceNotifier.addListener(_update);
    systemReducesMotionNotifier.addListener(_update);
    _value = _compute();
  }

  late bool _value;

  bool _compute() => resolveReduceMotion(
    motionPreferenceNotifier.value,
    systemReduces: systemReducesMotionNotifier.value,
  );

  void _update() {
    final next = _compute();
    if (next == _value) return;
    _value = next;
    notifyListeners();
  }

  @override
  bool get value => _value;
}

/// Follows the system's animation setting as it changes. Call once, after
/// the binding exists.
void watchSystemMotionSetting() {
  WidgetsBinding.instance.addObserver(_SystemMotionObserver());
  systemReducesMotionNotifier.value =
      PlatformDispatcher.instance.accessibilityFeatures.disableAnimations;
}

class _SystemMotionObserver with WidgetsBindingObserver {
  @override
  void didChangeAccessibilityFeatures() {
    systemReducesMotionNotifier.value =
        PlatformDispatcher.instance.accessibilityFeatures.disableAnimations;
  }
}

/// With motion reduced, a page change is a short fade instead of a slide
/// or zoom. Routes return this from `buildTransitions` when [reduceMotion]
/// is set.
Widget reducedMotionTransition(Animation<double> animation, Widget child) =>
    FadeTransition(
      opacity: CurvedAnimation(parent: animation, curve: Curves.easeOut),
      child: child,
    );

/// How long a route takes with motion reduced: long enough to read as a
/// change of page, too short to be movement.
const reducedTransitionDuration = Duration(milliseconds: 150);

extension ReducedMotionScrolling on ScrollController {
  /// [animateTo], or a jump when motion is reduced.
  Future<void> glideTo(
    double offset, {
    required Duration duration,
    required Curve curve,
  }) async {
    if (reduceMotion) {
      jumpTo(offset);
      return;
    }
    await animateTo(offset, duration: duration, curve: curve);
  }
}

extension ReducedMotionPaging on PageController {
  /// [animateToPage], or a jump when motion is reduced.
  Future<void> glideToPage(
    int page, {
    required Duration duration,
    required Curve curve,
  }) async {
    if (reduceMotion) {
      jumpToPage(page);
      return;
    }
    await animateToPage(page, duration: duration, curve: curve);
  }
}
