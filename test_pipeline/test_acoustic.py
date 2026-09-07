"""Tests for the acoustic analysis module.

Real feature extraction needs the compiled `_bliss_analyze` extension and a
real audio file, so the core tests here cover the sidecar format and the
directory scanning logic rather than the analysis output itself. The mood
formulas are exercised manually against real tracks -- see the module
docstring in acoustic.py for how they were derived.
"""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pipeline"))

from soiboi_pipeline import acoustic  # noqa: E402


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
    from soiboi_pipeline import runtime

    caps = runtime.capabilities()
    assert "acoustic_analysis" in caps
    assert isinstance(caps["acoustic_analysis"]["available"], bool)
