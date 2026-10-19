"""Apple Music streaming via Widevine decryption.

Apple's Web Playback API returns multiple quality tiers ("flavors"). The
`28:ctrp256` flavor is Widevine CENC (~256 kbps AAC), which pywidevine can
decrypt without FairPlay. This module resolves a track ID to a playable
decrypted file.

Requires:
  - pywidevine (already in the pipeline venv)
  - Valid Apple Music credentials (cookies or wrapper)

The decrypted file is cached; subsequent requests for the same track return
the cached path without re-downloading.
"""

import asyncio
import hashlib
import logging
from base64 import b64decode, b64encode
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .apple_auth import open_api
from .protocol import Emit, Event, Payload, done, error, missing_fields, progress

logger = logging.getLogger(__name__)

# Bundled L3 CDM device (Android SDK emulator, same as gamdl uses)
BUNDLED_WVD = """V1ZEAgIDAASoMIIEpAIBAAKCAQEAwnCFAPXy4U1J7p1NohAS+xl040f5FBaE/59bPp301bGz0UGFT9VoEtY3vaeakKh/d319xTNvCSWsEDRaMmp/wSnMiEZUkkl04872jx2uHuR4k6KYuuJoqhsIo1TwUBueFZynHBUJzXQeW8Eb1tYAROGwp8W7r+b0RIjHC89RFnfVXpYlF5I6McktyzJNSOwlQbMqlVihfSUkv3WRd3HFmA0Oxay51CEIkoTlNTHVlzVyhov5eHCDSp7QENRgaaQ03jC/CcgFOoQymhsBtRCM0CQmfuAHjA9e77R6m/GJPy75G9fqoZM1RMzVDHKbKZPd3sFd0c0+77gLzW8cWEaaHwIDAQABAoIBAQCB2pN46MikHvHZIcTPDt0eRQoDH/YArGl2Lf7J+sOgU2U7wv49KtCug9IGHwDiyyUVsAFmycrF2RroV45FTUq0vi2SdSXV7Kjb20Ren/vBNeQw9M37QWmU8Sj7q6YyWb9hv5T69DHvvDTqIjVtbM4RMojAAxYti5hmjNIh2PrWfVYWhXxCQ/WqAjWLtZBM6Oww1byfr5I/wFogAKkgHi8wYXZ4LnIC8V7jLAhujlToOvMMC9qwcBiPKDP2FO+CPSXaqVhH+LPSEgLggnU3EirihgxovbLNAuDEeEbRTyR70B0lW19tLHixso4ZQa7KxlVUwOmrHSZf7nVuWqPpxd+BAoGBAPQLyJ1IeRavmaU8XXxfMdYDoc8+xB7v2WaxkGXb6ToX1IWPkbMz4yyVGdB5PciIP3rLZ6s1+ruuRRV0IZ98i1OuN5TSR56ShCGg3zkd5C4L/xSMAz+NDfYSDBdO8BVvBsw21KqSRUi1ctL7QiIvfedrtGb5XrE4zhH0gjXlU5qZAoGBAMv2segn0Jx6az4rqRa2Y7zRx4iZ77JUqYDBI8WMnFeR54uiioTQ+rOs3zK2fGIWlrn4ohco/STHQSUTB8oCOFLMx1BkOqiR+UyebO28DJY7+V9ZmxB2Guyi7W8VScJcIdpSOPyJFOWZQKXdQFW3YICD2/toUx/pDAJh1sEVQsV3AoGBANyyp1rthmvoo5cVbymhYQ08vaERDwU3PLCtFXu4E0Ow90VNn6Ki4ueXcv/gFOp7pISk2/yuVTBTGjCblCiJ1en4HFWekJwrvgg3Vodtq8Okn6pyMCHRqvWEPqD5hw6rGEensk0K+FMXnF6GULlfn4mgEkYpb+PvDhSYvQSGfkPJAoGAF/bAKFqlM/1eJEvU7go35bNwEiij9Pvlfm8y2L8Qj2lhHxLV240CJ6IkBz1Rl+S3iNohkT8LnwqaKNT3kVB5daEBufxMuAmOlOX4PmZdxDj/r6hDg8ecmjj6VJbXt7JDd/c5ItKoVeGPqu035dpJyE+1xPAY9CLZel4scTsiQTkCgYBt3buRcZMwnc4qqpOOQcXK+DWD6QvpkcJ55ygHYw97iP/lF4euwdHd+I5b+11pJBAao7G0fHX3eSjqOmzReSKboSe5L8ZLB2cAI8AsKTBfKHWmCa8kDtgQuI86fUfirCGdhdA9AVP2QXN2eNCuPnFWi0WHm4fYuUB5be2c18ucxAb9CAESmgsK3QMIAhIQ071yBlsbLoO2CSB9Ds0cmRif6uevBiKOAjCCAQoCggEBAMJwhQD18uFNSe6dTaIQEvsZdONH+RQWhP+fWz6d9NWxs9FBhU/VaBLWN72nmpCof3d9fcUzbwklrBA0WjJqf8EpzIhGVJJJdOPO9o8drh7keJOimLriaKobCKNU8FAbnhWcpxwVCc10HlvBG9bWAEThsKfFu6/m9ESIxwvPURZ31V6WJReSOjHJLcsyTUjsJUGzKpVYoX0lJL91kXdxxZgNDsWsudQhCJKE5TUx1Zc1coaL+Xhwg0qe0BDUYGmkNN4wvwnIBTqEMpobAbUQjNAkJn7gB4wPXu+0epvxiT8u+RvX6qGTNUTM1Qxymym"""

