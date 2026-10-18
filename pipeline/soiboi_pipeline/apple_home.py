"""Apple Music home shelves.

The personalized home is `/v1/me/recommendations` plus the account's recently
played history, read through the web-player token client
(`open_personalized_api`): Apple rejects the Android client's dev token on
`/v1/me/*` with 403 even though the same token works for catalog and
downloads. Each recommendation's `href` is expanded with `?types=` into the
playable resources the UI renders, the way the web player's Listen Now does.

Catalog shelves (charts) keep the home useful when personalization is not
available for a region or account. They are the fallback, not the home: when
the personalized calls fail the UI shows charts only, and the shelf titles
say where they came from.
"""
import asyncio
from typing import Any

from .apple_auth import open_api, open_personalized_api
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


def _recommendation_title(resource: dict, fallback: str) -> str:
    """One `personal-recommendation` resource's display title.

    Apple nests the title as {stringForDisplay, stringForAccessibility}; a
    bare string means Apple reshaped the response, and the fallback keeps the
    shelf readable either way.
    """
    title = _attrs(resource).get("title")
    if isinstance(title, dict):
        return title.get("stringForDisplay") or title.get("stringForAccessibility") or fallback
    if isinstance(title, str) and title:
        return title
    return fallback


async def _expand_recommendation(api, resource: dict) -> list[dict]:
    """The playable resources behind one recommendation.

    Apple returns recommendations as shelf definitions with an `href` per
    kind of content; following each with `?types=` resolves them into
    playlists, albums and stations the UI can open. Failures cost the shelf,
    not the home.
    """
    items: list[dict] = []
    relationships = resource.get("relationships") or {}
    for key in ("contents", "recommendations", "items"):
        relation = relationships.get(key) or {}
        items.extend(_items(relation.get("data") or []))
    if items:
        return items
    href = resource.get("href") or (relationships.get("contents") or {}).get("href")
    if not href:
        return []
    for types in ("albums,playlists,stations", "playlists,albums"):
        try:
            expanded = await asyncio.wait_for(
                api._amp_request(f"{href}?types={types}"), timeout=8.0
            )
        except Exception:
            continue
        data = expanded.get("data") or []
        if data:
            return _items(data)
    return []


def _shelves(response: dict, fallback_title: str) -> list[dict]:
    """Shelves from a personalized or catalog response."""
    shelves: list[dict] = []
    for group in response.get("data") or []:
        if not isinstance(group, dict):
            continue
        attrs = _attrs(group)
        title = attrs.get("title") or attrs.get("name") or fallback_title
        if isinstance(title, dict):
            title = title.get("stringForDisplay") or fallback_title
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
        return await asyncio.wait_for(api._amp_request(path, params or {}), timeout=8.0)
    except Exception:
        return None


async def _personalized_shelves(api, storefront: str | None) -> list[dict]:
    """The account's own shelves: recommendations expanded, then recently played."""
    shelves: list[dict] = []

    response = await _request(api, "/v1/me/recommendations", {"limit": 12})
    for resource in (response or {}).get("data") or []:
        if not isinstance(resource, dict):
            continue
        title = _recommendation_title(resource, "Made For You")
        items = await _expand_recommendation(api, resource)
        if items:
            shelves.append({"title": title, "kind": "recommendation", "items": items[:20]})

    for path, title in (
        ("/v1/me/recent/played", "Recently Played"),
        ("/v1/me/history/heavy-rotation", "Heavy Rotation"),
    ):
        response = await _request(api, path, {"limit": 20})
        if response:
            shelves.extend(_shelves(response, title))
    return shelves


async def _collect(
    cookies_path: str, wrapper_url: str | None, storefront_override: str | None = None
) -> tuple[str, list[dict]]:
    personalized = await open_personalized_api(cookies_path, wrapper_url)
    storefront = (
        storefront_override
        or getattr(personalized, "storefront", None)
        or getattr(personalized, "storefront", None)
        or "us"
    )
    shelves: list[dict] = []
    if personalized is not None:
        try:
            shelves = await _personalized_shelves(personalized, storefront)
        except Exception:
            shelves = []
        await personalized.client.aclose()

    # The catalog client (wrapper or cookies token) still answers charts, so
    # the home is never empty; when personalization worked this is context,
    # and when it did not this is the whole page.
    catalog = await open_api(cookies_path, wrapper_url)
    if storefront_override:
        storefront = storefront_override
    else:
        storefront = getattr(catalog, "storefront", None) or storefront

    charts = await _request(catalog, f"/v1/catalog/{storefront}/charts", {"types": "albums,playlists,artists", "limit": 20})
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
