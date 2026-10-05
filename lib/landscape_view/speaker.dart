import 'package:material_ui/material_ui.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/asset_images.dart';
import 'package:soiboi/base/widgets/app_icon.dart';
import 'package:soiboi/base/widgets/icon_label.dart';

double? _volumeBeforeMute;

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
              if (_volumeBeforeMute != null) {
                volumeNotifier.value = _volumeBeforeMute!;
                audioHandler.setVolume(_volumeBeforeMute!);
                audioHandler.savePlayState();
              }
            },
            icon: labelIcon('Unmute', AppIcon(speakerOffImage, size: 25)),
          );
        }
        _volumeBeforeMute = null;

        return IconButton(
          color: color,
          tooltip: 'Mute',
          onPressed: () {
            _volumeBeforeMute = volumeNotifier.value;
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
