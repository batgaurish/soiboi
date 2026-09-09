import 'package:material_ui/material_ui.dart';
import 'package:smooth_corner/smooth_corner.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/asset_images.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/utils/metadata_utils.dart';
import 'package:soiboi/base/widgets/cover_art_widget.dart';
import 'package:soiboi/base/widgets/my_divider.dart';
import 'package:soiboi/base/widgets/my_navigator.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/landscape_view/title_bar.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:soiboi/portrait_view/custom_appbar_leading.dart';
import 'package:soiboi/base/widgets/app_icon.dart';

part '../landscape_view/panels/folders_panel.dart';
part '../portrait_view/pages/folders_page.dart';

final GlobalKey<NavigatorState> foldersKey = GlobalKey();
final foldersVisibleNotifier = ValueNotifier(true);

class FoldersLayer extends StatelessWidget {
  const FoldersLayer({super.key});

  @override
  Widget build(BuildContext context) {
    return myNavigator(
      key: foldersKey,
      visibleNotifier: foldersVisibleNotifier,
      pageViewBuilder: () => pageView(context),
      panelViewBuilder: () => panelView(context),
    );
  }
}
