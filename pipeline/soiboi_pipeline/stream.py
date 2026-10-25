"""Resolves a playable audio URL for a YouTube video, so the app can stream it without downloading a file.

yt-dlp already rides along for playlist import; the same module reads the player response here. Done carries
{"url": direct audio URL, "ext": container, "abr": kbit/s or null, "headers": request headers the CDN wants}.
"""
from .protocol import Emit, Event, Payload, done, error, missing_fields

try:
    from yt_dlp import YoutubeDL
except ImportError:  # the bundled environment is incomplete
    YoutubeDL = None


def handle_youtube_stream(payload: Payload, emit: Emit) -> Event:
    """Payload: {"id": video id or search query or url}."""
    if (bad := missing_fields(payload, "id")) is not None:
        return bad
    if YoutubeDL is None:
        return error("no_ytdlp", "yt-dlp is missing from this build")
    options = {"quiet": True, "no_warnings": True, "noplaylist": True, "skip_download": True,
               "format": "bestaudio[ext=m4a]/bestaudio/best", "socket_timeout": 20}
    target = str(payload["id"]).strip()
    if target.startswith("http://") or target.startswith("https://") or target.startswith("ytsearch"):
        target_url = target
    else:
        target_url = f"https://music.youtube.com/watch?v={target}"
    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(target_url, download=False)
    except Exception as exc:  # yt-dlp raises its own error types for every refusal
        return error("youtube", str(exc).splitlines()[-1][:300] if str(exc) else "YouTube refused")
    if isinstance(info, dict) and "entries" in info:
        entries = info.get("entries") or []
        info = entries[0] if entries else None
    url = info.get("url") if isinstance(info, dict) else None
    if not url:
        return error("youtube", "YouTube gave no audio stream for this track")
    return done(url=url, ext=info.get("ext"), abr=info.get("abr"), headers=info.get("http_headers") or {})
