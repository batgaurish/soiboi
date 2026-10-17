"""Multi-service download resolution.

Resolves tracks from any session (Spotify, YouTube Music, SoundCloud) to a
downloadable source. Priority:

1. If user has Apple Music session and track has ISRC: resolve via Apple
   catalog and download lossless via gamdl (best quality)
2. Fall back to yt-dlp search on YouTube (matches by artist + title)
3. Return error if no source found

This enables friends using Spotify to get downloads through Apple Music
quality, or via YouTube if Apple isn't available.
"""

import asyncio
import logging

from .apple_auth import open_api, has_credentials
from .downloader import handle_download
from .protocol import Emit, Event, Payload, done, error, ignore, missing_fields, progress

logger = logging.getLogger(__name__)

try:
    from gamdl.api.apple_music import AppleMusicApi
    HAS_GAMDL = True
except ImportError:
    HAS_GAMDL = False


async def _resolve_by_isrc(
    isrc: str,
    storefront: str,
    cookies_path: str | None,
    wrapper_url: str | None,
) -> str | None:
    """Find an Apple Music URL for a track by ISRC."""
    if not HAS_GAMDL:
        return None
    if not has_credentials(cookies_path, wrapper_url):
        return None

    try:
        api = await open_api(cookies_path, wrapper_url)
        results = await api.get_search_results(
            term=isrc,
            types=["songs"],
            limit=1,
        )
        songs = results.get("results", {}).get("songs", {}).get("data", [])
        if songs:
            attrs = songs[0].get("attributes", {})
            return attrs.get("url")
    except Exception as e:
        logger.warning("ISRC resolution failed: %s", e)
    return None


async def _resolve_by_search(
    artist: str,
    title: str,
    storefront: str,
    cookies_path: str | None,
    wrapper_url: str | None,
) -> str | None:
    """Find an Apple Music URL by artist + title search."""
    if not HAS_GAMDL:
        return None
    if not has_credentials(cookies_path, wrapper_url):
        return None

    query = f"{artist} {title}".strip()
    if not query:
        return None

    try:
        api = await open_api(cookies_path, wrapper_url)
        results = await api.get_search_results(
            term=query,
            types=["songs"],
            limit=5,
        )
        songs = results.get("results", {}).get("songs", {}).get("data", [])

        # Find best match (simple: first result with matching artist)
        artist_lower = artist.lower() if artist else ""
        title_lower = title.lower() if title else ""

        for song in songs:
            attrs = song.get("attributes", {})
            song_artist = (attrs.get("artistName") or "").lower()
            song_title = (attrs.get("name") or "").lower()

            if artist_lower in song_artist or song_artist in artist_lower:
                if title_lower in song_title or song_title in title_lower:
                    return attrs.get("url")

        # Fall back to first result
        if songs:
            return songs[0].get("attributes", {}).get("url")

    except Exception as e:
        logger.warning("Apple search failed: %s", e)
    return None


def handle_session_download(payload: Payload, emit: Emit) -> Event:
    """Pipeline handler for session_download command.

    Resolves a track from any session to a downloadable source and initiates
    the download.

    payload:
        session: str - Source session ("spotify", "ytmusic", "soundcloud")
        track_id: str - Track ID on the source service
        artist: str? - Artist name (for search resolution)
        title: str? - Track title (for search resolution)
        isrc: str? - ISRC for cross-service matching
        prefer_apple: bool - Try Apple Music first (default: true)
        apple_cookies_path: str? - Apple cookies for ISRC resolution
        apple_wrapper_url: str? - Apple wrapper URL
        apple_storefront: str - Storefront code (default: "us")
        output_dir: str - Download output directory

    Returns: Same as handle_download
    """
    problem = missing_fields(payload, "session", "track_id")
    if problem:
        return problem

    session = str(payload["session"])
    track_id = str(payload["track_id"])
    artist = payload.get("artist")
    title = payload.get("title")
    isrc = payload.get("isrc")
    prefer_apple = payload.get("prefer_apple", True)
    apple_cookies = payload.get("apple_cookies_path")
    apple_wrapper = payload.get("apple_wrapper_url")
    storefront = str(payload.get("apple_storefront", "us"))
    output_dir = payload.get("output_dir")

    if artist:
        artist = str(artist)
    if title:
        title = str(title)
    if isrc:
        isrc = str(isrc)
    if apple_cookies:
        apple_cookies = str(apple_cookies)
    if apple_wrapper:
        apple_wrapper = str(apple_wrapper)

    emit(progress(5, "Resolving source"))

    apple_url = None

    # Try Apple Music resolution
    if prefer_apple:
        async def try_apple():
            nonlocal apple_url
            # Try ISRC first (most accurate)
            if isrc:
                apple_url = await _resolve_by_isrc(isrc, storefront, apple_cookies, apple_wrapper)
                if apple_url:
                    return

            # Try search
            if artist or title:
                apple_url = await _resolve_by_search(
                    artist or "",
                    title or "",
                    storefront,
                    apple_cookies,
                    apple_wrapper,
                )

        asyncio.run(try_apple())

    if apple_url:
        emit(progress(10, "Found on Apple Music"))
        logger.info("Resolved %s to Apple Music: %s", track_id, apple_url)
        download_payload = {
            "url": apple_url,
            **({"output_dir": output_dir} if output_dir else {}),
            **({"cookies_path": apple_cookies} if apple_cookies else {}),
            **({"wrapper_url": apple_wrapper} if apple_wrapper else {}),
        }
        return handle_download(download_payload, emit)

    # Fall back to yt-dlp
    search_query = f"{artist or ''} {title or ''}".strip()
    if not search_query:
        search_query = track_id

    emit(progress(10, "Searching YouTube"))
    logger.info("Falling back to YouTube search: %s", search_query)

    download_payload = {
        "url": f"ytsearch1:{search_query}",
        "source": session,
        **({"output_dir": output_dir} if output_dir else {}),
    }
    return handle_download(download_payload, emit)
