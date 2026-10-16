# Soiboi to do

What is left after the Kotlin rewrite. Everything listed here was left
undone on purpose; nothing below is half-built.

## Needs a person

- Sign in to Apple Music and run a real download end to end. The pipeline and
  the readiness checks are verified, but a download needs an account.
- Build the lossless wrapper (`tools/build_wrapper.sh`) and the Android wheel
  repository (`tools/build_android_pipeline.sh`), then check an ALAC download
  on both platforms.
- Try each connector against a real server: Navidrome, OpenSubsonic,
  Jellyfin, Emby, Plex, WebDAV and Google Drive. They are written from the
  public APIs and covered by the engine's parsing, but only the local library
  has run against real data.
- TorBox and Real-Debrid: save a key and play a song from an account.

- Spotify: sign in with your own Spotify app, pick a device, play a track.
  The calls and token handling are covered by mocked tests; nothing has run
  against Spotify itself.
- Musixmatch (needs your user token) and Paxsenix lyrics: written from their
  public shapes, never seen to return a hit. Paxsenix answered "track not
  found" or "busy" for every song tried.
- Tray and Discord presence: the Discord conversation is covered by a fake
  Discord; the tray was not run on a desktop with a tray.
- SMB and YouTube Music streaming on Android: SMB was tested against a real
  Samba server on Linux, YouTube streams through yt-dlp on Linux. Neither was
  run on a phone. Nextcloud reuses the tested WebDAV code, against no Nextcloud.

## Known gaps

- Addons: the `/resolve` and `/resolve-isrc` endpoints are read by the client but nothing
  calls them yet; playlist import could use them to match tracks on an addon.
  Addon video streams and chapters are ignored. Tried only against a local fake
  addon, not a live Eclipse addon.

- Spotify tracks play only on a Connect device, never inside Soiboi, and
  Spotify limits what apps in development mode may read.
- SoundCloud serves 30-second previews for some tracks; they are labelled.
- WebDAV and Google Drive songs take artist, album and title from the folder
  layout and file name; neither carries audio tags.
- The Android app reads folders by path, so it asks for All files access.
- Linux packages other than `tools/install_linux.sh` (deb, rpm, AppImage).
