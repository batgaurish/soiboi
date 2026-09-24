import 'dart:io';

import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/widgets/ai_widgets.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/landscape_view/sidebar.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/portrait_view/play_bar.dart';
import 'package:soiboi/base/theme/motion.dart';

final GlobalKey<ScaffoldState> portraitKey = GlobalKey();

/// Set when the back button, with nowhere left to go, opened the sidebar.
/// Back again then leaves the app; the sidebar closing any other way clears
/// it, so a sidebar opened by hand never quits on back.
bool drawerOpenedByBack = false;
bool isDrawerOpen = false;
final endDrawerNotifier = ValueNotifier(false);

class PortraitView extends StatefulWidget {
  const PortraitView({super.key});

  @override
  State<StatefulWidget> createState() => _PortraitViewState();
}

class _PortraitViewState extends State<PortraitView>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<Offset> _slideAnimation;

  void slideBegin() {
    // From the end when motion is reduced: the new page is simply there.
    _controller.forward(from: reduceMotion ? 1 : 0);
  }

  void statusListener(AnimationStatus status) {
    if (status != .completed) {
      return;
    }
    if (layersManager.bottomRootPage != null) {
      layersManager.bottomRootPage = null;
      if (mounted) {
        setState(() {});
      }
    }
  }

  void updateDrawerSetting() {
    _slideAnimation =
        Tween<Offset>(
          begin: Offset(endDrawerNotifier.value ? 1.0 : -1.0, 0.0),
          end: Offset.zero,
        ).animate(
          CurvedAnimation(parent: _controller, curve: Curves.linearToEaseOut),
        );
    setState(() {
      _slideAnimation =
          Tween<Offset>(
            begin: Offset(endDrawerNotifier.value ? 1.0 : -1.0, 0.0),
            end: Offset.zero,
          ).animate(
            CurvedAnimation(parent: _controller, curve: Curves.linearToEaseOut),
          );
    });
  }

  @override
  void initState() {
    super.initState();

    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 400),
    );

    _controller.addStatusListener(statusListener);

    _slideAnimation =
        Tween<Offset>(
          begin: Offset(endDrawerNotifier.value ? 1.0 : -1.0, 0.0),
          end: Offset.zero,
        ).animate(
          CurvedAnimation(parent: _controller, curve: Curves.linearToEaseOut),
        );

    endDrawerNotifier.addListener(updateDrawerSetting);
    layersManager.switchNotifier.addListener(slideBegin);
    _controller.forward(from: 1);
  }

  @override
  void dispose() {
    endDrawerNotifier.removeListener(updateDrawerSetting);
    layersManager.switchNotifier.removeListener(slideBegin);
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      key: portraitKey,
      extendBodyBehindAppBar: true,
      backgroundColor: Colors.transparent,
      resizeToAvoidBottomInset: false,
      drawer: !endDrawerNotifier.value ? myDrawer() : null,
      endDrawer: endDrawerNotifier.value ? myDrawer() : null,
      drawerEnableOpenDragGesture: !Platform.isIOS,
      onEndDrawerChanged: (isOpened) {
        if (!isOpened) drawerOpenedByBack = false;
      },
      onDrawerChanged: (isOpened) async {
        if (!isOpened) drawerOpenedByBack = false;
        // ensure popscope gets correct drawer state
        if (!isOpened) {
          await Future.delayed(Duration(milliseconds: 250));
        }

        isDrawerOpen = isOpened;
      },
      body: Stack(
        children: [
          ValueListenableBuilder(
            valueListenable: layersManager.switchNotifier,
            builder: (context, _, _) {
              return GestureDetector(
                onHorizontalDragEnd: (details) {
                  final velocity = (details.primaryVelocity ?? 0);

                  if (!endDrawerNotifier.value && velocity > 500) {
                    portraitKey.currentState?.openDrawer();
                  } else if (endDrawerNotifier.value && velocity < -500) {
                    portraitKey.currentState?.openEndDrawer();
                  }
                },
                child: Stack(
                  children: [
                    ...layersManager.rootPageMap.values
                        .where((page) => page != layersManager.topRootPage)
                        .map((page) {
                          return Visibility(
                            visible: page == layersManager.bottomRootPage,
                            maintainState: true,
                            child: page,
                          );
                        }),
                    if (layersManager.bottomRootPage == null)
                      layersManager.topRootPage!
                    else
                      SlideTransition(
                        position: _slideAnimation,
                        child: layersManager.topRootPage,
                      ),
                  ],
                ),
              );
            },
          ),

          Positioned(left: 20, right: 20, bottom: 40, child: PlayBar()),
          // Ask AI from anywhere, just above the mini player. Page buttons
          // (New playlist, Add songs) stack above this one.
          const Positioned(right: 20, bottom: 116, child: AskAiFab()),
        ],
      ),
    );
  }

  Widget myDrawer() {
    return ValueListenableBuilder(
      valueListenable: layersManager.backgroundChangeNotifier,
      builder: (context, value, child) {
        return Drawer(
          backgroundColor: backgroundCoverArtColor,
          width: sidebarWidth(context),
          child: Column(
            children: [
              ValueListenableBuilder(
                valueListenable: sidebarColor.valueNotifier,
                builder: (context, value, child) {
                  return Container(
                    color: value,
                    height: MediaQuery.of(context).padding.top,
                  );
                },
              ),
              Expanded(
                child: Sidebar(
                  closeDrawer: () {
                    Navigator.pop(context);
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