# Quality tier → Apple flavor code
FLAVOR_MAP = {
    "aac": "28:ctrp256",      # ~256 kbps AAC-LC, Widevine CENC
    "aac-he": "28:ctrp64",    # ~64 kbps HE-AAC
}

# Widevine KEYFORMAT UUID for m3u8 EXT-X-KEY matching
WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

try:
    from pywidevine.cdm import Cdm
    from pywidevine.device import Device
    from pywidevine.pssh import PSSH
    HAS_WIDEVINE = True
except ImportError:
    HAS_WIDEVINE = False
    Cdm = Device = PSSH = None

try:
    from Crypto.Cipher import AES
    from Crypto.Util import Counter
    HAS_CRYPTO = True
except ImportError:
    HAS_CRYPTO = False

try:
    import m3u8
    HAS_M3U8 = True
except ImportError:
    HAS_M3U8 = False

# Shared CDM device to avoid loading on every call
_cdm_device = None


def _get_cdm_device():
    """Get or create the CDM device (cached)."""
    global _cdm_device
    if _cdm_device is None:
        _cdm_device = Device.loads(BUNDLED_WVD)
    return _cdm_device


def _cache_path(cache_dir: Path, track_id: str, quality: str) -> Path:
    """Stable cache path for a decrypted track."""
    h = hashlib.sha256(f"{track_id}:{quality}".encode()).hexdigest()[:16]
    return cache_dir / f"{h}.m4a"


def _extract_pssh_from_m3u8(m3u8_content: str) -> tuple[str | None, str | None]:
    """Extract Widevine PSSH and key URI from m3u8 content.

    Returns (pssh_b64, key_uri). The key_uri is the full URI from EXT-X-KEY
    which is needed for the license exchange.

    gamdl's approach: look for KEYFORMAT="urn:uuid:edef8ba9-..." first,
    then fall back to untyped key (KEYFORMAT missing/None) per issue #352.
    """
    if not HAS_M3U8:
        return None, None
    try:
        playlist = m3u8.loads(m3u8_content)
    except Exception as e:
        logger.warning("Failed to parse m3u8: %s", e)
        return None, None

    # Look for Widevine key first (explicit KEYFORMAT)
    for key in playlist.keys:
        if key is None:
            continue
        if key.keyformat == WIDEVINE_KEYFORMAT and key.uri:
            return _parse_key_uri(key.uri), key.uri

    # Fall back to untyped key (KEYFORMAT missing or None) - gamdl issue #352
    for key in playlist.keys:
        if key is None:
            continue
        if key.keyformat is None and key.uri:
            return _parse_key_uri(key.uri), key.uri

    return None, None


def _parse_key_uri(key_uri: str) -> str | None:
    """Extract base64 PSSH from a key URI.

    Handles:
    - data:text/plain;base64,<pssh>
    - data:;base64,<pssh>
    - Query param ?pssh=<pssh>
    - Path segment /widevine/pssh/<pssh>
    """
    if not key_uri:
        return None

    # Handle data: URIs (most common for Apple Music)
    if key_uri.startswith("data:"):
        try:
            _, encoded = key_uri.split(",", 1)
            return encoded
        except ValueError:
            return None

    # Try query param
    parsed = urlparse(key_uri)
    params = parse_qs(parsed.query)
    if "pssh" in params:
        return params["pssh"][0]

    # Try path segment (format: /widevine/pssh/<base64>)
    parts = parsed.path.split("/")
    for i, part in enumerate(parts):
        if part == "pssh" and i + 1 < len(parts):
            return parts[i + 1]

    return None


async def _fetch_m3u8(api, url: str) -> str | None:
    """Fetch m3u8 playlist content."""
    try:
        response = await api.client.get(url)
        response.raise_for_status()
        return response.text
    except Exception as e:
        logger.warning("Failed to fetch m3u8 from %s: %s", url, e)
        return None


