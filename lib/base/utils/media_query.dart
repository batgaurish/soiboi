import 'package:flutter/material.dart';

bool isTooNarrow(BuildContext context) {
  return MediaQuery.widthOf(context) < 800;
}
