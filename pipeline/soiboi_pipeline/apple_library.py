"""The signed-in account's own Apple Music playlists.

Importing a playlist used to mean pasting its URL, one at a time, which is
absurd for an app that is already authenticated as that account: the same
cookies the downloader uses are exactly what Apple's personalised endpoints
want, so the list can simply be asked for.

gamdl already carries the client (`AppleMusicApi.create_from_netscape_cookies`
plus `get_library_playlists`), so this is a thin adapter: fetch, flatten the
JSON:API shape into something the Dart side can render, and say for each
playlist whether it is archivable as one URL.

Two kinds of playlist come back, and the difference decides how each is
archived:

  * **Catalog-backed** — an Apple-published playlist the user added, or one of
    their own that Apple has mirrored into the catalog. `playParams.globalId`
    holds a `pl.*` id, which is a normal catalog URL the downloader takes
    whole.
  * **Library-only** — made by the user and never published. There is no
    catalog id and no URL to hand over, so the tracks are resolved
    individually and archived one by one.

Nothing here downloads anything; it only reports what exists.
"""

import asyncio
import logging

try:
    import structlog
except Exception:  # pragma: no cover - structlog ships with gamdl
    structlog = None


def _quiet_gamdl_logging():
    """gamdl logs every request at debug through structlog.

    Left alone it writes the account payload — tokens included — into the same
    stream this module answers on, which both corrupts the JSON the app parses
    and puts credentials somewhere they have no reason to be.
    """
    if structlog is None:
        return
    structlog.configure(
        wrapper_class=structlog.make_filtering_bound_logger(logging.CRITICAL)
    )


def _playlist_entry(item):
    """One library playlist, flattened.

    Returns None for anything that is not a playlist, rather than raising:
    Apple has added fields to this response before, and an unreadable entry
    should cost that one row, not the whole list.
    """
    try:
        attributes = item.get("attributes") or {}
        play_params = attributes.get("playParams") or {}
        artwork = (attributes.get("artwork") or {}).get("url")
        if artwork:
            # Apple templates the size into the URL; ask for something a list
            # row can actually use rather than the 1200px original.
            artwork = artwork.replace("{w}", "300").replace("{h}", "300")
            artwork = artwork.replace("{c}", "bb").replace("{f}", "jpg")
        return {
            "library_id": item.get("id"),
            "name": attributes.get("name") or "Untitled playlist",
            "catalog_id": play_params.get("globalId"),
            "track_count": attributes.get("trackCount"),
            "artwork_url": artwork,
            "can_edit": bool(attributes.get("canEdit")),
            "description": (attributes.get("description") or {}).get("standard"),
        }
    except Exception:
        return None


async def _collect(cookies_path, storefront_out):
    from gamdl.api.apple_music import AppleMusicApi

    api = await AppleMusicApi.create_from_netscape_cookies(cookies_path)
    if not api.active_subscription:
        raise PermissionError("This Apple Music account has no active subscription.")
    storefront_out["storefront"] = getattr(api, "storefront", None) or "us"

    response = await api.get_library_playlists()
    return response.get("data") or []


def list_playlists(cookies_path, emit=None):
    """Every playlist in the signed-in account's library."""
    _quiet_gamdl_logging()

    if emit:
        emit({"event": "progress", "progress": 10, "status": "Reading your library"})

    storefront_out = {}
    try:
        raw = asyncio.run(_collect(cookies_path, storefront_out))
    except PermissionError as exc:
        return {"event": "error", "code": "no_subscription", "message": str(exc)}
    except FileNotFoundError:
        return {
            "event": "error",
            "code": "no_cookies",
            "message": "Sign in to Apple Music first.",
        }
    except Exception as exc:
        # A stale media-user-token is the common case and reads as an auth
        # failure rather than a crash, so the app can say "sign in again".
        message = str(exc)
        code = "auth" if "401" in message or "403" in message else "failed"
        return {"event": "error", "code": code, "message": message}

    playlists = [entry for entry in (_playlist_entry(i) for i in raw) if entry]
    playlists.sort(key=lambda p: (p["name"] or "").lower())

    if emit:
        emit({"event": "progress", "progress": 100, "status": "Done"})

    return {
        "event": "done",
        "playlists": playlists,
        "storefront": storefront_out.get("storefront", "us"),
        "total": len(playlists),
    }


async def _collect_tracks(cookies_path, library_id):
    from gamdl.api.apple_music import AppleMusicApi

    api = await AppleMusicApi.create_from_netscape_cookies(cookies_path)
    response = await api.get_library_playlist(library_id)
    data = response.get("data") or []
    if not data:
        return []
    relationships = data[0].get("relationships") or {}
    return (relationships.get("tracks") or {}).get("data") or []


def _track_entry(item):
    attributes = item.get("attributes") or {}
    play_params = attributes.get("playParams") or {}
    return {
        "title": attributes.get("name"),
        "artist": attributes.get("artistName"),
        "album": attributes.get("albumName"),
        # catalog_id is what makes a track archivable on its own; a track only
        # ever uploaded by the user has none and cannot be fetched.
        "catalog_id": play_params.get("catalogId") or play_params.get("id"),
        "is_library_only": not play_params.get("catalogId"),
    }


def list_tracks(cookies_path, library_id, emit=None):
    """Tracks of one library playlist, for the ones with no catalog URL."""
    _quiet_gamdl_logging()

    if emit:
        emit({"event": "progress", "progress": 10, "status": "Reading playlist"})

    try:
        raw = asyncio.run(_collect_tracks(cookies_path, library_id))
    except Exception as exc:
        return {"event": "error", "code": "failed", "message": str(exc)}

    tracks = [_track_entry(i) for i in raw]
    if emit:
        emit({"event": "progress", "progress": 100, "status": "Done"})
    return {"event": "done", "tracks": tracks, "total": len(tracks)}
