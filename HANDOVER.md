# Soiboi: handover

Paste this at the start of a new session.

## What Soiboi is

A Linux and Android music player, written in Kotlin, with an archiving pipeline
embedded in the app (Python, gamdl 3.x; a subprocess on Linux, Chaquopy on
Android). Metadata and the decrypt step are Apple Music. Playlist discovery
spans nine platforms. Repo: `github.com/batgaurish/soiboi`. It began as a fork
of Sylvakru; the `upstream` remote points there and must never be pushed to.

Standing decisions, not up for re-litigation:

- Strictly offline for playback of Apple Music content. Soiboi never streams it.
- Apple album IDs are canonical, not MusicBrainz.
- Standalone on both platforms. No Docker, no server, no sync service.
- No accounts, no OAuth and no client secrets for playlist sources.
  (Google Drive is the one exception: it needs the user's own OAuth client.)
- Scrobbling is out of scope.
- Codec and bitrate badges stay obvious in every look.
- Private distribution (the user and friends); not F-Droid, not Play Store.

## Layout

| Folder | What lives there |
|---|---|
| `engine/` | UI-free Kotlin. `Engine.kt` is the composition root. |
| `shared-ui/` | One Compose UI source folder, compiled into both apps. |
| `app-desktop/` | Linux app. `MpvBackend` drives an mpv process over its JSON IPC socket. |
| `app-android/` | Android app. `ExoBackend` (Media3), `AndroidPipelineRunner` (Chaquopy). |
| `pipeline/` | Python: downloader, `read_tags`, `cover`, playlist, analysis. |
| `tools/` | Python environment, wrapper and Android wheel builds, Linux installer. |

The engine knows nothing about screens or the operating system. Each app builds
a `Platform` (paths, `PipelineRunner`, `AudioBackend`, `SecretStore`, wrapper
locations) and hands it to `Engine`. The UI calls `SoiboiApp(engine, platform,
fonts, scope)`.

The two apps compile the same `shared-ui/kotlin` folder. The desktop and Android
Compose artifacts share one API, so UI code must use only common Compose APIs;
anything platform-specific goes behind `PlatformUi`.

## Where things are

- Download path: `download/Archive.kt` builds the payload, `DownloadQueue.kt`
  runs jobs one at a time, `ErrorCatalog.kt` explains failures, `Wrapper.kt`
  runs the lossless wrapper.
- Sources: `sources/` has one file per platform; each implements
  `ExternalPlaylistSource`, and `PlaylistSources.standard` registers them.
  Matching: `apple/AppleCatalog.kt`, `discovery/Discovery.kt`.
- Library: `library/` (SQLite through `androidx.sqlite`, the bundled driver, so
  the same SQLite runs on both platforms). Tags are read by the pipeline's
  `read_tags`, covers by `cover`.
- Connectors: `servers/` behind `MusicServer`.
- Debrid: `debrid/Debrid.kt` implements `Resolver`.
- Design: `shared-ui/.../theme/` (tokens, flavours, the glyph set) and
  `components/`. Zine and Liner Notes differ in shape and voice, never colour.

## Traps

- **Chaquopy and Gradle's configuration cache do not mix.** It is off in
  `gradle.properties`.
- **Compose needs `compileSdk` 37** on both Android modules.
- **Install dependencies to `~/.gradle`, never the root partition.** The root
  drive is small. Do not use `sudo` or `pacman` for build tooling.
- Variables named `app` shadow the `app.soiboi.*` package inside lambdas. Import
  the class instead of writing `app.soiboi.x.Y(...)` in UI code.
- The scan is recursive by default; a flat scan of an `Artist/Album` tree finds
  nothing.
- The first Android launch spends about a minute starting Python before the first
  scan returns. The Today screen says so.
- `SOIBOI_DEV_COMMANDS=<file>` makes the desktop app run one UI action per line
  appended to that file (go, play, now, flavour...). It exists to drive and
  screenshot the real UI without a pointer; it never runs in a normal launch.
- Never push to `upstream`. With `gh`, always pass `-R batgaurish/soiboi`.

## Verified so far

Engine: 73 Kotlin tests. Pipeline: 126 Python tests. Linux: played real songs
from a 2,425-song library, synced lyrics, queue, covers, both looks in both
themes, the readiness checks, the installed copy. Android (x86_64 emulator):
Python scan through Chaquopy, covers, playback start, the phone layout.

Not yet run against real accounts or servers: see `TODOS.md`.
