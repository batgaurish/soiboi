import 'package:flutter/widgets.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/base/widgets/settings_list.dart';

class BigSettingsPanel extends StatefulWidget {
  const BigSettingsPanel({super.key});

  @override
  State<StatefulWidget> createState() => _BigSettingsPanelState();
}

class _BigSettingsPanelState extends State<BigSettingsPanel> {
  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: .only(top: 75 + getTopOffset(context), bottom: 75),
      child: SettingsList(),
    );
  }
}
