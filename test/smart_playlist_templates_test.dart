import 'dart:io';

import 'package:audio_tags_lofty/audio_tags_lofty.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:soiboi/base/app.dart';
import 'package:soiboi/base/data/smart_playlist.dart';
import 'package:soiboi/base/data/smart_playlist_templates.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

MyAudioMetadata song({
  required String id,
  String? title,
  String? artist,
  double? energy,
  double? danceable,
  double? relaxed,
  double? bpm,
  int? year,
}) {
  final meta = MyAudioMetadata(
    AudioMetadata(title: title ?? id, artist: artist, year: year),
    id: id,
    path: '/tmp/$id.m4a',
  );
  meta.energy = energy;
  meta.danceable = danceable;
  meta.relaxed = relaxed;
  meta.bpm = bpm;
  return meta;
}

void main() {
  appSupportDir = Directory.systemTemp.createTempSync('soiboi_templates_test');

  // A threshold-matching track that has no acoustic data at all must NOT match
  // the acoustic rules: a missing number is "not a number", not zero.
  test('a track with no acoustic features matches no template', () {
    final library = [song(id: 'plain', title: 'No analysis')];
    for (final template in smartPlaylistTemplates) {
      final hit = template.playlist
          .evaluate(library)
          .any((s) => s.id == 'plain');
      expect(hit, isFalse, reason: '${template.name} must ignore unanalysed');
    }
  });

  test('Energy picks upbeat tracks and sorts by energy descending', () {
    final t = smartPlaylistTemplates.firstWhere((t) => t.name == 'Energy');
    final library = [
      song(id: 'slow', energy: 0.3, title: 'Slow'),
      song(id: 'pump', energy: 0.85, title: 'Pump'),
      song(id: 'mid', energy: 0.7, title: 'Mid'),
    ];
    expect(t.playlist.evaluate(library).map((s) => s.id), ['pump', 'mid']);
  });

  test('Sleep requires relaxed and low energy', () {
    final t = smartPlaylistTemplates.firstWhere((t) => t.name == 'Sleep');
    final library = [
      song(id: 'good', relaxed: 0.9, energy: 0.1, title: 'Good'),
      song(id: 'loud', relaxed: 0.9, energy: 0.8, title: 'Loud'),
    ];
    expect(t.playlist.evaluate(library).map((s) => s.id), ['good']);
  });

  test('Workout combines energy and high BPM', () {
    final t = smartPlaylistTemplates.firstWhere((t) => t.name == 'Workout');
    final library = [
      song(id: 'fast', energy: 0.8, bpm: 130, title: 'Fast'),
      song(id: 'slow', energy: 0.8, bpm: 90, title: 'Slow'),
    ];
    expect(t.playlist.evaluate(library).map((s) => s.id), ['fast']);
  });

  test('every template seeds flags that the editor understands', () {
    // The templates are meant to drop straight into the editor, so each one
    // must carry a valid name and be const (already guaranteed by the const
    // list) — the real contract is that the rules reference known fields.
    for (final template in smartPlaylistTemplates) {
      expect(template.playlist.name, isNotEmpty);
      for (final rule in template.playlist.rules) {
        expect(SmartField.values, contains(rule.field));
        expect(SmartOperator.forKind(rule.field.kind), contains(rule.operator));
      }
    }
  });
}
