# Soiboi — handover

Paste this at the start of a new session. Last updated on 2026-09-25, at
the end of the session that built **Phase 2 (First run and setup)** on
`scope-expansion` and fixed three bugs the user found on beta.3 (duplicate
film songs, compilation albums chosen over soundtracks, stale covers), plus
one found while testing (the wrapper forgot its sign-in after going
offline). Phases 0, 1 and 2 are built; Phase 3 is next on the roadmap (3.1
is already built). The sections from "How the lossless wrapper is put
together" onward are carried over from older handovers and are still
accurate.

> **Start here:** **v1.2.0-beta.4** is out (`7ebd563`, APK + Linux
> tarball). Read "Beta.4: logs, storefront, matching" and "Built this
> session (Phase 2 and beta.3 bugs)", and the traps (the emulator debug app
> is signed in to the wrapper; Linux test builds need real isolation from
> the user's desktop). All checks pass at `7ebd563`: flutter analyze 0
> issues, 368 Dart tests (1 skipped), 119 pytest (also against the bundled
> gamdl 3.9.1), the live catalog test. Open questions for the user:
> whether the download-engine fixes go to `main` for 1.1.8 (rc.2 has the
> bug), and a pending chip to trace why an Apple *library* playlist track
> got the playlist's cover ("In Your Feels" on Treat You Better).

---

## What Soiboi is

Cross-platform music player (Linux + Android) forked from **Sylvakru**
(Apache-2.0), with a streaming-to-offline archival pipeline embedded **inside
the app** (Python, gamdl 3.9, run through Chaquopy on Android and a bundled
venv on Linux). Metadata and the actual archive/decrypt step are Apple
Music; playlist *discovery* spans platforms (ListenBrainz, YouTube Music,
Deezer, Spotify public pages, Tidal, JioSaavn, SoundCloud, Qobuz, Gaana,
Bandcamp, pasted tracklists).

Repo on the user's machine:
`~/Projects/Soiboi - Local Music Player with Download and Lyric Support/soiboi`
GitHub: `github.com/batgaurish/soiboi` (public; `origin`). The `upstream`
remote points at `AfalpHy/sylvakru` — **never push there**. With `gh`,
always pass `-R batgaurish/soiboi`.

Standing product constraints, already decided, not up for re-litigation:

- **Strictly offline.** No streaming services beyond self-hosted, for playback.
- **Apple album IDs are canonical, not MusicBrainz.**
- **Standalone on both platforms.** No Docker, no server, no Syncthing.
  Whatever device downloads a track does the whole job and keeps the only copy.
- **Scrobbling out of scope** (recommend Pano Scrobbler).
- Codec/quality badges stay obvious in every flavour.
- Three flavours: Zine (default), Liner Notes, Signal.
- Private distribution: the user and friends. Not F-Droid, not Play Store.
- **No accounts, no OAuth, no client secrets** for any external source.
- **Accessibility is a rule from Phase 1 on:** every new widget gets a
  label, a sane focus order and readable contrast the first time.

---

## Branches, versions and releases (read this first)

Two lines of work run side by side:

| Branch | What goes there | Version on it now |
|---|---|---|
| `main` | Bug fixes for the stable 1.1.x line only | 1.1.8+19 (rc.2) |
| `scope-expansion` | Everything from `docs/scope-expansion-plan.md`, plus what the user asks for on the beta | 1.2.0-beta.3+22 |

Rules the user set, verbatim or near it:

- Every commit from the plan goes to `scope-expansion`, **never to `main`**.
  Don't merge, cherry-pick, rebase onto or push plan work to `main`, and
  don't open a PR into `main` unless asked.
- `main` only gets stable-line fixes. After such a fix:
  `git switch scope-expansion && git merge main` (keep the branch's version
  of any file the plan rewrote, e.g. `keyboard.dart`, `pubspec.yaml`'s
  version line).
- **Before every commit, run `git branch --show-current`.**
- Phases are milestones, not releases. Publish a GitHub release only when
  the user asks; from the branch it is a pre-release
  (`--prerelease`, never `--latest`).
- Don't bump `pubspec.yaml` on the branch unless the user asks for a test
  build. (They did, for 1.2.0-beta.1, beta.2 and beta.3.)
- The user decides when everything goes onto `main` and an official release
  is cut. **Wait for them to say so.**
