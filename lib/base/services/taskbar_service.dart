import 'package:windows_taskbar/windows_taskbar.dart';

/// Windows taskbar thumbnail buttons. The player passes its own actions in,
/// so this service does not depend on it.
void setupTaskbar({
  required bool isPlaying,
  required void Function() onPrevious,
  required void Function() onTogglePlay,
  required void Function() onNext,
}) async {
  await WindowsTaskbar.setThumbnailToolbar([
    ThumbnailToolbarButton(
      ThumbnailToolbarAssetIcon('assets/previous.ico'),
      'Previous',
      onPrevious,
    ),

    ThumbnailToolbarButton(
      ThumbnailToolbarAssetIcon(
        isPlaying ? 'assets/pause.ico' : 'assets/play.ico',
      ),
      isPlaying ? 'Pause' : 'Play',
      onTogglePlay,
    ),

    ThumbnailToolbarButton(
      ThumbnailToolbarAssetIcon('assets/next.ico'),
      'Next',
      onNext,
    ),
  ]);
}
