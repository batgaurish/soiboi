"""Tests for the parts of download orchestration that gamdl can silently break.

Both cases here are regressions that shipped: neither raised, neither logged,
and both left the UI showing a green tick for a download that never happened.
Run with: PYTHONPATH=pipeline .pipeline-venv/bin/python -m pytest pipeline/tests
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from soiboi_pipeline import downloader  # noqa: E402


def _tap():
    events = []
    return downloader._StreamTap(events.append), events


def test_error_lines_are_recorded_not_treated_as_progress():
    # gamdl logs a failure and then exits 0, so the log is the only signal.
    tap, events = _tap()
    tap.write(
        "\x1b[31m[ERROR    00:41:00]\x1b[0m\x1b[2m [Track 1/1]\x1b[0m "
        "Song is not available in your storefront\n"
    )
    assert tap.failures == ["Song is not available in your storefront"]
    assert events == [], "an error must not also register as progress"


def test_progress_is_reported_and_never_goes_backwards():
    tap, events = _tap()
    tap.write("[INFO 00:00:01] Downloading track\n")
    tap.write("[INFO 00:00:02] Muxing\n")
    tap.write("[INFO 00:00:03] Downloading cover\n")  # late keyword, earlier stage
    assert [e["progress"] for e in events] == [50, 78]


def test_carriage_returns_split_lines():
    # yt-dlp writes progress with \r, not \n; splitting on \n alone would
    # buffer the whole download into one line and report nothing until the end.
    tap, events = _tap()
    tap.write("[INFO] Downloading 1.0%\r[INFO] Downloading 2.0%\r")
    assert len(events) == 1  # second is same stage, not a new event
    assert events[0]["status"] == "Downloading"


def test_gamdl_logger_is_captured():
    """The one that actually mattered.

    gamdl binds its log stream from a mutable default argument at import time,
    so redirect_stdout does not reach it. If this ever stops working, gamdl's
    raw log lines corrupt the JSON protocol on desktop and progress silently
    stops being reported everywhere.
    """
    pytest_skip_if_missing()
    tap, _ = _tap()
    assert downloader._capture_gamdl_logging(tap) is True

    from gamdl.cli.utils import CustomOutputWriter

    assert CustomOutputWriter().streams == [tap]


def pytest_skip_if_missing():
    try:
        import gamdl  # noqa: F401
    except ImportError:  # pragma: no cover - depends on the built environment
        import pytest

        pytest.skip("gamdl not installed in this environment")
