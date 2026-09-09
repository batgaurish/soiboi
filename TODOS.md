# Soiboi Bug Fix Backlog — v4.2.2

## Bug Fixes (in priority order)

### P0 — Visible breakage
1. **Auto-generated playlists UI too white to be visible** — mood card uses `buttonColor.value` which is nearly transparent in vivid mode. Fix: use `menuColor.value` for card background.
2. **Auto-generated playlists didn't populate any songs** — Home's ListenableBuilder doesn't listen to `library.changeNotifier`, so moods are evaluated against empty library and never re-evaluated. Fix: add library notifier.
3. **Weekly Discoveries only on Downloads page, not Home** — same root cause: Home doesn't rebuild after library loads. Also, mood rules need acoustic features which may be missing. Fix: add library notifier + retry.
4. **Albums randomly showing "not found in Apple Music Catalog"** — iTunes Search API rate-limits/timeout returns null which gets cached. Fix: retry logic + don't cache failures on timeout.
5. **Creating normal playlists — no option to add songs** — `Add2PlaylistPanel` exists but no "add songs" button on empty playlists. Fix: add "add songs" action.

### P1 — Theming / Flavours
6. **Follow system colours only changes sidebar** — sidebar uses ValueListenableBuilder on its color, but rest of app reads `.value` directly and only rebuilds when mainPageThemeNotifier fires. Fix: add flavour/dynamic notifiers to main.dart's ListenableBuilder.
7. **Fullscreen player should use album art colours (blurred)** — add blurred album art background to lyrics page.
8. **Flavours don't change anything** — same root cause as #6. `colorManager.updateColors()` is called but widgets don't rebuild because mainPageThemeNotifier doesn't change. Fix: add flavourNotifier to main.dart's ListenableBuilder.

### P2 — Missing features
9. **No option to change download quality** — downloader.py already accepts `codec` param. Fix: add quality setting to settings, pass through to pipeline.
10. **No Widevine wrapper login option for gamdl ALAC** — downloader.py already accepts `wvd_path`, `use_wrapper`, `wrapper_url`. Fix: add UI for these.
11. **Multi-source playlists** — ISRC matching to convert playlists from other platforms to Apple Music via iTunes Search API `isrc` parameter.

## Implementation order
1. main.dart: add flavourNotifier + dynamicColor notifiers to ListenableBuilder → fixes bugs 6, 8
2. home_layer.dart: add library.changeNotifier to ListenableBuilder → fixes bugs 2, 3
3. home_layer.dart: fix mood card colors → fixes bug 1
4. mood_playlists.dart: add fallback criteria for missing acoustic features → fixes bug 2
5. apple_catalog_service.dart: add retry logic → fixes bug 4
6. playlist creation: add "add songs" button → fixes bug 5
7. lyrics page: add blurred album art background → fixes bug 7
8. settings + pipeline: add download quality → fixes bug 9
9. settings + pipeline: add Widevine config → fixes bug 10
10. discovery_service: add ISRC matching → fixes bug 11
