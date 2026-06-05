import 'dart:io';

import 'package:sylvakru/base/services/single_instance.dart';
import 'package:window_manager/window_manager.dart';

bool _exited = false;

void exitApp() async {
  if (_exited) {
    return;
  }

  await SingleInstance.end();
  // only this allows quick exit on Windows
  if (Platform.isWindows) {
    await windowManager.setPreventClose(false);
    _exited = true;
    windowManager.close();
    return;
  }

  exit(0);
}
