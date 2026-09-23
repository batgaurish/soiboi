"""Reading a public YouTube Music playlist through the bundled yt-dlp.

YouTube Music has no official API, but gamdl depends on yt-dlp, so it is
already inside this app and already working on Android. A flat playlist
extraction is one call, needs no key and no account, and returns in about a
second. (Spotify and Deezer links are read on the Dart side, from their public
pages and API.)

The extraction is flat (`extract_flat`) on purpose: resolving each entry fully
would cost one request per track for data that is thrown away, because the
tracks are matched against Apple's catalog afterwards regardless. A title and
the channel that uploaded it are enough.
"""
from .protocol import Emit, Event, JsonValue, Payload, done, error, ignore
from .protocol import missing_fields, progress

try:
    from yt_dlp import YoutubeDL
except ImportError:  # the bundled environment is incomplete
    YoutubeDL = None

# Videos longer than this are not songs. YouTube playlists collect concert
# films, hour-long mixes and podcast episodes alongside music, and each one
# would otherwise become a nonsense Apple Search query.
MAX_TRACK_SECONDS = 20 * 60

# Titles yt-dlp gives entries it can list but cannot read. Passing them through
# would put "[Private video]" into a download queue.
_PLACEHOLDER_TITLES = {"[Private video]", "[Deleted video]"}

Track = dict[str, JsonValue]


def _track(entry: object) -> Track | None:
    """One playlist entry as a track, or None if it is not a song."""
    if not isinstance(entry, dict):
        return None
    title = (entry.get("title") or "").strip()
    if not title or title in _PLACEHOLDER_TITLES:
        return None
    duration = entry.get("duration")
    if duration and duration > MAX_TRACK_SECONDS:
        return None
    return {
        "title": title,
        # channel is the more reliable of the two on music uploads; uploader
        # is the fallback for older extractions.
        "uploader": entry.get("channel") or entry.get("uploader") or "",
        "duration": duration,
        # Flat extraction fills ISRC only sometimes. When it does, Apple
        # resolution matches exactly instead of by keyword.
        "isrc": entry.get("isrc"),
    }


def fetch(url: str, limit: int | None = None, emit: Emit = ignore) -> Event:
    """Read the public playlist at [url].

    Never raises: a private or deleted playlist is an ordinary outcome for a UI
    streaming events, not a crash.
    """
    if YoutubeDL is None:
        return error("no_ytdlp", "Playlist reader unavailable: yt-dlp is missing")

    emit(progress(15, "Reading playlist"))

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
        # yt-dlp raises its own DownloadError and a spread of network errors.
        # Its message is the useful one ("This playlist is private"); ours
        # would only be vaguer.
        message = str(exc).replace("ERROR: ", "").strip()
        return error("playlist_failed", message)

    if not info:
        return error("playlist_empty", "Nothing found at that link.")

    tracks = [track for track in map(_track, info.get("entries") or []) if track]

    emit(progress(100, "Done"))
    return done(title=info.get("title") or "Imported playlist", entries=tracks)


def handle_playlist(payload: Payload, emit: Emit) -> Event:
    problem = missing_fields(payload, "url")
    if problem:
        return problem
    return fetch(payload["url"], limit=payload.get("limit"), emit=emit)
