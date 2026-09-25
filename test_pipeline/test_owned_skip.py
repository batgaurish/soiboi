from soiboi_pipeline.downloader import DownloadRequest, owned_key


def test_owned_key_ignores_case_and_punctuation():
    assert owned_key("Rex Orange County", "Corduroy Dreams") == owned_key(
        "rex orange county", "Corduroy  Dreams!"
    )
    assert owned_key("A & B", "x") == owned_key("A and B", "x")


def test_owned_key_keeps_non_latin_titles_apart():
    assert owned_key("Arpit Bala", "तारों से") != owned_key("Arpit Bala", "मन")
    assert owned_key("Arpit Bala", "तारों से") != "arpitbala|"


def test_owned_key_ignores_soundtrack_and_featuring_credits():
    artist = "Amit Trivedi, Kavita Seth & Amitabh Bhattacharya"
    film = owned_key(artist, "Iktara")
    assert owned_key(artist, 'Iktara (From "Wake Up Sid")') == film
    assert owned_key(artist, "Iktara - From “Wake Up Sid”") == film
    assert owned_key(artist, "Iktara [feat. Someone]") == film
    # Must agree with the app's ownedSongKey, which the Dart tests pin.
    assert film == "amittrivedikavitasethandamitabhbhattacharya|iktara"


def test_owned_key_keeps_live_and_remix_versions_apart():
    studio = owned_key("Shawn Mendes", "Treat You Better")
    assert owned_key("Shawn Mendes", "Treat You Better (Live From New York)") != studio
    assert owned_key("Shawn Mendes", "Treat You Better (Ashworth Remix)") != studio


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


# Playlists hold compilation copies of film songs; the download should take
# the song from its own album. original_release() against a fake catalog.

def _song(id, name, album, *, artist="Amit Trivedi", millis=250000,
          compilation=False, single=False, isrc="INS000"):
    return {
        "id": id, "type": "songs",
        "attributes": {"name": name, "artistName": artist, "albumName": album,
                       "durationInMillis": millis, "isrc": isrc},
        "relationships": {"albums": {"data": [{"attributes": {
            "name": album, "isCompilation": compilation, "isSingle": single,
            "artistName": "Various Artists" if compilation else artist,
        }}]}},
    }


class _FakeApi:
    storefront = "in"

    def __init__(self, songs, search=()):
        self.songs = {s["id"]: s for s in songs}
        self.search = list(search)
        self.calls = []

    async def _amp_request(self, uri, params):
        self.calls.append((uri.rsplit("/", 1)[-1], params))
        if uri.endswith("/search"):
            return {"results": {"songs": {"data": [
                {"id": s["id"], "attributes": s["attributes"]} for s in self.search]}}}
        if "filter[isrc]" in params:
            return {"data": [s for s in self.songs.values()
                             if s["attributes"]["isrc"] == params["filter[isrc]"]]}
        return {"data": [self.songs[i] for i in params["ids"].split(",")
                         if i in self.songs]}


def _bare(song):
    """What a playlist hands gamdl: attributes, no album relationship."""
    return {"id": song["id"], "type": "songs", "attributes": song["attributes"]}


compilation = _song("1", "Iktara", "Chai aur Baarish", compilation=True)
from_film = _song("2", 'Iktara (From "Wake Up Sid")', "Handpicked By Karan",
                  compilation=True)
soundtrack = _song("3", "Iktara", "Wake Up Sid (Original Motion Picture Soundtrack)")
deluxe = _song("4", "Iktara", "Wake Up Sid (Deluxe Edition)")


def test_isrc_finds_the_soundtrack_for_a_compilation_copy():
    api = _FakeApi([compilation, from_film, deluxe, soundtrack])
    better = asyncio.run(downloader.original_release(api, "1", _bare(compilation)))
    assert better["id"] == "3"
    assert [c[0] for c in api.calls] == ["songs"]  # one request


def test_a_song_already_on_its_album_is_kept_after_one_request():
    api = _FakeApi([compilation, soundtrack])
    assert asyncio.run(downloader.original_release(api, "3", _bare(soundtrack))) is None
    assert len(api.calls) == 1


def test_search_finds_the_soundtrack_when_the_isrc_differs():
    other_isrc = _song("3", "Iktara", "Wake Up Sid (Original Motion Picture Soundtrack)",
                       isrc="INS999")
    live = _song("5", "Iktara", "Live at MTV", isrc="INS777", millis=300000)
    api = _FakeApi([from_film, other_isrc, live], search=[live, other_isrc])
    better = asyncio.run(downloader.original_release(api, "2", _bare(from_film)))
    assert better["id"] == "3"
    # The live take (a minute longer) was never considered.
    assert "5" not in api.calls[-1][1]["ids"]


def test_no_better_copy_keeps_the_playlist_one():
    api = _FakeApi([compilation], search=[compilation])
    assert asyncio.run(downloader.original_release(api, "1", _bare(compilation))) is None


def test_playlist_tracks_are_swapped_but_album_links_are_not(monkeypatch):
    fetched = []

    async def fake_song_media(self, media_id, index=None, total=None,
                              media_metadata=None, playlist_metadata=None,
                              is_library=False):
        fetched.append(media_id)
        yield downloader.AppleMusicMedia(media_id=media_id,
                                         media_metadata=media_metadata)

    interface = downloader.gamdl_interface.AppleMusicInterface
    monkeypatch.setattr(interface, "_get_song_media", fake_song_media)
    monkeypatch.setattr(downloader, "_owned_patched", False)
    monkeypatch.setattr(downloader, "_owned", frozenset())
    downloader._cancel_requested.clear()
    downloader._skip_owned_tracks()

    class Self:
        class base:
            apple_music_api = _FakeApi([compilation, soundtrack])

    async def run(playlist):
        return [m async for m in interface._get_song_media(
            Self(), "1", media_metadata=_bare(compilation),
            playlist_metadata=playlist)]

    asyncio.run(run({"id": "pl"}))
    asyncio.run(run(None))
    assert fetched == ["3", "1"]


def test_a_failed_lookup_downloads_the_playlist_copy(monkeypatch):
    class Broken:
        storefront = "in"

        async def _amp_request(self, uri, params):
            raise RuntimeError("offline")

    fetched = []

    async def fake_song_media(self, media_id, *args, **kwargs):
        fetched.append(media_id)
        yield downloader.AppleMusicMedia(media_id=media_id)

    interface = downloader.gamdl_interface.AppleMusicInterface
    monkeypatch.setattr(interface, "_get_song_media", fake_song_media)
    monkeypatch.setattr(downloader, "_owned_patched", False)
    monkeypatch.setattr(downloader, "_owned", frozenset())
    downloader._skip_owned_tracks()

    class Self:
        class base:
            apple_music_api = Broken()

    async def run():
        return [m async for m in interface._get_song_media(
            Self(), "1", media_metadata=_bare(compilation),
            playlist_metadata={"id": "pl"})]

    asyncio.run(run())
    assert fetched == ["1"]
