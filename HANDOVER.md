# Soiboi — handover

Paste this at the start of a new session.

---

## What Soiboi is

Cross-platform music player (Linux + Android) forked from **Sylvakru**
(Apache-2.0), with a streaming-to-offline archival pipeline embedded **inside
the app**. Metadata and the actual archive/decrypt step are Apple Music;
playlist *discovery* spans platforms (ListenBrainz + YouTube Music).

Repo: `~/Projects/Soiboi - Local Music Player with Download and Lyric Support/soiboi`
GitHub: `github.com/batgaurish/soiboi` (public; `origin` remote). The
`upstream` remote points at the original `AfalpHy/sylvakru` fork source —
**never push there**.

Standing constraints, already decided, not up for re-litigation:

- **Strictly offline.** No streaming services beyond self-hosted, for playback.
- **Apple album IDs are canonical, not MusicBrainz.**
- **Standalone on both platforms.** No Docker, no server, no Syncthing.
  Whatever device downloads a track does the whole job — and keeps the only
  copy.
- **Scrobbling out of scope** (recommend Pano Scrobbler).
- Codec/quality badges stay obvious in every flavour.
- Expressive is the default flavour; three flavours, matugen, light/dark.
- Private distribution: the user and friends. Not F-Droid, not Play Store.
- **No accounts, no OAuth, no client secrets.** Every external source used so
  far needs only a public URL or a username. Spotify was evaluated and
  rejected on exactly this ground.

---

## State: shipped, and there is no backlog

**v4.2.1 is released** — `github.com/batgaurish/soiboi/releases/tag/v4.2.1`,
prerelease, with an Android APK (debug keystore) and a Linux x64 **release**
tarball. `main` is clean and pushed. 166 Dart tests, 29 pipeline tests,
analyzer clean.

An eleven-phase backlog (two bugs, a rebrand, nine features) was planned,
implemented, verified and shipped. The plan lives at
`~/.claude-personal/plans/optimized-watching-globe.md` and each phase there
carries an **"As built"** block recording where the implementation diverged
from the plan and why. Read it before touching any of that machinery — it is
the reference for *why* things are shaped as they are, not just what they do.

What landed, in one line each: the Weekly Exploration race; the rebrand;
library backup/restore; the partial-ownership catalog fix plus
`library_match_service.dart`; smart playlist templates; auto mood playlists on
Home; the download queue with track-level resume and storage cleanup; a
working update checker and in-app installer; YouTube Music playlist import
behind `ExternalPlaylistSource`; global search; and Android Material You.

**There is no approved work left.** Get a new backlog from the user rather
than inventing one.

### The two things still genuinely open

1. **The Linux side of the auto-updater has never been run end-to-end.** It
   was untestable while no Linux build would start; that is fixed, so it is
   testable now and still has not been done. `linuxSwapScript` and
   `findBundleDir` are pure and tested (one test executes the generated script
   against real directories), but no Linux build has replaced itself for real.
   Doing it means the installed app swapping its own directory, so ask first.
2. **A running download cannot be cancelled**, on either platform — nothing
   can interrupt the pipeline once a track has started. `cancel()` therefore
   drops queued jobs only, and Pause takes effect after the current track. The
   UI says so. **Do not "fix" this without changing the transport first.**

Minor, noted not fixed: the smart playlist editor recreates a
`TextEditingController` on every parent rebuild, so the cursor jumps to the
end when a dropdown changes.

---

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

**Emulator:** `emulator-5554` (AVD `soiboi_test`), signed into Apple Music
(session expires 2027-03-05) and connected to ListenBrainz as
`localindiesoyboy` (the user's real, public username). Reuse it rather than
re-signing-in.

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
