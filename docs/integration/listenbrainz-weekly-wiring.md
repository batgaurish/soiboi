# ListenBrainz weekly discovery: API contract and wiring

## Verified API contract

This slice uses the official ListenBrainz API documentation (`users/api/core.html` and `users/api/playlist.html`):

- `GET /1/validate-token`, with `Authorization: Token <token>`, returns `valid` and `user_name`; the documented `user_name` is the user's MusicBrainz ID. The client validates the existing scrobble token and uses that returned identity.
- `GET /1/user/{user_name}/playlists/createdfor` returns JSPF playlist metadata without recordings for the validated ListenBrainz user. ListenBrainz documents these as playlists created for that user; the endpoint is public, so the token is used only to resolve the local account identity.
- `GET /1/playlist/{playlist_mbid}?fetch_metadata=false` fetches playlist recordings while skipping third-party recording metadata lookup. Playlist and recording IDs are extracted from the documented JSPF `identifier` URI forms and validated as UUIDs; title/creator remain descriptive metadata. Responses are bounded to 2 MiB, four playlists, and 100 tracks each; requests respect the API rate-limit headers and have a 35-second total deadline.

Official sources: ListenBrainz API Core and Playlist documentation at `https://listenbrainz.readthedocs.io/en/latest/users/api/core.html` and `https://listenbrainz.readthedocs.io/en/latest/users/api/playlist.html`.

## Parent wiring (intentionally not in MainActivity.kt or DesktopApp.kt)

1. Construct `ListenBrainzWeeklyClient { AppSettings.listenBrainzToken.value }` on Android and `ListenBrainzWeeklyClient { DesktopScrobbleSettings.listenBrainzToken }` on Linux. If the Linux setting accessor is not public, expose a read-only accessor in its settings wrapper; do not duplicate or move secrets into the shared model.
2. At the home integration point, keep a `ListenBrainzWeeklyResult` state initialized to `Loading`, call `client.load()` in a coroutine keyed to token changes/refresh, and pass the result to `ListenBrainzWeeklyHome(result, ListenBrainzTrackAction { track -> ... })`.
3. The callback is the playback boundary. Convert title/artist/recording MBID into the existing BitChord track-matching request and then use the existing resolver/player flow; never treat a ListenBrainz playlist/recording MBID as a BitChord source playback ID. Surface unresolved matches through existing manual-resolution behavior.
4. Preserve `Failure` for invalid-token, HTTP, transport, and parse errors. Only a successful recommendation response with no playlist entries becomes `Empty`.

Android and Linux share the component in `sharedUi/src/jvmSharedMain`. No root screen file is modified in this slice, per task boundary.
