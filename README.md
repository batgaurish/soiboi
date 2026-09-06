# Soiboi

**An offline music player for people who want to own a local copy of their streaming library.**

Soiboi plays your local music collection on Linux and Android, and pairs with a
self-hosted [download pipeline](https://github.com/batgaurish/apple-music-archival-frontend)
that archives Apple Music playlists into properly tagged, canonically-versioned
local files.

It is a player first. There is no streaming-service integration and none is
planned — plenty of apps already do that. Soiboi is for the library you already
built.

## What it does

- **Offline playback** of local files on Linux desktop and Android
- **Self-hosted servers** — Navidrome, Emby, WebDAV
- **Download from Apple Music** — add a playlist URL, watch progress, and browse
  the resulting files, all backed by your own `download_bridge` service
- **Weekly discoveries** surfaced on the home screen from the pipeline's
  discovery engine
- **Synced lyrics** from local `.lrc` sidecars, your server, or LRCLIB
- **Quality at a glance** — codec and bitrate (ALAC, AAC 320, …) shown on tracks,
  because in an archival library that is information you actually want visible
- **Three UI flavours** plus light/dark and dynamic colour

Scrobbling is deliberately not built in. Use [Pano Scrobbler](https://github.com/kawaiiDango/pScrobbler)
or any media-session scrobbler.

## Architecture

The download engine (gamdl + ffmpeg + mutagen) needs a real filesystem and native
dependencies, so it does not run on Android. It stays where it is — a Docker
service on your own machine — and Soiboi talks to it over your LAN or Tailscale.

```
┌─────────────────────┐        REST         ┌──────────────────────┐
│  Soiboi             │ ──────────────────► │  download_bridge     │
│  Linux · Android    │  playlists, stats,  │  gamdl → ffmpeg →    │
│                     │  progress, discovery│  mutagen → dedupe    │
└─────────────────────┘                     └──────────┬───────────┘
          ▲                                            │ writes
          │              Syncthing / Navidrome         ▼
          └──────────────────────────────────  ~/music (tagged library)
```

Files reach your phone through Syncthing, so playback is genuinely offline — the
server is only needed when you want to download something new.

## Build

Requires the [Flutter SDK](https://docs.flutter.dev/install/manual).

### Linux (Arch / CachyOS)

```shell
sudo pacman -S clang lld cmake ninja pkgconf gtk3 xz libsecret mpv
git clone https://github.com/batgaurish/soiboi.git
cd soiboi
flutter doctor -v
flutter run --release
```

### Linux (Ubuntu / Debian)

```shell
sudo apt install clang lld cmake ninja-build pkg-config libgtk-3-dev liblzma-dev libsecret-1-dev libmpv-dev
git clone https://github.com/batgaurish/soiboi.git
cd soiboi
flutter run --release
```

Packages:

```shell
flutter build linux && ./generate_deb.sh   # .deb
flutter build linux && ./generate_rpm.sh   # .rpm (needs rpm installed)
```

### Android

```shell
flutter doctor --android-licenses
flutter build apk --release
```

Windows, macOS and iOS build targets are inherited from upstream and remain in
the tree, but are not tested here.

## Credits

Soiboi is a fork of **[Sylvakru](https://github.com/AfalpHy/sylvakru)** by
[AfalpHy](https://github.com/AfalpHy), an excellent cross-platform music player
licensed under Apache 2.0. All of the player foundation — the audio pipeline,
library model, server clients, and platform integration — is their work. This
fork adds the Apple Music archival workflow on top.

If you want a general-purpose music player without the archival pipeline, use
Sylvakru directly. It is the better choice for that, and it supports Windows,
macOS and iOS properly.

Audio playback is via [media_kit](https://github.com/media-kit/media-kit) (mpv/FFmpeg);
tags via [audio_tags_lofty](https://github.com/AfalpHy/audio_tags_lofty).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Upstream Sylvakru is Copyright 2025-2026 AfalpHy. Changes in this fork are listed
in [NOTICE](NOTICE); the complete modification history is preserved in git.
