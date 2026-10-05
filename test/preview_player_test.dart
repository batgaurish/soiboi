import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/services/preview_player.dart';

void main() {
  setUp(() {
    previewingKeyNotifier.value = null;
    previewProgressNotifier.value = 0;
  });

  tearDown(() async {
    await stopPreview();
  });

  test('notifiers initialize with default empty values', () {
    expect(previewingKeyNotifier.value, isNull);
    expect(previewProgressNotifier.value, 0);
  });

  test('togglePreview with null or empty url does not start preview', () async {
    await togglePreview('key_1', null);
    expect(previewingKeyNotifier.value, isNull);

    await togglePreview('key_2', '');
    expect(previewingKeyNotifier.value, isNull);
  });

  test('stopPreview resets previewing key and progress to zero', () async {
    previewingKeyNotifier.value = 'active_key';
    previewProgressNotifier.value = 0.5;

    await stopPreview();

    expect(previewingKeyNotifier.value, isNull);
    expect(previewProgressNotifier.value, 0);
  });
}
