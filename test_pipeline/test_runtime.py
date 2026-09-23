"""Tests for capability probing: what the app shows before a download is tried."""
from soiboi_pipeline import runtime


def _probe_all_present(name):
    return {"available": True, "version": "1", "path": f"/{name}"}


def test_can_download_needs_every_module_and_the_muxer(monkeypatch):
    monkeypatch.setattr(runtime, "_probe", _probe_all_present)
    assert runtime.capabilities()["can_download"] is True


def test_a_missing_module_is_named(monkeypatch):
    def probe(name):
        if name == "m3u8":
            return {"available": False, "error": "No module named 'm3u8'"}
        return _probe_all_present(name)

    monkeypatch.setattr(runtime, "_probe", probe)
    caps = runtime.capabilities()
    assert caps["missing"] == ["m3u8"]
    assert caps["can_download"] is False


def test_a_missing_muxer_blocks_downloads_on_its_own(monkeypatch):
    def probe(name):
        if name == runtime.NATIVE_MUXER:
            return {"available": False, "error": "wrong ELF class"}
        return _probe_all_present(name)

    monkeypatch.setattr(runtime, "_probe", probe)
    caps = runtime.capabilities()
    assert caps["missing"] == []
    assert caps["can_download"] is False


def test_probe_reports_an_import_failure_instead_of_raising():
    result = runtime._probe("soiboi_pipeline_no_such_module")
    assert result["available"] is False
    assert "soiboi_pipeline_no_such_module" in result["error"]


def test_android_is_detected_from_its_environment(monkeypatch):
    monkeypatch.delenv("ANDROID_ROOT", raising=False)
    monkeypatch.delenv("ANDROID_DATA", raising=False)
    assert runtime.is_android() is False
    monkeypatch.setenv("ANDROID_DATA", "/data")
    assert runtime.is_android() is True


def test_handler_wraps_capabilities_in_a_done_event(monkeypatch):
    monkeypatch.setattr(runtime, "_probe", _probe_all_present)
    result = runtime.handle_capabilities({}, lambda event: None)
    assert result["event"] == "done"
    assert result["can_download"] is True
