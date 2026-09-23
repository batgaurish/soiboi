"""The signed-in account's own Apple Music playlists.

The downloader's cookies are exactly what Apple's personalised endpoints want,
so the app lists the account's playlists instead of making the user paste
links one at a time.

gamdl already carries the client (`AppleMusicApi.create_from_netscape_cookies`
plus `get_library_playlists`), so this is a thin adapter: fetch, flatten the
JSON:API shape into something the Dart side can render, and say for each
playlist whether it is archivable as one URL.

Two kinds of playlist come back, and the difference decides how each is
archived:

  * **Catalog-backed**: an Apple-published playlist the user added, or one of
    their own that Apple has mirrored into the catalog. `playParams.globalId`
    holds a `pl.*` id, which is a normal catalog URL the downloader takes
    whole.
  * **Library-only**: made by the user and never published. There is no
    catalog id and no URL to hand over, so the tracks are resolved
    individually and archived one by one.

Nothing here downloads anything; it only reports what exists.
"""

import asyncio
import logging

from .protocol import Emit, Event, JsonValue, Payload, done, error, ignore
from .protocol import missing_fields, progress

try:
    import structlog
    from gamdl.api.apple_music import AppleMusicApi
except ImportError:  # the bundled environment is incomplete
    structlog = None
    AppleMusicApi = None

DEFAULT_STOREFRONT = "us"

Row = dict[str, JsonValue]


def _quiet_gamdl_logging() -> None:
    """gamdl logs every request at debug through structlog.

    Left alone it writes the account payload, tokens included, into the same
    stream this module answers on. That corrupts the JSON the app parses and
    puts credentials somewhere they have no reason to be.
    """
    if structlog is None:
        return
    structlog.configure(
        wrapper_class=structlog.make_filtering_bound_logger(logging.CRITICAL)
    )


def _artwork_url(attributes: dict) -> str | None:
    url = (attributes.get("artwork") or {}).get("url")
    if not url:
        return None
    # Apple templates the size into the URL. A list row needs 300px, not the
    # 1200px original.
    for placeholder, value in (("{w}", "300"), ("{h}", "300"), ("{c}", "bb"), ("{f}", "jpg")):
        url = url.replace(placeholder, value)
    return url


def _playlist_entry(item: object) -> Row | None:
    """One library playlist, flattened.

    Returns None for an entry it cannot read rather than raising: Apple has
    reshaped this response before, and one unreadable entry should cost that
    row, not the whole list.
    """
    if not isinstance(item, dict):
        return None
    try:
        attributes = item.get("attributes") or {}
        play_params = attributes.get("playParams") or {}
        description = attributes.get("description") or {}
        return {
            "library_id": item.get("id"),
            "name": attributes.get("name") or "Untitled playlist",
            "catalog_id": play_params.get("globalId"),
            "track_count": attributes.get("trackCount"),
            "artwork_url": _artwork_url(attributes),
            "can_edit": bool(attributes.get("canEdit")),
            "description": description.get("standard"),
        }
    except AttributeError:
        # A field that should be an object arrived as something else.
        return None


def _track_entry(item: dict) -> Row:
    attributes = item.get("attributes") or {}
    play_params = attributes.get("playParams") or {}
    return {
        "title": attributes.get("name"),
        "artist": attributes.get("artistName"),
        "album": attributes.get("albumName"),
        # catalog_id makes a track archivable on its own; a track the user
        # uploaded has none and cannot be fetched.
        "catalog_id": play_params.get("catalogId") or play_params.get("id"),
        "is_library_only": not play_params.get("catalogId"),
    }


async def _collect(cookies_path: str) -> tuple[str, list]:
    """The account's storefront and its raw library playlists."""
    api = await AppleMusicApi.create_from_netscape_cookies(cookies_path)
    if not api.active_subscription:
        raise PermissionError("This Apple Music account has no active subscription.")
    storefront = getattr(api, "storefront", None) or DEFAULT_STOREFRONT
    response = await api.get_library_playlists()
    return storefront, response.get("data") or []


async def _collect_tracks(cookies_path: str, library_id: str) -> list:
    api = await AppleMusicApi.create_from_netscape_cookies(cookies_path)
    response = await api.get_library_playlist(library_id)
    data = response.get("data") or []
    if not data:
        return []
    relationships = data[0].get("relationships") or {}
    return (relationships.get("tracks") or {}).get("data") or []


def _unavailable() -> Event:
    return error("no_gamdl", "Apple Music client unavailable: gamdl is missing")


def list_playlists(cookies_path: str, emit: Emit = ignore) -> Event:
    """Every playlist in the signed-in account's library."""
    if AppleMusicApi is None:
        return _unavailable()
    _quiet_gamdl_logging()
    emit(progress(10, "Reading your library"))

    try:
        storefront, raw = asyncio.run(_collect(cookies_path))
    except PermissionError as exc:
        return error("no_subscription", str(exc))
    except FileNotFoundError:
        return error("no_cookies", "Sign in to Apple Music first.")
    except Exception as exc:
        # gamdl surfaces HTTP failures as assorted exception types. A stale
        # media-user-token is the common case; reporting it as "auth" lets
        # the app say "sign in again" instead of showing a crash.
        message = str(exc)
        code = "auth" if "401" in message or "403" in message else "failed"
        return error(code, message)

    playlists = [entry for entry in map(_playlist_entry, raw) if entry]
    playlists.sort(key=lambda playlist: (playlist["name"] or "").lower())

    emit(progress(100, "Done"))
    return done(playlists=playlists, storefront=storefront, total=len(playlists))


def list_tracks(cookies_path: str, library_id: str, emit: Emit = ignore) -> Event:
    """Tracks of one library playlist, for the ones with no catalog URL."""
    if AppleMusicApi is None:
        return _unavailable()
    _quiet_gamdl_logging()
    emit(progress(10, "Reading playlist"))

    try:
        raw = asyncio.run(_collect_tracks(cookies_path, library_id))
    except Exception as exc:
        # Same spread of gamdl HTTP errors as above; the message is the
        # useful part.
        return error("failed", str(exc))

    tracks = [_track_entry(item) for item in raw if isinstance(item, dict)]
    emit(progress(100, "Done"))
    return done(tracks=tracks, total=len(tracks))


def handle_playlists(payload: Payload, emit: Emit) -> Event:
    problem = missing_fields(payload, "cookies_path")
    if problem:
        return problem
    return list_playlists(payload["cookies_path"], emit=emit)


def handle_playlist_tracks(payload: Payload, emit: Emit) -> Event:
    problem = missing_fields(payload, "cookies_path", "library_id")
    if problem:
        return problem
    return list_tracks(payload["cookies_path"], payload["library_id"], emit=emit)