- Never commit the stray `scorecard.png`: `git add -A -- . ':!scorecard.png'`.
- End every commit message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (plus the
  session's `Claude-Session:` line if the harness gives one).
- One plan item per commit. Tick the plan's checkbox only when its
  "Done when" is fully met, including the phone checks; until then, write a
  `*Status:*` line under the item saying what was checked and what wasn't.
- Confirm before anything irreversible or outward-facing: force-pushes,
  deleting releases or tags, merging into `main`, publishing.
- Report plainly what was verified and what wasn't.
- Never `pkill -f <path>` (it has killed the user's session before); use
  `pkill -x soiboi`. Never read or log cookie values. Never enter the
  user's Apple ID credentials. **Never regenerate the Android signing key.**
  **Never run `dart format lib`**: format only files you touched.

### Where things stand right now (2026-09-25)

Published: **v1.1.7** is the latest official release. Test pre-releases
(all `--prerelease --latest=false`, so the updater never offers them):

| Tag | Commit | Branch | versionCode | Assets |
|---|---|---|---|---|
| `v1.1.8-rc.1` | `b802a83` | main | 19 | APK only |
| `v1.2.0-beta.1` | `94da507` | scope-expansion | 20 | APK only |
| `v1.1.8-rc.2` | `66ab86a` | main | 19 | APK + Linux tarball |
| `v1.2.0-beta.2` | `2f5e817` | scope-expansion | 21 | APK + Linux tarball |
| `v1.2.0-beta.3` | `f6823bd` | scope-expansion | 22 | APK + Linux tarball |
| `v1.2.0-beta.4` | `7ebd563` | scope-expansion | 23 | APK + Linux tarball |

beta.3 is the one to test on the branch; rc.2 on the stable line. beta.3's
versionCode 22 installs over everything; going back to an RC afterwards
needs an uninstall. Local copies of every asset are in
`~/soiboi-test-builds/`.

- `origin/main` is at **`66ab86a`** (rc.2 = rc.1 + the pin/cover fix).
- `origin/scope-expansion` is at `7ebd563` (Version 1.2.0-beta.4) plus this handover.
- `origin/desloppify/pass-2` is at `1cb092d` (fast-forward merged into scope-expansion).
- `versionNumber` on the branch stays `1.2.0`; `test/version_test.dart`
  compares only the numeric part of the pubspec version.

**Where the user's bug reports go.** The user said explicitly that bugs they
find on the beta are fixed on the beta (`scope-expansion`), even when the
same code is on `main`. So the download fixes below are on the branch only.
**rc.2 still has the "instant gratification" bug**; porting it to `main` is
the user's call (ask before 1.1.8 is cut; the pipeline, `pipeline_runner`
and `PipelineChannel.kt` are identical on both branches, the queue manager
differs a little).

**Every release ships both platforms**, pre-releases included: the arm64
APK and the Linux tarball. The user was (rightly) angry that rc.1/beta.1 went
out APK-only. `tools/release_test_builds.sh` only builds APKs and is now
spent (its commits and tags are hard-coded to rc.1/beta.1); don't reuse it
as is. For a new test build, follow the release recipe below with
`--prerelease --latest=false` instead of `--latest`. For a beta: bump only
`pubspec.yaml` (`1.2.0-beta.N+code`), commit, push, tag, then in the main
checkout `tools/apply_patches.sh`, `flutter build apk --release
--target-platform android-arm64`, `flutter build linux --release &&
tools/package_linux.sh --release`, smoke-test the extracted tarball on Xvfb
(`timeout 25 ./soiboi` exits 124), `gh release create`.

**What the user will do next:** test beta.3 (and rc.2), then say whether it
is time to cut an official release. Don't start that on your own. When they
say go, the likely shape is: release 1.1.8 from `main` (tag `v1.1.8`,
`--latest`, APK + Linux), and separately decide with them how and when the
branch lands on `main` (the plan says the branch stays separate until the
final release is debugged, so ask).

**Asked of the user to test on beta.3:** queue a big playlist, then a second
one while it runs (the first must keep going, Stop must stop it); lock the
phone during a long playlist (background downloads, the 3.1 "Done when");
Delete from device from Home, the player menu and the Songs page; Sort,
Filter, Select and long-press on the Songs page.

### How a stable fix went out this session (the pattern to reuse)

1. `git worktree add -b fix/<name> ../soiboi-main-fix origin/main`, so the
   main checkout (on `scope-expansion`) is never switched. The worktree
   `../soiboi-main-fix` was removed at the end of that fix; make it again
   the same way next time.
2. A new worktree lacks the gitignored Android pieces. Copy from the main
   checkout: `android/app/src/main/java`, `android/app/src/main/jniLibs`,
   `android/gradle/wrapper/gradle-wrapper.jar`, `android/gradlew*`,
   `android/local.properties`, `android/pip-repo`; for a release build also
   symlink `android/key.properties` → `~/.config/soiboi/key.properties`.
   For Linux packaging, copy `build/wrapper/`.
3. Fix, `dart format <file>`, `flutter pub get && tools/apply_patches.sh`,
   `flutter analyze`, `flutter test --exclude-tags integration`, check on
   the emulator with a debug build.
4. Commit, fast-forward `main` (`git push origin HEAD:refs/heads/main`),
   tag, build APK + Linux, `gh release create`.
5. `git merge origin/main` into `scope-expansion` in the main checkout,
   run the branch's tests, push.

**Don't run `flutter pub get` in a second checkout while a release build is
running in the first:** both use the same pub cache, and `pub get` can
revert the media_kit mpv patch under the running build.

### Release recipe (official, from `main`)

Bump `versionNumber` in `lib/base/app.dart` **and** `version:` in
`pubspec.yaml` (a test, `test/version_test.dart`, now fails if they
disagree); commit; push; `tools/apply_patches.sh`;
`flutter build apk --release --target-platform android-arm64`;
`flutter build linux --release && tools/package_linux.sh --release`; upload
`soiboi-android-arm64-vX.apk` and `soiboi-linux-x64-vX.tar.gz` with
`gh release create vX -R batgaurish/soiboi --latest`. Release notes:
plain style, no em dashes. Then `tools/install_linux.sh` refreshes the
user's desktop copy.

The update check (`update_service.dart`) ignores prereleases and strips any
`-suffix` from tags, so the `-beta.N` and `-rc.N` pre-releases never nag
anyone.

**Android signing:** release key at `~/.config/soiboi/release.jks` +
`key.properties`, symlinked to `android/key.properties` (gitignored). Never
regenerate it. Release app id `com.batgaurish.soiboi`; debug builds are
`.debug`, a separate app.

---

## The stable fixes on `main` (all merged into the branch)

1. **No subscription / skipped tracks reported as success**
   (`pipeline/soiboi_pipeline/downloader.py`).
   - gamdl logs "No active Apple Music subscription found…" at **CRITICAL**,
     downloads nothing and exits 0. `_ERROR_LINE` only matched `ERROR`; it
     now matches `CRITICAL` too.
   - gamdl skips a track it can't fetch with
     `[WARNING …] [Track i/N] Skipping "<title>": <reason>` and still says
     "Finished with 0 error(s)". `_StreamTap` now records skips (except
     "Media file already exists", which also covers the app's own
     "already in your library" skip) and the track total from
     `[Track i/N]`. All tracks skipped → `gamdl_reported_error` with the
     first reason. Some skipped → `done` plus a `warning` event
     "Skipped k of N tracks: …".
   - **Known gap:** the Dart side ignores `warning` events on both
     branches, so a partial skip is only visible in the pipeline log.
     Worth surfacing in the queue row later.
   - Tests: 4 new in `test_pipeline/test_downloader.py`.
2. **Space paused music while typing** (`lib/base/services/keyboard.dart`).
   Only the app's own search fields set `isTyping`; any plain `TextField`
   (the Downloads link box) let Space through. The handler now checks
   whether the focused widget is inside an `EditableText`. Test:
   `test/space_while_typing_test.dart`.
3. **Wrong version reported.** `versionNumber` said 1.1.5 while the pubspec
   said 1.1.7, so the updater offered 1.1.7 to people on 1.1.7. Fixed, and
   `test/version_test.dart` compares them.
4. Then `Version 1.1.8 (release candidate 1)`: pubspec 1.1.8+19,
   `versionNumber` 1.1.8.
5. **Playlist pin and cover actions were undiscoverable on phones**
   (`66ab86a`, `lib/portrait_view/pages/song_list_page.dart`). Since 1.1.6
   they were only reachable by long-pressing a playlist on the Playlists
   tab. The playlist page's ⋮ sheet now lists `playlistOptionItems()` (Pin
   to sidebar / Unpin, Set cover image, Remove cover image) and scrolls.
   Note: phones *do* have the sidebar (it is the drawer), and pinning does
   show there. Pinning looked broken partly because every playlist that
   existed when 1.1.6 was installed started pinned. Verified on the
   emulator with a debug build: pin → drawer entry, label flips to Unpin,
   cover set through the photo picker shows on the page, remove deletes the
   file. Not checked on the user's phone.

On the branch, the error catalog (0.2) already turns every one of these
messages into a plain title: no_subscription, not_streamable,
quality_unavailable, needs_wrapper, tool_missing.

---

## Built on the branch this session, at the user's request (beta.3)

Four commits, oldest first. None of it is a plan item except 3.1.

### Download engine fixes (`f83e159`)
- **Root cause of "stuck on Starting / Stopping":**
  `apple_library._quiet_gamdl_logging()` called `structlog.configure(...
  CRITICAL)`. structlog's config is global, and on Android a playlist read
  (`apple_playlist_tracks`, run when an Apple playlist is queued and when
  the Downloads screen lists playlists) shares the interpreter with the
  download. The running gamdl went silent: no progress past "Starting", an
  empty log, and a stop (checked only in `_StreamTap.write`) never landed.
  Now filters at INFO (gamdl's own level; every gamdl API request log,
  tokens included, is DEBUG). `test_reading_apple_playlists_does_not_mute_a_download`
  reproduces it and fails on the old code.
- Stop also checked before every track (`_stop_if_cancelled` in the owned-
  track patches); Dart re-sends a stop pressed before the pipeline started
  (Python clears the flag at download start) on the first progress event;
  Android runs `cancel` on its own executor (`control`). Job log: "Stop
  requested", then "Stopped"/"Paused".
- **Log mixing:** job ids restart at 0 each launch and the pipeline appends,
  so `download-logs/0.log` collected the first job of every launch. Now
  `<epoch ms>-<id>.log`, kept across a pause/retry of the same job.
