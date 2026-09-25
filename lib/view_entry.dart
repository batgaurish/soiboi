import 'dart:async';
import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/config.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/keyboard.dart';
import 'package:soiboi/base/services/system_ui_service.dart';
import 'package:soiboi/base/services/taskbar_service.dart';
import 'package:soiboi/base/utils/dynamic_lyrics_page_route.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/layer/setup_wizard.dart';
import 'package:soiboi/big_picture_view/big_picture_view.dart';
import 'package:soiboi/landscape_view/landscape_view.dart';
import 'package:soiboi/landscape_view/sidebar.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/layer/lyrics_page_layer.dart';
import 'package:soiboi/mini_view/mini_view.dart';
import 'package:soiboi/portrait_view/portrait_view.dart';

class ViewEntry extends StatefulWidget {
  const ViewEntry({super.key});

  @override
  State<StatefulWidget> createState() => _ViewEntryState();
}

class _ViewEntryState extends State<ViewEntry> with WidgetsBindingObserver {
  int keyValue = 0;

  @override
  void initState() {
    super.initState();
    if (Platform.isAndroid) {
      WidgetsBinding.instance.addObserver(this);
    }

    if (autoPlayOnStartupNotifier.value && currentSongNotifier.value != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        Navigator.of(context, rootNavigator: true).push(
          DynamicLyricsPageRoute(pageBuilder: (_, _, _) => LyricsPageLayer()),
        );
      });
    }

    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (Platform.isIOS) {
        if (!firstLaunch) {
          await Future.delayed(Duration(milliseconds: 500));
          await NativeMenu.init();
        }
        await NativeMenu.initIcons();
      } else if (Platform.isMacOS) {
        await NativeMenu.initIcons();
      } else if (Platform.isWindows) {
        setupTaskbar();
      }
    });
  }

  @override
  void dispose() {
    if (Platform.isAndroid) {
      WidgetsBinding.instance.removeObserver(this);
    }
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (Platform.isAndroid) {
      if (state == .resumed) {
        applySystemUiMode(forceApply: true);
        // rebuild PopScope to allow it to handle pop
        setState(() {
          keyValue++;
        });
      } else if (isTV && state == .paused) {
        audioHandler.pause();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!Platform.isAndroid) {
      return view();
    }
    return PopScope(
      canPop: false,
      key: ValueKey(keyValue),
      onPopInvokedWithResult: (didPop, result) async {
        if (didPop | isTyping | isTV) {
          return;
        }
        // The setup wizard has its own Back: previous step, or leave.
        if (firstLaunch || needsSetupNotifier.value) {
          return;
        }
        // Back walks the app the way it was walked: close the drawer, close
        // a detail page, return to the previous section, and with nothing
        // left, show the sidebar. Back once more from that sidebar leaves.
        final scaffold = portraitKey.currentState;
        final drawerOpen =
            (scaffold?.isDrawerOpen ?? false) ||
            (scaffold?.isEndDrawerOpen ?? false);
        if (drawerOpen) {
          if (drawerOpenedByBack) {
            drawerOpenedByBack = false;
            SystemNavigator.pop();
          } else {
            scaffold?.closeDrawer();
            scaffold?.closeEndDrawer();
          }
          return;
        }
        if (await layersManager.popDetail(sidebarHighlighLabel.value)) {
          return;
        }
        if (layersManager.popRootLayer()) {
          return;
        }
        if (scaffold != null) {
          drawerOpenedByBack = true;
          endDrawerNotifier.value
              ? scaffold.openEndDrawer()
              : scaffold.openDrawer();
          return;
        }
        SystemNavigator.pop();
      },
      child: view(),
    );
  }

  Widget view() {
    return ValueListenableBuilder<bool>(
      valueListenable: needsSetupNotifier,
      builder: (context, needsSetup, _) => firstLaunch || needsSetup
          ? SetupWizard(firstRun: firstLaunch, onFinish: _finishSetup)
          : _appView(),
    );
  }

  Widget _appView() {
    return ValueListenableBuilder(
      valueListenable: viewModeNotifier,
      builder: (context, viewMode, child) {
        if (viewMode == .mini) {
          return MiniView();
        }
        if (viewMode == .bigPicture) {
          applySystemUiMode(
            mode: immersiveWideLayoutNotifier.value
                ? .immersiveSticky
                : .edgeToEdge,
          );

          if (immersiveWideLayoutNotifier.value) {
            return BigPictureView();
          }
          SystemChrome.setSystemUIOverlayStyle(
            const SystemUiOverlayStyle(
              statusBarIconBrightness: Brightness.light,
            ),
          );
          return SafeArea(child: BigPictureView());
        }
        if (isTooNarrow(context)) {
          applySystemUiMode(mode: .manual);
          return PortraitView();
        }
        // immersiveSticky：上滑临时显示的系统栏是透明浮层、不派发 insets
        // 变化也会自动隐藏，全面屏手势可正常完成；immersive 被唤出后会常驻
        applySystemUiMode(
          mode: immersiveWideLayoutNotifier.value
              ? .immersiveSticky
              : .edgeToEdge,
        );

        if (immersiveWideLayoutNotifier.value) {
          return LandscapeView();
        }
        SystemChrome.setSystemUIOverlayStyle(
          const SystemUiOverlayStyle(statusBarIconBrightness: Brightness.light),
        );
        return SafeArea(child: LandscapeView());
      },
    );
  }

  /// Leaves the setup wizard shown in place of the app.
  ///
  /// On the first launch this is what "Get started" used to do: save the
  /// chosen source and scan the library for the first time.
  Future<void> _finishSetup(SetupResult result) async {
    final wasFirstLaunch = firstLaunch;
    setupWizardDoneNotifier.value = true;
    setting.save();
    setState(() {
      firstLaunch = false;
      needsSetupNotifier.value = false;
    });

    if (wasFirstLaunch) {
      if (Platform.isIOS) {
        WidgetsBinding.instance.addPostFrameCallback((_) async {
          await NativeMenu.init();
        });
      }
      config.save();
      // firstSync opens Songs as it starts; Downloads replaces it at once
      // when that is where the user asked to go.
      final sync = Loader.firstSync();
      if (result.openDownloads) layersManager.switchRootLayer('downloads');
      await sync;
      return;
    }
    if (result.openDownloads) layersManager.switchRootLayer('downloads');
    if (result.foldersChanged) await Loader.sync();
  }
}
