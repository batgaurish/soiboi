/// Names an icon-only button for Linux screen readers.
///
/// An icon button's tooltip is its name on Android: Flutter hands it to
/// TalkBack as the content description when there is no label. Flutter's
/// Linux bridge, which is what Orca reads through AT-SPI, ignores tooltips
/// and reads only labels, so there every icon button was an unnamed "push
/// button". Wrapping the icon in the tooltip's words fixes Linux; elsewhere
/// the icon is returned as is, so nothing gets read twice.
library;

import 'dart:io';

import 'package:material_ui/material_ui.dart';

Widget labelIcon(String? name, Widget icon) {
  if (name == null || name.isEmpty || !Platform.isLinux) return icon;
  return Semantics(
    label: name,
    child: ExcludeSemantics(child: icon),
  );
}
