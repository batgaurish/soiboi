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
| 4 | Bug 2 + `library_match_service.dart` extraction | **Done, committed, verified live** |
| 5 | Smart playlist templates | **Done, committed, verified live** |
| 6 | Auto-generated mood playlists on Home | **Done, committed, verified live** |
| 7 | QOL: download queue / resumable downloads / storage cleanup | **Done, committed, verified live** |
| 8 | Auto-update checker + installer (Android + Linux) | **Done; Android verified by a real self-update, Linux path unrun** |
| 9 | Multi-platform playlist import (`ExternalPlaylistSource`) | **Done, committed, verified live** |
| 10 | Global search shell | **Done, committed, verified live** |
| 11 | Android dynamic color | **Done, committed, verified live** |

**All eleven phases are complete.** Every commit is pushed to `origin/main`.

### The backlog is finished — what to know before picking up anything new

All eleven phases are done, committed, pushed and verified on the emulator.
Notes from the last three that are not obvious from the code:

**Phase 9 (playlist import).** The spike the plan demanded came out for
**YouTube Music, via the yt-dlp already bundled** — gamdl depends on it and
it is already cross-compiled for Android. Spotify was rejected: it needs a
bearer token even for a *public* playlist, so it would mean shipping a
client secret, which contradicts the "nothing configured, no accounts"
shape the rest of discovery has. `ExternalPlaylistSource` abstracts only
the *supply*; Apple resolution and caching stay in
`discovery_service.dart` and are shared. Cache keys are
`sourceId:playlistId` — never a bare id, since a hex string is a plausible
MusicBrainz *and* YouTube id. The fragile part is
`parseYouTubeTrack`: YouTube entries are videos, so artist and title arrive
fused with promotional noise. It is pure and heavily tested — extend the
tests, not the regex, when something parses wrong.

**Phase 10 (search).** The plan called this the highest-uncertainty item
because it needed a new shell above `LayersManager`. **There was nothing to
build**: the sidebar is the drawer on narrow and a rail on wide, and it is
already on every screen, so Search is just another root layer. If a future
task wants app-level chrome, the sidebar is the place — do not wrap
`LayersManager`.

**Phase 11 (Android dynamic color).** `dynamic_color` (the official
package) supplies the channel; `getCorePalette()` returning null on
Android 11 and older *is* the fallback contract, so **no `minSdk` bump was
needed**. `paletteFromColorScheme` is a second mapping parallel to
`_roleForToken`, and a test asserts both cover the same tokens — a device
themed by half the mapping would come out half-flavour, half-system.

**Phase 8 is now verified to the plan's full bar on Android.** `v4.2.0-debug`
was released to `batgaurish/soiboi` with both assets, and the emulator —
still running the 4.1.0 build — detected it, downloaded the 594,840,659-byte
APK (exact size match), handed it to the system installer, and came back up
reporting `versionName=4.2.0`. **The app updated itself from the real
repository.** The Linux half is still unrun for the reason below.

**Releases are repeatable now.** `tools/package_linux.sh` assembles the
Linux bundle (Flutter build + `data/pipeline` + a `--copies` venv built *at
its final path*, because a venv is not relocatable) and verifies the bundled
environment before packing. That closes the "Desktop packaging" item that
was outstanding since the standalone pivot. `generate_deb.sh` /
`generate_rpm.sh` still do not bundle the pipeline — they were left alone.

**If you are starting fresh:** there is no approved backlog left. Get a
new one from the user rather than inventing work. The plan file at
`~/.claude-personal/plans/optimized-watching-globe.md` records every
divergence per phase and is still the reference for *why* things are
shaped as they are.

---

## Also outstanding (pre-existing, unrelated to the current backlog)

**Desktop packaging — resolved.** `tools/package_linux.sh` now does this:
Flutter build + `data/pipeline` + a venv built with `--copies` **directly at
its final path** (a venv is not relocatable — `bin/python` is a symlink and
`pyvenv.cfg` holds absolute paths), then verifies the *bundled* environment
before packing. It accepts a dependency on the host's libpython, which is
right for this project's private distribution; bundling
python-build-standalone is the alternative if distribution ever widens.
`generate_deb.sh` / `generate_rpm.sh` still do not bundle the pipeline.

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
dropdown changes). (The downloads layer's unbounded `_completed` list is gone
— Phase 7 replaced it with the queue's capped history.)

**Emulator state:** `emulator-5554`, signed into Apple Music (session expires
2027-03-05) and connected to ListenBrainz as `localindiesoyboy` (the user's
real, public username). **Run it headless** — `emulator -avd soiboi_test
-no-window -no-audio -no-boot-anim`, detached with `setsid nohup` — the user
asked for it off their display; `adb exec-out screencap -p` and `uiautomator
dump` work fine without a window. Reuse it rather than re-signing-in if it's
still up. A "How It Would End" (Balu
Brigada) track, a couple of Daft Punk tracks, and — as of the Phase 4
verification — three *Michael Jackson* tracks from **Bad** are downloaded
on it. It is also left with **"Follow system colours" switched on**
(Phase 11's Material You), so it will not look like the Expressive
default until that is turned off in Settings. That album is deliberately kept partial (3 of 11) as the standing
repro for partial-ownership UI states; don't archive the rest unless a
task specifically needs a fully-owned album.

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
- **iTunes Search does not index every catalog album under artist+album
  keywords.** Wallows' *Nothing Happens* demonstrably exists on Apple Music,
  yet `entity=album&term=Wallows+Nothing+Happens` returns 0 results. The
  catalog sheet's "Not found in the Apple Music catalog" then means "iTunes
  Search didn't rank it", not "it isn't on Apple Music". A null resolve is
  not ground truth — keep that in mind for Phases 9 and 10.
- **gamdl's "already downloaded" skip is a WARNING, not an ERROR** —
  `Skipping "<title>": Media file already exists`. That is what makes
  running without `--overwrite` safe: `_StreamTap` only treats `[ERROR ...]`
  lines as failures, so a fully-owned album re-run reports success rather
  than a queue full of red rows. gamdl's `--database-path` defaults to None,
  so no SQLite file lands in the working directory (`/` on Android); the
  skip is a pure `final_path.exists()` check.
- **The emulator must run headless** (`-no-window -no-audio -no-boot-anim`,
  detached with `setsid nohup`) — the user does not want it on their
  display. `adb exec-out screencap -p` and `uiautomator dump` both work
  without a window, so nothing is lost.
- **uiautomator `content-desc` carries the full row labels** the toy
  screenshot→coordinate path makes painful: dump, grep the descs, tap the
  center of their exact `bounds`. Row selectability also shows up as
  `clickable`/`long-clickable` flags, which is a cheap way to assert UI
  state (e.g. owned catalog rows being non-long-pressable).

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
tools/package_linux.sh                                     # linux bundle + tarball
gh release view v4.2.0-debug --repo batgaurish/soiboi       # current release
```
