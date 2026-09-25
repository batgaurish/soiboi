"""Entry point, shared by both transports.

Desktop spawns this as `python -m soiboi_pipeline <command>`; Android calls
`handle_json()` directly through Chaquopy. Both paths run the same handlers, so
a fix on one platform is a fix on both.

Protocol: one JSON object per line on stdout (see `protocol.py`). Every line is
either a progress event or a terminal result, so the Dart side can parse
incrementally and never has to wait for the process to exit before showing
something.
"""
import json
import logging
import sys
from collections.abc import Callable

from . import __version__, acoustic, apple_library, downloader, playlist, runtime
from . import wrapper_libs
from .protocol import Emit, Event, Payload, done, error, ignore

logger = logging.getLogger(__name__)

Handler = Callable[[Payload, Emit], Event]

# Every module loads up front. The capabilities check the app runs at startup
# imports gamdl and yt-dlp regardless, so loading lazily would save a desktop
# subprocess about 0.4s and nothing at all on Android.
_HANDLERS: dict[str, Handler] = {
    "capabilities": runtime.handle_capabilities,
    "disk_usage": runtime.handle_disk_usage,
    "analyze": acoustic.handle_analyze,
    "playlist": playlist.handle_playlist,
    "apple_playlists": apple_library.handle_playlists,
    "apple_playlist_tracks": apple_library.handle_playlist_tracks,
    "apple_storefront": apple_library.handle_storefront,
    "download": downloader.handle_download,
    "cancel": downloader.handle_cancel,
    "wrapper_install": wrapper_libs.handle_install,
}


def _handler_for(command: str) -> Handler | None:
    return _HANDLERS.get(command)


def handle(
    command: str,
    payload: Payload | None = None,
    emit: Emit | None = None,
) -> Event:
    """Run [command] and return its terminal event."""
    if command == "version":
        return done(version=__version__)
    handler = _handler_for(command)
    if handler is None:
        return error("unknown_command", command)
    return handler(payload or {}, emit or ignore)


def _emit_line(event: Event) -> None:
    """One JSON object per line, flushed immediately.

    Without the flush Python buffers stdout when it is not a terminal, and the
    app would see nothing until the download finished.
    """
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv:
        _emit_line(error("bad_request", "No command"))
        return 2

    command, *rest = argv
    try:
        payload = json.loads(rest[0]) if rest else {}
    except json.JSONDecodeError as exc:
        _emit_line(error("bad_json", str(exc)))
        return 2

    result = handle(command, payload, emit=_emit_line)
    _emit_line(result)
    return 1 if result.get("event") == "error" else 0


def handle_json(
    command: str,
    payload_json: str | None,
    emit_callable: Callable[[str], None] | None = None,
) -> str:
    """JSON-in, JSON-out entry point for the Android bridge.

    Chaquopy marshals Java strings cleanly but not nested dicts, so both sides
    exchange JSON text. [emit_callable] is a Java object whose __call__ takes a
    JSON string; progress goes through it as it happens rather than piling up
    until the download finishes.
    """
    try:
        payload = json.loads(payload_json) if payload_json else {}
    except (TypeError, json.JSONDecodeError) as exc:
        return json.dumps(error("bad_json", str(exc)))

    def emit(event: Event) -> None:
        if emit_callable is None:
            return
        try:
            emit_callable(json.dumps(event))
        except Exception:
            # The app failing to take one progress event must never abort
            # the download that sent it.
            logger.warning("Dropped a progress event", exc_info=True)

    try:
        result = handle(command, payload, emit=emit)
    except Exception as exc:
        # Nothing may cross the JNI boundary as an exception.
        logger.exception("Command %s failed", command)
        result = error("pipeline", str(exc))
    return json.dumps(result)


if __name__ == "__main__":
    sys.exit(main())
