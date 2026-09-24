"""One Apple Music API client, from whichever sign-in the app has.

A signed-in wrapper is the stronger credential: it is Apple's own Android
client logged into the account, and its `/me` reports the same
music-user-token and developer token a browser session would carry. So when
the app passes a `wrapper_url`, the client is built from that and no cookies
file is needed. The cookies file stays as the fallback for anyone without the
wrapper.
"""

import json
import os
import urllib.request

try:
    from gamdl.api.apple_music import AppleMusicApi
except ImportError:  # the bundled environment is incomplete
    AppleMusicApi = None


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