async def _download_segments(api, m3u8_url: str, m3u8_content: str, output: Path) -> bool:
    """Download and concatenate all segments from an HLS playlist.

    Uses httpx directly instead of ffmpeg subprocess (works on Android/Chaquopy).
    """
    if not HAS_M3U8:
        logger.error("m3u8 library not available")
        return False

    try:
        playlist = m3u8.loads(m3u8_content)
    except Exception as e:
        logger.warning("Failed to parse m3u8 for download: %s", e)
        return False

    # Resolve base URL for relative segment URIs
    base_url = m3u8_url.rsplit("/", 1)[0] + "/"

    segments_data = []
    for segment in playlist.segments:
        segment_url = segment.uri
        if not segment_url.startswith("http"):
            segment_url = base_url + segment_url

        try:
            response = await api.client.get(segment_url)
            response.raise_for_status()
            segments_data.append(response.content)
        except Exception as e:
            logger.warning("Failed to download segment %s: %s", segment_url, e)
            return False

    if not segments_data:
        return False

    try:
        with open(output, "wb") as f:
            for data in segments_data:
                f.write(data)
        return True
    except Exception as e:
        logger.warning("Failed to write segments: %s", e)
        return False


async def _get_webplayback(api, track_id: str) -> dict | None:
    """Fetch the webplayback response for a track."""
    try:
        return await api.get_webplayback(track_id)
    except Exception as e:
        logger.warning("Failed to get webplayback for %s: %s", track_id, e)
        return None


def _find_asset(assets: list, flavor: str) -> dict | None:
    """Find the asset matching the requested flavor."""
    for asset in assets:
        if asset.get("flavor") == flavor:
            return asset
    # Fall back to first CENC asset with matching bitrate tier
    target_bitrate = "256" if "256" in flavor else "64"
    for asset in assets:
        asset_flavor = asset.get("flavor") or ""
        if "ctrp" in asset_flavor and target_bitrate in asset_flavor:
            return asset
    # Last resort: any CENC asset (but warn about potential quality mismatch)
    for asset in assets:
        if "ctrp" in (asset.get("flavor") or ""):
            logger.warning("Using fallback CENC asset %s instead of %s", asset.get("flavor"), flavor)
            return asset
    return None


def _decrypt_cenc_pycrypto(encrypted_data: bytes, key: bytes, kid: bytes) -> bytes:
    """Decrypt CENC content using pycryptodome.

    CENC uses AES-CTR mode. For Apple Music fMP4, the IV is typically
    8 bytes of KID + 8 bytes counter starting at 0.
    """
    if not HAS_CRYPTO:
        raise ValueError("pycryptodome not available for decryption")

    # Standard CENC IV: first 8 bytes of KID, 8-byte counter
    iv = kid[:8] + b'\x00' * 8
    ctr = Counter.new(64, prefix=iv[:8], initial_value=0)
    cipher = AES.new(key, AES.MODE_CTR, counter=ctr)
    return cipher.decrypt(encrypted_data)


def _decrypt_cenc(encrypted_path: Path, decrypted_path: Path, key: bytes, kid: bytes) -> bool:
    """Decrypt a CENC-encrypted MP4 file.

    Uses pycryptodome AES-CTR. This is a simplified implementation that
    decrypts the entire file; for full CENC compliance we'd need to parse
    moof/traf boxes and decrypt only encrypted samples.
    """
    if not HAS_CRYPTO:
        logger.error("pycryptodome not available - cannot decrypt CENC content")
        return False

    try:
        with open(encrypted_path, "rb") as f:
            encrypted_data = f.read()

        decrypted_data = _decrypt_cenc_pycrypto(encrypted_data, key, kid)

        with open(decrypted_path, "wb") as f:
            f.write(decrypted_data)

        return True
    except Exception as e:
        logger.error("CENC decryption failed: %s", e)
        return False


