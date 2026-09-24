# Soiboi

Soiboi turns streaming playlists into a music library you own. Paste a link,
and the app downloads, decrypts, tags and files the tracks on the device you
are holding. It runs on Linux and Android with no server, no Docker and no
sync service.

Soiboi plays local files. It will not stream from Spotify, Apple Music or
anyone else.

## Features

- **Local playback** on Linux and Android, with synced lyrics from `.lrc`
  files or [LRCLIB](https://lrclib.net).
- **Archiving from a link.** Hand Soiboi an Apple Music track, album or
  playlist. It queues the download, resumes at the last finished track after
  an interruption, and drops tagged files with artwork into your library.
- **Playlist import from other services.** Paste a Spotify, Deezer, YouTube
  Music, Tidal, SoundCloud or Qobuz playlist link, or pick a playlist from your Apple Music account.
  Soiboi matches each track to Apple's catalog by ISRC first, then by artist
  and title, and archives the matches. None of these sources ask you to log
  in.
- **Weekly discoveries** from your public ListenBrainz exploration and jams
  playlists, on the home screen.
- **Smart playlists** built on BPM, energy, danceability, play count, date
  added and more. [bliss-audio](https://github.com/Polochon-street/bliss-rs)
  computes the audio features on the device.
- **Lossless ALAC** through a bundled
  [wrapper-v2](https://github.com/glomatico/wrapper-v2). Soiboi ships no
  Apple code: setup asks for an Apple Music APK you supply and checks the
  libraries it extracts from it.
- **Optional AI** with your own key (Gemini, OpenRouter, Groq, Anthropic,
  OpenAI, Ollama or any OpenAI-compatible endpoint): make playlists from a
  prompt and get album picks on Home. Off until you add a key.
- **Codec and bitrate on every track**, so you can see ALAC versus AAC at a
  glance.
- **Self-hosted servers**: browse Navidrome, Emby and WebDAV libraries next to
  your local files.
- **Backup and restore** for playlists, history and settings, plus an in-app
  updater that installs new releases from this repo.
- **Theming**: three UI flavours, light and dark, Material You on Android,
  matugen on Linux, and six prebuilt palettes.

Soiboi does not scrobble. Pair it with
[Pano Scrobbler](https://github.com/kawaiiDango/pScrobbler) or any other
media-session scrobbler.

## How it works

The app carries its own Python pipeline, built on
[gamdl](https://github.com/glomatico/gamdl). On desktop Soiboi runs it as a
subprocess; on Android it runs in-process through
[Chaquopy](https://chaquo.com/chaquopy/). Both platforms run the same code.
The device that downloads a track keeps the only copy, and nothing moves files
between devices.

```
┌──────────────────────────────────────────┐
│  Soiboi (Linux · Android)                │
│  ┌────────────────────────────────────┐  │
│  │ embedded pipeline                  │  │
│  │ download → decrypt → mux → tag     │  │
│  │ → audio analysis → local library   │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

## Install

Grab the Android APK or the Linux x64 tarball from
[Releases](https://github.com/batgaurish/soiboi/releases). Android will ask
you to allow installs from your browser or file manager. Updates install from
inside the app.

## Build from source

You need the [Flutter SDK](https://docs.flutter.dev/install/manual).

### Linux (Arch / CachyOS)

```shell
sudo pacman -S clang lld cmake ninja pkgconf gtk3 xz libsecret mpv wpewebkit
git clone https://github.com/batgaurish/soiboi.git
cd soiboi
tools/build_pipeline.sh
tools/apply_patches.sh
flutter run --release
```

### Linux (Ubuntu / Debian)

```shell
sudo apt install clang lld cmake ninja-build pkg-config libgtk-3-dev liblzma-dev libsecret-1-dev libmpv-dev
git clone https://github.com/batgaurish/soiboi.git
cd soiboi
tools/build_pipeline.sh
tools/apply_patches.sh
flutter run --release
```

`tools/apply_patches.sh` patches media_kit so libmpv skips its Lua scripts,
which crash the Linux build. `flutter pub get` reverts the patch, so run the
script again after every `pub get`. `tools/install_linux.sh` adds Soiboi to
your app launcher.

### Android

```shell
flutter doctor --android-licenses
ANDROID_NDK=<path to your NDK> tools/build_android_pipeline.sh
flutter build apk --release
```

Set `SOIBOI_ABIS=arm64-v8a` to leave out the x86_64 emulator libraries and
cut the APK by about 95 MB.

The tree still holds the Windows, macOS and iOS targets from upstream. Nobody
tests them here.

## Credits

Soiboi forks **[Sylvakru](https://github.com/AfalpHy/sylvakru)** by
[AfalpHy](https://github.com/AfalpHy), a cross-platform music player under
Apache 2.0. AfalpHy wrote the player foundation: audio engine, library model,
server clients and platform integration. This fork adds the archiving
pipeline on top. If you want a general music player without the archiving,
use Sylvakru. It also supports Windows, macOS and iOS.

Soiboi plays audio through [media_kit](https://github.com/media-kit/media-kit)
(mpv and FFmpeg), reads tags with
[audio_tags_lofty](https://github.com/AfalpHy/audio_tags_lofty), downloads
with [gamdl](https://github.com/glomatico/gamdl) and analyses audio with
[bliss-audio](https://github.com/Polochon-street/bliss-rs).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Upstream Sylvakru is Copyright 2025-2026 AfalpHy. [NOTICE](NOTICE) lists the
changes in this fork, and git keeps the full history.
