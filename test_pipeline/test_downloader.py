"""Tests for the parts of download orchestration that gamdl can silently break.

Both cases here are regressions that shipped: neither raised, neither logged,
and both left the UI showing a green tick for a download that never happened.
Kept outside pipeline/ because Chaquopy packages that directory verbatim
into the APK, and test code has no business shipping to a phone.

Run with: .pipeline-venv/bin/python -m pytest test_pipeline
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pipeline"))

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


def test_traceback_detail_is_appended_to_the_error():
    """gamdl's error text alone is useless.

    It logs 'Error downloading "<title>"' and prints the exception on the
    following lines, so without them the UI can only say that something went
    wrong with a track the user already knew they had asked for.
    """
    tap, _ = _tap()
    tap.write('[ERROR 00:00:01] [Track 1/1] Error downloading "One More Time"\n')
    tap.write("Traceback (most recent call last):\n")
    tap.write('  File "gamdl/downloader/song.py", line 110, in download\n')
    tap.write("RuntimeError: no suitable stream found\n")
    tap.write("[INFO 00:00:02] Finished with 1 error(s)\n")
    assert tap.failures == [
        'Error downloading "One More Time": no suitable stream found'
    ]


def test_ytdlp_patch_replaces_the_multiprocessing_path():
    """Android has no sem_open, so gamdl's subprocess step cannot run there.

    Guards the patch against a gamdl refactor: if the worker or the class is
    renamed the patch silently stops applying, and every Android download
    fails again with an OSError the user cannot act on.
    """
    pytest_skip_if_missing()
    assert downloader._patch_ytdlp_to_run_in_thread() is True

    from gamdl.downloader.base import AppleMusicBaseDownloader

    patched = AppleMusicBaseDownloader._download_ytdlp_async
    assert patched.__name__ == "_download_ytdlp_async"
    assert "multiprocessing" not in patched.__code__.co_names


def _captured_args(tmp_path, monkeypatch, **kwargs):
    """Runs download() far enough to see the argv it builds for gamdl."""
    seen = {}

    def fake_main(args, standalone_mode=True):
        seen["args"] = args

    monkeypatch.setattr(
        downloader,
        "_multiprocessing_works",
        lambda: True,
    )
    monkeypatch.setattr(downloader, "_capture_gamdl_logging", lambda tap: True)
    import types

    fake_cli = types.ModuleType("gamdl.cli.cli")
    fake_cli.main = fake_main
    monkeypatch.setitem(sys.modules, "gamdl.cli.cli", fake_cli)

    cookies = tmp_path / "cookies.txt"
    cookies.write_text("")
    downloader.download(
        url="https://music.apple.com/album/1",
        cookies_path=str(cookies),
        output_dir=str(tmp_path / "out"),
        temp_dir=str(tmp_path / "tmp"),
        **kwargs,
    )
    return seen["args"]


def test_retrying_resumes_by_default_rather_than_refetching(tmp_path, monkeypatch):
    """No --overwrite is what makes a retry cheap.

    gamdl skips any track whose final file already exists -- logging a warning,
    not an error -- so re-running a playlist that died halfway fetches only the
    rest. Passing --overwrite unconditionally, as this did, re-downloaded the
    whole thing every time.
    """
    args = _captured_args(tmp_path, monkeypatch)
    assert "--overwrite" not in args


def test_overwrite_is_passed_when_a_redownload_is_asked_for(tmp_path, monkeypatch):
    args = _captured_args(tmp_path, monkeypatch, overwrite=True)
    assert "--overwrite" in args
