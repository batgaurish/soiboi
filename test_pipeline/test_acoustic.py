"""Tests for the acoustic analysis module.

Real feature extraction needs the compiled `_bliss_analyze` extension and a
real audio file, so the core tests here cover the sidecar format and the
directory scanning logic rather than the analysis output itself. The mood
formulas are exercised manually against real tracks -- see the module
docstring in acoustic.py for how they were derived.
"""
from soiboi_pipeline import acoustic, runtime


def test_sidecar_path():
    assert acoustic.sidecar_path("/music/track.m4a") == "/music/track.m4a.soiboi-acoustic.json"


def test_write_and_read_sidecar(tmp_path):
    audio = tmp_path / "track.m4a"
    audio.write_bytes(b"")
    features = {"bpm": 122.9, "energy": 0.65, "source": "local"}
    acoustic.write_sidecar(str(audio), features)

    loaded = acoustic.read_sidecar(str(audio))
    assert loaded is not None
    assert loaded["bpm"] == 122.9
    assert loaded["energy"] == 0.65
    assert loaded["source"] == "local"
    assert loaded["version"] == acoustic.SIDECAR_VERSION


def test_read_sidecar_missing(tmp_path):
    assert acoustic.read_sidecar(str(tmp_path / "nope.m4a")) is None


def test_read_sidecar_corrupt(tmp_path):
    audio = tmp_path / "track.m4a"
    audio.write_bytes(b"")
    sidecar = tmp_path / "track.m4a.soiboi-acoustic.json"
    sidecar.write_text("not json")
    assert acoustic.read_sidecar(str(audio)) is None


def test_write_sidecar_atomic(tmp_path):
    """The sidecar is written via temp-then-rename, so no partial file on crash."""
    audio = tmp_path / "track.m4a"
    audio.write_bytes(b"")
    acoustic.write_sidecar(str(audio), {"bpm": 100})
    # No .tmp file should remain
    assert not (tmp_path / "track.m4a.soiboi-acoustic.json.tmp").exists()


def test_has_sidecar(tmp_path):
    audio = tmp_path / "track.m4a"
    audio.write_bytes(b"")
    assert not acoustic.has_sidecar(str(audio))
    acoustic.write_sidecar(str(audio), {"bpm": 100})
    assert acoustic.has_sidecar(str(audio))


def test_write_sidecar_with_none_records_empty(tmp_path):
    """A failed analysis writes an empty sidecar so it is not retried."""
    audio = tmp_path / "track.m4a"
    audio.write_bytes(b"")
    acoustic.write_sidecar(str(audio), None)
    loaded = acoustic.read_sidecar(str(audio))
    assert loaded is not None
    assert loaded["version"] == acoustic.SIDECAR_VERSION
    assert "bpm" not in loaded


def test_iter_audio_files(tmp_path):
    (tmp_path / "a.m4a").write_bytes(b"")
    (tmp_path / "b.mp3").write_bytes(b"")
    (tmp_path / "c.txt").write_bytes(b"")
    (tmp_path / "sub").mkdir()
    (tmp_path / "sub" / "d.flac").write_bytes(b"")

    found = sorted(acoustic.iter_audio_files(str(tmp_path)))
    assert len(found) == 3
    assert all(f.endswith((".m4a", ".mp3", ".flac")) for f in found)


def test_analyze_directory_skips_existing_sidecars(tmp_path):
    """A file with a sidecar is not re-analysed."""
    audio = tmp_path / "track.m4a"
    audio.write_bytes(b"")
    acoustic.write_sidecar(str(audio), {"bpm": 100})

    result = acoustic.analyze_directory(str(tmp_path))
    assert result["total"] == 1
    assert result["analysed"] == 0  # skipped because sidecar exists
    assert result["skipped"] == 1


def test_analyze_directory_no_bliss(tmp_path, monkeypatch):
    """Without the native extension, the function returns a zero-summary
    without failing."""
    audio = tmp_path / "track.m4a"
    audio.write_bytes(b"")
    monkeypatch.setattr(acoustic, "ANALYSIS_AVAILABLE", False)

    result = acoustic.analyze_directory(str(tmp_path))
    assert result["analysis_available"] is False
    assert result["analysed"] == 0
    # No sidecar written when analysis is unavailable
    assert not acoustic.has_sidecar(str(audio))


def test_analyze_file_without_bliss(tmp_path, monkeypatch):
    monkeypatch.setattr(acoustic, "ANALYSIS_AVAILABLE", False)
    assert acoustic.analyze_file("/nonexistent.m4a") is None


def test_analyze_file_on_decode_failure(tmp_path):
    """A file bliss cannot decode (missing, corrupt, DRM-locked) returns
    ``None`` rather than raising."""
    if not acoustic.ANALYSIS_AVAILABLE:
        return  # can't exercise the real decode path without the extension
    assert acoustic.analyze_file(str(tmp_path / "nonexistent.m4a")) is None


