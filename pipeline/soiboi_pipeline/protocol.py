"""The JSON events the pipeline answers with.

Every command streams zero or more `progress` events and ends with one `done`
or `error`. The Dart side parses exactly these field names (`event`, `code`,
`message`, `progress`, `status`), so every module builds its events here
rather than writing the dict literals out by hand.
"""
from collections.abc import Callable

JsonValue = (
    str | int | float | bool | None | list["JsonValue"] | dict[str, "JsonValue"]
)
Event = dict[str, JsonValue]
Payload = dict[str, JsonValue]
Emit = Callable[[Event], None]


def progress(percent: int, status: str) -> Event:
    return {"event": "progress", "progress": percent, "status": status}


def track(
    index: int,
    total: int | None,
    title: str,
    state: str,
    detail: str = "",
    owned: bool = False,
) -> Event:
    """Where one track of a playlist or album download is up to.

    state is downloading, done, skipped or failed; owned marks a skip because
    the library already has the song. total is None when gamdl doesn't know
    (playlists). Older apps ignore the event.
    """
    return {
        "event": "track",
        "index": index,
        "total": total,
        "title": title,
        "state": state,
        "detail": detail,
        "owned": owned,
    }


def warning(message: str) -> Event:
    """Something went wrong that the command survived.

    The app ignores event types it does not know, so a warning reaches the log
    without ever turning a finished download into a failed one.
    """
    return {"event": "warning", "message": message}


def error(code: str, message: str) -> Event:
    return {"event": "error", "code": code, "message": message}


def done(**fields: JsonValue) -> Event:
    return {"event": "done", **fields}


def missing_fields(payload: Payload, *keys: str) -> Event | None:
    """A `bad_request` error naming every absent key, or None if none are."""
    missing = [key for key in keys if not payload.get(key)]
    if not missing:
        return None
    return error("bad_request", f"Missing: {', '.join(missing)}")


def ignore(_event: Event) -> None:
    """An emit for callers that want no progress."""
