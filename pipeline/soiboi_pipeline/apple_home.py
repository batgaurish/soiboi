"""Apple Music home shelves.

The authenticated AMP API exposes personalized recommendation collections for a
signed-in account. Some accounts/regions return fewer collections, so this
handler also fills the feed from storefront catalog search and charts instead
of turning a partial Apple response into an empty home.
"""
import asyncio
from typing import Any

from .apple_auth import open_api
from .apple_library import _artwork_url
from .protocol import Emit, Event, Payload, done, error, progress


def _attrs(item: dict) -> dict:
    return item.get("attributes") or {}


def _item(item: dict) -> dict | None:
    if not isinstance(item, dict):
        return None
    attrs = _attrs(item)
    item_id = item.get("id") or (attrs.get("playParams") or {}).get("catalogId") or (attrs.get("playParams") or {}).get("globalId")
    title = attrs.get("name") or attrs.get("title")
    if not item_id or not title:
        return None
    artist = attrs.get("artistName") or attrs.get("curatorName") or attrs.get("editorialNotes", {}).get("name")
    artwork = _artwork_url(attrs)
    return {
        "id": item_id,
        "type": item.get("type", "unknown"),
        "title": title,
        "subtitle": artist or "Apple Music",
        "artwork_url": artwork,
    }


def _items(value: Any) -> list[dict]:
    if not isinstance(value, list):
        return []
    return [x for x in (_item(i) for i in value) if x]


def _shelves(response: dict, fallback_title: str) -> list[dict]:
    shelves: list[dict] = []
    for group in response.get("data") or []:
        if not isinstance(group, dict):
            continue
        attrs = _attrs(group)
        title = attrs.get("title") or attrs.get("name") or fallback_title
        relationships = group.get("relationships") or {}
        raw = []
        for key in ("contents", "recommendations", "items", "tracks", "playlists", "albums", "artists"):
            rel = relationships.get(key) or {}
            raw.extend(rel.get("data") or [])
        items = _items(raw)
        if items:
            shelves.append({"title": title, "kind": "recommendation", "items": items[:20]})
    direct = _items(response.get("data") or [])
    if direct and not shelves:
        shelves.append({"title": fallback_title, "kind": "catalog", "items": direct[:20]})
    return shelves


async def _request(api, path: str, params: dict | None = None) -> dict | None:
    try:
        return await api._amp_request(path, params or {})
    except Exception:
        return None


async def _collect(cookies_path: str, wrapper_url: str | None, storefront_override: str | None = None) -> tuple[str, list[dict]]:
    api = await open_api(cookies_path, wrapper_url)
    storefront = storefront_override or getattr(api, "storefront", None) or "us"
    shelves: list[dict] = []

    # Personalized collections. These are undocumented AMP surfaces and vary
    # by account; failures are intentionally non-fatal.
    for path, title in (
        ("/v1/me/recommendations", "Made For You"),
        ("/v1/me/history/heavy-rotation", "Daily Mixes"),
        ("/v1/me/recent/played", "Recently Played"),
    ):
        response = await _request(api, path, {"limit": 20})
        if response:
            shelves.extend(_shelves(response, title))

    # Public catalog shelves keep the home useful when personalization is not
    # available for a region or account.
    for term, title in (
        ("new music", "New Releases"),
        ("mood", "Mood Mixes"),
        ("bollywood", "Discover: Bollywood"),
        ("punjabi", "Discover: Punjabi"),
    ):
        response = await _request(api, f"/v1/catalog/{storefront}/search", {"term": term, "types": "playlists,albums,artists", "limit": 20})
        if response:
            shelves.extend(_shelves(response, title))

    charts = await _request(api, f"/v1/catalog/{storefront}/charts", {"types": "albums,playlists,artists", "limit": 20})
    if charts:
        shelves.extend(_shelves(charts, "Popular on Apple Music"))

    # Keep one shelf per title and cap the response so a broken endpoint cannot
    # flood the UI with duplicate cards.
    unique: list[dict] = []
    seen: set[str] = set()
    for shelf in shelves:
        key = str(shelf.get("title", "")).lower()
        if not key or key in seen:
            continue
        seen.add(key)
        unique.append(shelf)
    return storefront, unique[:12]


def home(payload: Payload, emit: Emit) -> Event:
    cookies_path_value = payload.get("cookies_path")
    if not isinstance(cookies_path_value, str) or not cookies_path_value:
        return error("no_cookies", "Sign in to Apple Music first.")
    wrapper_value = payload.get("wrapper_url")
    wrapper_url = wrapper_value if isinstance(wrapper_value, str) else None
    storefront_value = payload.get("storefront")
    storefront_override = storefront_value if isinstance(storefront_value, str) else None
    try:
        emit(progress(10, "Reading Apple Music recommendations"))
        storefront, shelves = asyncio.run(_collect(cookies_path_value, wrapper_url, storefront_override))
        emit(progress(100, "Apple Music home ready"))
        return done(storefront=storefront, shelves=shelves)
    except FileNotFoundError:
        return error("no_cookies", "Sign in to Apple Music first.")
    except Exception as exc:
        return error("failed", str(exc))


def handle_home(payload: Payload, emit: Emit) -> Event:
    return home(payload, emit)
