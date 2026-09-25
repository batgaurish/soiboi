"""Tests for the parts of download orchestration that gamdl can silently break.

Both cases here are regressions that shipped: neither raised, neither logged,
and both left the UI showing a green tick for a download that never happened.
Kept outside pipeline/ because Chaquopy packages that directory verbatim
into the APK, and test code has no business shipping to a phone.

Run with: .pipeline-venv/bin/python -m pytest test_pipeline
"""
import pytest

from soiboi_pipeline import downloader


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
    _skip_without_gamdl()
    tap, _ = _tap()
    assert downloader._capture_gamdl_logging(tap) is True
    assert downloader.CustomOutputWriter().streams == [tap]


def _skip_without_gamdl():
    if downloader.gamdl_main is None:  # pragma: no cover - depends on the build
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


def test_a_traceback_printed_before_its_error_is_attached():
    """A track whose lookup fails: gamdl prints the traceback first.

    From a real Weekly Exploration download (a US-only song on an Indian
    account); without the exception the app could only say "Unknown Title".
    """
    tap, _ = _tap()
    tap.write('[INFO 16:46:54] [URL 1/1] Processing "https://music.apple.com/x"\n')
    tap.write("Traceback (most recent call last):\n")
    tap.write('File "gamdl/api/apple_music.py", line 284, in _amp_request\n')
    tap.write("httpx.HTTPStatusError: Client error '404 Not Found' for url 'x'\n")
    tap.write("During handling of the above exception, another exception occurred:\n")
    tap.write("Traceback (most recent call last):\n")
    tap.write(
        "gamdl.api.exceptions.GamdlApiResponseError: Error fetching from AMP "
        "API (Status code: 404): {}\n"
    )
    tap.write('[ERROR 16:46:56] [Track 1/1] Error downloading "Unknown Title"\n')
    tap.write("[INFO 16:46:56] Finished with 1 error(s)\n")
    assert tap.failures == [
        'Error downloading "Unknown Title": Error fetching from AMP API '
        "(Status code: 404): {}"
    ]


def test_a_bare_cause_type_is_kept_and_a_repeated_message_is_not():
    """From a real Weekly Jams download: a connection timeout, reported by
    gamdl as a generic account error. The cause is a bare type line."""
    tap, _ = _tap()
    tap.write("[INFO 17:30:01] Starting Gamdl 3.8.5\n")
    tap.write("Traceback (most recent call last):\n")
    tap.write("httpcore.ConnectTimeout\n")
    tap.write("The above exception was the direct cause of the following exception:\n")
    tap.write("Traceback (most recent call last):\n")
    tap.write("httpx.ConnectTimeout\n")
    tap.write("During handling of the above exception, another exception occurred:\n")
    tap.write("Traceback (most recent call last):\n")
    tap.write(
        "gamdl.api.exceptions.GamdlApiResponseError: Error fetching account info\n"
    )
    tap.write("[ERROR 17:30:06] Error: Error fetching account info\n")
    assert tap.failures == ["Error: Error fetching account info (ConnectTimeout)"]


def test_a_bare_cause_after_the_error_line_is_kept_too():
    tap, _ = _tap()
    tap.write('[ERROR 00:00:01] [Track 1/1] Error downloading "A"\n')
    tap.write("Traceback (most recent call last):\n")
    tap.write("httpx.ReadTimeout\n")
    tap.write("During handling of the above exception, another exception occurred:\n")
    tap.write("RuntimeError: yt-dlp HLS download failed\n")
    tap.write("[INFO 00:00:02] Finished with 1 error(s)\n")
    assert tap.failures == [
        'Error downloading "A": yt-dlp HLS download failed (ReadTimeout)'
    ]


def test_an_old_exception_is_not_pinned_on_a_later_error():
    tap, _ = _tap()
    tap.write("ValueError: harmless, handled inside a dependency\n")
    tap.write('[INFO 00:00:01] [Track 1/1] Downloading "A"\n')
    tap.write('[ERROR 00:00:02] [Track 1/1] Error downloading "A"\n')
    assert tap.failures == ['Error downloading "A"']


def test_ytdlp_patch_replaces_the_multiprocessing_path():
    """Android has no sem_open, so gamdl's subprocess step cannot run there.

    Guards the patch against a gamdl refactor: if the worker or the class is
    renamed the patch silently stops applying, and every Android download
    fails again with an OSError the user cannot act on.
    """
    _skip_without_gamdl()
    assert downloader._patch_ytdlp_to_run_in_thread() is True

    patched = downloader.gamdl_base.AppleMusicBaseDownloader._download_ytdlp_async
    assert patched.__name__ == "_download_ytdlp_async"
    assert "multiprocessing" not in patched.__code__.co_names


