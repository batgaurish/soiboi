"""One Apple Music API client, from whichever sign-in the app has.

A signed-in wrapper is the stronger credential: it is Apple's own Android
client logged into the account, and its `/me` reports the same
music-user-token and developer token a browser session would carry. So when
the app passes a `wrapper_url`, the client is built from that and no cookies
file is needed. The cookies file stays as the fallback for anyone without the
wrapper.

Personalized endpoints (`/v1/me/*`) need a different developer token than the
wrapper carries. Apple's AMP API checks the dev token against the web
player's audience and rejects the Android client's token with 403 "Invalid
authentication" even though the same token works for catalog and downloads.
The web player's own dev token sits in music.apple.com's JS bundle, so it is
scraped the same way gamdl's `get_token` does it, and it pairs with either
the wrapper's music-user-token or the cookies file's `media-user-token`.
"""

import json
import os
import re
import urllib.request

try:
    from gamdl.api.apple_music import AppleMusicApi
except ImportError:  # the bundled environment is incomplete
    AppleMusicApi = None

HOMEPAGE_URL = "https://music.apple.com"
# Apple's AMP API compares the Origin header against an allowlist; the trailing
# slash of a URL constant read as an origin gets the request a 401. The
# homepage fetch needs no slash either way.
HOMEPAGE_URL_WITH_SLASH = HOMEPAGE_URL + "/"

_scraped_dev_token: str | None = None


def wrapper_tokens(wrapper_url: str | None) -> tuple[str, str] | None:
    """(music_user_token, dev_token) from a signed-in wrapper, else None."""
    if not wrapper_url:
        return None
    try:
        with urllib.request.urlopen(
            wrapper_url.rstrip("/") + "/me", timeout=10
        ) as response:
            auth = json.load(response).get("auth") or {}
    except (OSError, ValueError):
        return None
    user_token = auth.get("music_user_token")
    dev_token = auth.get("dev_token")
    if not user_token or not dev_token:
        return None
    return user_token, dev_token


def music_user_token(
    cookies_path: str | None, wrapper_url: str | None
) -> str | None:
    """The account's Music-User-Token, from the wrapper or the cookies file.

    gamdl reads the cookies file's `media-user-token` cookie and sends it as
    the `Media-User-Token`/`music-user-token` header Apple's personalized
    endpoints want; the wrapper reports the same token as `music_user_token`.
    """
    tokens = wrapper_tokens(wrapper_url)
    if tokens is not None:
        return tokens[0]
    if not cookies_path or not os.path.exists(cookies_path):
        return None
    try:
        from http.cookiejar import MozillaCookieJar

        cookies = MozillaCookieJar(cookies_path)
        cookies.load(ignore_discard=True, ignore_expires=True)
        for cookie in cookies:
            if cookie.name == "media-user-token":
                return cookie.value
    except (OSError, ValueError):
        return None
    return None


def scraped_dev_token() -> str | None:
    """Apple's web player dev token, scraped from music.apple.com's bundle.

    Cached module-wide: the token lives for months and the scrape costs one
    homepage fetch plus one JS fetch. The bundle carries several JWTs; the
    web player's is the one with `kid: WebPlayKid` in its header, and the
    header check is what keeps a catalog token from being taken by mistake.
    Apple changed the header shape before (the token used to start
    `eyJhbGciOiJFUzI1NiIs` and now starts `eyJ0eXBlIjoiSldUI`), so the match
    is any JWT whose decoded header names the web player, not a fixed prefix.

    None when Apple reshapes its bundle past the generic JWT pattern.
    """
    global _scraped_dev_token
    if _scraped_dev_token:
        return _scraped_dev_token
    try:
        with urllib.request.urlopen(HOMEPAGE_URL, timeout=15) as response:
            home_page = response.read().decode("utf-8", "ignore")
        match = re.search(r'/(assets/index[~-][^/"]+\.js)', home_page)
        if not match:
            return None
        with urllib.request.urlopen(
            HOMEPAGE_URL_WITH_SLASH + match.group(1).lstrip("/"), timeout=15
        ) as response:
            index_js = response.read().decode("utf-8", "ignore")
        candidates = re.findall(
            r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}",
            index_js,
        )
        token = _web_player_token(candidates)
        if not token:
            return None
        _scraped_dev_token = token
        return _scraped_dev_token
    except OSError:
        return None


def _web_player_token(candidates: list[str]) -> str | None:
    """The first candidate JWT whose header names the web player's key."""
    import base64

    def padded(part: str) -> str:
        return part + "=" * (-len(part) % 4)

    for candidate in candidates:
        try:
            header = json.loads(
                base64.urlsafe_b64decode(padded(candidate.split(".")[0]))
            )
        except (ValueError, IndexError):
            continue
        if not isinstance(header, dict):
            continue
        if header.get("kid") == "WebPlayKid" or header.get("iss") == "AMPWebPlay":
            return candidate
    return None


def has_credentials(cookies_path: str | None, wrapper_url: str | None) -> bool:
    return wrapper_tokens(wrapper_url) is not None or bool(
        cookies_path and os.path.exists(cookies_path)
    )


async def open_api(
    cookies_path: str | None, wrapper_url: str | None = None
) -> "AppleMusicApi":
    """The client, preferring the wrapper's session over the cookies file.

    Raises FileNotFoundError when neither is available.
    """
    tokens = wrapper_tokens(wrapper_url)
    if tokens is not None:
        user_token, dev_token = tokens
        return await AppleMusicApi.create(token=dev_token, media_user_token=user_token)
    if not cookies_path or not os.path.exists(cookies_path):
        raise FileNotFoundError("No Apple Music sign-in")
    return await AppleMusicApi.create_from_netscape_cookies(cookies_path)


async def open_personalized_api(
    cookies_path: str | None, wrapper_url: str | None = None
) -> "AppleMusicApi | None":
    """A client whose dev token Apple's `/v1/me/*` endpoints accept.

    The wrapper's Android-client dev token is rejected there with 403 even
    though the same token works for catalog and downloads, so this client
    always carries the scraped web player token. The music-user-token still
    comes from the wrapper or the cookies file: the account, not the token
    source, decides whose recommendations come back.

    Returns None (callers degrade to catalog shelves) when there is no sign-in
    or the web token cannot be scraped.
    """
    if AppleMusicApi is None:
        return None
    user_token = music_user_token(cookies_path, wrapper_url)
    dev_token = scraped_dev_token()
    if not user_token or not dev_token:
        return None
    import httpx

    client = httpx.AsyncClient(
        headers={
            "authorization": f"Bearer {dev_token}",
            "origin": HOMEPAGE_URL,
            "cookie": f"media-user-token={user_token}",
        }
    )
    return AppleMusicApi(
        client=client,
        token=dev_token,
        storefront="us",
        language="en-US",
        media_user_token=user_token,
    )