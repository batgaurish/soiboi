"""Extracts embedded cover art for the app's artwork cache.

Like `tags.py`, one mutagen-based implementation serves both platforms. The app asks for a file's cover and
gets back the path of an image written under the cache folder, named by the audio file's hash so a replaced
file at the same path is read again when its modified time changes.
"""
import hashlib
import os

from .protocol import Emit, Event, Payload, done, error, missing_fields

try:
    import mutagen
    from mutagen.flac import FLAC
    from mutagen.id3 import ID3
    from mutagen.mp4 import MP4
    ART_AVAILABLE = True
except ImportError:  # pragma: no cover - mutagen ships with gamdl
    ART_AVAILABLE = False


def _extract(path: str) -> tuple[bytes, str] | None:
    """The first embedded picture and its file extension, or None."""
    lower = path.lower()
    try:
        if lower.endswith((".m4a", ".mp4", ".m4b", ".m4r")):
            covers = (MP4(path).tags or {}).get("covr")
            if covers:
                cover = covers[0]
                return bytes(cover), ".png" if cover.imageformat == 14 else ".jpg"
        elif lower.endswith(".flac"):
            pictures = FLAC(path).pictures
            if pictures:
                return pictures[0].data, ".png" if pictures[0].mime.endswith("png") else ".jpg"
        elif lower.endswith(".mp3"):
            for frame in (ID3(path) or {}).getall("APIC"):
                return frame.data, ".png" if frame.mime.endswith("png") else ".jpg"
        else:
            audio = mutagen.File(path)
            pictures = getattr(audio, "pictures", None)
            if pictures:
                return pictures[0].data, ".jpg"
    except Exception:
        return None
    return None


def handle_cover(payload: Payload, emit: Emit) -> Event:
    """Payload: {"path": audio file, "out_dir": cache folder}. Done carries {"cover": path or null}."""
    if not ART_AVAILABLE:
        return error("no_tags", "mutagen is not installed")
    bad = missing_fields(payload, "path", "out_dir")
    if bad is not None:
        return bad
    path, out_dir = str(payload["path"]), str(payload["out_dir"])
    try:
        stamp = f"{path}|{int(os.stat(path).st_mtime)}"
    except OSError:
        return done(cover=None)
    digest = hashlib.md5(stamp.encode()).hexdigest()
    os.makedirs(out_dir, exist_ok=True)
    for ext in (".jpg", ".png"):
        existing = os.path.join(out_dir, digest + ext)
        if os.path.exists(existing):
            return done(cover=existing)
    found = _extract(path)
    if found is None:
        # Remember there is none, so a coverless file is not reopened on every scroll.
        open(os.path.join(out_dir, digest + ".none"), "wb").close()
        return done(cover=None)
    data, ext = found
    target = os.path.join(out_dir, digest + ext)
    with open(target, "wb") as handle:
        handle.write(data)
    return done(cover=target)
