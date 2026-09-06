import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/utils/media_query.dart';
import 'package:soiboi/landscape_view/pages/landscape_lyrics_page.dart';
import 'package:soiboi/portrait_view/pages/portrait_lyrics_page.dart';

bool displayLyricsPage = false;

class LyricsPageLayer extends StatefulWidget {
  const LyricsPageLayer({super.key});

  @override
  State<StatefulWidget> createState() => _LyricsPageLayerState();
}

class _LyricsPageLayerState extends State<LyricsPageLayer> {
  @override
  void initState() {
    super.initState();
    displayLyricsPage = true;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await Future.delayed(Duration(milliseconds: 600));
      updateHoverFocusColor();
    });
  }

  @override
  void dispose() {
    displayLyricsPage = false;
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await Future.delayed(Duration(milliseconds: 600));
      updateHoverFocusColor();
    });
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (isTooNarrow(context)) {
      return PortraitLyricsPage();
    }
    return LandscapeLyricsPage();
  }
}
