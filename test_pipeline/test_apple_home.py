"""Tests for the Apple home shelves and the personalized-token split.

The live API needs a real session, so these cover the shaping, the fallback
order, and the auth split the home depends on.
"""
from soiboi_pipeline import apple_auth, apple_home
from soiboi_pipeline.protocol import ignore


def _reco(title, href="/v1/me/recommendations/r1", items=None):
    return {
        "id": "r." + title,
        "type": "personal-recommendation",
        "href": href,
        "attributes": {"title": {"stringForDisplay": title}},
        "relationships": {
            "contents": {"href": href, "data": items or []},
        },
    }


def _resource(rid, rtype="playlists", name="Mix", artist="Apple Music"):
    return {
        "id": rid,
        "type": rtype,
        "attributes": {"name": name, "artistName": artist,
                       "artwork": {"url": "https://a/{w}x{h}{c}.{f}"}},
    }


def test_recommendation_title_reads_the_nested_shape():
    assert apple_home._recommendation_title(_reco("Made For You"), "x") == "Made For You"
    # Apple reshaping title into a bare string must not blank the shelf.
    bare = {"attributes": {"title": "Fallback"}, "id": "r.1"}
    assert apple_home._recommendation_title(bare, "fb") == "Fallback"
    assert apple_home._recommendation_title({"attributes": {}}, "fb") == "fb"


def test_expand_follows_href_when_no_inline_items():
    import asyncio

    class Api:
        async def _amp_request(self, uri, params=None):
            assert "types=" in uri
            return {"data": [_resource("pl.1")]}

    items = asyncio.run(apple_home._expand_recommendation(Api(), _reco("Mix", href="/v1/me/recommendations/r1")))
    assert [i["id"] for i in items] == ["pl.1"]


def test_expand_prefers_inline_items():
    import asyncio

    class Api:
        calls = 0

        async def _amp_request(self, uri, params=None):
            raise AssertionError("should not fetch")

    resource = _reco("Mix", items=[_resource("pl.2")])
    items = asyncio.run(apple_home._expand_recommendation(Api(), resource))
    assert [i["id"] for i in items] == ["pl.2"]


def test_expand_failure_costs_one_shelf_not_the_home():
    import asyncio

    class Api:
        async def _amp_request(self, uri, params=None):
            raise RuntimeError("403")

    items = asyncio.run(apple_home._expand_recommendation(Api(), _reco("Mix")))
    assert items == []


def test_personalized_shelves_use_the_real_titles():
    import asyncio

    class Api:
        async def _amp_request(self, uri, params=None):
            if uri == "/v1/me/recommendations":
                return {"data": [_reco("Playlists Made for You",
                                       items=[_resource("pl.1")]),
                                _reco("World of Ed Sheeran",
                                      href="", items=[])]}
            if uri == "/v1/me/recent/played":
                return {"data": [_resource("i.1", name="one for the midnight blues")]}
            return {"data": []}

    shelves = asyncio.run(apple_home._personalized_shelves(Api(), "in"))
    titles = [s["title"] for s in shelves]
    assert "Playlists Made for You" in titles
    assert "Recently Played" in titles
    # An expansion that resolves to nothing costs its shelf, not the home.
    assert "World of Ed Sheeran" not in titles


def test_charts_fallback_keeps_a_title_that_says_where_it_is_from(monkeypatch):
    import asyncio

    class NoAuth:
        async def client_aclose(self):
            pass

        client = property(lambda self: self)

    async def fake_open_personalized(cookies_path, wrapper_url=None):
        return None

    class CatalogApi:
        storefront = "in"

        async def _amp_request(self, uri, params=None):
            assert "/charts" in uri
            return {"data": [_resource("pl.9", name="Today's Hits", artist="Apple Music")]}

    async def fake_open_api(cookies_path, wrapper_url=None):
        return CatalogApi()

    monkeypatch.setattr(apple_home, "open_personalized_api", fake_open_personalized)
    monkeypatch.setattr(apple_home, "open_api", fake_open_api)

    result = apple_home.home({"cookies_path": "c"}, ignore)
    assert result["event"] == "done"
    titles = [s["title"] for s in result["shelves"]]
    assert titles == ["Popular on Apple Music"]
    kinds = [s["kind"] for s in result["shelves"]]
    assert "recommendation" not in kinds


def test_personalized_api_needs_both_tokens(monkeypatch):
    monkeypatch.setattr(apple_auth, "scraped_dev_token", lambda: None)
    assert apple_auth.music_user_token(None, None) is None
    import asyncio
    assert asyncio.run(apple_auth.open_personalized_api(None, None)) is None


def test_home_without_sign_in_is_no_cookies():
    assert apple_home.home({}, ignore)["code"] == "no_cookies"
