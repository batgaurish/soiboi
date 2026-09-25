"""Tests for listing the signed-in account's Apple Music playlists.

The API calls need a live session, so these cover the flattening and the error
mapping the app's "sign in again" and "no subscription" states depend on.
"""
from soiboi_pipeline import apple_library
from soiboi_pipeline.protocol import ignore


def _raw_playlist(name, global_id=None, **attributes):
    play_params = {"globalId": global_id} if global_id else {}
    return {
        "id": f"p.{name}",
        "attributes": {"name": name, "playParams": play_params, **attributes},
    }


def test_playlist_entry_flattens_and_sizes_artwork():
    entry = apple_library._playlist_entry(_raw_playlist(
        "Road",
        global_id="pl.123",
        artwork={"url": "https://a/{w}x{h}{c}.{f}"},
        description={"standard": "for driving"},
        canEdit=True,
        trackCount=12,
    ))
    assert entry == {
        "library_id": "p.Road",
        "name": "Road",
        "catalog_id": "pl.123",
        "track_count": 12,
        "artwork_url": "https://a/600x600bb.jpg",
        "can_edit": True,
        "description": "for driving",
    }


def test_playlist_entry_skips_what_it_cannot_read():
    assert apple_library._playlist_entry("not a playlist") is None
    # description should be an object; a bare string must cost only this row.
    assert apple_library._playlist_entry(_raw_playlist("X", description="oops")) is None


def test_track_without_a_catalog_id_is_library_only():
    track = apple_library._track_entry({
        "attributes": {"name": "Demo", "artistName": "Me", "playParams": {"id": "i.1"}},
    })
    assert track["is_library_only"] is True
    assert track["catalog_id"] == "i.1"


def _collect_returning(storefront, raw):
    async def collect(cookies_path, wrapper_url=None):
        return storefront, raw

    return collect


def _collect_raising(exc):
    async def collect(cookies_path, wrapper_url=None):
        raise exc

    return collect


def test_playlists_come_back_sorted_with_the_storefront(monkeypatch):
    raw = [_raw_playlist("beta"), _raw_playlist("Alpha"), "junk"]
    monkeypatch.setattr(apple_library, "_collect", _collect_returning("gb", raw))
    result = apple_library.list_playlists("cookies.txt", ignore)
    assert [p["name"] for p in result["playlists"]] == ["Alpha", "beta"]
    assert result["storefront"] == "gb"
    assert result["total"] == 2


def test_error_codes_the_app_acts_on(monkeypatch):
    cases = [
        (PermissionError("no sub"), "no_subscription"),
        (FileNotFoundError("cookies.txt"), "no_cookies"),
        (RuntimeError("HTTP 401 Unauthorized"), "auth"),
        (RuntimeError("connection reset"), "failed"),
    ]
    for exc, code in cases:
        monkeypatch.setattr(apple_library, "_collect", _collect_raising(exc))
        assert apple_library.list_playlists("c", ignore)["code"] == code, code


def test_missing_client_is_reported(monkeypatch):
    monkeypatch.setattr(apple_library, "AppleMusicApi", None)
    assert apple_library.list_playlists("c", ignore)["code"] == "no_gamdl"
    assert apple_library.list_tracks("c", "p.1", ignore)["code"] == "no_gamdl"


def test_handlers_validate_their_payloads():
    assert apple_library.handle_playlists({}, ignore)["code"] == "bad_request"
    result = apple_library.handle_playlist_tracks({"cookies_path": "c"}, ignore)
    assert result["message"] == "Missing: library_id"


def test_playlist_tracks_follow_every_page():
    # Regression: only Apple's first page (100 tracks) was read, so long
    # playlists came back truncated.
    import asyncio

    class FakeApi:
        async def get_extended_api_data(self, next_uri, href_uri):
            if next_uri == "/p2":
                return {"data": [{"id": "3"}], "next": "/p3"}
            return {"data": [{"id": "4"}]}

    relation = {"data": [{"id": "1"}, {"id": "2"}], "next": "/p2", "href": "/p1"}
    items = asyncio.run(apple_library._follow_pages(FakeApi(), relation))
    assert [item["id"] for item in items] == ["1", "2", "3", "4"]


def test_storefront_is_the_accounts(monkeypatch):
    class Api:
        storefront = "in"

    async def fake_open(cookies_path, wrapper_url=None):
        return Api()

    monkeypatch.setattr(apple_library, "open_api", fake_open)
    assert apple_library.handle_storefront({"cookies_path": "c"}, ignore) == {
        "event": "done",
        "storefront": "in",
    }


def test_storefront_without_a_sign_in(monkeypatch):
    async def fake_open(cookies_path, wrapper_url=None):
        raise FileNotFoundError(cookies_path)

    monkeypatch.setattr(apple_library, "open_api", fake_open)
    assert apple_library.storefront("c")["code"] == "no_cookies"
    assert apple_library.handle_storefront({}, ignore)["code"] == "bad_request"
