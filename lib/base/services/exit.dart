import 'dart:io';

import 'package:soiboi/base/services/single_instance.dart';
import 'package:soiboi/base/services/wrapper_service.dart';
import 'package:window_manager/window_manager.dart';

bool _exited = false;

void exitApp() async {
  if (_exited) {
    return;
  }

  await SingleInstance.end();
  // The wrapper is a separate process and would outlive the app otherwise.
  await wrapperService.stop();
  // only this allows quick exit on Windows
  if (Platform.isWindows) {
    await windowManager.setPreventClose(false);
    _exited = true;
    windowManager.close();
    return;
  }

  exit(0);
}
