import 'package:material_ui/material_ui.dart';
import 'package:flutter_switch/flutter_switch.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/widgets/focus_ring.dart';
import 'package:soiboi/base/widgets/scale_widget.dart';

class MySwitch extends StatelessWidget {
  final String? trueText;
  final String? falseText;
  final ValueNotifier<bool> valueNotifier;
  final void Function()? onToggleCallBack;
  final bool inLyricsPage;

  /// What the switch controls, for screen readers: usually the title of the
  /// row it sits in. Without one, [trueText] names it.
  final String? semanticLabel;

  const MySwitch({
    super.key,
    this.trueText,
    this.falseText,
    required this.valueNotifier,
    this.onToggleCallBack,
    this.inLyricsPage = false,
    this.semanticLabel,
  });

  void _toggle() {
    tryVibrate();
    valueNotifier.value = !valueNotifier.value;
    onToggleCallBack?.call();
  }

  /// The switch draws no semantics of its own (flutter_switch has none), so
  /// it is described here: a plain on/off switch, or, when its two states
  /// are named ("List" / "Grid"), a button that says which one is showing.
  /// Nor does it take the keyboard, so [FocusRing] adds that: Tab reaches
  /// it and Space or Enter flips it.
  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: valueNotifier,
      builder: (context, value, child) {
        final choice =
            trueText != null && falseText != null && trueText != falseText;
        final current = value ? trueText : falseText;
        final other = value ? falseText : trueText;
        final name = semanticLabel ?? (choice ? null : trueText);
        return Semantics(
          container: true,
          button: choice,
          toggled: choice ? null : value,
          label: choice ? [?name, ?current].join(': ') : name,
          hint: choice ? 'Switches to $other' : null,
          onTap: _toggle,
          child: FocusRing(
            onActivate: _toggle,
            radius: 12,
            // The switch's own ScaleWidget would be a second, unmarked Tab
            // stop (and Tab would loop between the two).
            child: ExcludeFocus(child: ExcludeSemantics(child: child)),
          ),
        );
      },
      child: _visual(),
    );
  }

  Widget _visual() {
    if (trueText == null) {
      return switcher();
    }
    return Row(
      mainAxisSize: .min,
      children: [
        ValueListenableBuilder(
          valueListenable: valueNotifier,
          builder: (context, value, child) {
            return Text(
              value ? trueText! : falseText!,
              style: TextStyle(
                color: inLyricsPage ? lyricsPageForegroundColor.value : null,
              ),
            );
          },
        ),
        SizedBox(width: 5),
        switcher(),
      ],
    );
  }

  Widget switcher() {
    return ValueListenableBuilder(
      valueListenable: valueNotifier,
      builder: (context, value, child) {
        return ValueListenableBuilder(
          valueListenable: switchColor.valueNotifier,
          builder: (_, _, _) {
            return ScaleWidget(
              onTap: () {
                valueNotifier.value = !valueNotifier.value;
                onToggleCallBack?.call();
              },
              child: FlutterSwitch(
                width: 45,
                height: 20,
                toggleSize: 15,
                activeColor: switchColor.value,
                inactiveColor: Colors.grey.shade300,
                value: value,
                onToggle: (value) {
                  tryVibrate();
                  valueNotifier.value = !valueNotifier.value;
                  onToggleCallBack?.call();
                },
              ),
            );
          },
        );
      },
    );
  }
}
