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

## Known gaps

- WebDAV and Google Drive songs take artist, album and title from the folder
  layout and file name; neither carries audio tags.
- The Android app reads folders by path, so it asks for All files access.
- Linux packages other than `tools/install_linux.sh` (deb, rpm, AppImage).
