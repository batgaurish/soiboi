import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/artist_album.dart';
import 'package:soiboi/base/data/folder.dart';
import 'package:soiboi/base/data/history.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/loader.dart';
import 'package:soiboi/base/data/playlist.dart';
import 'package:soiboi/layer/layers_manager.dart';

void main() {
  setUpAll(() async {
    final tempDir = await Directory.systemTemp.createTemp('layers_manager_test_');
    appSupportDir = tempDir;
  });
  test('layersManager responds to model and loader notifiers', () {
    final initialSwitchCount = layersManager.switchNotifier.value;

    // Trigger clearDataLayers via Loader notifier
    Loader.clearDataLayersNotifier.value++;
    expect(layersManager.switchNotifier.value, initialSwitchCount + 1);

    // Trigger clearArtistAlbum via ArtistAlbumManager notifier
    ArtistAlbumManager.clearNotifier.value++;
    expect(layersManager.switchNotifier.value, initialSwitchCount + 2);

    // Trigger firstSync songs switch via Loader notifier
    Loader.firstSyncNotifier.value++;
    expect(layersManager.switchNotifier.value, initialSwitchCount + 3);

    // Model global notifiers can be bumped without throwing
    expect(() => Library.globalChangeNotifier.value++, returnsNormally);
    expect(() => Folder.globalChangeNotifier.value++, returnsNormally);
    expect(() => History.globalChangeNotifier.value++, returnsNormally);
    expect(() => playlistManager.updateNotifier.value++, returnsNormally);
  });
}
