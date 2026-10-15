# Soiboi

Soiboi turns streaming playlists into a music library you own. Paste a link,
and the app downloads, decrypts, tags and files the tracks on the device you
are holding. It runs on Linux and Android with no server, no Docker and no
sync service.

Soiboi plays files you own and your own servers. It will not stream from
Spotify, Apple Music or anyone else.

## What it does

- **Plays your music** from folders on the device, with synced lyrics from
  `.lrc` files or [LRCLIB](https://lrclib.net), a ten-band equalizer, a sleep
  timer and a queue that survives a restart.
- **Archives from a link.** Hand it an Apple Music track, album or playlist and
  it queues the download, resumes after an interruption, and files tagged songs
  with artwork into your library. ALAC lossless works through a bundled
  [wrapper-v2](https://github.com/glomatico/wrapper-v2); Soiboi ships none of
  Apple's code and asks for the one Apple Music app file the wrapper is pinned
  to.
- **Imports playlists from nine platforms:** Spotify, Deezer, YouTube Music,
  Tidal, SoundCloud, JioSaavn, Qobuz, Gaana and Bandcamp, plus a pasted
  tracklist. None of them need an account. Each song is matched to Apple's
  catalog by ISRC first, then by artist and title; the ones that miss wait for
  your pick instead of failing mid-download.
- **Finds new music.** Weekly playlists from your public ListenBrainz profile,
  and your own Apple Music playlists once you are signed in.
- **Smart playlists** built on tempo, energy, danceability, play count, date
  added and more. [bliss-audio](https://github.com/Polochon-street/bliss-rs)
  measures the audio on the device.
- **Optional AI** with your own key (Gemini, OpenRouter, Groq, Anthropic,
  OpenAI, Ollama or any OpenAI-compatible endpoint): playlists from a sentence,
  mood tags written once and read offline, and album ideas. Off until you add a
  key.
- **Other libraries next to your files:** Navidrome, OpenSubsonic, Jellyfin,
  Emby, Plex, WebDAV and Google Drive, all in one searchable library.
- **Addons:** paste the address of an addon server in Sources and its search
  results, streams, album and artist pages and shelves appear in Soiboi. The
  protocol is the one Eclipse uses (`/manifest.json`, `/search`, `/stream/{id}`,
  `/album/{id}`, `/artist/{id}`, `/playlist/{id}`, `/catalog/{id}`), so Eclipse
  addons work. Addon songs play from the queue and can be saved to playlists.
- **Debrid sources:** TorBox and Real-Debrid accounts are searched for a song
  that is not on the device, and the match streams. TorBox AirLock can be
  switched per download, for files already in the account.
- **Codec and bitrate on every song**, so lossless and lossy never look alike.
- **Two looks:** Zine (chunky type, round corners, tilted codec stickers) and
  Liner Notes (a record sleeve: serif headings, small corners, printed labels),
  each in paper and ink.

Soiboi does not scrobble. Pair it with
[Pano Scrobbler](https://github.com/kawaiiDango/pScrobbler) or any media-session
scrobbler.

## How it is built

```
engine/        pure Kotlin: download queue, pipeline runner, sources, Apple
               catalog, discovery, AI, smart playlists, lyrics, SQLite library,
               server connectors, debrid, player controller. No UI.
shared-ui/     one Compose UI source folder, compiled into both apps
app-desktop/   Linux app. Audio through an mpv process.
app-android/   Android app. Audio through Media3; Python runs in-process
               through Chaquopy.
pipeline/      the Python pipeline (gamdl, tag and cover reading, analysis),
               the same code on both platforms
```

The engine never touches the screen or the operating system directly. Each app
hands it a `Platform` (paths, a pipeline runner, an audio backend, a secret
store), so a fix in the engine is a fix on both platforms.

## Install

Build it from source for now; there are no tagged releases of the Kotlin
version yet.

## Build from source

You need JDK 21, Python 3.12+ with `venv`, and [mpv](https://mpv.io) on Linux.

### Linux

```shell
git clone https://github.com/batgaurish/soiboi.git
cd soiboi
tools/build_pipeline.sh          # the Python environment, once
./gradlew :app-desktop:run       # run from the checkout
tools/install_linux.sh           # or install for your user
```

On Arch or CachyOS: `sudo pacman -S jdk21-openjdk python mpv`.

The lossless wrapper is optional and built by `tools/build_wrapper.sh`.

### Android

You need the Android SDK (platform 37) and Python 3.12 for Chaquopy.

```shell
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
tools/build_android_pipeline.sh   # cross-compiles gamdl's muxer, bliss and the wheel repo
./gradlew :app-android:assembleDebug
```

Without `pip-repo/` the app still builds and runs: scans and covers need only
mutagen, and downloads report that the downloader is missing. Set
`SOIBOI_ABIS=arm64-v8a` to leave out the x86_64 emulator libraries.

### Tests

```shell
./gradlew :engine:test           # Kotlin
.pipeline-venv/bin/python -m pytest   # Python
```

## Credits

Soiboi began as a fork of **[Sylvakru](https://github.com/AfalpHy/sylvakru)**
by [AfalpHy](https://github.com/AfalpHy), a cross-platform music player under
Apache 2.0, and has since been rewritten in Kotlin with its own interface. Some
of the library, queue and lyric-parsing logic follows Sylvakru's design;
[NOTICE](NOTICE) says exactly what. If you want a general player without the
archiving, use Sylvakru.

Audio plays through [mpv](https://mpv.io) on Linux and
[Media3](https://developer.android.com/media/media3) on Android. Downloads use
[gamdl](https://github.com/glomatico/gamdl); tags and artwork use
[mutagen](https://mutagen.readthedocs.io); the audio analysis is
[bliss-audio](https://github.com/Polochon-street/bliss-rs). Type is Bricolage
Grotesque, Fraunces and IBM Plex Sans, all under the SIL Open Font License.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
