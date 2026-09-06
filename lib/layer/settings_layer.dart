import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/widgets/my_navigator.dart';
import 'package:soiboi/base/widgets/settings_list.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/landscape_view/title_bar.dart';
import 'package:soiboi/portrait_view/custom_appbar_leading.dart';

final GlobalKey<NavigatorState> settingsKey = GlobalKey();
final settingsVisibleNotifier = ValueNotifier(true);

class SettingsLayer extends StatelessWidget {
  const SettingsLayer({super.key});

  @override
  Widget build(BuildContext context) {
    return myNavigator(
      key: settingsKey,
      visibleNotifier: settingsVisibleNotifier,
      pageViewBuilder: () => ValueListenableBuilder(
        valueListenable: mainPageThemeNotifier,
        builder: (context, value, child) {
          return Scaffold(
            backgroundColor: Colors.transparent,
            resizeToAvoidBottomInset: false,
            appBar: AppBar(
              automaticallyImplyLeading: false,
              leading: customAppBarLeading(context),
              backgroundColor: Colors.transparent,
              systemOverlayStyle: mainPageThemeNotifier.value == .dark
                  ? .light
                  : .dark,
              elevation: 0,
              scrolledUnderElevation: 0,
              title: Text(AppLocalizations.of(context).settings),
              centerTitle: true,
            ),
            body: SettingsList(iconSize: 30),
          );
        },
      ),
      panelViewBuilder: () => Column(
        children: [
          TitleBar(),
          Expanded(child: SettingsList()),
        ],
      ),
    );
  }
}
