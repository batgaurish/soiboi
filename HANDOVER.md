# Soiboi — handover

Paste this at the start of a new session.

---

## What Soiboi is

Cross-platform music player (Linux + Android) forked from **Sylvakru**
(Apache-2.0), with a streaming-to-offline archival pipeline embedded
**inside the app**. Metadata and the actual archive/decrypt step are Apple
Music today; playlist *discovery* already spans platforms via ListenBrainz,
with more sources planned (see Phase 9 below).

Repo: `~/Projects/Soiboi - Local Music Player with Download and Lyric Support/soiboi`
GitHub: `github.com/batgaurish/soiboi` (public; `origin` remote). `upstream`
remote points at the original `AfalpHy/sylvakru` fork source — never push
there.

Standing constraints, already decided, not up for re-litigation:

- **Strictly offline.** No streaming services beyond self-hosted, for playback.
- **Apple album IDs are canonical, not MusicBrainz.**
- **Standalone on both platforms.** No Docker, no server, no Syncthing. Whatever
  device downloads a track does the whole job — and keeps the only copy.
- **Scrobbling out of scope** (recommend Pano Scrobbler).
- Codec/quality badges stay obvious in every flavour.
- Expressive is the default flavour; three flavours, matugen, light/dark.
- Private distribution: the user and friends. Not F-Droid, not Play Store.

---

## Current session: working a large backlog against an approved plan

The user reviewed the running app and handed over two bugs, a rebrand, and
six features. A plan was written and approved, saved at
`~/.claude-personal/plans/optimized-watching-globe.md` — **read that file**,
it has the full phase-by-phase design with file:line references for
everything below. This handover is the status pointer into that plan, not a
replacement for it.

| Phase | What | Status |
|---|---|---|
| 1 | Bug 1: Weekly Exploration race (stuck at 4/50 tracks) | **Done, committed, verified live** |
| 2 | Rebrand: README/repo/pubspec to "streaming-to-offline archival" | **Done, committed** |
| 3 | QOL: library/playlist backup & restore | **Done, committed, verified live** |
| 4 | Bug 2 + `library_match_service.dart` extraction | **In progress — see below** |
| 5 | Smart playlist templates | Not started |
| 6 | Auto-generated mood playlists on Home | Not started |
| 7 | QOL: download queue / resumable downloads / storage cleanup | Not started |
| 8 | Auto-update checker + installer (Android + Linux) | Not started (partial infra already exists, see note below) |
| 9 | Multi-platform playlist import (`ExternalPlaylistSource`) | Not started |
| 10 | Global search shell | Not started |
| 11 | Android dynamic color | Not started |

All commits through Phase 3 are pushed to `origin/main`. **Phase 4 is
uncommitted, mid-flight, in the working tree right now.**

### Phase 4 exact state (pick up here)

Goal: fix "an album/artist partially in the local library shows as entirely
'Not in library'" and extract the local/catalog name-matching logic into a
shared service, since later phases (6, 10) need it too.

**Done:**
- `lib/base/services/library_match_service.dart` created — `normaliseForMatch`,
  `matchArtist`, `matchAlbum`, `matchSong` (the last is new; album/artist
  matching was moved here from `listenbrainz_service.dart`'s old private
  `_normalise`/`_matchArtist`/`_matchAlbum`, deleted from there).
- `lib/base/services/listenbrainz_service.dart` updated to call the extracted
  functions. Pure refactor, `LbEntry.isInLibrary` unchanged in behavior.
- `lib/layer/catalog_sheet.dart`'s `_CatalogAlbumSheetState`:
  - `_load()` now also resolves `_localAlbum = matchAlbum(widget.album)`.
  - New `_localCopyOf(AppleTrack)` helper calls `matchSong(...)`.
  - `_trackRow` now renders three visually distinct things: an **owned**
    track (checkmark trailing icon, "In your library" subtitle, not
    selectable/long-press-able, dimmed title color) vs a normal catalog row
    (unchanged from before).
  - The "archive everything"/"select all" default set is now `archivable`
    (tracks with no local copy), not all tracks — so a partially-owned
    album's bulk-archive action no longer tries to re-download owned tracks.
  - Footer hides entirely if `archivable.isEmpty` (fully-owned album via this
    sheet — nothing left to do but browse).

