import 'package:material_ui/material_ui.dart';

bool isTooNarrow(BuildContext context) {
  return MediaQuery.widthOf(context) < 800;
}

double getTopOffset(BuildContext context) {
  final topPadding = MediaQuery.of(context).padding.top;
  if (topPadding >= 20) {
    return topPadding - 20;
  }
  return 0;
}

/// How much larger than normal the user's text is: 1 at the default size, 2
/// at 200%. Measured at body size, because Android 14 and later scale large
/// text less than small text.
double textGrowth(BuildContext context) =>
    MediaQuery.textScalerOf(context).scale(15) / 15;

/// A fixed size, grown for the user's text size.
///
/// Only the part of [base] that holds text ([textShare]) grows with it;
/// padding and icons stay put. Rows keep a fixed height (lists use it to
/// scroll straight to an item) but no longer clip their text at large sizes.
double scaledExtent(
  BuildContext context,
  double base, {
  double textShare = 0.6,
}) {
  final growth = textGrowth(context);
  return growth <= 1 ? base : base * (1 + textShare * (growth - 1));
}
