import 'dart:io';
import 'dart:ui' as ui;

import 'package:material_ui/material_ui.dart';
import 'package:flutter/services.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/my_audio_metadata.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/song_deletion.dart';
import 'package:soiboi/base/widgets/my_divider.dart';
import 'package:soiboi/base/widgets/playlist_widgets.dart';
import 'package:soiboi/l10n/generated/app_localizations.dart';
import 'package:soiboi/layer/layers_manager.dart';
import 'package:smooth_corner/smooth_corner.dart';

class MenuItem {
  final IconData? iconData;
  final String? text;
  final void Function()? callback;
  final bool isDivider;

  MenuItem({this.iconData, this.text, this.callback, this.isDivider = false});
}

/// The actions for one song, for a right-click or long-press menu anywhere a
/// song is shown on its own (Home's shelves, for one).
List<MenuItem> songMenuItems(BuildContext context, MyAudioMetadata song) {
  final l10n = AppLocalizations.of(context);
  return [
    MenuItem(
      iconData: Icons.play_arrow_rounded,
      text: l10n.playNow,
      callback: () {
        audioHandler.singlePlay(song);
        audioHandler.saveAllStates();
      },
    ),
    MenuItem(
      iconData: Icons.navigate_next_rounded,
      text: l10n.playNext,
      callback: () {
        playQueue.isEmpty
            ? audioHandler.singlePlay(song)
            : audioHandler.insert2Next(song);
        audioHandler.saveAllStates();
      },
    ),
    MenuItem(
      iconData: Icons.playlist_add_rounded,
      text: l10n.add2Queue,
      callback: () {
        playQueue.isEmpty
            ? audioHandler.singlePlay(song)
            : audioHandler.add2Last(song);
        audioHandler.saveAllStates();
      },
    ),
    MenuItem(
      iconData: Icons.add_rounded,
      text: l10n.add2Playlist,
      callback: () => showAddPlaylistDialog(context, [song]),
    ),
    ...deleteMenuItems(context, [song]),
  ];
}

/// "Delete from device" for [songs], or nothing when none of them are files
/// on this device.
List<MenuItem> deleteMenuItems(
  BuildContext context,
  List<MyAudioMetadata> songs,
) => [
  if (songs.any(canDeleteFromDevice))
    MenuItem(
      iconData: Icons.delete_forever_rounded,
      text: 'Delete from device',
      callback: () => confirmAndDeleteSongs(context, songs),
    ),
];

