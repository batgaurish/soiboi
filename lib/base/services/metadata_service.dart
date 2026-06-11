import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter/material.dart';
import 'package:sylvakru/base/my_audio_metadata.dart';
import 'package:sylvakru/base/services/emby_client.dart';
import 'package:sylvakru/base/services/navidrome_client.dart';
import 'package:sylvakru/base/services/webdav_client.dart';
import 'package:sylvakru/base/services/logger.dart';
import 'package:sylvakru/base/services/picture_load_scheduler.dart';

Future<void> loadPictureSafe(MyAudioMetadata? song) async {
  if (song == null || song.pictureLoaded) {
    return;
  }
  return pictureLoadScheduler.load(song.id, () => _loadPicture(song));
}

Future<void> _loadPicture(MyAudioMetadata song) async {
  try {
    Uint8List? bytes;

    switch (song.sourceType) {
      case .local:
        bytes = await readPictureAsync(song.path!);
        break;
      case .webdav:
        bytes = await readPictureAsync(
          song.path!,
          headers: webdavClient?.headers,
        );
        break;
      case .navidrome:
        bytes = await navidromeClient!.getPictureBytes(song.id);
        break;
      default:
        bytes = await embyClient!.getPictureBytes(song.id);
        break;
    }

    if (bytes != null) {
      File pictureFile = File(song.picturePath);
      if (!await pictureFile.exists()) {
        await pictureFile.create(recursive: true);
      }
      await pictureFile.writeAsBytes(bytes);
      song.pictureExist = true;
    }
  } catch (e) {
    logger.output(e.toString());
  }
  song.pictureLoaded = true;
}

Future<Color> computeCoverArtColor(MyAudioMetadata? song) async {
  if (song?.coverArtColor != null) {
    return song!.coverArtColor!;
  }
  Uint8List? bytes;
  await loadPictureSafe(song);

  if (song?.pictureExist == true) {
    File pictureFile = File(song!.picturePath);
    if (await pictureFile.exists()) {
      bytes = await pictureFile.readAsBytes();
    }
  }

  if (bytes == null) {
    song?.coverArtColor = Colors.grey;
    return Colors.grey;
  }
  final codec = await ui.instantiateImageCodec(
    bytes,
    targetWidth: 20,
    targetHeight: 20,
  );

  final frameInfo = await codec.getNextFrame();
  final image = frameInfo.image;

  final byteData = await image.toByteData(format: ui.ImageByteFormat.rawRgba);

  if (byteData == null) {
    return Colors.grey;
  }

  final Uint8List buffer = byteData.buffer.asUint8List();

  double r = 0;
  double g = 0;
  double b = 0;
  int count = 0;
  for (int i = 0; i < buffer.length; i += 4) {
    final int red = buffer[i];
    final int green = buffer[i + 1];
    final int blue = buffer[i + 2];
    final int alpha = buffer[i + 3];

    if (alpha == 0) {
      r += 128;
      g += 128;
      b += 128;
    } else {
      r += red;
      g += green;
      b += blue;
    }
    count++;
  }

  if (count == 0) return Colors.grey;

  r /= count;
  g /= count;
  b /= count;
  final color = Color.fromARGB(255, r.toInt(), g.toInt(), b.toInt());
  song!.coverArtColor = color;

  final luminance = 0.299 * r + 0.587 * g + 0.114 * b;
  const maxLuminance = 200;

  if (luminance > maxLuminance) {
    final reduction = luminance.toInt() - maxLuminance;

    song.lowerLuminance = Color.fromARGB(
      255,
      (r - reduction).clamp(0, 255).toInt(),
      (g - reduction).clamp(0, 255).toInt(),
      (b - reduction).clamp(0, 255).toInt(),
    );
  }
  return color;
}
