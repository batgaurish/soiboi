"""Tests for command dispatch and the two transports' entry points."""
import json

from soiboi_pipeline import __main__ as entry
from soiboi_pipeline import __version__


def test_version_needs_no_handler_module():
    assert entry.handle("version") == {"event": "done", "version": __version__}


def test_unknown_command_is_an_error():
    result = entry.handle("reticulate")
    assert result == {"event": "error", "code": "unknown_command", "message": "reticulate"}


def test_each_command_validates_its_payload():
    for command in ("analyze", "playlist", "apple_playlists", "apple_playlist_tracks", "download"):
        assert entry.handle(command, {})["code"] == "bad_request", command


def test_main_prints_one_json_line_per_event(capsys):
    assert entry.main(["version"]) == 0
    lines = capsys.readouterr().out.splitlines()
    assert [json.loads(line)["event"] for line in lines] == ["done"]


def test_main_rejects_bad_json(capsys):
    assert entry.main(["download", "{not json"]) == 2
    assert json.loads(capsys.readouterr().out)["code"] == "bad_json"


def test_main_exit_status_follows_the_result(capsys):
    assert entry.main(["reticulate"]) == 1


def test_handle_json_round_trips():
    assert json.loads(entry.handle_json("version", "{}"))["version"] == __version__


def test_handle_json_reports_bad_json():
    assert json.loads(entry.handle_json("version", "{"))["code"] == "bad_json"


def test_handle_json_turns_exceptions_into_errors(monkeypatch):
    def explode(payload, emit):
        raise RuntimeError("boom")

    monkeypatch.setattr(entry, "_handler_for", lambda command: explode)
    result = json.loads(entry.handle_json("download", "{}"))
    assert result == {"event": "error", "code": "pipeline", "message": "boom"}


def test_a_failing_progress_callback_does_not_abort_the_command(monkeypatch):
    def chatty(payload, emit):
        emit({"event": "progress", "progress": 1, "status": "x"})
        return {"event": "done"}

    def broken_callback(text):
        raise RuntimeError("UI went away")

    monkeypatch.setattr(entry, "_handler_for", lambda command: chatty)
    result = json.loads(entry.handle_json("download", "{}", broken_callback))
    assert result == {"event": "done"}


def test_progress_reaches_the_callback_as_json(monkeypatch):
    seen = []

    def chatty(payload, emit):
        emit({"event": "progress", "progress": 5, "status": "Starting"})
        return {"event": "done"}

    monkeypatch.setattr(entry, "_handler_for", lambda command: chatty)
    entry.handle_json("download", "{}", seen.append)
    assert [json.loads(text)["status"] for text in seen] == ["Starting"]
