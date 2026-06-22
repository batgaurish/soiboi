part of '../../layer/about_layer.dart';

extension _AboutPanel on _AboutLayerState {
  Widget panelView(BuildContext context) {
    return Column(
      children: [
        TitleBar(
          backToRoot: () {
            layersManager.popDetail('settings');
          },
        ),
        Expanded(child: content()),
      ],
    );
  }
}
