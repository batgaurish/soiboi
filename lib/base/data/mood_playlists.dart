/// Ephemeral mood playlists surfaced on the Home screen.
///
/// These are not saved in the playlist store — they are auto-generated each
/// time the Home shelf builds, gated on whether the library actually has
/// enough matching tracks. A track with no acoustic features safely fails
/// every threshold (a missing number is not zero), so an unanalysed library
/// produces no mood cards.
///
/// The primary mood is time-of-day: a different spec for morning, daytime,
/// evening and night. "Deep Focus" is always offered so the shelf is never a
/// singleton. If nothing passes the gate, the shelf does not render at all.
library;

import 'package:flutter/material.dart';
import 'package:soiboi/base/data/library.dart';
import 'package:soiboi/base/data/smart_playlist.dart';
import 'package:soiboi/base/my_audio_metadata.dart';

/// One mood shown on Home, with the list and count already resolved.
class MoodCardData {
  const MoodCardData({
    required this.icon,
    required this.name,
    required this.playlist,
    required this.trackCount,
  });
  final IconData icon;
  final String name;
  final SmartPlaylist playlist;
  final int trackCount;
}

const _minTracks = 5;

SmartPlaylist _morning() => const SmartPlaylist(
  name: 'Morning Light',
  rules: [
    SmartRule(
      field: SmartField.energy,
      operator: SmartOperator.greaterThan,
      value: '0.2',
    ),
    SmartRule(
      field: SmartField.energy,
      operator: SmartOperator.lessThan,
      value: '0.5',
    ),
  ],
  sort: SmartSort.energy,
  descending: false, // gentlest first
);

SmartPlaylist _upbeat() => const SmartPlaylist(
  name: 'Upbeat Mix',
  rules: [
    SmartRule(
      field: SmartField.energy,
      operator: SmartOperator.greaterThan,
      value: '0.5',
    ),
    SmartRule(
      field: SmartField.danceable,
      operator: SmartOperator.greaterThan,
      value: '0.4',
    ),
  ],
  sort: SmartSort.energy,
  descending: true,
);

SmartPlaylist _evening() => const SmartPlaylist(
  name: 'Wind Down',
  rules: [
    SmartRule(
      field: SmartField.relaxed,
      operator: SmartOperator.greaterThan,
      value: '0.5',
    ),
    SmartRule(
      field: SmartField.energy,
      operator: SmartOperator.lessThan,
      value: '0.5',
    ),
  ],
  sort: SmartSort.relaxed,
  descending: true,
);

SmartPlaylist _lateNight() => const SmartPlaylist(
  name: 'Late Night Drive',
  rules: [
    SmartRule(
      field: SmartField.energy,
      operator: SmartOperator.lessThan,
      value: '0.3',
    ),
    SmartRule(
      field: SmartField.relaxed,
      operator: SmartOperator.greaterThan,
      value: '0.5',
    ),
  ],
  sort: SmartSort.energy,
  descending: false, // quietest first
);

SmartPlaylist _focus() => const SmartPlaylist(
  name: 'Deep Focus',
  rules: [
    SmartRule(
      field: SmartField.energy,
      operator: SmartOperator.greaterThan,
      value: '0.3',
    ),
    SmartRule(
      field: SmartField.danceable,
      operator: SmartOperator.lessThan,
      value: '0.4',
    ),
  ],
  sort: SmartSort.energy,
  descending: false, // steady
);

/// Pick the primary mood for the current hour.
SmartPlaylist _primaryFor(int hour) {
  if (hour >= 5 && hour < 11) return _morning();
  if (hour >= 11 && hour < 17) return _upbeat();
  if (hour >= 17 && hour < 22) return _evening();
  return _lateNight(); // 22:00–04:59
}

