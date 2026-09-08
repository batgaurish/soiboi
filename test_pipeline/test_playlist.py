"""Tests for reading a public playlist through the bundled yt-dlp.

The extraction itself needs the network, so what is asserted here is the
filtering: the entries that must never reach a download queue.

Run with: .pipeline-venv/bin/python -m pytest test_pipeline
"""
import sys
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pipeline"))

from soiboi_pipeline import playlist  # noqa: E402


class _FakeYDL:
    """Stands in for YoutubeDL, returning a canned extraction."""

    info = {}

    def __init__(self, options):
        self.options = options
        type(self).last_options = options

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def extract_info(self, url, download=False):
        return type(self).info


def _patch(monkeypatch, info):
    _FakeYDL.info = info
    module = types.ModuleType("yt_dlp")
    module.YoutubeDL = _FakeYDL
    monkeypatch.setitem(sys.modules, "yt_dlp", module)


def test_placeholder_entries_are_dropped(monkeypatch):
    # yt-dlp lists these for entries it can see but cannot read. Passing them
    # through would put "[Private video]" into a download queue as a track.
    _patch(monkeypatch, {
        "title": "Mix",
        "entries": [
            {"title": "[Private video]"},
            {"title": "[Deleted video]"},
            {"title": "Real Song", "channel": "Artist", "duration": 200},
        ],
    })
    result = playlist.fetch("https://music.youtube.com/playlist?list=x")
    assert [e["title"] for e in result["entries"]] == ["Real Song"]


def test_long_videos_are_dropped(monkeypatch):
    # YouTube playlists collect concert films and hour-long mixes alongside
    # music; each would become a nonsense Apple Search query.
    _patch(monkeypatch, {
        "title": "Mix",
        "entries": [
            {"title": "Full Concert", "duration": 60 * 90},
            {"title": "Song", "duration": 180},
            {"title": "Unknown Length"},
        ],
    })
    titles = [e["title"] for e in playlist.fetch("u")["entries"]]
    # An entry with no duration is kept: unknown is not the same as too long.
    assert titles == ["Song", "Unknown Length"]


def test_channel_wins_over_uploader(monkeypatch):
    _patch(monkeypatch, {
        "title": "Mix",
        "entries": [
            {"title": "Song", "channel": "Real Artist", "uploader": "Stale"},
            {"title": "Other", "uploader": "Fallback"},
        ],
    })
    entries = playlist.fetch("u")["entries"]
    assert entries[0]["uploader"] == "Real Artist"
    assert entries[1]["uploader"] == "Fallback"


def test_a_failure_is_reported_with_yt_dlps_own_message(monkeypatch):
    module = types.ModuleType("yt_dlp")

    class _Failing(_FakeYDL):
        def extract_info(self, url, download=False):
            raise RuntimeError("ERROR: This playlist is private")

    module.YoutubeDL = _Failing
    monkeypatch.setitem(sys.modules, "yt_dlp", module)

    result = playlist.fetch("u")
    assert result["event"] == "error"
    # yt-dlp's wording is the useful one; ours would only be vaguer.
    assert result["message"] == "This playlist is private"


def test_limit_is_passed_through_as_playlistend(monkeypatch):
    _patch(monkeypatch, {"title": "Mix", "entries": []})
    playlist.fetch("u", limit=4)
    assert _FakeYDL.last_options["playlistend"] == 4
    assert _FakeYDL.last_options["extract_flat"] == "in_playlist"
