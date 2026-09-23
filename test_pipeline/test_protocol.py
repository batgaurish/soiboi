"""Tests for the event shapes the Dart side parses."""
from soiboi_pipeline import protocol


def test_event_field_names_match_what_the_app_reads():
    assert protocol.progress(40, "Muxing") == {
        "event": "progress", "progress": 40, "status": "Muxing",
    }
    assert protocol.error("auth", "Sign in again") == {
        "event": "error", "code": "auth", "message": "Sign in again",
    }
    assert protocol.done(total=3) == {"event": "done", "total": 3}
    assert protocol.warning("skipped") == {"event": "warning", "message": "skipped"}


def test_missing_fields_names_every_absent_or_empty_key():
    result = protocol.missing_fields({"url": "u", "output_dir": ""}, "url", "cookies_path", "output_dir")
    assert result == protocol.error("bad_request", "Missing: cookies_path, output_dir")


def test_missing_fields_is_none_when_complete():
    assert protocol.missing_fields({"url": "u"}, "url") is None


def test_ignore_accepts_any_event():
    assert protocol.ignore(protocol.progress(1, "x")) is None
