"""Reads audio tags for the app's library scan.

One implementation for both platforms: mutagen ships with gamdl, so Linux and
Android (through Chaquopy) read tags with the same code and a scan means the
same thing on both. The app sends a batch of paths and gets one record per
readable file; unreadable files are left out rather than failing the batch.
"""
import os

from .protocol import Emit, Event, JsonValue, Payload, done, error, missing_fields, progress

try:
    import mutagen
    TAGS_AVAILABLE = True
except ImportError:  # pragma: no cover - mutagen is a gamdl dependency
    TAGS_AVAILABLE = False


def _first(tags, *keys) -> str | None:
    for key in keys:
        value = tags.get(key) if tags is not None else None
        if value is None:
            continue
        if isinstance(value, (list, tuple)):
            value = value[0] if value else None
        if value is None:
            continue
        text = str(value).strip()
        if text:
            return text
    return None


def _int(text: str | None) -> int | None:
    """'3/12' -> 3, '2019-05-01' -> 2019. Tag fields are free text."""
    if not text:
        return None
    digits = ""
    for char in text:
        if char.isdigit():
            digits += char
        else:
            break
    return int(digits) if digits else None


def _lyrics(path: str) -> str | None:
    """Lyrics live somewhere different in every container, so ask each."""
    try:
        raw = mutagen.File(path)
    except Exception:
        return None
    if raw is None or raw.tags is None:
        return None
    tags = raw.tags
    # MP4/M4A
    value = tags.get("\xa9lyr") if hasattr(tags, "get") else None
    if value:
        return str(value[0]).strip() or None
    # ID3 (mp3): USLT frames are keyed "USLT::lang"
    try:
        for key in list(tags.keys()):
            if str(key).startswith("USLT"):
                text = str(tags[key].text).strip()
                if text:
                    return text
    except Exception:
        pass
    # Vorbis comments (flac, ogg, opus)
    for key in ("lyrics", "unsyncedlyrics", "LYRICS", "UNSYNCEDLYRICS"):
        try:
            found = tags.get(key)
        except Exception:
            found = None
        if found:
            return str(found[0]).strip() or None
    return None


def read_one(path: str) -> dict[str, JsonValue] | None:
    try:
        audio = mutagen.File(path, easy=True)
    except Exception:
        return None
    if audio is None:
        return None
    info = getattr(audio, "info", None)
    tags = audio.tags
    # The container, as the tag library names it ("MP4", "FLAC", "MP3"). ALAC and AAC share MP4;
    # the app tells them apart by bitrate.
    fmt = type(audio).__name__
    if fmt.startswith("Easy"):
        fmt = fmt[4:]
    codec = getattr(info, "codec", None)
    if codec == "alac":
        fmt = "ALAC"
    return {
        "path": path,
        "modified": int(os.stat(path).st_mtime * 1000),
        "format": fmt,
        "title": _first(tags, "title"),
        "artist": _first(tags, "artist"),
        "album": _first(tags, "album"),
        "album_artist": _first(tags, "albumartist"),
        "genre": _first(tags, "genre"),
        "year": _int(_first(tags, "date", "year", "originaldate")),
        "track": _int(_first(tags, "tracknumber")),
        "disc": _int(_first(tags, "discnumber")),
        "bitrate": int(getattr(info, "bitrate", 0) or 0) or None,
        "samplerate": int(getattr(info, "sample_rate", 0) or 0) or None,
        "duration_ms": int((getattr(info, "length", 0) or 0) * 1000) or None,
        "lyrics": _lyrics(path),
    }


def handle_read_tags(payload: Payload, emit: Emit) -> Event:
    """Payload: {"paths": [...]}. Done carries {"tracks": [...]} for the files it could read."""
    if not TAGS_AVAILABLE:
        return error("no_tags", "mutagen is not installed")
    bad = missing_fields(payload, "paths")
    if bad is not None:
        return bad
    paths = payload["paths"]
    if not isinstance(paths, list):
        return error("bad_request", "paths must be a list")
    tracks: list[JsonValue] = []
    total = len(paths)
    for index, path in enumerate(paths, 1):
        record = read_one(str(path))
        if record is not None:
            tracks.append(record)
        if index % 100 == 0 or index == total:
            emit(progress(int(index * 100 / max(total, 1)), f"Read tags {index}/{total}"))
    return done(tracks=tracks)
