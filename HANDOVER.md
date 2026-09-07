# Soiboi — handover

Paste this at the start of a new session.

---

## What Soiboi is

Cross-platform music player (Linux + Android) forked from **Sylvakru**
(Apache-2.0), with an Apple Music archival pipeline embedded **inside the app**.

Repo: `~/Projects/Soiboi - Local Music Player with Download and Lyric Support/soiboi`

Standing constraints, already decided, not up for re-litigation:

- **Strictly offline.** No streaming services beyond self-hosted.
- **Apple album IDs are canonical, not MusicBrainz.**
- **Standalone on both platforms.** No Docker, no server, no Syncthing. Whatever
  device downloads a track does the whole job — and keeps the only copy.
- **Scrobbling out of scope** (recommend Pano Scrobbler).
- Codec/quality badges stay obvious in every flavour.
- Expressive is the default flavour; three flavours, matugen, light/dark.
- Private distribution: the user and friends. Not F-Droid, not Play Store.

---

## State

HEAD is `e1cded0`. **There is uncommitted work in the tree** — the desktop mood
pipeline from a session that ran out of credits mid-way. Review before
committing:

```
 M lib/base/data/library.dart          M pipeline/soiboi_pipeline/downloader.py
 M lib/base/data/smart_playlist.dart   M pipeline/soiboi_pipeline/runtime.py
 M lib/base/my_audio_metadata.dart     M pipeline/soiboi_pipeline/__main__.py
 M lib/base/services/pipeline_runner.dart
 M test/smart_playlist_test.dart       M tools/build_pipeline.sh
?? pipeline/soiboi_pipeline/acoustic.py
?? test_pipeline/test_acoustic.py
```

67 Dart tests, 19 Python tests, analyzer clean (as reported by that session; re-run
to confirm).

**Verified working end to end on the emulator:** sign in → download → decrypt →
mux → tag → library → play with synced lyrics. Real track, 10.6 MB, AAC-LC
258 kbps, no `sinf`/`encv`/`enca`/`pssh` boxes, so the cross-compiled muxer
really decrypts.

**Desktop mood analysis works:** after a download, Essentia writes a per-file
`.soiboi-acoustic.json` sidecar; the library scan reads it; the smart playlist
editor offers BPM, Energy, Danceable, Relaxed, Aggressive as fields and sorts.

### Architecture

| Piece | Where | Notes |
|---|---|---|
| Shared pipeline | `pipeline/soiboi_pipeline/` | Same Python both platforms. |
| Acoustic analysis | `pipeline/soiboi_pipeline/acoustic.py` | Essentia at download time. Desktop only. |
| Desktop transport | `DesktopPipelineRunner` | Subprocess into `.pipeline-venv` (3.14). JSON per line. |
| Android transport | `AndroidPipelineRunner` + `PipelineChannel.kt` | Chaquopy, CPython **3.12**. |
| Native muxer | `tools/build_android_muxer.sh` | gamdl's Rust `_ammuxer`, arm64-v8a + x86_64. |
| Android wheels | `tools/build_android_wheels.py` → `android/pip-repo/` | Per-ABI gamdl with muxer embedded; repaired `construct`, `pywidevine`. |
| Rebuild both | `tools/build_android_pipeline.sh` | Outputs gitignored. |

`pywidevine-1.9.0` in `android/pip-repo/` is **unused** — pip backtracks to
1.8.0. The wheel that unblocked the chain was `construct`.

---

## THE OPEN DECISION: mood analysis on Android

Essentia has no Android wheel. The alternative evaluated is **`bliss-audio`**, a
Rust crate ("a song analysis library for making playlists") built with
`symphonia` instead of ffmpeg.

**A 220-track comparison against the user's real library has been run.** Do not
redo it. Artefacts are in `docs/`:

- `docs/bliss-vs-essentia-220.jsonl` — raw paired results
- `docs/compare.py`, `docs/report.py` — harness and analysis
- `docs/bliss-probe/` — the Rust probe (main.rs + Cargo.toml)

### Findings

**Viability: confirmed.** Built with
`default-features = false, features = ["symphonia-aac", "symphonia-isomp4"]`,
the binary links against **libc, libm, libgcc_s and nothing else** — no ffmpeg,
no C audio libraries. Same linkage profile as `_ammuxer`, which already
cross-compiles to both ABIs. And it is **8x faster** than Essentia
(0.33 s vs 2.47 s median per track).

**Accuracy: roughly one track in four disagrees on BPM.**