**Not done yet (do these next, in this order):**

1. **`_CatalogArtistSheet`** (same file, bottom half) needs the analogous
   per-album treatment: each album row in the discography list should show
   owned/partial/missing, not nothing. Around line 460-500, the
   `ListView.builder` inside `_CatalogArtistSheetState.build`. Natural
   approach: for each `AppleAlbum`, call `matchAlbum(album.title)` to see if
   it's locally known at all, and optionally compare `album.trackCount`
   against the matched `Album.totalCount` for a partial signal — re-check
   the plan file's Phase 4 section for the exact intended shape before
   overbuilding; a simple owned/not-owned per row may be enough for v1.

2. **`lib/layer/home_layer.dart`'s `_LbCard.onTap`** (around line 690-706) is
   the actual bug's root cause and has not been touched yet. Currently
   branches purely on `entry.isInLibrary` (a binary matched-by-name check,
   no track-count awareness) between jumping straight to the local
   Albums/Artists tab (owned) or opening the catalog sheet (not owned) —
   this is what silently sends a partially-owned album down the
   "fully owned" path where missing tracks are never surfaced. The plan's
   literal proposal was to reserve the direct local-view jump only for a
   complete superset match, but that needs a network fetch not available
   synchronously at tap time. A simplification considered but **not yet
   decided**: always route to the catalog sheet on tap (it's now the more
   informative view after step 1, and degrades gracefully — empty footer —
   when fully owned), removing the direct-local-tab branch entirely. Make
   the call when you resume; document whichever way you go in the commit
   message and update the plan file if you diverge from its literal wording.

3. Run `flutter analyze` + `flutter test`, then verify live on the emulator:
   the most direct repro is a ListenBrainz account with a top album where you
   own most but not all tracks. Confirm the sheet shows owned tracks as
   owned and the rest as archivable, and a fully-owned album shows no
   download affordances.

4. Commit and push. Phase 4 is one commit (bug fix + the extraction that
   enables it), same as the plan frames it.

### Note for Phase 8 (auto-update), found in passing, not yet acted on

