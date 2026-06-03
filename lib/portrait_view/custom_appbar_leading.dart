import 'package:flutter/material.dart';
import 'package:sylvakru/layer/layers_manager.dart';
import 'package:sylvakru/portrait_view/portrait_view.dart';

Widget customAppBarLeading(BuildContext context, {String label = ''}) {
  return IconButton(
    icon: Icon(label.isEmpty ? Icons.menu : Icons.arrow_back_ios_new_rounded),
    onPressed: () => label.isEmpty
        ? portraitKey.currentState?.openDrawer()
        : layersManager.popDetail(label),
  );
}
