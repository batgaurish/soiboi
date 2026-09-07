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
| 5 | Smart playlist templates | Not started |
| 6 | Auto-generated mood playlists on Home | Not started |
| 7 | QOL: download queue / resumable downloads / storage cleanup | Not started |
| 8 | Auto-update checker + installer (Android + Linux) | Not started (partial infra already exists, see note below) |
| 9 | Multi-platform playlist import (`ExternalPlaylistSource`) | Not started |
| 10 | Global search shell | Not started |
| 11 | Android dynamic color | Not started |

All commits through Phase 4 are pushed to `origin/main`.

### Where to pick up: Phase 5 (smart playlist templates)

Phase 4 (the local/catalog matcher + per-track ownership in the catalog
sheets) is done and verified live on the emulator. Its
`lib/base/services/library_match_service.dart`
(`normaliseForMatch`/`matchArtist`/`matchAlbum`/`matchSong`) is the
primitive Phases 6, 9 and 10 were waiting for. Next is **Phase 5** — read
the plan file's Phase 5 section; it builds on `smart_playlist.dart`'s
existing rule store and the acoustic fields the bliss work added.

Phase 4 decisions and observations worth knowing:

- **Every ListenBrainz card — owned, partial, or missing — opens the
  catalog sheet on tap.** There is deliberately no "jump to the local tab
  when fully owned" carve-out: the plan's complete-superset exception would
  need the catalog tracklist, a network fetch not available synchronously
  at tap time, and the sheet degrades gracefully when nothing is missing
  (footer hidden, every row "In your library"). This diverges from the
  plan's literal wording; the plan file records the divergence.
- `matchAlbum(name)` gained optional artist scoping
  (`matchAlbum(name, artist: …)`) — the artist discography sheet uses it so
  a same-titled album by a different artist ("Greatest Hits") does not read
  as owned. Unscoped calls keep the old first-match behaviour.
- The artist discography sheet renders per-album state via
  `Album.totalCount` vs Apple's `trackCount`, so "owned" is really
  "local count ≥ catalog count" — a local deluxe edition is not a gap.
- Verified live end-to-end: archived 3 of Bad's 11 tracks from the catalog
  sheet → those rows flipped to "In your library" and lost selectability,
  the footer counted down 11 → 8, the Home cards flipped to "22 plays" /
  "41 plays", a tap on the now-partially-owned album opened the sheet
  (the old code would have jumped to the local Albums tab — that was the
  bug), and the Michael Jackson artist sheet shows
  "Bad · 1987 · 11 tracks · 3 of 11 in your library". The fully-owned
  case (footer hides when `archivable` is empty) shares that exact
  predicate and was accepted by review rather than downloading a whole
  album.

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
Brigada) track, a couple of Daft Punk tracks, and — as of the Phase 4
verification — three *Michael Jackson* tracks from **Bad** are downloaded
on it. That album is deliberately kept partial (3 of 11) as the standing
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
gh release view v4.1.0-debug --repo batgaurish/soiboi       # current release
```