def _captured_args(tmp_path, monkeypatch, **fields):
    """Runs download() far enough to see the argv it builds for gamdl."""
    seen = {}

    def fake_main(args, standalone_mode=True):
        seen["args"] = args

    monkeypatch.setattr(downloader, "_multiprocessing_works", lambda: True)
    monkeypatch.setattr(downloader, "_capture_gamdl_logging", lambda tap: True)
    monkeypatch.setattr(downloader, "gamdl_main", fake_main)

    cookies = tmp_path / "cookies.txt"
    cookies.write_text("")
    result = downloader.download(
        downloader.DownloadRequest(
            url="https://music.apple.com/album/1",
            cookies_path=str(cookies),
            output_dir=str(tmp_path / "out"),
            temp_dir=str(tmp_path / "tmp"),
            **fields,
        )
    )
    assert result["event"] == "done"
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


def test_the_wrapper_replaces_cookies(tmp_path, monkeypatch):
    args = _captured_args(
        tmp_path, monkeypatch, use_wrapper=True, wrapper_url="http://127.0.0.1:1"
    )
    assert "-c" not in args
    assert args[args.index("--wrapper-url") + 1] == "http://127.0.0.1:1"


def test_missing_cookies_fail_before_anything_runs(tmp_path):
    result = downloader.download(
        downloader.DownloadRequest(
            url="u",
            cookies_path=str(tmp_path / "absent.txt"),
            output_dir=str(tmp_path / "out"),
        )
    )
    assert result["code"] == "no_cookies"
    assert not (tmp_path / "out").exists()


def test_a_reported_gamdl_error_fails_the_download(tmp_path, monkeypatch):
    def failing_main(args, standalone_mode=True):
        print("[ERROR 00:00:01] Song is not available in your storefront")

    monkeypatch.setattr(downloader, "_multiprocessing_works", lambda: True)
    monkeypatch.setattr(downloader, "_capture_gamdl_logging", lambda tap: True)
    monkeypatch.setattr(downloader, "gamdl_main", failing_main)
    cookies = tmp_path / "cookies.txt"
    cookies.write_text("")

    result = downloader.download(
        downloader.DownloadRequest(
            url="u", cookies_path=str(cookies), output_dir=str(tmp_path / "out")
        )
    )
    assert result["code"] == "gamdl_reported_error"
    assert result["message"] == "Song is not available in your storefront"


def _run_with_output(tmp_path, monkeypatch, *lines):
    """Runs download() with a gamdl that only prints [lines] and exits 0."""

    def fake_main(args, standalone_mode=True):
        for line in lines:
            print(line)

    monkeypatch.setattr(downloader, "_multiprocessing_works", lambda: True)
    monkeypatch.setattr(downloader, "_capture_gamdl_logging", lambda tap: True)
    monkeypatch.setattr(downloader, "gamdl_main", fake_main)
    cookies = tmp_path / "cookies.txt"
    cookies.write_text("")
    events = []
    result = downloader.download(
        downloader.DownloadRequest(
            url="u", cookies_path=str(cookies), output_dir=str(tmp_path / "out")
        ),
        emit=events.append,
    )
    return result, events


def test_no_subscription_fails_the_download(tmp_path, monkeypatch):
    # gamdl logs this as CRITICAL, downloads nothing and exits 0.
    result, _ = _run_with_output(
        tmp_path,
        monkeypatch,
        "\x1b[1;31m[CRITICAL 00:00:01]\x1b[0m No active Apple Music subscription "
        "found, you won't be able to download anything",
        "[INFO 00:00:01] Finished with 0 error(s)",
    )
    assert result["code"] == "gamdl_reported_error"
    assert result["message"].startswith("No active Apple Music subscription")


def test_every_track_skipped_fails_the_download(tmp_path, monkeypatch):
    result, _ = _run_with_output(
        tmp_path,
        monkeypatch,
        '[WARNING 00:00:01] [URL   1/1  ] [Track   1/2  ] Skipping "Intro": '
        "Media is not streamable: 123",
        '[WARNING 00:00:02] [URL   1/1  ] [Track   2/2  ] Skipping "Outro": '
        "Media is not streamable: 124",
        "[INFO 00:00:03] Finished with 0 error(s)",
    )
    assert result["code"] == "gamdl_reported_error"
    assert result["message"] == 'Skipping "Intro": Media is not streamable: 123'


def test_some_tracks_skipped_finishes_with_a_warning(tmp_path, monkeypatch):
    result, events = _run_with_output(
        tmp_path,
        monkeypatch,
        '[WARNING 00:00:01] [URL   1/1  ] [Track   1/2  ] Skipping "Intro": '
        "Media is not streamable: 123",
        "[INFO 00:00:02] [URL   1/1  ] [Track   2/2  ] Downloading track",
        "[INFO 00:00:03] Finished with 0 error(s)",
    )
    assert result["event"] == "done"
    assert any(
        e["event"] == "warning" and e["message"].startswith("Skipped 1 of 2 tracks")
        for e in events
    )


