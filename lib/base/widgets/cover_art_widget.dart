import 'dart:io';

import 'package:flutter/material.dart';
import 'package:sylvakru/base/asset_images.dart';
import 'package:sylvakru/base/my_audio_metadata.dart';
import 'package:sylvakru/base/services/metadata_service.dart';
import 'package:smooth_corner/smooth_corner.dart';

class CoverArtWidget extends StatelessWidget {
  final double? size;
  final double borderRadius;
  final MyAudioMetadata? song;
  final String? picturePath;
  final double elevation;
  final Color? color;
  const CoverArtWidget({
    super.key,
    this.size,
    this.borderRadius = 0,
    this.song,
    this.picturePath,
    this.elevation = 0,
    this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      key: ValueKey(song?.updateNotifier.value),
      elevation: elevation,
      color: color ?? Colors.transparent,
      shape: SmoothRectangleBorder(
        smoothness: 1,
        borderRadius: BorderRadius.circular(borderRadius),
      ),
      clipBehavior: .antiAlias,
      child: content(context),
    );
  }

  Widget content(BuildContext context) {
    final cacheSize = size != null
        ? (size! * MediaQuery.of(context).devicePixelRatio).toInt()
        : null;

    if (picturePath != null) {
      return imageWidget(picturePath!, cacheSize);
    }
    if (song == null) {
      return musicNote();
    }

    if (song!.pictureLoaded) {
      return song!.pictureExist
          ? imageWidget(song!.picturePath, cacheSize)
          : musicNote();
    }

    return FutureBuilder(
      future: loadPictureSafe(song),
      builder: (context, asyncSnapshot) {
        if (asyncSnapshot.connectionState == ConnectionState.waiting) {
          return SizedBox(width: size, height: size);
        }

        if (asyncSnapshot.hasError) {
          return musicNote();
        }
        return imageWidget(song!.picturePath, cacheSize);
      },
    );
  }

  Widget imageWidget(String path, int? cacheSize) {
    final ImageProvider imageProvider = cacheSize != null
        ? ResizeImage(
            FileImage(File(path)),
            width: cacheSize,
            height: cacheSize,
          )
        : FileImage(File(path));

    return Image(
      image: imageProvider,
      width: size,
      height: size,
      fit: BoxFit.cover,
      gaplessPlayback: true,
      errorBuilder: (context, error, stackTrace) {
        return musicNote();
      },
    );
  }

  Widget musicNote() {
    return ImageIcon(musicNoteImage, size: size);
  }
}
