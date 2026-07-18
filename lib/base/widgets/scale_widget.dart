import 'package:flutter/material.dart';

class ScaleWidget extends StatefulWidget {
  final Widget child;
  final void Function()? onTap;
  const ScaleWidget({super.key, required this.child, this.onTap});

  @override
  State<StatefulWidget> createState() => _ScaleWidgetState();
}

class _ScaleWidgetState extends State<ScaleWidget> {
  final focusNotifier = ValueNotifier(false);
  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: focusNotifier,
      builder: (context, value, child) {
        return Transform.scale(
          scale: value ? 1.1 : 1,
          child: InkWell(
            mouseCursor: SystemMouseCursors.click,
            hoverColor: Colors.transparent,
            focusColor: Colors.transparent,
            splashColor: Colors.transparent,
            highlightColor: Colors.transparent,
            onFocusChange: (value) {
              focusNotifier.value = !focusNotifier.value;
            },
            onTap: widget.onTap ?? () {},
            child: widget.child,
          ),
        );
      },
    );
  }
}