def test_mood_estimates_are_clamped_to_unit_range():
    raw = {"bpm": 400.0, "zcr": 0.9, "flatness": 0.9, "loudness_db": 0.0,
           "centroid_hz": 999999.0}
    result = acoustic._mood_estimates(raw)
    for key in ("energy", "aggressive", "relaxed", "danceable", "brightness"):
        assert 0.0 <= result[key] <= 1.0


def test_mood_estimates_missing_centroid_omits_brightness():
    raw = {"bpm": 120.0, "zcr": 0.05, "flatness": 0.2, "loudness_db": -10.0}
    result = acoustic._mood_estimates(raw)
    assert "brightness" not in result
    assert "energy" in result


def test_mood_estimates_energy_and_relaxed_are_complementary():
    raw = {"bpm": 128.0, "zcr": 0.1, "flatness": 0.3, "loudness_db": -6.0,
           "centroid_hz": 2000.0}
    result = acoustic._mood_estimates(raw)
    assert result["relaxed"] == round(1.0 - result["energy"], 3)


def test_capabilities_reports_acoustic():
    caps = runtime.capabilities()
    assert "acoustic_analysis" in caps
    assert isinstance(caps["acoustic_analysis"]["available"], bool)


def test_handler_requires_a_directory():
    assert acoustic.handle_analyze({}, lambda event: None)["code"] == "bad_request"


def test_handler_reports_missing_analyser(tmp_path, monkeypatch):
    monkeypatch.setattr(acoustic, "ANALYSIS_AVAILABLE", False)
    result = acoustic.handle_analyze({"directory": str(tmp_path)}, lambda event: None)
    assert result["code"] == "no_analysis"


def test_empty_version_1_sidecar_is_retried(tmp_path):
    # The v1 analyser could only decode AAC and marked everything else as
    # unreadable. Those files deserve one more attempt.
    audio = tmp_path / "track.flac"
    audio.write_bytes(b"")
    (tmp_path / "track.flac.soiboi-acoustic.json").write_text('{"version": 1}')
    assert not acoustic.has_sidecar(str(audio))


def test_failed_analysis_records_why_and_is_settled(tmp_path):
    audio = tmp_path / "track.opus"
    audio.write_bytes(b"")
    acoustic.write_sidecar(str(audio), None, error_reason="unsupported codec")
    assert acoustic.read_sidecar(str(audio))["error"] == "unsupported codec"
    assert acoustic.has_sidecar(str(audio))


def test_unwritable_folder_falls_back_to_the_private_store(tmp_path, monkeypatch):
    music = tmp_path / "music"
    music.mkdir()
    audio = music / "track.m4a"
    audio.write_bytes(b"")
    store = tmp_path / "store"
    real_write = acoustic._write_json_atomically

    def deny_music_folder(path, data):
        if path.startswith(str(music)):
            raise PermissionError(13, "Permission denied")
        real_write(path, data)

    monkeypatch.setattr(acoustic, "_write_json_atomically", deny_music_folder)
    written = acoustic.write_sidecar(str(audio), {"bpm": 90}, store_dir=str(store))
    assert written.startswith(str(store))
    assert acoustic.read_sidecar(str(audio), str(store))["bpm"] == 90


def test_one_unwritable_file_does_not_stop_the_folder(tmp_path, monkeypatch):
    for name in ("a.m4a", "b.m4a"):
        (tmp_path / name).write_bytes(b"")
    monkeypatch.setattr(acoustic, "ANALYSIS_AVAILABLE", True)
    monkeypatch.setattr(acoustic, "_analyze", lambda path: ({"bpm": 100.0}, None))

    def deny(path, data):
        raise PermissionError(13, "Permission denied")

    monkeypatch.setattr(acoustic, "_write_json_atomically", deny)
    result = acoustic.analyze_directory(str(tmp_path))
    assert result["analysed"] == 2
    assert result["unwritable"] == 2


def test_a_download_analyses_only_what_it_wrote(tmp_path, monkeypatch):
    import os
    old, new = tmp_path / "old.m4a", tmp_path / "new.m4a"
    old.write_bytes(b"")
    new.write_bytes(b"")
    os.utime(old, (1000, 1000))
    monkeypatch.setattr(acoustic, "ANALYSIS_AVAILABLE", True)
    monkeypatch.setattr(acoustic, "_analyze", lambda path: ({"bpm": 90.0}, None))
    result = acoustic.analyze_directory(str(tmp_path), since=2000)
    assert result["total"] == 1
    assert acoustic.has_sidecar(str(new)) and not acoustic.has_sidecar(str(old))
