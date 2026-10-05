import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/audio_handler.dart';
import 'package:soiboi/base/data/setting.dart';

void main() {
  test('relocated app-level view state notifiers initialize with expected defaults', () {
    expect(trialRemainingMinNotifier.value, -1);
    expect(displayLyricsPage, isFalse);
    expect(endDrawerNotifier.value, isFalse);
    expect(miniModeDisplayOverlayNotifier.value, isTrue);
    expect(miniViewMainHeight, 85.0);
    expect(miniViewDisplayBottom, isFalse);
    expect(miniViewDisplayLyricsNotifier.value, isTrue);
    expect(miniModeSwitching, isFalse);

    expect(lyricsFontSizeOffsetNotifier.value, 0.0);
    expect(lyricsTimeOffsetNotifier.value, 0);
    expect(lyricsFontWeightNotifier.value, FontWeight.bold);
    expect(updateLyricsNotifier.value, 0);
  });

  test('relocated setting and audio_handler state notifiers exist and can be mutated', () {
    expect(recursiveScanNotifier.value, isFalse);
    expect(sleepTimerOnNotifier.value, isFalse);
    expect(remainTimesNotifier.value, 0);
    expect(pauseAfterCompletedNotifier.value, isFalse);
    expect(needPause, isFalse);

    sleepTimerOnNotifier.value = true;
    expect(sleepTimerOnNotifier.value, isTrue);
    sleepTimerOnNotifier.value = false;
  });
}
