"""Tests for session download dispatch and fallback."""
import pytest
from soiboi_pipeline import session_download


def test_missing_fields():
    res = session_download.handle_session_download({}, lambda e: None)
    assert res["code"] == "bad_request"


def test_fallback_downloads_via_ytdlp(monkeypatch, tmp_path):
    downloaded_urls = []

    class FakeYDL:
        def __init__(self, options):
            self.options = options

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc_val, exc_tb):
            pass

        def download(self, urls):
            downloaded_urls.extend(urls)
            # simulate progress hook
            for hook in self.options.get("progress_hooks", []):
                hook({"status": "downloading", "total_bytes": 100, "downloaded_bytes": 50})
                hook({"status": "finished"})

    monkeypatch.setattr(session_download, "YoutubeDL", FakeYDL)

    progress_events = []
    payload = {
        "session": "spotify",
        "track_id": "track_123",
        "artist": "Artist",
        "title": "Title",
        "prefer_apple": False,
        "output_dir": str(tmp_path),
    }

    result = session_download.handle_session_download(payload, progress_events.append)
    assert result["event"] == "done"
    assert downloaded_urls == ["ytsearch1:Artist Title"]
    assert any(e.get("event") == "progress" for e in progress_events)