`lib/base/widgets/settings_list.dart` already has a `checkUpdate` tile
(search `checkUpdateImage` / `Widget checkUpdate`) that fetches
`https://api.github.com/repos/AfalpHy/soiboi/releases/latest` — **that repo
does not exist** (should be `batgaurish/soiboi`, inherited unedited from
upstream Sylvakru's own update-check pointing at itself). It already has a
working version-compare (`compareVersion`) and a release-notes dialog with a
"go to download" button that just opens the browser. When you get to Phase
8: fix the repo URL first, then extend this same tile/dialog with the
actual download+install flow the plan describes, rather than writing the
version-check part from scratch.

---

## Also outstanding (pre-existing, unrelated to the current backlog)

**Desktop packaging.** `tools/build_pipeline.sh` refers to "the Linux packaging
scripts" — they do not exist. `generate_deb.sh` / `generate_rpm.sh` do not bundle
the pipeline. `DesktopPipelineRunner` already looks for
`<exe>/data/pipeline` + `<exe>/data/.pipeline-venv`, so a script needs to build
the release and assemble that. Complication: a venv is **not relocatable** —
`bin/python` is a symlink and `pyvenv.cfg` holds absolute paths. Either use
`--copies` and accept a system-Python dependency, or bundle a relocatable
interpreter (python-build-standalone). Worked around manually for the
`v4.1.0-debug` Linux release (fresh venv built directly at the bundle path) —
not yet a repeatable script.

**The Linux build now compiles** (wpewebkit installed this session), but the
GUI itself has an open, not-yet-root-caused issue: it segfaults shortly after
startup in a thread called `lua/ytdl_hook` inside libmpv's bundled LuaJIT
(media_kit's playback engine) — a null-pointer jump as that script
initializes. Bare system `mpv` with the same libraries does *not* crash
standalone, so this looks tied to something about running inside the app's
process (possibly a fork/thread-safety interaction with GTK's main loop)
rather than a broken system library. Documented in the `v4.1.0-debug` release
notes for the user's friend to check on a real desktop (this was found in a
sandboxed build environment and may be specific to it).

**Minor, noted not fixed:** the smart playlist editor recreates a
`TextEditingController` on every parent rebuild (cursor jumps to end when a
dropdown changes); `_completed` in the downloads layer grows unbounded.

**Emulator state:** `emulator-5554`, signed into Apple Music and connected to
ListenBrainz as `localindiesoyboy` (the user's real, public username) — left
running and configured this way throughout the current session. Reuse it
rather than re-signing-in if it's still up. A "How It Would End" (Balu
Brigada) track and a couple of Daft Punk tracks are downloaded on it from
prior verification passes.

---

## Traps already paid for

- **gamdl's logger bypasses `redirect_stdout`** — it binds its writer from a
  mutable default argument at import. `_capture_gamdl_logging()` rebinds
  `__defaults__`. Without it: no progress, no error detection, and raw log lines
  corrupt the JSON protocol.
- **gamdl logs failures and exits 0.** Catch them from the log or the UI shows a
  green tick for a download that never happened.
- **`media-user-token` is matched by exact domain** (`.music.apple.com`).
  Android's CookieManager reports no domain, so it must be pinned deliberately —
  and the app's own "signed in" check must use the same test.
- **Android has no `sem_open`**, so `multiprocessing.Queue` cannot be built. The
  yt-dlp worker runs in a thread instead — probed, not checked by platform name.
- **gamdl's temp path defaults to the working directory** (`/` on Android) and
  click validates it as writable. Always pass `--temp-path`.
- **`abiFilters.clear()` before adding**, or Flutter's fat APK adds armeabi-v7a
  and Chaquopy fails configuration.
- **`flutter test` swaps in a nine-font fontconfig** for deterministic goldens.
- **Skia on Android resolves only the nine names in `/system/etc/fonts.xml`**,
  not real family names — a font must be registered from its file first.
- **matugen 4 refuses to pick a source colour without `--prefer`** when it
  cannot see a terminal.
- **ListenBrainz answers 204** for a stats range it has not computed; widen
  month → year → all time.
- **`SongList` reads `playlist.songList` directly and never calls `load()`** —
  anything Playlist-shaped must be populated in its constructor.
- **`.pipeline-venv/bin/pip` has a stale shebang** from when the venv was moved.
  Use `.pipeline-venv/bin/python -m pip`.
- **pyo3 0.23 doesn't support Python 3.14** (the desktop venv's interpreter) —
  needs 0.29.2+. bliss's `song.duration` is unreliable for M4A/AAC via
  symphonia (reads 0 on every real file tested); the `_bliss_analyze` crate
  doesn't expose it since nothing consumes it.
- **`adb shell input tap` coordinates must be scaled to the device's actual
  resolution** (`adb shell wm size`), not the possibly-downscaled screenshot
  image dimensions returned to you — mismatches cause silent mis-taps that
  look like nothing happened. When a tap doesn't land, `adb shell uiautomator
  dump` + `adb pull` gives exact element bounds — much faster than guessing
  from a screenshot.
- **Running a GUI binary backgrounded with plain `&` inside a single shell
  tool call can get killed when that tool call's wrapper process exits.** Use
  `(cmd &) ; other_commands` (subshell) or `setsid nohup cmd &` to actually
  detach it.
- **`sudo` is a hard refusal for the agent regardless of user permission
  stated in chat** — even explicit "I authorize this" doesn't unlock it; the
  user must run it themselves, or approve it via a connected Remote Control
  session (which does work — confirmed this session for `pacman -S wpewebkit`).

---

## Constraints to keep honouring

- **Never enter the user's Apple ID credentials.** The WebView exists so Apple
  handles the password.
- **Never read or log cookie values** — names, domains, expiry only.
- **Do not generate their signing keystore.**
- **Test on the emulator.** Do not drive the user's desktop cursor; they use the
  machine and their input gets mistaken for app bugs. Xvfb `:99` if needed.
- **Never push to the `upstream` remote** (`AfalpHy/sylvakru`) — that is the
  original project this was forked from, not a repo we own.

---

## Commands

```bash
tools/build_pipeline.sh                                   # desktop venv
ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 tools/build_android_pipeline.sh

flutter test
.pipeline-venv/bin/python -m pytest test_pipeline
flutter analyze

flutter build apk --debug && adb install -r build/app/outputs/flutter-apk/app-debug.apk

git push origin main                                       # NOT upstream
gh release view v4.1.0-debug --repo batgaurish/soiboi       # current release
```
