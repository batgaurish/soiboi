import 'package:flutter/material.dart';
import 'package:sylvakru/base/app.dart';
import 'package:sylvakru/base/widgets/settings_list.dart';
import 'package:sylvakru/landscape_view/title_bar.dart';
import 'package:sylvakru/layer/layers_manager.dart';
import 'package:sylvakru/portrait_view/custom_appbar_leading.dart';

final GlobalKey<NavigatorState> settingsKey = GlobalKey();
final settingsVisibleNotifier = ValueNotifier(true);

class SettingsLayer extends StatelessWidget {
  const SettingsLayer({super.key});

  @override
  Widget build(BuildContext context) {
    return NavigatorPopHandler(
      onPopWithResult: (result) {
        layersManager.popDetail('settings');
      },

      child: Navigator(
        key: settingsKey,
        onGenerateRoute: (settings) => MaterialPageRoute(
          builder: (_) => OrientationBuilder(
            builder: (context, orientation) {
              if (isMobile && orientation == Orientation.portrait) {
                return Scaffold(
                  backgroundColor: Colors.transparent,
                  resizeToAvoidBottomInset: false,
                  appBar: AppBar(
                    automaticallyImplyLeading: false,
                    leading: customAppBarLeading(context),

                    backgroundColor: Colors.transparent,
                    elevation: 0,
                    scrolledUnderElevation: 0,
                  ),
                  body: SettingsList(iconSize: 30),
                );
              } else {
                return ValueListenableBuilder(
                  valueListenable: settingsVisibleNotifier,
                  builder: (context, value, child) {
                    return Opacity(
                      opacity: value ? 1 : 0,
                      child: Column(
                        children: [
                          TitleBar(),
                          Expanded(child: SettingsList()),
                        ],
                      ),
                    );
                  },
                );
              }
            },
          ),
        ),
      ),
    );
  }
}
