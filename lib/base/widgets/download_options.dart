/// Download quality and download folder choices.
///
/// Shared by the Settings dialogs and the setup wizard, so both save the same
/// settings the same way and a folder is checked before it is accepted in
/// either place.
library;

import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:material_ui/material_ui.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/setting.dart';
import 'package:soiboi/base/services/color_manager.dart';
import 'package:soiboi/base/services/interaction.dart';
import 'package:soiboi/base/services/pipeline_runner.dart';

/// Makes [path] the download folder, but only if it can actually be written
/// to. Returns whether it was accepted.
///
/// Android grants All files access separately from anything the folder
/// picker returns, so a path can be chosen and still be unwritable. Testing
/// it here turns a silent failure at download time into an answer now.
Future<bool> useDownloadFolder(String path) async {
  if (Platform.isAndroid && !downloadDirIsUsable(path)) {
    final status = await Permission.manageExternalStorage.request();
    if (!status.isGranted) {
      showCenterMessage(
        'Soiboi needs All files access to save there.',
        duration: 4000,
      );
      return false;
    }
  }
  if (!downloadDirIsUsable(path)) {
    showCenterMessage('That folder cannot be written to.', duration: 4000);
    return false;
  }
  downloadFolderNotifier.value = path;
  downloadOutputDir = resolveDownloadDir();
  setting.save();
  return true;
}

/// The download folder as a short phrase.
String downloadFolderLabel(String value) =>
    value.trim().isEmpty ? "The app's own folder" : value;

/// AAC or ALAC, saved as soon as one is chosen.
class DownloadQualityPicker extends StatelessWidget {
  const DownloadQualityPicker({super.key, this.onChanged});

  /// Called after a choice is saved.
  final VoidCallback? onChanged;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<String>(
      valueListenable: downloadCodecNotifier,
      builder: (context, codec, _) => RadioGroup<String>(
        groupValue: codec,
        onChanged: (value) {
          if (value == null) return;
          downloadCodecNotifier.value = value;
          setting.save();
          onChanged?.call();
        },
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final entry in downloadCodecLabels.entries)
              RadioListTile<String>(
                value: entry.key,
                title: Text(entry.value),
                subtitle: Text(
                  entry.key == 'alac'
                      ? 'Lossless, about three times the size. Needs the '
                            'lossless sign-in.'
                      : 'Works with either sign-in.',
                  style: TextStyle(fontSize: 11, color: textColor.value),
                ),
                dense: true,
              ),
          ],
        ),
      ),
    );
  }
}

/// Where downloads are saved: the app's own folder, one of the library's
/// folders, or any other writable folder.
///
/// Offering the folders already configured first is the point: the common
/// case is "put downloads with the music I already have", and those are
/// exactly the folders the library scans.
class DownloadFolderPicker extends StatelessWidget {
  const DownloadFolderPicker({super.key, this.shrinkWrap = false});

  /// True inside a scrolling page, false to fill a fixed-height dialog.
  final bool shrinkWrap;

  List<String> _candidates(String current) {
    final candidates = <String>[
      for (final folder in library.folderList)
        if (!folder.isWebdav && folder.path != defaultDownloadDir) folder.path,
    ];
    // A folder chosen with "Choose another folder" stays listed, or the
    // selection would vanish from the list.
    if (current.isNotEmpty && !candidates.contains(current)) {
      candidates.add(current);
    }
    return candidates;
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<String>(
      valueListenable: downloadFolderNotifier,
      builder: (context, value, _) => RadioGroup<String>(
        groupValue: value,
        onChanged: (v) async {
          if (v == null) return;
          if (v.isEmpty) {
            downloadFolderNotifier.value = '';
            downloadOutputDir = resolveDownloadDir();
            setting.save();
          } else {
            await useDownloadFolder(v);
          }
        },
        child: ListView(
          shrinkWrap: shrinkWrap,
          physics: shrinkWrap ? const NeverScrollableScrollPhysics() : null,
          children: [
            RadioListTile<String>(
              value: '',
              dense: true,
              title: const Text("The app's own folder"),
              subtitle: Text(
                'Private to Soiboi, always writable',
                style: TextStyle(fontSize: 11, color: textColor.value),
              ),
            ),
            for (final path in _candidates(value))
              RadioListTile<String>(
                value: path,
                dense: true,
                title: Text(path, style: const TextStyle(fontSize: 13)),
              ),
            ListTile(
              dense: true,
              leading: const Icon(Icons.create_new_folder_outlined),
              title: const Text('Choose another folder…'),
              onTap: () async {
                final picked = await FilePicker.getDirectoryPath();
                if (picked == null) return;
                await useDownloadFolder(picked);
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// The download folder choice as a dialog, from Settings and from the
/// Downloads status panel.
Future<void> showDownloadFolderDialog(BuildContext context) {
  return showAnimationDialog(
    context: context,
    child: SizedBox(
      width: 380,
      height: 420,
      child: Padding(
        padding: const EdgeInsets.all(18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Download folder',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(
              'Where archived music is saved. Choosing a folder you already '
              'scan keeps downloads and library together.',
              style: TextStyle(fontSize: 11, color: textColor.value),
            ),
            const SizedBox(height: 12),
            const Expanded(child: DownloadFolderPicker()),
            Builder(
              builder: (context) => Align(
                alignment: Alignment.centerRight,
                child: FilledButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Done'),
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