- **Owned-track skipping moved earlier:** gamdl already fetches a whole
  playlist or album (every page) before the first track; the 2-3 s per
  owned song was `_get_song_media` fetching details before our check in
  `_download`. `_skip_owned_tracks()` now also patches
  `AppleMusicInterface._get_song_media` to yield a non-partial
  `AppleMusicMedia` carrying `GamdlDownloaderMediaFileExistsError` for owned
  tracks (gamdl's CLI reports it as a skip). The `_download` check stays for
  single-song URLs.
- **Not verified against Apple:** the emulator has no Apple session (see
  traps). Verified by unit tests only.

### Delete from device (`071c24a`)
- `lib/base/services/song_deletion.dart`: `canDeleteFromDevice`,
  `deleteSongFiles` (audio + `.lrc` + mood sidecar; one retry after asking
  for All files access on Android), `removeFromLibrary` (library + DB,
  folders **(their saved id lists are loaded with `id2Song[id]!`, so a
  stale id crashes the next launch)**, playlists, `updateArtistAlbum()`,
  `history.load()`, `audioHandler.sync()`; in place, no `Loader.sync`),
  `confirmAndDeleteSongs` (the warning dialog).
- `showConfirmDialog` gained `message` and `confirmText`; titles wrap to 3
  lines. `songMenuItems()` / `deleteMenuItems()` in `interaction.dart`.
- Entry points: Home song cards (right-click / long-press, new menu), phone
  player ⋮, desktop player bar right-click (new), song row menus on both
  layouts (desktop multi-select), phone Select page, Big Picture song
  options. In playlists "Delete" became "Remove from playlist".
- Verified on the emulator (Home long-press → dialog → file and `.lrc` gone
  from `/sdcard/Music/SoiboiTest` → Home refreshed → clean restart).

### Songs page sort / filter / select (`f22eae6`)
- `lib/base/data/song_filter.dart` (`SongFilter`, `songFilterNotifier`,
  session-long; quality and codec from `QualityInfo`, the badge's rules),
  `lib/base/widgets/song_list_toolbar.dart` (`SongListToolbar`,
  `songSortOptions`, `showSongFilter`). Shown only on the Songs page
  (`isLibrary`). Reorder is off while filtered.
- New sort types in `sortSongList`: 13/14 year, 15 most played (12 stays
  "shuffle permanently").
- Phone: long-press a row → `openSelection(context, song)`. Desktop: the
  button selects all rows.
- Verified on the emulator and on a Linux debug build.

### Background downloads and notifications (`97f327a`, plan 3.1)
- `DownloadService.kt`: special-use foreground service with a partial wake
  lock and Wi-Fi lock. `NotificationBridge.show` routes any ongoing
  `download_progress` notification through it (even without the
  notification permission); `dismiss` stops it. Manifest declares it with
  the special-use subtype property.
- `lib/base/services/download_notifications.dart`: `DownloadNotifier`
  (started in `main.dart`) — progress "Downloading i of N", Pause/Resume,
  Stop; summary with "Retry failed". `NotificationService.show(...,
  always:)` bypasses the Settings switch; used for progress on Android only.
- Verified: unit tests; on the emulator, Settings > Notifications > Send a
  test makes `dumpsys activity services` show `DownloadService`
  `isForeground=true` for the notification's lifetime, then gone. **Not
  verified:** a real playlist with the phone locked.

---

## Desloppify pass 2 (`desloppify/pass-2` merged into `scope-expansion`)

Run on 2026-09-25 following `.claude/skills/desloppify/SKILL.md`.

- **Scores:**
  - Dart: started at **51.0/100** strict (51.0 overall), finished at **73.2/100** strict (73.2 overall), **+22.2 pts gain**.
  - Python: **13.0/100** strict (20.2 overall); pipeline refactored in pass 1 with all 102 unit tests passing.
  - Overall combined health improved with all 20 subjective dimensions actively reviewed and scored.

- **What was fixed (4 commits on branch `desloppify/pass-2`):**
  1. `7832478`: Converted mutable globals `isStreamSource` and `isNotStreamSource` into derived getters on `sourceType`, removing duplicated 3-line writes across `config.dart`, `view_entry.dart`, and `settings_list.dart`. Added safe fallback to `SourceType.local`.
  2. `6abd1e7`: Replaced raw JSON file reads with corruption-safe `readJsonMapFile` and `readJsonListFile` across `config.dart`, `audio_handler.dart`, `my_window_listener.dart`, `bookmark_service.dart`, and `folder.dart` (with null-check for `library.id2Song[id]`). Changed `AudioHandler` public async methods (`saveAllStates`, `singlePlay`, `changePlayMode`) from `void` to `Future<void>`. Replaced relative import in `main.dart` and normalized `material_ui` imports.
  3. `856ace4`: Aligned `EmbyClient.safeRequest` error surfacing with `NavidromeClient` by honouring `showRealError`.
  4. `1cb092d`: Migrated deprecated `RadioListTile` `groupValue`/`onChanged` usages in `lib/base/widgets/settings_list.dart` (folder chooser and codec selector) to `RadioGroup<String>` ancestors. This eliminated all 6 `flutter analyze` deprecation warnings.

- **What was verified:**
  - `flutter analyze`: **0 issues found** (down from 6 `RadioGroup` deprecations).
  - `flutter test --exclude-tags integration`: **321 pass, 1 skipped** (fixtures/cover_colors).
  - `.pipeline-venv/bin/python -m pytest test_pipeline`: **102 pass in 0.54s**.
  - `flutter build apk --debug --target-platform android-x64`: built successfully.
  - `flutter build linux --release && tools/package_linux.sh --release`: built successfully with `"can_download": true` in the bundled environment.
  - Smoke test: extracted release tarball executed headless on Xvfb (`:99`) for 25s; `timeout 25 ./soiboi` exited **124** (healthy, no crashes).

- **Suppressed or skipped:**
  - `unused::lib/*::unused_import::*` kept suppressed in `.desloppify/config.json` (authoritative check is `flutter analyze`).
  - Deliberate architectural patterns preserved untouched: gamdl monkeypatches in `downloader.py`, Chaquopy `Consumer<String>` callback, mpv Lua patch in `tools/patches/`, AT-SPI accessibility helpers, delete-from-device ID handling.

---

## Scope expansion: Phases 0 and 1 (built, on `scope-expansion`)

The plan is `docs/scope-expansion-plan.md` (Phases 0–6, each item with a
size and a "Done when"). Phases 0 and 1 are built. **No box is ticked**,
because every item's "Done when" includes a phone or TalkBack check that
could not be done from the cloud container; each item has a `*Status:*`
line instead. Next is **Phase 2 (2.1 Setup wizard)**, but only once the
user has tested the beta and says to go on.

Commits, oldest first: `c60e7ef` 0.1, `66cbeda` 0.2, `3aacd4f` 0.3,
`a7a8689` 1.1, `8fbb3e1` 1.2, `98e187e` 1.3, `9c4d1ee` 1.4, `2e05b3a` 1.5,
`04e2d07` merge of `main`, `94da507` beta version, then this handover.

### 0.1 Notification service
- `lib/base/services/notification_service.dart`: `NotificationService`
  with `show(key, AppNotification)`, `dismiss(key)`, `dismissAll()`,
  `requestPermission()`, `supported`, `taps` stream; kinds `progress`
  (ongoing, updated in place), `result`, `update`; optional action buttons.
  Global `notifications`; on/off via `notificationsEnabledNotifier`
  (setting `notificationsEnabled`).
- Linux backend: D-Bus `org.freedesktop.Notifications` through the `dbus`
  package (added to pubspec). Updates reuse `replaces_id`; hints
  `desktop-entry: soiboi`, category, urgency, `value` for progress; body
  markup escaped. Clicking a notification raises the window
  (`_raiseWindowOnNotificationClick` in `main.dart`).
- Android backend: MethodChannel `com.batgaurish.soiboi/notifications` →
  `android/app/src/main/kotlin/com/batgaurish/soiboi/NotificationBridge.kt`
  (framework APIs only, no androidx), channels `download_progress`,
  `download_results`, `updates`; action buttons through a
  BroadcastReceiver; small icon `res/drawable/ic_stat_soiboi.xml`;
  `POST_NOTIFICATIONS` in the manifest, asked for through permission_handler.
  Notification ids are FNV-1a of the key, mapped into 0x10000..0x7fffffff.
- Settings > Notifications: switch plus "Send a test".
- Verified: 10 tests (one against a real in-process D-Bus server), and end
  to end on Linux with dunst + dbus-monitor. **Android only type-checked**
  (kotlinc against android-all-14), never run.
- Not yet wired to the download queue as the plan's later items expect;
  that is part of later phases.

### 0.2 Error catalog
- `lib/base/services/error_catalog.dart`: 25 ordered entries matched on
  pipeline error codes and gamdl 3.9 messages; each has id, title, detail,
  a `FailureFix` (signIn, retry, skip, openLog, chooseFolder,
  setUpWrapper, changeQuality, update) and whether it is temporary.
  `explainFailure(message, {code})`, `describeFailure`, `catalogIds`.
- `archive_service.dart` returns `DownloadFailure(message, code)`;
  `DownloadJob` keeps `errorCode`; the log gets a "Catalog: id (title)"
  line. Used by the queue sheet rows, the Downloads failure banner, the
  Apple playlists error and `catalog_sheet.dart`.

### 0.3 Motion and contrast helpers
- `lib/base/theme/motion.dart`: `MotionPreference {system, reduced, full}`
  (setting `motion`), `reduceMotionNotifier` / `reduceMotion`,
  `watchSystemMotionSetting()` (follows
  `accessibilityFeatures.disableAnimations`), `motionDuration(d)`,
  `reducedMotionTransition`, `ScrollController.glideTo`,
  `PageController.glideToPage` (jump when reduced).
- `lib/base/utils/contrast.dart`: `kTextContrast` 4.5, `kLargeContrast` 3,
  `contrastRatio`, `ensureContrast` (nudges HSL lightness only, smallest
  move), `readableOr`, `ensureContrastOnAll`. Re-exported by
  `color_manager.dart`.

### 1.1 Screen reader labels
- Helpers: `lib/base/utils/semantics_labels.dart` (`durationLabel`,
  `songLabel`, `percentLabel`, `nameWithValue`),
  `lib/base/widgets/icon_label.dart` (`labelIcon`),
  `lib/base/widgets/song_semantics.dart` (`SongSemantics` for custom rows,
  `SongSemantics.title` for a ListTile's title slot).
- Tooltips on every icon-only button (~115 sites wrapped in `labelIcon`),
  seek bar and volume as labelled sliders, `MySwitch.semanticLabel`,
  grid tiles and song rows as single items that play when activated.
- **Engine facts learned (Flutter 3.47, checked in the engine source):**
  the Linux ATK bridge uses only the semantics `label` as the accessible
  name. It ignores `tooltip` and `value`. That is why `labelIcon` adds a
  label on Linux only, and why `nameWithValue` folds a slider's value into
  its name on Linux. Android maps `tooltip` to the content description, so
  tooltips alone are enough there.
- Verified with AT-SPI on the Linux build: unnamed controls went from 18
  to 0 on Main, Songs, Albums and Downloads; activating a song row through
  AT-SPI plays it. **TalkBack not checked.**

### 1.2 Text scaling
- `lib/base/utils/media_query.dart`: `textGrowth(context)`,
  `scaledExtent(context, base, {textShare})`. Fixed item extents, sheet
  heights, seek bar, bottom bar, title boxes and the sidebar width grow
  with the text; scroll-to-index maths use the scaled extent.
- Verified: no overflow at 200% in the phone layout at 360×780 and in the
  desktop layout (debug build on Linux). **Not checked on a phone.**

### 1.3 Readable palettes
- `color_manager.dart`: `_keepMainPageReadable()` and
  `_keepLyricsPageReadable()` nudge text, highlight and icon colours onto
  every surface they sit on (page or vivid composite, panel, sidebar,
  bottom bar, menu, selected row); surfaces give way when ink can't.
- `contrast_color_generator.dart`: light-or-dark now picks whichever of
  white and black reads better (a grey cover used to get pale grey lyrics
  at ~2:1), then `ensureContrast`.
- `test/readable_palettes_test.dart`: every cover colour on a 32³ grid,
  every flavour and prebuilt palette light and dark, Material You for 16
  seeds × 3 variants, vivid on a 12³ grid.
- **Needs the user:** a test on 50 of their real covers is skipped until
  they run `.pipeline-venv/bin/python tools/cover_colors.py ~/Music`,
  which writes `test/fixtures/cover_colors.json` (average colours only,
  no titles or file names).

### 1.4 Reduce motion
- Settings > Reduce motion: follow the system / reduce / full. Routes fade
  in 150 ms instead of sliding or zooming, `HeroMode` off, `MarqueeText`
  (`lib/base/widgets/marquee_text.dart`) replaces TextScroll and stops
  scrolling, lyrics jump, the Rive now-playing icon holds still, Home skips
  its stagger, `animateTo` → `glideTo` in 16 files.

### 1.5 Keyboard navigation and shortcuts
- `lib/base/services/keyboard.dart` (rewritten): one
  `HardwareKeyboard` handler (`keyboardInit()`, idempotent, registered on
  every platform except TV).
  - Space play/pause · ←/→ seek 5 s · Shift+←/→ previous/next ·
    ↑/↓ volume ±5% · Ctrl+F or `/` search (and focuses the box, via
    `focusSearchNotifier` in `global_search_layer.dart`) · Ctrl+L lyrics ·
    Ctrl+D Downloads · Esc closes lyrics / leaves full screen · F11 full
    screen lyrics · `?` (matched by character) shows `shortcutList`.
  - Guards: `_typing` (any `EditableText`), `_onControl` (focused widget
    handles `ActivateIntent`, so Space presses it), `_inPopup` (a
    `PopupRoute` **or any route with `barrierDismissible`**, which covers the
    `PageRouteBuilder` context menus), `_onSlider`, TV and Big Picture.
    Ctrl+F works even in a text field; Ctrl+L/Ctrl+D and `/` don't.
- `lib/base/widgets/focus_ring.dart`: `focusRingColor()` (accent nudged to
  3:1 on the page) and `FocusRing` (FocusableActionDetector + a 2 px ring
  painted outside the child, no layout change). Themed Icon/Text/Elevated/
  Filled/Outlined buttons get the same ring via `side` (`_focusOutline()`
  in `main.dart`); the theme `focusColor` is the accent at alpha 90.
- `SongSemantics` rows: Enter plays, Space plays/pauses (plays the row if
  nothing is queued), Menu or Shift+F10 opens the options, only when the
  row itself has focus (a button inside it keeps its keys).
- Tab order: `landscape_view.dart` wraps sidebar (1), page (2) and player
  bar (3) in ordered `FocusTraversalGroup`s, and each page in its own group
  so the floating Ask AI button comes after the page.
- Fixed along the way: `showAnimationDialog` routes were not dismissible,
  so **Esc never closed a dialog**; `MySwitch` was two Tab stops (its
  `ScaleWidget` InkWell) and Tab looped between them; the hidden Back
  button in `TitleBar` took a stop; cards in Search and Downloads were
  coloured `Container`s that hid the rows' focus ink (now `Material`).
- Verified on the Linux build with xdotool + AT-SPI: full Tab order of
  Downloads and Settings, Search, Songs (Enter plays, Menu, arrows in the
  menu, Esc), lyrics, dialogs, Space/arrows/volume/next. Tests:
  `test/keyboard_test.dart` (9).
- **Not checked:** Big Picture, album/artist/playlist pages, a non-empty
  download queue, F11, Android with a keyboard.
- Small leftovers seen: the Settings page header ("Settings, 13 in total")
  is a focusable panel with no button role; choosing a page in the sidebar
  leaves focus in the sidebar; the seek bar isn't a Tab stop (←/→ cover it).

---

## Tests and checks (state at `7ebd563`)

- Dart: `flutter test --exclude-tags integration` → **368 pass, 1 skipped**
  (the real-covers fixture). On `main`: 216 pass.
  `test/apple_catalog_test.dart` is tagged `integration` and calls Apple's
  live API; it fails in the cloud container (no route) and passes where
  there is internet.
- Python: `.pipeline-venv/bin/python -m pytest test_pipeline` → **119
  pass** on the branch. The `test_acoustic.py` failure noted before did not
  reproduce on the user's machine; not investigated further. The dev venv's
  gamdl is **3.8.5**, as are the Android wheels in `android/pip-repo` (the
  phone log says "Starting Gamdl 3.8.5"); the packaged Linux runtime venv
  got 3.9.1. The owned-track patches were written against 3.8.5.
- `flutter analyze`: 0 issues (the `RadioGroup` migration was done in
  desloppify pass 2).

---

## Working in the Claude Code cloud container

Useful if the next session is also a cloud session (it was this time):

- Flutter at `/opt/flutter-sdk/flutter/bin` (not on PATH by default), runs
  as root (harmless warning). `.pipeline-venv` exists in the repo checkout.
- **No Android SDK, and `dl.google.com` is unreachable**, so no APK, no
  Gradle build. `maven.google.com` answers; Maven Central rate-limits
  (429). Kotlin was type-checked with a standalone kotlinc against
  robolectric's android-all-14 jar plus the Flutter embedding jar.
- **Pushing `main` or tags may be refused** by the session's permission
  check even with the user's go-ahead. Pushing `scope-expansion` works.
  Don't try to get around a refusal; hand the commands to the user.
- **Linux build that runs headless** (done in a throwaway copy, never in the
  repo): rsync the repo to a scratch dir, then in the copy
  1. add `dependency_overrides` for `flutter_inappwebview_linux` → the
     stub in `tools/cloud_build/wpe-stub` (no WPE WebKit available), and
     `rive_native` → a copy of the pub-cache package with an empty
     `linux/rive_marker_linux_development` file and no prebuilt libs;
  2. `set(MIMALLOC_USE_STATIC_LIBS OFF …)` before `project(` in
     `linux/CMakeLists.txt` (codeload.github.com is blocked, media_kit
     can't fetch mimalloc);
  3. append `target_link_options(${BINARY_NAME} PRIVATE
     -Wl,--allow-shlib-undefined)`;
  4. `tools/apply_patches.sh`, `flutter build linux --release`.
  Also needed once: `mkdir -p /dev/input` (gamepads plugin) and the
  `xdg-user-dirs` package (path_provider). That build is for testing
  only; Rive animations and the webview don't work in it. **Never ship it.**
- Running it: Xvfb `:99` (1400×900), its own `dbus-daemon --session`,
  `dunst` for notifications, `DISPLAY=:99 GDK_BACKEND=x11`, separate
  `XDG_DATA_HOME`/`XDG_CONFIG_HOME` so the real profile is untouched,
  PulseAudio null sink for playback. Drive it with `xdotool`, screenshot
  with ImageMagick `import -window root`.
- Accessibility checks: `tools/a11y/dump.py` (whole tree as Orca sees it),
  `tools/a11y/focused.py` (deepest focused node; loop it after each Tab to
  record focus order), `tools/a11y/do.py NAME [ACTION]` (activate a node).
  Run them with **`/usr/bin/python3.12`** (the default `python3` is 3.11
  and can't import `gi`). Don't query a node after the walk: that can time
  out and has crashed GTK once.
- To see *which widget* holds focus, temporarily add a
  `FocusManager.instance.addListener` in the scratch copy's `main.dart`
  that appends the focused context's ancestor widget types to a file.

---

## Beta.4: logs, storefront, matching (2026-09-25)

The user reported no log button on beta.3 and asked for a real playlist to
be downloaded end to end before beta.4. Commits, oldest first:

| Commit | What |
|---|---|
| `68d4fc5` | Settings > Download logs, **tapped**, lists every saved log (`lib/layer/saved_download_logs.dart`, `downloadLogDir`); a row paused back into the queue keeps its log button. |
| `e0f1cd0` | Catalog matching in the **account's storefront**: pipeline `apple_storefront`, remembered as setting `appleStorefront`, refreshed at startup after the session checks, default for every `apple_catalog_service` call, `accountStorefront()` in discovery; caches key on it. Everything used to assume `us`, so US-only song ids 404'd for the Indian account. |
| `5674352` | Stream tap attaches a traceback printed **before** its error line; catalog `playback_refused` (wrapper "Apple store error" / `playback_dispatch_failed`, Retry, temporary). |
| `f992496` | Settings > Lossless says "Signed in · starts when a download needs it" instead of "Set up". |
| `ca5417f` | Bare exception types (`httpx.ConnectTimeout`) kept in brackets; a message already in the error line is not repeated. |
| `deda7ff` | `pickOriginalRelease` returns **null** when no row matches song and artist (was Apple's top hit: covers, karaoke, remixes, even a different song). |
| `ac079b4` | `sameArtist`: any credited artist either side, containment or 1-2 letter edits (none under 5 letters); `resolveAppleTrack` retries with first artist + bare title, then the title alone. |
| `7ebd563` | Version 1.2.0-beta.4+23. |

Why the log button looked missing on beta.3 is not proven: the beta.3 APK
cannot run on the emulator (see traps) and the queue code is the same as the
debug build, where it shows. The fix covers the gaps that exist either way.

**Playlist runs on the emulator (debug app, wrapper, ALAC):** Weekly
Exploration (50) on the old build: 48 done, 2 failed (a US-only id; an Apple
refusal). Weekly Jams (50) on the storefront build: 49 done, 1 connect
timeout, finished on Retry. Weekly Exploration again on the final build: 47
matched (3 are not sold in India in original form: Måneskin's "I Wanna Be
Your Slave", Chase Atlantic's "Into It", Alan Walker's "On My Way"), 43
skipped as owned, Phir Mohabbat downloaded from Murder 2, Cradles and
"Rewrite the Stars" downloaded, 2 Apple refusals finished on Retry. Apple
refuses roughly 1 in 25 tracks at random (`playback_dispatch_failed`); plan
3.2's automatic retry would absorb that.

**Known leftovers:** the first run's wrong versions stay in the emulator
library ("Into It (instrumental)", "On My Way (Da Tweekaz Remix)", Jaydan
Wolf's "I Wanna Be Your Slave"). For single-song links gamdl fetches details
and stream info before the owned check, so an owned track can still fail
there on a network blip (seen twice); harmless, but slower than it could be.

## Built this session (Phase 2 and beta.3 bugs)

Commits on `scope-expansion`, oldest first, all pushed:

| Commit | What |
|---|---|
| `7fcb7b7` | Same-song key ignores `(From "Film")`, `- From "Film"`, `(feat. X)`; Dart `ownedSongKey`/`bareSongTitle` and Python `owned_key`/`bare_title` agree (a test pins the exact key on both sides). Live/remix tags still make a different song. |
| `34c27ee` | `pickOriginalRelease` in `apple_catalog_service.dart`: other-source matching (search takes 25 hits, ISRC takes every track row) prefers not-compilation, no "(From" credit, album over single/EP, plain over deluxe/anniversary. |
| `ccddef8` | Pipeline: for **Apple playlist** tracks (not album/song links, not library tracks) `original_release()` swaps a compilation copy for the same recording on its own album (ISRC via `songs?filter[isrc]`, then catalog search checked on title, artist, ±2 s). One line in the job log per swap. Checked against the live catalog. |
| `73b0678` | Cover cache: a file re-read by a sync drops its cached picture (`files/local/pictures/md5(path)`), resets the load scheduler's id (it returned the old completed load) and the old song's picture, clears the image cache once. |
| `3b6102a` | **2.1 Setup wizard** (`lib/layer/setup_wizard.dart`). |
| `db25f34` | **2.2 Status panel** (`download_status.dart`, `download_status_panel.dart`, pipeline `disk_usage`). |
| `463cf51` | Wrapper keeps its sign-in marker when it starts offline; catalog `sign_in_unconfirmed`. |
| `fec99ff` | **2.3** Fix buttons that act (`lib/layer/failure_fix.dart`). |

### 2.1 Setup wizard
- Five steps, each skippable: music (source picker on first run only, then
  `ManageMusicFolders(inline: true, onChanged:)`), Apple sign-in (lossless
  vs browser, side by side; embeds `LosslessSetup`, pushes
  `AppleSignInLayer`), download options (`DownloadQualityPicker`,
  `DownloadFolderPicker` from the new `download_options.dart`, shared with
  Settings), ListenBrainz (`ListenBrainzForm`, shared with Settings),
  summary with a Change per row. Ends in "Start listening" or "Find music to
  download" (opens Downloads).
- Replaces `firstLaunchView` in `view_entry.dart`. Also shown at launch when
  a local library has no folders and `setupWizardDone` (new setting) is
  false (`needsSetupNotifier`, set in `main.dart` after `Loader.load`).
  Settings > Setup wizard (first tile) pushes it as a route.
- Accessibility: a persistent 1 px live region announces "Step n of 5:
  title"; the progress bar keeps a numeric value (a text value asserts);
  the header scrolls with the step so 200% text fits a phone.
- `test/setup_wizard_test.dart`: skipping through, labelled tap targets and
  text contrast on every step, announcements, Back, 200% text.
- Not checked: a literally fresh Android install end to end (clearing the
  emulator app would lose the wrapper sign-in), TalkBack.

### 2.2 Status panel
- `buildStatusChecks(StatusInputs)` is pure (19+ tests): account (which
  sign-in, cookie expiry, warning within 7 days), wrapper (a problem only
  when ALAC or the only sign-in needs it; unconfirmed sign-in is a warning
  with Try again), engine, folder (unwritable choice, free space), network
  (HEAD music.apple.com). `StatusInputs.live()` rereads notifiers;
  `gather()` adds disk and network.
- Free space comes from a new pipeline command `disk_usage` (generic
  channel, so no Kotlin change); Dart has no call for it.
- Replaces "Before you can archive"; always shown, folds to "Ready to
  archive".

### 2.3 Plain-language errors
- `runFailureFix` does the catalog's fix and retries when that clears the
  cause (sign-in, folder changed, lossless signed in, Use AAC). Skip uses
  the new `DownloadQueueManager.dismiss`. Failed rows always show the log
  button. Failure text: `failureTextColor()` (red nudged to 4.5:1).
- The real desktop `download-temp/gamdl.log` failures: wrapper playback
  errors, server disconnects, permission denied under `~/Music`, ALAC not
  offered; all mapped and pinned in `error_catalog_test.dart`.

### Verified on the emulator this session
Wizard from Settings (folder add/remove, lossless detected, ALAC), then two
real ALAC downloads (Iktara from the Wake Up Sid soundtrack, Treat You
Better from Illuminate); status panel values and airplane mode; an offline
download failing as "Could not reach Apple Music" and Retry finishing it.

### How the lossless wrapper is put together (read before touching it)

- wrapper-v2 (github.com/glomatico/wrapper-v2, Unlicense) pinned at
  `100e0a8`, plus two patches in `tools/patches/`: `WRAPPER_LAUNCHER`
  override, and `WRAPPER_NATIVE_ANDROID` (skips the chroot-only
  `resolv_set_nameservers_for_net`, which crashes on real Android).
- **Soiboi ships no Apple code.** Setup links the exact APK (Apple Music
  3.6.0-beta build 1109, arm64-v8a + x86_64, APKMirror) and
  `wrapper_libs.py` extracts and SHA-256-checks the 18 libraries from the
  user's file. The worker links NDK r23b's libc++ for its SONAME only.
- Needs **NDK r23b** (`~/Android/Sdk/ndk/23.1.7779620`) as well as NDK 28.
- Android: `libwrapperd.so` / `libwrapperworker.so` in jniLibs with legacy
  (extracted) packaging, since Android only execs from nativeLibraryDir;
  Apple libs dlopen from app storage. Built by
  `tools/build_android_wrapper.sh`, called from `build_android_pipeline.sh`.
- Linux: `tools/build_wrapper.sh` → `build/wrapper/x86_64`, bundled by
  `package_linux.sh` / `install_linux.sh`; runs under `unshare -rmpf`.
- Dart: `lib/base/services/wrapper_service.dart` (start/stop/sign-in, free
  localhost ports), `lib/base/widgets/lossless_setup.dart` (wizard).
  ALAC downloads start it on demand (`archive_service.dart`).

## The Linux build works now — and the patch that makes it work is fragile

Every Linux build before v4.2.1 segfaulted seconds after startup. Earlier
notes guessed that was specific to a sandboxed build environment. **That was
wrong** — it reproduced on the user's own CachyOS desktop, release and debug,
Wayland and X11.

Cause: **libmpv's built-in Lua scripts**. The crashing thread was always one
of them (`lua/ytdl_hook`, `lua/commands`, `lua/stats` all observed) with the
program counter at `0x0`. Disabling them one at a time only moved the crash to
the next script — which is what identifies the fault as LuaJIT inside libmpv
rather than any single script. mpv v0.41.0 here cannot run its Lua layer in a
hosted process, though the standalone `mpv` binary with the same libraries is
fine.

Fix: set every `load-*` flag **and** `ytdl` to `no` before `mpv_initialize`.
`load-scripts` alone is **not** enough — that covers only *user* scripts, and
each built-in has its own flag. Nothing is traded away: those scripts are
mpv-UI features with no surface in an embedded audio player, and `ytdl_hook`
handles streaming URLs this app never gives mpv.

**The trap:** the change has to live inside `media_kit`, which is a *git*
dependency, so it ships as `tools/patches/media_kit-disable-mpv-lua.patch`.
**`flutter pub get` reverts it.** Re-run `tools/apply_patches.sh` (idempotent)
and rebuild. `tools/install_linux.sh` calls it every time. The real fix is
upstreaming to the `AfalpHy/media-kit` fork, which would retire
`tools/patches/` entirely.

It applies on **every** platform, not just Linux — media_kit sets those
options everywhere — so any change here needs an Android playback re-check.
Cheap way to do that: `adb shell dumpsys audio | sed -n '/players:/,/^$/p'`
and look for mpv's OpenSL ES player at `state:started`.

---

## Packaging and installing

- `tools/package_linux.sh [--release]` — assembles a runnable bundle (Flutter
  build + `data/pipeline` + a runtime venv) and tars it. Verifies the *bundled*
  environment before packing.
- `tools/install_linux.sh [--debug|--uninstall]` — installs to `~/.local`
  (binary, icon, `.desktop` entry). No root, nothing outside `$HOME`.
  Applies the mpv patch first. **Soiboi is currently installed this way on the
  user's machine and is in their app launcher.**
- `tools/_runtime_venv.sh` — the shared venv/pipeline logic both use, so they
  cannot drift.

**A `--copies` venv *is* relocatable** — an earlier note here claiming
otherwise was wrong. Python derives `sys.prefix` from the interpreter binary's
own location, so moving the tree works; only the `bin/*` console-script
shebangs (pip, maturin) go stale, and nothing at runtime uses them because the
app runs `<venv>/bin/python -m soiboi_pipeline` directly. Verified by
extracting a packaged tarball to an unrelated path and getting
`can_download: true`. The scripts still build at the final path, for a
different reason: it compiles the native bliss wheel against the interpreter
that will run it.

`generate_deb.sh` / `generate_rpm.sh` still do **not** bundle the pipeline.

---

## Verifying on Android

**Emulator:** `emulator-5554` (AVD `soiboi_test`). **Since 2026-09-25 the
debug app (`com.batgaurish.soiboi.debug`) is signed in to the lossless
wrapper** (the user did it once in a visible window; marker
`files/wrapper/signed_in`) and has ListenBrainz `localindiesoyboy` in
`files/setting.json`. Real downloads work there. Both survive `adb install
-r`; never uninstall the debug app or clear its data. Run the emulator
headless. The release app is still signed out.

Library on it: a "How It Would End" (Balu Brigada) track, a couple of Daft
Punk tracks, and three *Michael Jackson* tracks from **Bad**. That album is
deliberately kept partial (3 of 11) as the standing repro for
partial-ownership UI states — **don't archive the rest** unless a task
specifically needs a fully-owned album. It is also left with "Follow system
colours" on, so it will not look like the Expressive default.

**Testing an update without publishing anything:** temporarily lower
`versionNumber` in `lib/base/app.dart` below the newest real tag, build, run
the flow, revert. That is how the Android updater was verified against the
genuine release. Note the APK is ~600 MB, so a real update download needs the
space; the staged file lands in `files/updates/` and is worth deleting after.

---

## Traps already paid for

**Paid for in the beta.4 session (2026-09-25)**

- **A release APK cannot be tested on the x86_64 emulator.** Installed over
  an old copy it kept the x86_64 ABI (the APK carries Chaquopy x86_64 libs)
  and crashed loading arm64 `libflutter.so`; with `adb install -r --abi
  arm64-v8a` it runs under translation but aborts natively
  (`pthread_mutex_lock called on a destroyed mutex`) when Downloads opens.
  Test release behaviour on the phone; test code on the debug build.
- **`monkey -p com.batgaurish.soiboi` can launch the debug app** (the
  package name is a prefix). Use `am start -n com.batgaurish.soiboi/.MainActivity`.
- **Weekly playlists: tap the Home card** (bounds from `uiautomator`); the
  sheet's "Archive N" and "N of 50 matched" are content-descs. A retried job
  appends to its own log file, so wait on that file, not the newest.
- **The scratchpad can be emptied between sessions**; the private D-Bus
  config (`bus.conf`) had to be rewritten. It is in the memory note
  `feedback-linux-test-isolation`.
- **Formatting check outside the project is wrong:** a copied file loses the
  package's language version and formats differently. Use `dart format
  --output=none --set-exit-if-changed` in place.

**Paid for in the Phase 2 session (2026-09-25)**

- **Xvfb alone does not isolate a Linux test build.** An Xbox controller
  (`/dev/input/js0`) is connected and the gamepad plugin drove the hidden
  app through the wizard; the file picker (file_picker v12 beta, portal
  only) opened a portal dialog on the user's real desktop; `dbus-run-session`
  auto-starts portals that fall back to the real Wayland socket. What works:
  `bwrap --dev-bind / / --tmpfs /dev/input`, a private `dbus-daemon` with
  **no servicedir** on `unix:abstract=soiboi-test-bus` (a scratchpad socket
  path is over the 108-byte limit), `DBUS_SESSION_BUS_ADDRESS` pointing at
  it, `XDG_RUNTIME_DIR=<scratch>`. The app refuses to start with no bus at
  all (MPRIS). File pickers can then only be tested on the emulator.
- A rebuilt test binary shows as `.../soiboi (deleted)`; kill test copies
  with a prefix match on the exe path.
- **Offline, the wrapper reports "logged_out"** with a valid session; the
  app used to delete its marker then (fixed in `463cf51`).
- `dart format` on a file that was never formatted (`pipeline_runner.dart`)
  reflows ~200 lines; revert and add only the change.
- Airplane mode on the emulator: `adb shell cmd connectivity airplane-mode
  enable|disable`.

**Paid for in the beta.3 session (2026-09-25)**

- (Superseded 2026-09-25: the debug app now has a wrapper sign-in, see
  "Verifying on Android".) Never enter the user's Apple ID.
- **`showContextMenu` pops itself on any relayout after its first frame**,
  and a `uiautomator dump` causes one. Long-press, then tap the item
  without dumping in between (use a screenshot for coordinates).
- `showAnimationDialog` fades in; screenshot about a second later.
- Emulator debug app library: `/storage/emulated/0/Music/SoiboiTest`
  (test tones). For a deletion test, push a throwaway tone there (ffmpeg
  sine + tags), Settings > Synchronize Library, and delete that.
- Exercise the foreground service without a download: Settings >
  Notifications > Send a test, while polling `adb shell dumpsys activity
  services com.batgaurish.soiboi.debug`.
- **Xvfb + xdotool:** `xdotool mousemove X Y click 1` sometimes misses;
  `mousemove`, short sleep, `mousedown 1`, `mouseup 1` works.
- **Killing a test Linux instance:** the user may have their installed
  Soiboi running, so `pkill -x soiboi` is not safe either. Kill by PID
  after checking `readlink /proc/$PID/exe` points into the build dir.

**Paid for in the release session (2026-09-25)**

- **Test releases went out APK-only** because `release_test_builds.sh` only
  builds Android. Every release needs the Linux tarball too (see above).
- **This checkout can be a day behind GitHub** when the previous session
  ran in the cloud. `git fetch` over SSH works from a local session; check
  `git log origin/scope-expansion` against the handover before trusting
  local state.
- **Emulator, playlist page:** the top-left button is Back, not the drawer.
  Open the drawer from a root page (Home, Playlists).
- **Emulator, cover picker:** Android's photo picker opens in multi-select
  mode for `FilePicker.pickFiles(type: image)`. Tap the photo, then "Add
  (1)" at the bottom; Back cancels it silently.
- **Tap by `uiautomator` bounds, fresh each time:** the ⋮ sheet grows with
  the extra playlist rows, so a tap reused from an earlier dump hit Delete
  (its confirm dialog saved it).
- **A debug Gradle build in a worktree failed once** while the release
  script's Gradle build was running in the main checkout; the retry
  succeeded. Don't run two Gradle builds at once.
- `flutter build linux` + Gradle leave `android/build/reports/problems/
  problems-report.html` modified (it is tracked). `git checkout --` it
  before committing.

**Paid for in the Phase 0-1 session**

- **`Loader.sync()` replaces `history` and `artistAlbumManager` objects.**
  Any listener list built once keeps watching dead objects. Rebuild
  listeners on `Loader.stateNotifier` (Home does now).
- **`layersManager.pushDetail` needs the tab's navigator to exist**; a tab
  never opened has none and the push silently does nothing. It now
  switches to the tab first and returns to the origin page on Back.
- **Theme type Vivid bypasses every palette** (flavour, matugen,
  prebuilt): colours come from album art, grey with nothing playing.
- **Emulator scripting:** the shell is zsh-like without word splitting of
  `$VAR` lists; `pkill -f` patterns can match the running shell itself
  (exit 144). Use exact anchored patterns. Release builds are not
  debuggable, so `run-as` fails on `com.batgaurish.soiboi`; drive the UI
  or use the `.debug` app.
- **Linux screenshots:** start `Xvfb :99` with `run_in_background`, run the
  app with `env -u WAYLAND_DISPLAY GDK_BACKEND=x11 DISPLAY=:99
  XDG_DATA_HOME=<scratch>` for an isolated profile; Synchronize Library
  asks for confirmation.

**Paid for in earlier sessions**

- **Chaquopy can call a Java object only through a functional interface.**
  The progress callback must be a `java.util.function.Consumer<String>`; an
  object with `__call__` silently never fires (this hid all Android progress
  for months).
- **Android pipeline events carry a runId** over one permanent EventChannel
  listener; downloads, analysis and everything else each have their own
  executor in `PipelineChannel.kt`.
- **Post-download work must be scoped to the files the download wrote**
  (`since=` start time). The output folder is often the user's whole
  library. Both analysis and the lyrics fetch follow this rule.
- **bliss must release the GIL** (`py.detach`) or analysis starves downloads.
- **bliss decoders**: symphonia 0.6.1's mp3 bundle is not on crates.io; the
  whole symphonia family is patched to the git tag `v0.6.1`. Opus has no
  decoder. Sidecar v2 retries files the AAC-only v1 analyser marked bad.
- **Shared-storage downloads need All files access**; `READ_MEDIA_AUDIO`
  only reads. `archive_service.dart` asks when the chosen folder needs it.
- **uiautomator dumps list the hidden drawer's items**; trust screenshots
  (`adb exec-out screencap -p`) to see what is actually on screen.
- **Do not `pkill -f` from the agent shell**: the pattern matches the
  shell's own command line and kills it. Kill by PID. Stopping a
  `flutter build` leaves a Gradle daemon holding a lock; `./gradlew --stop`.
- **desloppify's Dart unused-import detector is wrong** (1,374 false
  positives; `flutter analyze` is authoritative). Suppressed in
  `.desloppify/config.json`, scoped to `lib/`.
- The phone sleeps during long tests; `adb shell svc power stayon true`
  with the user's OK, and reset `stay_on_while_plugged_in` to 0 after.

**The pipeline / gamdl**

- **gamdl's logger bypasses `redirect_stdout`** — it binds its writer from a
  mutable default argument at import. `_capture_gamdl_logging()` rebinds
  `__defaults__`. Without it: no progress, no error detection, and raw log
  lines corrupt the JSON protocol.
- **gamdl logs failures and exits 0.** Catch them from the log or the UI shows
  a green tick for a download that never happened.
- **gamdl's "already downloaded" skip is a WARNING, not an ERROR** —
  `Skipping "<title>": Media file already exists`. That is what makes running
  without `--overwrite` safe: `_StreamTap` only treats `[ERROR ...]` lines as
  failures, so re-running a fully-owned album reports success rather than a
  queue full of red rows. `--database-path` defaults to None, so no SQLite
  file lands in the working directory (`/` on Android); the skip is a pure
  `final_path.exists()` check.
- **Resume is track-level, not byte-level, and cannot be otherwise.** gamdl
  hands yt-dlp `overwrites: True` and drives `HttpFD`/`HlsFD` with no
  `continuedl`, so an interrupted *file* always restarts.
- **gamdl's temp path defaults to the working directory** (`/` on Android) and
  click validates it as writable. Always pass `--temp-path`.
- **Android has no `sem_open`**, so `multiprocessing.Queue` cannot be built.
  The yt-dlp worker runs in a thread instead — probed, not checked by platform
  name.
- **pyo3 0.23 doesn't support Python 3.14** (the desktop venv's interpreter) —
  needs 0.29.2+. bliss's `song.duration` is unreliable for M4A/AAC via
  symphonia (reads 0 on every real file tested); the `_bliss_analyze` crate
  doesn't expose it since nothing consumes it.
- **`.pipeline-venv/bin/pip` can have a stale shebang.** Use
  `.pipeline-venv/bin/python -m pip`.

**External APIs**

- **`media-user-token` is matched by exact domain** (`.music.apple.com`).
  Android's CookieManager reports no domain, so it must be pinned
  deliberately — and the app's own "signed in" check must use the same test.
- **iTunes Search does not index every catalog album under artist+album
  keywords.** Wallows' *Nothing Happens* demonstrably exists on Apple Music,
  yet `entity=album&term=Wallows+Nothing+Happens` returns 0 results. "Not
  found in the Apple Music catalog" means "iTunes Search didn't rank it", not
  "it isn't on Apple Music". A null resolve is not ground truth.
- **ListenBrainz answers 204** for a stats range it has not computed; widen
  month → year → all time.
- **GitHub's `/releases/latest` excludes prereleases**, and every build this
  project ships is one — so it 404s for this repo. List `/releases` and
  filter. Release tags also break naive version parsing (`v4.1.0-debug` →
  `int.parse('0-debug')` throws); `releaseVersion()` strips to the numeric
  core.

**Flutter / UI**

- **`SongList` reads `playlist.songList` directly and never calls `load()`** —
  anything Playlist-shaped must be populated in its constructor.
- **`flutter test` swaps in a nine-font fontconfig** for deterministic
  goldens.
- **Skia on Android resolves only the nine names in `/system/etc/fonts.xml`**,
  not real family names — a font must be registered from its file first.
- **`abiFilters.clear()` before adding**, or Flutter's fat APK adds
  armeabi-v7a and Chaquopy fails configuration.
- **`material_ui` shadows Flutter's `ColorScheme`.** A test touching theme
  types must import `package:material_ui/material_ui.dart`, not
  `package:flutter/material.dart`, or the two types won't unify.
- **matugen 4 refuses to pick a source colour without `--prefer`** when it
  cannot see a terminal.

**Driving the machines**

- **The emulator must run headless** — `emulator -avd soiboi_test -no-window
  -no-audio -no-boot-anim`, detached with `setsid nohup`. The user does not
  want it on their display. `adb exec-out screencap -p` and `uiautomator dump`
  both work without a window, so nothing is lost.
- **`adb shell input tap` coordinates must be scaled to the device's actual
  resolution** (`adb shell wm size`), not the possibly-downscaled screenshot
  dimensions you were shown. `adb shell uiautomator dump` gives exact element
  `bounds` — far faster than guessing from a screenshot.
- **uiautomator `content-desc` carries the full row labels.** Dump, grep the
  descs, tap the centre of their `bounds`. `clickable`/`long-clickable` flags
  are a cheap way to assert UI state.
- **A system dialog (the package installer, a permission prompt) sits *above*
  the app**, and a `uiautomator dump` can still return the app window behind
  it. Screenshot to confirm what is actually in front.
- **Launching a GUI on Linux inherits `WAYLAND_DISPLAY` and ignores your
  `DISPLAY=:99`** — GTK prefers the Wayland backend, so the window opens on
  the user's real desktop instead of Xvfb. Use
  `env -u WAYLAND_DISPLAY GDK_BACKEND=x11 DISPLAY=:99`. This has already gone
  wrong once.
- **`timeout N cmd` exiting 124 means it survived** the whole N seconds; 139
  is SIGSEGV. For a GUI that is supposed to stay up, 124 is the pass.
- **Running a GUI binary backgrounded with plain `&` inside a single shell
  tool call can get killed** when that call's wrapper process exits. Use
  `(cmd &) ; other_commands` or `setsid nohup cmd &`.
- **`sudo` is a hard refusal for the agent regardless of user permission
  stated in chat** — even an explicit "I authorize this" doesn't unlock it.
  The user must run it themselves, or approve it via a connected Remote
  Control session (confirmed working for `pacman -S wpewebkit`).
- **Uploading a ~600 MB release asset takes ~25 minutes.** Background it; the
  release shows an `untagged-…` URL until `gh release create` finishes.
- **`git push origin main` can fail with "Permission denied (publickey)"** —
  `origin` is an SSH remote and the agent is not always reachable from the
  session (`SSH_AUTH_SOCK` unset). `gh` is authenticated independently, so
  push over HTTPS with its token instead of changing the user's remote:

  ```bash
  GH_TOKEN=$(gh auth token)
  git -c credential.helper='!f(){ echo username=batgaurish; echo password='"$GH_TOKEN"'; };f' \
    push https://github.com/batgaurish/soiboi.git main
  ```

  `git fetch` fails the same way, which leaves `origin/main` stale and the
  working copy looking "ahead" after a successful push — `git update-ref
  refs/remotes/origin/main <sha>` corrects it.

---

## Constraints to keep honouring

- **Never enter the user's Apple ID credentials.** The WebView exists so Apple
  handles the password.
- **Never read or log cookie values** — names, domains, expiry only.
- **Do not generate their signing keystore.** Android ships debug-signed.
- **Test on the emulator, and keep GUIs off the user's screen.** Do not drive
  their desktop cursor; their input gets mistaken for app bugs. Xvfb `:99`
  with the Wayland caveat above.
- **Never push to the `upstream` remote** (`AfalpHy/sylvakru`).

---

## Commands

```bash
tools/apply_patches.sh                    # REQUIRED after any `flutter pub get`
tools/build_pipeline.sh                   # desktop dev venv
ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 tools/build_android_pipeline.sh

flutter analyze
flutter test
.pipeline-venv/bin/python -m pytest test_pipeline

flutter build apk --debug && adb install -r build/app/outputs/flutter-apk/app-debug.apk
flutter build linux --release && tools/package_linux.sh --release
tools/install_linux.sh                    # install to ~/.local + launcher entry

git push origin main                      # NOT upstream
gh release view v4.2.1 --repo batgaurish/soiboi
```