| | |
|---|---|
| within 5% | 170/220 (77%) |
| octave error (half/double) | 13/220 (6%) |
| metrical-ratio disagreement (≈4:3, 3:2, 2:3) | 37/220 (17%) |

The 17% bucket is not noise — the ratios cluster at 1.35 and 0.68, i.e. genuine
ambiguity about where the beat is, not random error.

On the tracks that do agree, bliss reads **+1.58% high on 99% of them** — a very
clean systematic bias.

**No cheap correction works.** Tested on the data:

| mitigation | within 5% |
|---|---|
| as-is | 77% |
| bias-corrected (÷1.0158) | 78% |
| octave-folded into [80,160) | 75% |
| octave-folded into [90,180) | 76% |

Folding makes it *worse*. And **Essentia's own beat confidence does not flag the
octave errors** (median 2.39 on flipped vs 2.21 on agreeing), so it cannot be
used to filter them either.

**bliss normalises every feature to [-1,1]** — `2(v−MIN)/(MAX−MIN)−1`. Index 0
is not BPM. Constants: tempo 0..206, spectral 0..11025 (SAMPLE_RATE/2, and
SAMPLE_RATE=22050), flatness and ZCR 0..1, loudness −90..0. `docs/bliss-probe`
already inverts these.

**bliss has no onset-rate equivalent.** The shipping mood formulas weight
onset_rate at 0.6 in `energy` and 0.4 in `danceable`, so they cannot be ported
to bliss unchanged — they would need retuning against bliss's feature set
(which is richer: ZCR, flatness, rolloff, loudness stddev, 13 chroma).

### A bug this uncovered, independent of Android

`acoustic.py` computes brightness from `audio[:32768]` — **one 0.74 s window
from the start of the track**, usually the intro. Measured against a whole-track
mean it correlates at **r = 0.086**. It is measuring noise. bliss's whole-track
centroid correlates with a proper Essentia whole-track mean at **r = 0.932**.

So on brightness, bliss is *better than what currently ships*. Fix this
regardless of which way the Android decision goes: replace the single window
with a `FrameGenerator` mean (`docs/compare.py` has the working code).

### Recommendation to put to the user

Use **bliss on both platforms** rather than Essentia on desktop and bliss on
Android. Reasons: one set of numbers so a rule means the same thing everywhere;
8x faster; fixes the brightness bug by construction; and it removes Essentia,
a 100 MB+ native dependency, from the desktop bundle — which also makes the
unsolved desktop-packaging problem smaller.

The cost is absolute BPM accuracy. That matters if the user writes literal
"BPM > 120" rules; it matters much less for energy/danceable/mood axes, which
would be retuned to bliss's features anyway. **Ask before committing to this** —
it means deleting working desktop code.

---

## Also outstanding

**Desktop packaging.** `tools/build_pipeline.sh` refers to "the Linux packaging
scripts" — they do not exist. `generate_deb.sh` / `generate_rpm.sh` do not bundle
the pipeline. `DesktopPipelineRunner` already looks for
`<exe>/data/pipeline` + `<exe>/data/.pipeline-venv`, so a script needs to build
the release and assemble that. Complication: a venv is **not relocatable** —
`bin/python` is a symlink and `pyvenv.cfg` holds absolute paths. Either use
`--copies` and accept a system-Python dependency, or bundle a relocatable
interpreter (python-build-standalone).

**The Linux build will not compile here.** `flutter_inappwebview_linux` needs
`sudo pacman -S wpewebkit`. Left for the user (needs root). Consequence: the
matugen work is verified by direct testing but has never been seen running in
the Linux app.

**Minor, noted not fixed:** the smart playlist editor recreates a
`TextEditingController` on every parent rebuild (cursor jumps to end when a
dropdown changes); `_completed` in the downloads layer grows unbounded.

**Left on the emulator:** a test smart playlist "Daft Punk picks". The
ListenBrainz username was set to a public test account during verification and
**has been cleared**.

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

---

## Constraints to keep honouring

- **Never enter the user's Apple ID credentials.** The WebView exists so Apple
  handles the password.
- **Never read or log cookie values** — names, domains, expiry only.
- **Do not generate their signing keystore.**
- **Test on the emulator.** Do not drive the user's desktop cursor; they use the
  machine and their input gets mistaken for app bugs. Xvfb `:99` if needed.

---

## Commands

```bash
tools/build_pipeline.sh                                   # desktop venv
ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 tools/build_android_pipeline.sh

flutter test
.pipeline-venv/bin/python -m pytest test_pipeline
flutter analyze lib/

flutter build apk --debug && adb install -r build/app/outputs/flutter-apk/app-debug.apk
```