async def resolve_stream(
    cookies_path: str,
    track_id: str,
    cache_dir: Path,
    quality: str = "aac",
    wrapper_url: str | None = None,
) -> dict:
    """Resolve a track to a decrypted playable file.

    Returns: {"path": str, "format": "m4a", "bitrate": int}
    Raises: ValueError on failure
    """
    if not HAS_WIDEVINE:
        raise ValueError("pywidevine not available")
    if not HAS_M3U8:
        raise ValueError("m3u8 library not available")
    if not HAS_CRYPTO:
        raise ValueError("pycryptodome not available")

    cached = _cache_path(cache_dir, track_id, quality)
    if cached.exists():
        return {"path": str(cached), "format": "m4a", "bitrate": 256, "cached": True}

    # Open API
    api = await open_api(cookies_path, wrapper_url)

    try:
        # Get webplayback
        wp = await _get_webplayback(api, track_id)
        if not wp:
            raise ValueError(f"No webplayback response for track {track_id}")

        songs = wp.get("songList") or []
        if not songs:
            raise ValueError(f"No songs in webplayback response for {track_id}")

        song = songs[0]
        assets = song.get("assets") or []

        # Find the right flavor
        flavor = FLAVOR_MAP.get(quality, FLAVOR_MAP["aac"])
        asset = _find_asset(assets, flavor)
        if not asset:
            raise ValueError(f"No {quality} asset found for track {track_id}")

        url = asset.get("URL")
        if not url:
            raise ValueError("Asset has no URL")

        # Fetch the m3u8 to get PSSH from EXT-X-KEY (gamdl's approach)
        m3u8_content = await _fetch_m3u8(api, url)
        if not m3u8_content:
            raise ValueError("Failed to fetch stream manifest")

        # Extract PSSH from m3u8 EXT-X-KEY
        pssh_b64, key_uri = _extract_pssh_from_m3u8(m3u8_content)

        if not pssh_b64:
            # Asset might be unencrypted; try direct download
            logger.info("No PSSH found in m3u8; attempting direct download")
            enc_path = cache_dir / f"{track_id}_enc.m4a"
            if await _download_segments(api, url, m3u8_content, enc_path):
                enc_path.rename(cached)
                return {"path": str(cached), "format": "m4a", "bitrate": 256}
            raise ValueError("Download failed and no PSSH for decryption")

        # Load CDM from bundled device (cached to avoid reload per track)
        device = _get_cdm_device()
        cdm = Cdm.from_device(device)
        session_id = cdm.open()

        try:
            # Parse PSSH and generate challenge
            pssh = PSSH(pssh_b64)
            challenge = cdm.get_license_challenge(session_id, pssh)

            # Exchange for license - correct method name and params
            license_response = await api.get_license_exchange(
                track_id=track_id,
                track_uri=key_uri,  # The full key URI from m3u8
                challenge=b64encode(challenge).decode(),
            )

            license_b64 = license_response.get("license")
            if not license_b64:
                raise ValueError("No license in response")

            # Parse license
            cdm.parse_license(session_id, b64decode(license_b64))

            # Get content key
            keys = list(cdm.get_keys(session_id))
            content_key = next((k for k in keys if k.type == "CONTENT"), None)
            if not content_key:
                raise ValueError("No content key in license")

            # Download encrypted content (using httpx, not ffmpeg subprocess)
            enc_path = cache_dir / f"{track_id}_enc.m4a"
            if not await _download_segments(api, url, m3u8_content, enc_path):
                raise ValueError("Failed to download encrypted content")

            # Decrypt using pycryptodome (not mp4decrypt subprocess)
            if not _decrypt_cenc(enc_path, cached, content_key.key, content_key.kid):
                raise ValueError("Decryption failed")

            # Clean up encrypted file
            enc_path.unlink(missing_ok=True)

            return {"path": str(cached), "format": "m4a", "bitrate": 256}

        finally:
            cdm.close(session_id)

    finally:
        # Always close the API client to avoid resource leaks
        await api.client.aclose()


def handle_apple_stream(payload: Payload, emit: Emit) -> Event:
    """Pipeline handler for apple_stream command.

    payload:
        track_id: str - Apple Music catalog ID
        cookies_path: str - Path to cookies.txt
        wrapper_url: str? - Optional wrapper URL
        cache_dir: str - Directory for cached decrypted files
        quality: str - "aac" (default) or "aac-he"

    Returns:
        path: str - Path to decrypted m4a file
        format: str - "m4a"
        bitrate: int - ~256 for aac, ~64 for aac-he
    """
    problem = missing_fields(payload, "track_id", "cookies_path", "cache_dir")
    if problem:
        return problem

    track_id = str(payload["track_id"])
    cookies_path = str(payload["cookies_path"])
    cache_dir = Path(str(payload["cache_dir"]))
    quality = str(payload.get("quality", "aac"))
    wrapper_url = payload.get("wrapper_url")
    if wrapper_url:
        wrapper_url = str(wrapper_url)

    cache_dir.mkdir(parents=True, exist_ok=True)

    emit(progress(10, "Resolving stream"))

    try:
        result = asyncio.run(resolve_stream(
            cookies_path=cookies_path,
            track_id=track_id,
            cache_dir=cache_dir,
            quality=quality,
            wrapper_url=wrapper_url,
        ))
        emit(progress(100, "Done"))
        return done(**result)
    except ValueError as e:
        return error("stream_failed", str(e))
    except FileNotFoundError as e:
        return error("no_credentials", str(e))
    except Exception as e:
        logger.exception("Apple stream failed")
        return error("stream_error", str(e))
