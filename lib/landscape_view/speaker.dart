import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/asset_images.dart';
import 'package:soiboi/base/widgets/app_icon.dart';
import 'package:soiboi/base/widgets/icon_label.dart';

double? _volumeTmp;

class Speaker extends StatelessWidget {
  final Color color;
  const Speaker({super.key, required this.color});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder(
      valueListenable: volumeNotifier,
      builder: (_, value, _) {
        if (value == 0) {
          return IconButton(
            color: color,
            tooltip: 'Unmute',
            onPressed: () {
              if (_volumeTmp != null) {
                volumeNotifier.value = _volumeTmp!;
                audioHandler.setVolume(_volumeTmp!);
                audioHandler.savePlayState();
              }
            },
            icon: labelIcon('Unmute', AppIcon(speakerOffImage, size: 25)),
          );
        }
        _volumeTmp = null;

        return IconButton(
          color: color,
          tooltip: 'Mute',
          onPressed: () {
            _volumeTmp = volumeNotifier.value;
            volumeNotifier.value = 0;
            audioHandler.setVolume(0);
            audioHandler.savePlayState();
          },
          icon: labelIcon('Mute', AppIcon(speakerImage, size: 25)),
        );
      },
    );
  }
}
