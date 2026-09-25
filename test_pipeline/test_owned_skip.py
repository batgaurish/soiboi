from soiboi_pipeline.downloader import DownloadRequest, owned_key


def test_owned_key_ignores_case_and_punctuation():
    assert owned_key("Rex Orange County", "Corduroy Dreams") == owned_key(
        "rex orange county", "Corduroy  Dreams!"
    )
    assert owned_key("A & B", "x") == owned_key("A and B", "x")


def test_owned_key_keeps_non_latin_titles_apart():
    assert owned_key("Arpit Bala", "तारों से") != owned_key("Arpit Bala", "मन")
    assert owned_key("Arpit Bala", "तारों से") != "arpitbala|"


def test_payload_carries_owned_keys():
    request = DownloadRequest.from_payload(
        {"url": "u", "cookies_path": "c", "output_dir": "o", "owned": ["a|b"]}
    )
    assert "a|b" in request.owned


import asyncio
import io
import logging

import pytest
import structlog

from soiboi_pipeline import apple_library, downloader


@pytest.fixture
def patched(monkeypatch):
    """Fresh owned-track patches over fake gamdl internals."""
    fetched = []

    async def fake_song_media(self, media_id, index=None, total=None,
                              media_metadata=None, playlist_metadata=None,
                              is_library=False):
        fetched.append(media_id)
        yield downloader.AppleMusicMedia(media_id=media_id, index=index,
                                         media_metadata=media_metadata)

    async def fake_download(self, item):
        fetched.append(("download", item.media.media_id))

    interface = downloader.gamdl_interface.AppleMusicInterface
    monkeypatch.setattr(interface, "_get_song_media", fake_song_media)
    monkeypatch.setattr(downloader.gamdl_downloader.AppleMusicDownloader,
                        "_download", fake_download)
    monkeypatch.setattr(downloader, "_owned_patched", False)
    monkeypatch.setattr(downloader, "_owned",
                        frozenset({owned_key("Wallows", "Pleaser")}))
    downloader._cancel_requested.clear()
    downloader._skip_owned_tracks()
    yield interface, fetched
    downloader._cancel_requested.clear()


def _track(title, artist):
    return {"id": title, "type": "songs",
            "attributes": {"name": title, "artistName": artist}}


async def _collect(interface, track):
    return [m async for m in interface._get_song_media(
        object(), track["id"], index=0, media_metadata=track)]


def test_owned_track_is_skipped_without_fetching_its_details(patched):
    interface, fetched = patched
    [media] = asyncio.run(_collect(interface, _track("Pleaser", "Wallows")))
    assert fetched == []
    assert media.partial is False
    assert "Media file already exists" in str(media.error)
    assert "already in your library" in str(media.error)


def test_new_track_still_fetches_its_details(patched):
    interface, fetched = patched
    [media] = asyncio.run(_collect(interface, _track("Calling After Me", "Wallows")))
    assert fetched == ["Calling After Me"]
    assert media.error is None


def test_stop_lands_before_the_next_track(patched):
    interface, fetched = patched
    downloader.request_cancel()
    with pytest.raises(downloader.DownloadCancelled):
        asyncio.run(_collect(interface, _track("Calling After Me", "Wallows")))
    assert fetched == []


def test_reading_apple_playlists_does_not_mute_a_download():
    """The Android "stuck on Starting / Stopping" bug, in miniature.

    A download configures gamdl's logger (as gamdl's CLI does: INFO, printed
    to the stream tap). A playlist read then runs in the same interpreter.
    The download's next line must still reach the tap, or the app sees no
    progress and a stop, noticed only on output, never lands.
    """
    seen = io.StringIO()
    structlog.configure(
        processors=[structlog.processors.KeyValueRenderer()],
        logger_factory=structlog.PrintLoggerFactory(file=seen),
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
    )
    log = structlog.get_logger()
    try:
        log.info("track 1")
        apple_library._quiet_gamdl_logging()
        log.info("track 2")
        log.debug("account payload with tokens")
    finally:
        structlog.reset_defaults()
    assert "track 1" in seen.getvalue()
    assert "track 2" in seen.getvalue()
    assert "tokens" not in seen.getvalue()
