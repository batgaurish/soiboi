"""Reading a public playlist from a platform that has no usable API.

Spiked both candidates before writing any of this:

* **Spotify** needs a bearer token even for a public playlist, which means a
  registered app and a client secret shipped inside the binary. That is OAuth
  by another name, and it contradicts the standing "nothing configured, no
  accounts" shape of discovery here -- ListenBrainz needs only a username.
* **YouTube Music** has no official API, but yt-dlp is *already inside this
  app*: gamdl depends on it, and it is already cross-compiled and working on
  Android. A flat playlist extraction is one call, needs no key, no account
  and no new dependency, and returns in about a second.

So this is YouTube Music, through the yt-dlp that was going to be here anyway.

Deliberately flat (`extract_flat`): resolving each entry fully would mean one
request per track for data that is thrown away -- the tracks are matched
against Apple's catalog afterwards regardless, so all that is needed here is a
title and whoever uploaded it.
"""

# Videos longer than this are not songs. YouTube playlists collect concert
# films, hour-long mixes and podcast episodes alongside music, and each one
# would otherwise become a nonsense Apple Search query.
MAX_TRACK_SECONDS = 20 * 60


def fetch(url, limit=None, emit=None):
    """Read the public playlist at [url].

    Returns a result dict with the playlist title and its entries. Never
    raises: the caller is a UI streaming events, and a private or deleted
    playlist is an ordinary outcome rather than a crash.
    """
    emit = emit or (lambda event: None)

    try:
        from yt_dlp import YoutubeDL
    except Exception as exc:
        return {
            "event": "error",
            "code": "no_ytdlp",
            "message": f"Playlist reader unavailable: {exc}",
        }

    emit({"event": "progress", "progress": 15, "status": "Reading playlist"})

    options = {
        "quiet": True,
        "no_warnings": True,
        "extract_flat": "in_playlist",
        "skip_download": True,
    }
    if limit:
        options["playlistend"] = int(limit)

    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as exc:
        # yt-dlp's own message is the useful one ("The playlist does not
        # exist", "This playlist is private"); ours would only be vaguer.
        message = str(exc).replace("ERROR: ", "").strip()
        return {"event": "error", "code": "playlist_failed", "message": message}

    if not info:
        return {
            "event": "error",
            "code": "playlist_empty",
            "message": "Nothing found at that link.",
        }

    entries = []
    for entry in info.get("entries") or []:
        if not isinstance(entry, dict):
            continue
        title = (entry.get("title") or "").strip()
        if not title:
            continue
        # yt-dlp uses these two placeholder titles for entries it can see in
        # the listing but cannot read. Passing them through would put
        # "[Private video]" into a download queue.
        if title in ("[Private video]", "[Deleted video]"):
            continue
        duration = entry.get("duration")
        if duration and duration > MAX_TRACK_SECONDS:
            continue
        entries.append(
            {
                "title": title,
                # channel is the more reliable of the two on music uploads;
                # uploader is kept as the fallback for older extractions.
                "uploader": entry.get("channel") or entry.get("uploader") or "",
                "duration": duration,
            }
        )

    emit({"event": "progress", "progress": 100, "status": "Done"})
    return {
        "event": "done",
        "title": info.get("title") or "Imported playlist",
        "entries": entries,
    }
