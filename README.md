# Soiboi

**A streaming-to-offline archival music player.** Point it at a playlist, get
back a properly tagged local copy you own — no server, no subscription
required to keep listening.

Soiboi plays your local music collection on Linux and Android, and embeds its
own download pipeline directly in the app — whichever device does the
download keeps the only copy, standalone, with nothing to host or sync.
Playlist discovery already spans multiple platforms via ListenBrainz's public
weekly-exploration lists, with more sources planned; the actual archive step
today runs through Apple Music, which also supplies canonical album/track
metadata and artwork.

It is a player first. There is no streaming-service playback and none is
planned — plenty of apps already do that. Soiboi is for building the local
library you actually own.

## What it does

- **Offline playback** of local files on Linux desktop and Android, with
  synced lyrics from local `.lrc` sidecars or LRCLIB
- **Archive a track, album or playlist** — paste a link, watch it download,
  decrypt, tag, and land in your library, entirely on-device (no Docker, no
  companion server)
- **Weekly discoveries** — ListenBrainz's public weekly exploration and jams
  playlists, matched to Apple Music and archivable straight from the home
  screen
- **Mood-aware smart playlists** — BPM, energy, danceability and more,
  computed on-device at download time (via [bliss-audio](https://github.com/Polochon-street/bliss-rs)
  on both platforms) and filterable/sortable like any other track field
- **Self-hosted server support** — Navidrome, Emby, WebDAV — for browsing
  music that lives elsewhere alongside your local library
- **Quality at a glance** — codec and bitrate (ALAC, AAC 320, …) shown on
  tracks, because in an archival library that's information you actually want
  visible
- **Three UI flavours** plus light/dark and dynamic colour (matugen on Linux)

Scrobbling is deliberately not built in. Use [Pano Scrobbler](https://github.com/kawaiiDango/pScrobbler)
or any media-session scrobbler.

## Architecture

Standalone by design: the same Python download/decrypt/tag/analyze pipeline
runs as a subprocess on desktop and in-process (via Chaquopy) on Android, so
there is nothing to self-host and nothing to keep running. A track downloaded
on your phone lives on your phone; there's no sync layer moving files between
devices.

```
┌─────────────────────────────────────────┐
│  Soiboi (Linux · Android)                │
│  ┌─────────────────────────────────┐    │
│  │ embedded pipeline                │    │
│  │ download → decrypt → mux → tag   │    │
│  │ → mood analysis → local library  │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

## Build

Requires the [Flutter SDK](https://docs.flutter.dev/install/manual).

### Linux (Arch / CachyOS)

```shell
sudo pacman -S clang lld cmake ninja pkgconf gtk3 xz libsecret mpv wpewebkit
git clone https://github.com/batgaurish/soiboi.git
cd soiboi
tools/build_pipeline.sh
flutter run --release
```

### Linux (Ubuntu / Debian)

```shell
sudo apt install clang lld cmake ninja-build pkg-config libgtk-3-dev liblzma-dev libsecret-1-dev libmpv-dev
git clone https://github.com/batgaurish/soiboi.git
cd soiboi
tools/build_pipeline.sh
flutter run --release
```

### Android

```shell
flutter doctor --android-licenses
ANDROID_NDK=<path to your NDK> tools/build_android_pipeline.sh
flutter build apk --release
```

Windows, macOS and iOS build targets are inherited from upstream and remain in
the tree, but are not tested here.

## Credits

Soiboi is a fork of **[Sylvakru](https://github.com/AfalpHy/sylvakru)** by
[AfalpHy](https://github.com/AfalpHy), an excellent cross-platform music player
licensed under Apache 2.0. All of the player foundation — the audio pipeline,
library model, server clients, and platform integration — is their work. This
fork adds the embedded streaming-to-offline archival workflow on top.

If you want a general-purpose music player without the archival pipeline, use
Sylvakru directly. It is the better choice for that, and it supports Windows,
macOS and iOS properly.

Audio playback is via [media_kit](https://github.com/media-kit/media-kit) (mpv/FFmpeg);
tags via [audio_tags_lofty](https://github.com/AfalpHy/audio_tags_lofty);
mood analysis via [bliss-audio](https://github.com/Polochon-street/bliss-rs).

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

Upstream Sylvakru is Copyright 2025-2026 AfalpHy. Changes in this fork are listed
in [NOTICE](NOTICE); the complete modification history is preserved in git.
