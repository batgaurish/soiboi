# Soiboi — handover

Paste this at the start of a new session. It covers what the app is, what is
done, what is next, and the traps that already cost time once.

---

## What Soiboi is

A cross-platform music player (Linux + Android) forked from **Sylvakru**
(Apache-2.0), with an Apple Music archival pipeline embedded **inside the app**.

Repo: `~/Projects/Soiboi - Local Music Player with Download and Lyric Support/soiboi`

Standing constraints, all decided earlier and not up for re-litigation:

- **Strictly offline.** No streaming services beyond self-hosted. Plenty of apps
  already do streaming.
- **Apple album IDs are canonical, not MusicBrainz.** MusicBrainz lacks niche
  releases and caused duplicates and wrong versions in the previous project.
- **Standalone on both platforms.** No Docker, no server, no Syncthing. The
  whole pipeline runs on the device doing the downloading.
- **Scrobbling is out of scope** — recommend Pano Scrobbler instead.
- Codec/quality badges must stay obvious and clearly visible in every flavour.
- Expressive is the default flavour; three flavours, matugen, light/dark.
- Private distribution: the user and friends. Not F-Droid, not Play Store.

---

## Current state

`342d840`, clean tree. 61 Dart tests across 9 files, 6 Python tests, analyzer clean.

**The standalone Android pipeline works end to end and is verified on the
emulator**: sign in → download → decrypt → mux → tag → library → play with
synced lyrics. A real track from the user's own subscription came down as
`Downloads/Daft Punk/Discovery/01 One More Time.m4a`, 10.6 MB, AAC-LC 258 kbps /
44.1 kHz / 320.4 s, tagged with 221 KB of cover art, and containing **no `sinf`,
`encv`, `enca` or `pssh` boxes** — so the cross-compiled muxer really did
decrypt it.

### Architecture facts worth knowing before touching anything

| Piece | Where | Notes |
|---|---|---|
| Shared pipeline | `pipeline/soiboi_pipeline/` | Same Python on both platforms. Fix once, fixed everywhere. |
| Desktop transport | `DesktopPipelineRunner` | Subprocesses `.pipeline-venv/bin/python` (3.14). JSON, one object per line. |
| Android transport | `AndroidPipelineRunner` + `PipelineChannel.kt` | Chaquopy embeds CPython **3.12**; MethodChannel + EventChannel. |
| Native muxer | `tools/build_android_muxer.sh` | gamdl's Rust `_ammuxer`, cross-compiled for arm64-v8a **and** x86_64. |
| Android wheels | `tools/build_android_wheels.py` → `android/pip-repo/` | Per-ABI gamdl wheels with the muxer embedded, plus repaired `construct` and `pywidevine`. |
| Rebuild both | `tools/build_android_pipeline.sh` | Outputs are gitignored; run after checkout and after changing pinned versions. |

Three dependency repairs are documented at the point they are made, each with
the reason the shortcut is safe: `construct` 2.8.8 predates wheels (built from
sdist), `pillow` relaxed 12→11, `pycryptodome` relaxed 3.23→3.21.

Note: `pywidevine-1.9.0-py3-none-any.whl` in `android/pip-repo/` is currently
**unused** — pip backtracks to 1.8.0 from PyPI because of a protobuf pin. The
wheel that actually unblocked the chain was `construct`. Harmless, but don't
assume the 1.9 repackage is load-bearing.

---

## Done in the last session (11 commits)

```
342d840 Add smart playlists
c6bd384 Make music you don't own browsable instead of a dead card
a51c6e0 Add multi-select to discovery, and stop playlists looking four tracks long
516e52b Detect matugen automatically and offer its scheme variants
5395880 Make the font picker work on Linux and Android
de8d077 Fix the play queue test helper
ed27335 Put archived tracks into the library
cd60dea Make downloads actually complete on Android
5122576 Keep pipeline tests out of the APK
0f0191b Fix downloads reporting success when gamdl failed
db19179 Make the Android download engine work standalone
```

