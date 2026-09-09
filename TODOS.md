# Soiboi Bug Fix Backlog — history

## v4.2.3 — colour/flavour rework

v4.2.2 shipped notifier fixes for "Follow system colours only changes the
sidebar" and "Flavours don't change anything" (items 6/8 below), and they
were real bugs, but they weren't the whole story: even with the app
rebuilding correctly, `MyColor.updateColor()` resolved colour as
`dynamicColor() ?? flavourColor() ?? upstream`. Dynamic colour is on by
default (Android) and covers almost every token, so it always won and a
flavour's colours were essentially never visible — switching flavour looked
like it did nothing to anyone who hadn't turned system colours off first.

Fix: colour and flavour were split into two independent settings.

- `lib/base/theme/flavour.dart` — `Flavour` (Expressive/Glasshouse/Console)
  now controls only shape and motion (corner radius, density, surface
  treatment). It has no palette any more.
- `lib/base/theme/color_source.dart` (new) — `ColorSource` (off / matugen /
  prebuilt) controls colour, independently. "Prebuilt" is a new option: six
  named palettes (Dracula, Tokyo Night, Catppuccin, Nord, Gruvbox, Rosé
  Pine), each with a hand-picked light and dark variant.
- "Album art" is not a third thing bolted on here — it's the existing
  `ThemeType.vivid`, already implemented and already exposed per-page from
  the Theme setting. Selecting it needs no new plumbing.
- Settings: "Colour source" replaces "Follow system colours" with a proper
  three-way picker (App colours / Matugen–Material You / Prebuilt palette),
  the latter showing a swatch-and-name list.

Verified on both Android (emulator) and Linux: switching flavour with a
prebuilt palette active changes shape only; switching colour source changes
colour only; the interaction that caused the original complaint is gone.

## v4.2.2 — bug fixes

### P0 — Visible breakage
1. **Auto-generated playlists UI too white to be visible** — fixed: mood card uses `menuColor.value` instead of near-transparent `buttonColor.value`.
2. **Auto-generated playlists didn't populate any songs** — fixed: Home's ListenableBuilder now listens to `library.changeNotifier`; `mood_playlists.dart` also gained a metadata-only fallback for devices with no acoustic features (Android).
3. **Weekly Discoveries only on Downloads page, not Home** — fixed, same root cause as #2. Also fixed a related complaint not in the original list: the Downloads sheet used to cap at whatever the Home card had prefetched (4 tracks); it now resolves and displays the full playlist progressively.
4. **Albums randomly showing "not found in Apple Music Catalog"** — fixed: `apple_catalog_service.dart` retries once on a transient failure before caching a miss; timeouts and parse errors are never cached.
5. **Creating normal playlists — no option to add songs** — fixed: empty playlists show an "Add songs" button that opens the library picker (both portrait and landscape/desktop).

### P1 — Theming / Flavours
6. **Follow system colours only changes sidebar** — the notifier fix landed in v4.2.2; the actual root cause (colour source always beating flavour) is fixed in v4.2.3, see above.
7. **Fullscreen player should use album art colours (blurred)** — fixed: lyrics page always shows a blurred album-art background, not just in vivid mode.
8. **Flavours don't change anything** — same story as #6: notifier fix in v4.2.2, real fix (decoupling colour from flavour) in v4.2.3.

### P2 — Missing features
9. **No option to change download quality** — fixed: AAC/ALAC/FLAC selector in Settings, passed through to the pipeline.
10. **No Widevine wrapper login option for gamdl ALAC** — fixed: `.wvd` file picker + wrapper URL/toggle in Settings.
11. **Multi-source playlists** — fixed: ISRC-based matching (`resolveAppleTrackByIsrc`, `ExternalTrack.isrc`) resolves tracks from other platforms against Apple's catalog by ISRC first, falling back to keyword search. This *is* the "convert to an Apple Music playlist for gamdl" answer — no separate playlist-converter tool needed.

## Still open

- The smart playlist editor recreates a `TextEditingController` on every
  parent rebuild, so the cursor jumps to the end when a dropdown changes.
- A running download cannot be cancelled (needs transport change first).
- The Linux auto-updater has never been run end-to-end.
- On a wide desktop window (`LandscapeView`, not `BigPictureView`), Settings
  has no entry point from Home, Artists, or Albums — the gear icon only
  appears on pages that use `TitleBar` (Folders, a song list, etc.). Not
  reported as a bug, so left alone, but worth knowing.
- `apple_catalog_service.dart` contains a literal null byte, used
  deliberately as a cache-key separator (`'$artist\x00$title'`). It's valid
  Dart and works, but it makes git and GitHub treat the file as binary —
  diffs won't render on GitHub's web UI.