IconData _icon(int hour) {
  if (hour >= 5 && hour < 11) return Icons.wb_sunny_rounded;
  if (hour >= 11 && hour < 17) return Icons.brightness_5_rounded;
  if (hour >= 17 && hour < 22) return Icons.nights_stay_rounded;
  return Icons.brightness_2_rounded;
}

/// Moods that are worth showing right now, empty if nothing passes the gate.
List<MoodCardData> autoMoodPlaylists({DateTime? now, List<MyAudioMetadata>? songs}) {
  final when = now ?? DateTime.now();
  final hour = when.hour;

  final source = songs ?? library.songList;

  // Check whether any songs actually have acoustic features. On Android or
  // a desktop without essentia, analysis is unavailable and every energy /
  // danceable / relaxed value is null — so every acoustic rule matches
  // nothing and the shelf silently disappears, even on a library of
  // thousands. Falling back to metadata-only criteria keeps the shelf useful
  // rather than hiding a feature the user cannot see is broken.
  final hasAcoustic = source.any(
    (s) => s.energy != null || s.danceable != null || s.relaxed != null,
  );

  final primary = _primaryFor(hour);
  final primaryIcon = _icon(hour);
  final focus = _focus();

  final candidates = [
    (icon: primaryIcon, playlist: hasAcoustic ? primary : _metadataFallback(hour)),
    (icon: Icons.self_improvement_rounded, playlist: hasAcoustic ? focus : _metadataFocus()),
  ];

  final out = <MoodCardData>[];
  for (final c in candidates) {
    final tracks = c.playlist.evaluate(source);
    if (tracks.length < _minTracks) continue;
    out.add(
      MoodCardData(
        icon: c.icon,
        name: c.playlist.name,
        playlist: c.playlist,
        trackCount: tracks.length,
      ),
    );
  }
  return out;
}

// ---------------------------------------------------------------------------
// Metadata-only fallbacks, used when no song in the library has acoustic
// features. These use the same rule shape but query fields that are always
// populated (play count, date added, format) rather than ones that need
// audio analysis.
// ---------------------------------------------------------------------------

SmartPlaylist _metadataFallback(int hour) {
  if (hour >= 5 && hour < 11) {
    // Morning: recently added, quieter genres
    return const SmartPlaylist(
      name: 'Morning Light',
      rules: [
        SmartRule(
          field: SmartField.playCount,
          operator: SmartOperator.lessThan,
          value: '5',
        ),
      ],
      sort: SmartSort.added,
      descending: true,
      limit: 25,
    );
  }
  if (hour >= 11 && hour < 17) {
    // Daytime: energetic = recently played
    return const SmartPlaylist(
      name: 'Upbeat Mix',
      rules: [
        SmartRule(
          field: SmartField.playCount,
          operator: SmartOperator.greaterThan,
          value: '0',
        ),
      ],
      sort: SmartSort.playCount,
      descending: true,
      limit: 25,
    );
  }
  if (hour >= 17 && hour < 22) {
    // Evening: not played recently
    return const SmartPlaylist(
      name: 'Wind Down',
      rules: [
        SmartRule(
          field: SmartField.lastPlayed,
          operator: SmartOperator.notInLastDays,
          value: '7',
        ),
      ],
      sort: SmartSort.added,
      descending: true,
      limit: 25,
    );
  }
  // Late night: recently added
  return const SmartPlaylist(
    name: 'Late Night Drive',
    rules: [
      SmartRule(
        field: SmartField.added,
        operator: SmartOperator.inLastDays,
        value: '30',
      ),
    ],
    sort: SmartSort.added,
    descending: true,
    limit: 25,
  );
}

SmartPlaylist _metadataFocus() {
  // Deep focus: least played, so you discover things you haven't heard
  return const SmartPlaylist(
    name: 'Deep Focus',
    rules: [
      SmartRule(
        field: SmartField.playCount,
        operator: SmartOperator.lessThan,
        value: '3',
      ),
    ],
    sort: SmartSort.added,
    descending: true,
    limit: 25,
  );
}