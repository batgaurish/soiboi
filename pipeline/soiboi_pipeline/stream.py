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
    """Payload: {"id": video id}."""
    if (bad := missing_fields(payload, "id")) is not None:
        return bad
    if YoutubeDL is None:
        return error("no_ytdlp", "yt-dlp is missing from this build")
    options = {"quiet": True, "no_warnings": True, "noplaylist": True, "skip_download": True,
               "format": "bestaudio[ext=m4a]/bestaudio/best", "socket_timeout": 20}
    try:
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(f"https://music.youtube.com/watch?v={payload['id']}", download=False)
    except Exception as exc:  # yt-dlp raises its own error types for every refusal
        return error("youtube", str(exc).splitlines()[-1][:300] if str(exc) else "YouTube refused")
    url = info.get("url") if isinstance(info, dict) else None
    if not url:
        return error("youtube", "YouTube gave no audio stream for this track")
    return done(url=url, ext=info.get("ext"), abr=info.get("abr"), headers=info.get("http_headers") or {})
