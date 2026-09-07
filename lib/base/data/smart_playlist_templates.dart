/// Ready-made smart playlists someone can start from, one tap away from the
/// editor instead of a blank form.
///
/// Purely additive UI in front of the existing rule model: a template is just
/// a prefilled [SmartPlaylist]. The [SmartRule]s are built on the same
/// `SmartField`s the editor offers, so once a template has seeded the editor
/// the rest of the create flow is unchanged — nothing here needs the pipeline,
/// a network call or a service account.
///
/// Defaults are deliberately fixed and conservative. Editing handles
/// personalization (the thresholds and the sort), so these are meant to be
/// *interesting*, not perfect — and they only select what the library already
/// analysed: a track with no acoustic features matches none of the thresholds
/// (a missing number is not zero, per `SmartRule.matches`).
library;

import 'package:flutter/material.dart';
import 'package:soiboi/base/data/smart_playlist.dart';

/// One entry in the template picker: a name, an icon, and the playlist it
/// seeds the editor with.
class SmartPlaylistTemplate {
  const SmartPlaylistTemplate({
    required this.name,
    required this.icon,
    required this.description,
    required this.playlist,
  });

  final String name;
  final IconData icon;

  /// A one-line reading of what it selects, shown under the name so the picker
  /// says what each template means before anyone opens the editor.
  final String description;
  final SmartPlaylist playlist;
}

/// The fixed template set. Not fetched or updatable — shipping a `const` list
/// keeps this offline and deterministic, which is the point of the app.
const smartPlaylistTemplates = <SmartPlaylistTemplate>[
  SmartPlaylistTemplate(
    name: 'Energy',
    icon: Icons.bolt_rounded,
    description: 'Uptempo, high energy',
    playlist: SmartPlaylist(
      name: 'Energy',
      rules: [
        SmartRule(
          field: SmartField.energy,
          operator: SmartOperator.greaterThan,
          value: '0.6',
        ),
      ],
      sort: SmartSort.energy,
      descending: true,
    ),
  ),
  SmartPlaylistTemplate(
    name: 'Sleep',
    icon: Icons.bedtime_rounded,
    description: 'Relaxed and quiet',
    playlist: SmartPlaylist(
      name: 'Sleep',
      rules: [
        SmartRule(
          field: SmartField.relaxed,
          operator: SmartOperator.greaterThan,
          value: '0.6',
        ),
        SmartRule(
          field: SmartField.energy,
          operator: SmartOperator.lessThan,
          value: '0.4',
        ),
      ],
      sort: SmartSort.energy,
      descending: false, // quietest first
    ),
  ),
  SmartPlaylistTemplate(
    name: 'Workout',
    icon: Icons.fitness_center_rounded,
    description: 'Fast, high energy',
    playlist: SmartPlaylist(
      name: 'Workout',
      rules: [
        SmartRule(
          field: SmartField.energy,
          operator: SmartOperator.greaterThan,
          value: '0.6',
        ),
        SmartRule(
          field: SmartField.bpm,
          operator: SmartOperator.greaterThan,
          value: '120',
        ),
      ],
      sort: SmartSort.energy,
      descending: true,
    ),
  ),
  SmartPlaylistTemplate(
    name: 'Dance party',
    icon: Icons.music_note_rounded,
    description: 'Danceable and upbeat',
    playlist: SmartPlaylist(
      name: 'Dance party',
      rules: [
        SmartRule(
          field: SmartField.danceable,
          operator: SmartOperator.greaterThan,
          value: '0.6',
        ),
        SmartRule(
          field: SmartField.energy,
          operator: SmartOperator.greaterThan,
          value: '0.5',
        ),
      ],
      sort: SmartSort.danceable,
      descending: true,
    ),
  ),
  SmartPlaylistTemplate(
    name: 'Chill',
    icon: Icons.self_improvement_rounded,
    description: 'Laid-back, lower tempo',
    playlist: SmartPlaylist(
      name: 'Chill',
      rules: [
        SmartRule(
          field: SmartField.relaxed,
          operator: SmartOperator.greaterThan,
          value: '0.4',
        ),
        SmartRule(
          field: SmartField.energy,
          operator: SmartOperator.lessThan,
          value: '0.5',
        ),
      ],
      sort: SmartSort.energy,
      descending: false, // mellowest first
    ),
  ),
];