void showContextMenu(
  BuildContext context,
  List<MenuItem> items,
  Offset globalPosition,
) {
  if (Platform.isIOS) {
    final RenderBox? renderBox = context.findRenderObject() as RenderBox?;
    if (renderBox != null && renderBox.hasSize) {
      final Size size = renderBox.size;
      final Offset position = renderBox.localToGlobal(Offset.zero);

      NativeMenu.showForIOS(items, position, size);
    }
    return;
  }

  if (Platform.isMacOS) {
    NativeMenu.show(items);
    return;
  }

  Navigator.of(context, rootNavigator: true).push(
    PageRouteBuilder(
      opaque: false,
      barrierDismissible: true,
      barrierColor: Colors.transparent,
      transitionDuration: Duration.zero,
      reverseTransitionDuration: Duration.zero,
      pageBuilder: (context, _, _) {
        bool first = true;

        // stack is important to position, I don't know why
        return Stack(
          children: [
            LayoutBuilder(
              builder: (context, _) {
                WidgetsBinding.instance.addPostFrameCallback((timeStamp) {
                  if (first) {
                    first = false;
                    return;
                  }
                  Navigator.of(context).pop();
                });
                return CustomSingleChildLayout(
                  delegate: MenuPositionDelegate(
                    globalPosition,
                    MediaQuery.of(context).size,
                  ),
                  child: ListenableBuilder(
                    listenable: Listenable.merge([
                      layersManager.backgroundChangeNotifier,
                      currentSongNotifier,
                    ]),
                    builder: (context, value) {
                      return Material(
                        color: Color.alphaBlend(
                          colorManager.getSpecificMenuColor(),
                          colorManager.getSpecificBgBaseColor(),
                        ),
                        elevation: 6.0,
                        shape: SmoothRectangleBorder(
                          smoothness: 1,
                          borderRadius: .circular(8),
                        ),

                        child: IntrinsicWidth(
                          child: Padding(
                            padding: const EdgeInsets.all(6.0),
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              children: items.map((item) {
                                if (item.isDivider) {
                                  return Padding(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 4,
                                      vertical: 4,
                                    ),
                                    child: MyDivider(
                                      color: dividerColor,
                                      height: 1,
                                    ),
                                  );
                                }
                                return InkWell(
                                  mouseCursor: SystemMouseCursors.click,
                                  onTap: () {
                                    Navigator.of(context).pop();
                                    item.callback?.call();
                                  },
                                  child: Padding(
                                    padding: EdgeInsets.symmetric(
                                      horizontal: 10,
                                      vertical: Platform.isAndroid ? 8 : 5,
                                    ),
                                    child: Row(
                                      children: [
                                        if (item.iconData != null) ...[
                                          Icon(
                                            item.iconData,
                                            size: 18,
                                            color: colorManager
                                                .getSpecificIconColor(),
                                          ),
                                          const SizedBox(width: 10),
                                        ],
                                        Text(
                                          item.text!,
                                          style: .new(
                                            color: colorManager
                                                .getSpecificTextColor(),
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                );
                              }).toList(),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                );
              },
            ),
          ],
        );
      },
    ),
  );
}

class MenuPositionDelegate extends SingleChildLayoutDelegate {
  final Offset position;
  final Size screenSize;

  MenuPositionDelegate(this.position, this.screenSize);

  @override
  Offset getPositionForChild(Size size, Size childSize) {
    double x = position.dx;
    double y = position.dy;

    if (x + childSize.width > screenSize.width) {
      x -= childSize.width;
    }
    if (y + childSize.height > screenSize.height) {
      y -= childSize.height;
    }

    return Offset(x.clamp(0, screenSize.width), y.clamp(0, screenSize.height));
  }

  @override
  bool shouldRelayout(covariant SingleChildLayoutDelegate oldDelegate) => true;
}

class NativeMenu {
  static const _channel = MethodChannel('com.batgaurish.soiboi.menu');

  static final Map<IconData, Uint8List> _iconMap = {};

  static Future<void> init() async {
    await _channel.invokeMethod('initNativeMenu');
  }

  static Future<void> initIcons() async {
    await _iconToPng(Icons.vertical_align_top_rounded);
    await _iconToPng(Icons.play_arrow_rounded);
    await _iconToPng(Icons.navigate_next_rounded);
    await _iconToPng(Icons.playlist_add_rounded);
    await _iconToPng(Icons.add_rounded);
    await _iconToPng(Icons.people);
    await _iconToPng(Icons.album_rounded);
    await _iconToPng(Icons.info_outline_rounded);
    await _iconToPng(Icons.edit_rounded);
    await _iconToPng(Icons.delete_rounded);
    await _iconToPng(Icons.navigate_next_rounded);
    await _iconToPng(Icons.close_rounded);
    await _iconToPng(Icons.reorder_rounded);
    await _iconToPng(Icons.delete);
  }

  static Future<void> _iconToPng(IconData icon) async {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);

    final painter = TextPainter(
      textDirection: TextDirection.ltr,
      text: TextSpan(
        text: String.fromCharCode(icon.codePoint),
        style: TextStyle(
          fontFamily: icon.fontFamily,
          package: icon.fontPackage,
          fontSize: 96,
          color: Colors.black,
        ),
      ),
    );

    painter.layout();
    painter.paint(canvas, Offset.zero);

    final image = await recorder.endRecording().toImage(
      painter.width.ceil(),
      painter.height.ceil(),
    );

    final data = await image.toByteData(format: ui.ImageByteFormat.png);

    final result = data!.buffer.asUint8List();
    _iconMap[icon] = result;
  }

  static Future<void> show(List<MenuItem> items) async {
    final menuData = items.map((item) {
      return {
        'text': item.text,
        'isDivider': item.isDivider,
        'iconBytes': item.iconData != null ? _iconMap[item.iconData] : null,
      };
    }).toList();

    _channel.setMethodCallHandler((call) async {
      if (call.method == "onMenuItemSelected") {
        final int index = call.arguments;
        items[index].callback?.call();
      }
    });

    await _channel.invokeMethod('showNativeMenu', {'items': menuData});
  }

  static Future<void> showForIOS(
    List<MenuItem> items,
    Offset position,
    Size size,
  ) async {
    final menuData = items.map((item) {
      return {
        'text': item.text,
        'isDivider': item.isDivider,
        'iconBytes': item.iconData != null ? _iconMap[item.iconData] : null,
      };
    }).toList();

    _channel.setMethodCallHandler((call) async {
      if (call.method == "onMenuItemSelected") {
        final int index = call.arguments;
        items[index].callback?.call();
      }
    });

    await _channel.invokeMethod('showNativeMenu', {
      'items': menuData,
      'x': position.dx,
      'y': position.dy,
      'width': size.width,
      'height': size.height,
    });
  }
}