def test_files_already_there_are_not_failures(tmp_path, monkeypatch):
    # With overwrite off, "already exists" is what makes a retry cheap.
    result, _ = _run_with_output(
        tmp_path,
        monkeypatch,
        '[WARNING 00:00:01] [Track   1/1  ] Skipping "Intro": '
        "Media file already exists: /music/Intro.m4a",
    )
    assert result["event"] == "done"



def test_payload_fields_map_onto_the_request():
    request = downloader.DownloadRequest.from_payload({
        "url": "u",
        "cookies_path": "c",
        "output_dir": "o",
        "codec": "alac",
        "use_wrapper": 1,
        "overwrite": "",
    })
    assert request.codec == "alac"
    assert request.use_wrapper is True
    assert request.overwrite is False
    assert request.temp_dir is None


def test_handler_names_every_missing_field():
    result = downloader.handle_download({"url": "u"}, lambda event: None)
    assert result["message"] == "Missing: cookies_path, output_dir"


def test_cancel_stops_the_download_and_discards_partial_files(tmp_path, monkeypatch):
    temp = tmp_path / "tmp"

    def slow_main(args, standalone_mode=True):
        (temp / "partial.m4a").write_bytes(b"half")
        downloader.request_cancel()
        print("[INFO 00:00:01] Downloading track")  # the next log line stops it
        raise AssertionError("should have been cancelled")

    monkeypatch.setattr(downloader, "_multiprocessing_works", lambda: True)
    monkeypatch.setattr(downloader, "_capture_gamdl_logging", lambda tap: True)
    monkeypatch.setattr(downloader, "gamdl_main", slow_main)
    cookies = tmp_path / "cookies.txt"
    cookies.write_text("")

    result = downloader.download(
        downloader.DownloadRequest(
            url="u", cookies_path=str(cookies),
            output_dir=str(tmp_path / "out"), temp_dir=str(temp),
        )
    )
    assert result["code"] == "cancelled"
    assert not (temp / "partial.m4a").exists()
    assert (temp / "gamdl.log").exists()


def test_a_new_download_is_not_cancelled_by_an_old_request(tmp_path, monkeypatch):
    downloader.request_cancel()
    args = _captured_args(tmp_path, monkeypatch)
    assert args  # ran to completion


def _tracks(events):
    return [
        (e["index"], e["title"], e["state"], e["detail"], e["owned"])
        for e in events
        if e["event"] == "track"
    ]


def test_playlist_tracks_are_reported_one_by_one(tmp_path, monkeypatch):
    # A playlist is one job; the app lists each track under it from these.
    result, events = _run_with_output(
        tmp_path,
        monkeypatch,
        '[INFO 21:46:03] [Track   1/-  ] Downloading "GO TO HELL"',
        '[INFO 21:46:06] [Track   2/-  ] Downloading "change ur mind"',
        '[WARNING 21:46:14] [Track   2/-  ] Skipping "change ur mind": Media '
        "file already exists: change ur mind (already in your library)",
        '[INFO 21:46:16] [Track   3/-  ] Downloading "Intro"',
        '[WARNING 21:46:17] [Track   3/-  ] Skipping "Intro": '
        "Media is not streamable: 123",
        '[INFO 21:46:18] [Track   4/-  ] Downloading "Black Sheep"',
        "[INFO 21:46:28] Finished with 0 error(s)",
    )
    assert result["event"] == "done"
    assert _tracks(events) == [
        (1, "GO TO HELL", "downloading", "", False),
        (1, "GO TO HELL", "done", "", False),
        (2, "change ur mind", "downloading", "", False),
        (
            2,
            "change ur mind",
            "skipped",
            "Media file already exists: change ur mind (already in your library)",
            True,
        ),
        (3, "Intro", "downloading", "", False),
        (3, "Intro", "skipped", "Media is not streamable: 123", False),
        (4, "Black Sheep", "downloading", "", False),
        (4, "Black Sheep", "done", "", False),
    ]
    assert next(e for e in events if e["event"] == "track")["total"] is None


def test_a_failed_track_carries_its_cause(tmp_path, monkeypatch):
    result, events = _run_with_output(
        tmp_path,
        monkeypatch,
        '[INFO 00:00:01] [Track   1/2  ] Downloading "A"',
        '[ERROR 00:00:02] [Track   1/2  ] Error downloading "A"',
        "Traceback (most recent call last):",
        '  File "x.py", line 1, in f',
        "httpx.ConnectTimeout",
        '[INFO 00:00:03] [Track   2/2  ] Downloading "B"',
        "[INFO 00:00:04] Finished with 1 error(s)",
    )
    assert result["event"] == "error"
    tracks = _tracks(events)
    assert tracks[1] == (1, "A", "failed", 'Error downloading "A"', False)
    assert tracks[2] == (
        1, "A", "failed", 'Error downloading "A" (ConnectTimeout)', False
    )
    assert tracks[3:] == [
        (2, "B", "downloading", "", False),
        (2, "B", "done", "", False),
    ]
    assert events[[e["event"] for e in events].index("track")]["total"] == 2
