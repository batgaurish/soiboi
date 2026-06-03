import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:sylvakru/base/app.dart';
import 'package:sylvakru/base/audio_handler.dart';
import 'package:sylvakru/base/utils/dynamic_route.dart';
import 'package:sylvakru/landscape_view/landscape_view.dart';
import 'package:sylvakru/landscape_view/pages/landscape_lyrics_page.dart';
import 'package:sylvakru/landscape_view/sidebar.dart';
import 'package:sylvakru/layer/lyrics_page_layer.dart';
import 'package:sylvakru/mini_view/mini_view.dart';
import 'package:sylvakru/portrait_view/portrait_view.dart';

bool isLandscape = false;

class ViewEntry extends StatefulWidget {
  const ViewEntry({super.key});

  @override
  State<StatefulWidget> createState() => _ViewEntryState();
}

class _ViewEntryState extends State<ViewEntry> with WidgetsBindingObserver {
  bool systemCanPop = false;
  Timer? _exitTimer;

  @override
  void initState() {
    super.initState();
    if (Platform.isAndroid) {
      WidgetsBinding.instance.addObserver(this);
    }

    if (autoPlayOnStartupNotifier.value && currentSongNotifier.value != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        Navigator.of(
          context,
          rootNavigator: true,
        ).push(DynamicRoute(pageBuilder: (_, _, _) => LyricsPageLayer()));
      });
    }

    if (isTV) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        songsFocusNode.requestFocus();
      });
    }
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
    if (Platform.isAndroid && state == AppLifecycleState.resumed) {
      systemCanPop = false;
      _exitTimer?.cancel();
      // rebuild PopScope to allow it to handle pop
      setState(() {
        if (isTV) {
          if (displayLyricsPage) {
            playControlScopeNode.requestFocus();
          } else {
            songsFocusNode.requestFocus();
          }
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: miniModeNotifier,
      builder: (context, miniMode, child) {
        if (miniMode) {
          return MiniView();
        }
        return OrientationBuilder(
          builder: (context, orientation) {
            if (isMobile && orientation == Orientation.portrait) {
              SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
              isLandscape = false;
              return PortraitView();
            } else {
              SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersive);
              isLandscape = true;
              return LandscapeView();
            }
          },
        );
      },
    );
  }
}