Everything on the user's reported bug list is closed:

- **Android downloads** — the muxer, the dependency chain, and four separate
  silent defects (below).
- **Downloads reaching the library** — the archive folder was never a library
  folder, and scanning is non-recursive by default while gamdl writes
  `Artist/Album/NN Title.m4a`.
- **Font changer** — `just_font_scan` supports Windows/macOS only, so the list
  was empty on both target platforms. Now enumerates via fontconfig on Linux
  (302 families on this machine) and by parsing `name` tables on Android, with
  lazy per-font registration.
- **matugen** — now auto-detected on PATH and run directly, no template setup;
  all nine scheme variants offered.
- **Discovery playlists showing 4 songs** — they weren't; resolution was one
  lookup at a time and took ~70 s while showing the card's prefetch. Now six at
  a time (~6 s) with live "Matching 24 of 50…".
- **Multi-select** — in discovery and in catalog albums.
- **Clickable non-local albums/artists with metadata and 30 s samples** — new
  `catalog_sheet.dart`.
- **Home shelf overflow** clipping the quality badge, and the ranked shelves
  never appearing (ListenBrainz answers 204 for a range it hasn't computed).

---

## What is left

### 1. Mood playlists — decided: analyse with Essentia at download time

**The user has made this call. Do it.** The earlier objection (that Essentia is
too expensive for a phone) applied to analysing a whole library on a handset,
not to analysing one track right after downloading it.

Verified on 2026-09-07, not assumed:

- Essentia publishes a **cp314 manylinux2014_x86_64 wheel**, so it installs
  straight into `.pipeline-venv` (Python 3.14) with `pip install essentia`.
- A 5:20 track costs **3.08 s total** — 0.20 s load, 2.74 s
  `RhythmExtractor2013`, 0.15 s `KeyExtractor`. Against a 20–60 s download that
  is free.
- Accuracy is good: it reported One More Time at **122.9 BPM, G major**
  (the track is 123 BPM).

**Where to hook it:** `pipeline/soiboi_pipeline/downloader.py`, after a
successful download, before the result is returned. Features go somewhere the
Dart side can query — either the library DB or a sidecar.

**Reference implementation:** `~/apple-music-archival-frontend/acoustic_analyzer.py`
already has the feature extraction, the sidecar format, and the mood heuristics
(energy / brightness / aggressive / relaxed / danceable derived from loudness,
onset rate, spectral centroid and tempo). Its "runs on the server, never on a
phone" docstring is now wrong for the desktop path and should be rewritten
rather than deleted — the *reason* it gives still explains the Android gap.

**Two things to settle while doing it:**

- **Android has no Essentia wheel** — only macosx and manylinux are published,
  and building it for Android means FFTW/KissFFT, libav and TagLib. So a track
  downloaded on the phone gets no features. Options: accept the gap and analyse
  on desktop only (weakens the standalone promise); decode with MediaCodec and
  compute features in Kotlin/Dart; or a Rust extension using `symphonia`,
  cross-compiled exactly the way `_ammuxer` already is for both ABIs. **Ask
  before picking** — this is the same decision the user deferred, now narrowed
  to Android only.
- **The trained mood classifiers are not bundled.** Importing Essentia prints
  `MusicExtractorSVM: no classifier models were configured by default`. Trained
  mood models are a separate download from Essentia's model site. Without them,
  use the heuristics in the old file — and keep them labelled `source: local`
  so the difference from a trained model stays visible.

Then build the mood-playlist UI on top. The rule engine in
`lib/base/data/smart_playlist.dart` is designed to extend: add `SmartField`
entries with `SmartFieldKind.number` for energy/danceability and they inherit
the operators, the editor, persistence and the live match count.

### 2. Desktop packaging

`tools/build_pipeline.sh` refers to "the Linux packaging scripts" — **those do
not exist.** `DesktopPipelineRunner` already looks for the bundle layout
(`<exe>/data/pipeline` with `<exe>/data/.pipeline-venv`), so a script needs to
build the release and assemble that.

Complication: a Python venv is **not relocatable**. `bin/python` is a symlink
and `pyvenv.cfg` holds absolute paths, so copying `.pipeline-venv` into a bundle
ships something that only works where a matching system Python exists. Either
create it with `--copies` and accept the system-Python dependency, or bundle a
relocatable interpreter (python-build-standalone) for a genuinely
self-contained build.

### 3. The Linux build cannot compile on this machine

`flutter_inappwebview_linux` needs WPE WebKit:

```bash
sudo pacman -S wpewebkit
```

It is in the CachyOS repos but installing needs root, so it was left for the
user. Consequence: the matugen work is verified by direct testing against
matugen 4.2 (all nine schemes produce distinct 23-token palettes) but has
**never been seen running in the Linux app**. Install it and look before
trusting the UI half.

### 4. Loose ends

- A test smart playlist ("Daft Punk picks") is left on the emulator. Harmless,
  delete from the UI.
- The ListenBrainz username on the emulator was set to a public test account
  during verification and **has been cleared**. The user's own is not set.

---

## Traps already paid for — do not rediscover these

- **gamdl's logger bypasses `redirect_stdout`.** It builds its writer as
  `CustomOutputWriter(streams=[sys.stdout])` — a mutable default argument bound
  when the module is first imported. `_capture_gamdl_logging()` rebinds
  `__defaults__`. Without it: no progress, no error detection, and raw log lines
  written into the middle of the JSON protocol on desktop.
- **gamdl logs failures and exits 0.** A track it reports as an error must be
  caught from the log or the UI shows a green tick for a download that never
  happened.
- **`media-user-token` is matched by exact domain** (`.music.apple.com`, no
  subdomain matching). Android's CookieManager does not report domains, so the
  capture has to pin that one deliberately. The app's own "signed in" check must
  use the same test or the two disagree.
- **Android has no `sem_open`.** `multiprocessing.Queue` cannot be constructed,
  which killed gamdl's yt-dlp subprocess step. The worker now runs in a thread
  when multiprocessing is unavailable — probed, not checked by platform name.
- **gamdl's temp path defaults to the working directory**, which is `/` on
  Android, and click validates it as writable. Always pass `--temp-path`.
- **`abiFilters.clear()` before adding**, or Flutter's fat APK adds armeabi-v7a
  and Chaquopy fails configuration outright.
- **`flutter test` replaces fontconfig with a nine-font config** for
  deterministic goldens. A font test going through fc-list there tests almost
  nothing; `scanFontDirectories()` is public so the real path can be tested.
- **Skia on Android only resolves the nine names in `/system/etc/fonts.xml`**,
  not real family names. A font must be registered from its file first.
- **matugen 4 refuses to pick a source colour without `--prefer`** when it
  cannot see a terminal — which is always, from inside an app.
- **ListenBrainz answers 204** for a stats range it has not computed. Normal for
  accounts with years of history; the request widens month → year → all time.
- **`SongList` reads `playlist.songList` directly and never calls `load()`.**
  Anything Playlist-shaped must be populated in its constructor.

---

## Constraints to keep honouring

- **Never enter the user's Apple ID credentials.** The WebView exists
  specifically so Apple handles the password.
- **Never read or log cookie values** — names, domains and expiry only.
- **Do not generate their signing keystore.**
- **Test on the emulator.** Do not drive the user's desktop cursor; they are
  using the machine, and their inputs get mistaken for app bugs. Use Xvfb `:99`
  if a desktop UI needs driving.

---

## Commands

```bash
# after checkout, or when pinned versions change
tools/build_pipeline.sh                                   # desktop venv
ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 tools/build_android_pipeline.sh

flutter test                                              # 61 tests
.pipeline-venv/bin/python -m pytest test_pipeline         # 6 tests
flutter analyze lib/

flutter build apk --debug && adb install -r build/app/outputs/flutter-apk/app-debug.apk
```
