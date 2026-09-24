"""Word-by-word lyrics from Apple Music, saved as enhanced LRC.

gamdl embeds Apple's line-timed lyrics. Apple also serves word-timed
("syllable") lyrics from a separate endpoint gamdl never calls, and the app's
lyric view already highlights word by word when a line carries per-word
timestamps. So after a download this fetches the syllable TTML for each new
track and writes it next to the file as enhanced LRC:

    [00:12.34]<00:12.34>Hello <00:12.80>world

The track's Apple id comes from the `cnID` tag gamdl writes. Tracks Apple has
no word timing for are left with the embedded lyrics.
"""
import asyncio
import logging
import os
import xml.etree.ElementTree as ET

from .protocol import Emit, progress, warning

try:
    import structlog
    from gamdl.api.apple_music import AppleMusicApi
    from mutagen.mp4 import MP4
except ImportError:  # the bundled environment is incomplete
    structlog = AppleMusicApi = MP4 = None

logger = logging.getLogger(__name__)

_TT = "{http://www.w3.org/ns/ttml}"


def _seconds(clock: str) -> float:
    """TTML clock values: '12.3', '1:02.345' or '00:01:02.345'."""
    parts = [float(p) for p in clock.strip().rstrip("s").split(":")]
    total = 0.0
    for part in parts:
        total = total * 60 + part
    return total


def _stamp(seconds: float) -> str:
    minutes, rest = divmod(seconds, 60)
    return f"{int(minutes):02d}:{rest:05.2f}"


def ttml_to_enhanced_lrc(ttml: str) -> tuple[str, bool]:
    """(LRC text, whether any line has word timing)."""
    root = ET.fromstring(ttml)
    lines: list[str] = []
    word_timed = False
    for para in root.iter(f"{_TT}p"):
        begin = para.get("begin")
        if begin is None:
            continue
        spans = [s for s in para.iter(f"{_TT}span") if s.get("begin")]
        if spans:
            word_timed = True
            words = []
            for span in spans:
                text = "".join(span.itertext())
                # Apple marks word boundaries with the whitespace after a span.
                gap = " " if (span.tail or "").startswith(" ") else ""
                words.append(f"<{_stamp(_seconds(span.get('begin')))}>{text}{gap}")
            lines.append(f"[{_stamp(_seconds(begin))}]" + "".join(words).rstrip())
        else:
            text = " ".join("".join(para.itertext()).split())
            lines.append(f"[{_stamp(_seconds(begin))}]{text}")
    return "\n".join(lines) + "\n", word_timed


def lrc_path(audio_path: str) -> str:
    return os.path.splitext(audio_path)[0] + ".lrc"


def _needs_lyrics(path: str) -> bool:
    """An .m4a without word-timed lyrics beside it.

    gamdl writes its own line-timed .lrc during the download, so an existing
    file only counts once it carries per-word <mm:ss.xx> tags.
    """
    if not path.lower().endswith(".m4a"):
        return False
    try:
        with open(lrc_path(path), encoding="utf-8") as f:
            return "<" not in f.read()
    except OSError:
        return True


def _catalog_id(path: str) -> str | None:
    try:
        ids = (MP4(path).tags or {}).get("cnID")
    except Exception:
        # mutagen raises its own error types for any unreadable file.
        return None
    return str(ids[0]) if ids else None


async def _fetch_all(cookies_path: str, tracks: list[tuple[str, str]], emit: Emit) -> int:
    api = await AppleMusicApi.create_from_netscape_cookies(cookies_path)
    written = 0
    for index, (path, song_id) in enumerate(tracks):
        emit(progress(96, f"Fetching word-timed lyrics ({index + 1}/{len(tracks)})"))
        try:
            response = await api._amp_request(
                f"/v1/catalog/{api.storefront}/songs/{song_id}/syllable-lyrics"
            )
            ttml = response["data"][0]["attributes"]["ttml"]
            lrc, word_timed = ttml_to_enhanced_lrc(ttml)
        except Exception:
            # No syllable lyrics for this song, or Apple refused: keep the
            # embedded line-timed lyrics.
            logger.info("No word-timed lyrics for %s", song_id, exc_info=True)
            continue
        if not word_timed:
            continue
        with open(lrc_path(path) + ".tmp", "w", encoding="utf-8") as f:
            f.write(lrc)
        os.replace(lrc_path(path) + ".tmp", lrc_path(path))
        written += 1
    return written


def fetch_for_directory(
    cookies_path: str, directory: str, emit: Emit, since: float = 0.0
) -> int:
    """Write word-timed lyrics for tracks written since [since] that have none.

    [since] keeps this to the download that just ran: the output folder can be
    the user's whole music library.

    Returns how many were written. Never raises: lyrics are a bonus on a
    download that has already succeeded.
    """
    if AppleMusicApi is None or not os.path.exists(cookies_path):
        return 0
    tracks = []
    for root, _dirs, files in os.walk(directory):
        for name in files:
            path = os.path.join(root, name)
            if os.path.getmtime(path) < since or not _needs_lyrics(path):
                continue
            if song_id := _catalog_id(path):
                tracks.append((path, song_id))
    if not tracks:
        return 0
    structlog.configure(
        wrapper_class=structlog.make_filtering_bound_logger(logging.CRITICAL)
    )
    try:
        return asyncio.run(_fetch_all(cookies_path, tracks, emit))
    except Exception as exc:
        # A dead session or no network: the download itself still stands.
        emit(warning(f"Word-timed lyrics skipped: {exc}"))
        return 0
