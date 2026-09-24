"""Tests for installing Apple's libraries from a user-supplied Apple Music APK."""
import hashlib
import io
import json
import zipfile

import pytest

from soiboi_pipeline import wrapper_libs

LIBS = {"libA.so": b"alpha", "libB.so": b"bravo"}


def _manifest(tmp_path, libs=LIBS):
    path = tmp_path / "LIBS_VERSION.json"
    path.write_text(json.dumps({
        "apple_music": {"version": "3.6.0-beta", "build": "1109"},
        "libs": {"x86_64": {n: hashlib.sha256(d).hexdigest() for n, d in libs.items()}},
    }))
    return str(path)


def _apk(libs, arch="x86_64"):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as apk:
        for name, data in libs.items():
            apk.writestr(f"lib/{arch}/{name}", data)
    return buffer.getvalue()


def _apkm(tmp_path, libs=LIBS):
    path = tmp_path / "music.apkm"
    with zipfile.ZipFile(path, "w") as bundle:
        bundle.writestr("base.apk", b"")
        bundle.writestr("split_config.x86_64.apk", _apk(libs))
    return str(path)


def test_installs_every_pinned_library_from_a_bundle(tmp_path):
    out = tmp_path / "lib64"
    installed = wrapper_libs.install(_apkm(tmp_path), _manifest(tmp_path), "x86_64", str(out))
    assert installed == ["libA.so", "libB.so"]
    assert (out / "libA.so").read_bytes() == b"alpha"


def test_accepts_a_single_apk_too(tmp_path):
    apk = tmp_path / "music.apk"
    apk.write_bytes(_apk(LIBS))
    out = tmp_path / "lib64"
    wrapper_libs.install(str(apk), _manifest(tmp_path), "x86_64", str(out))
    assert (out / "libB.so").exists()


def test_a_different_build_is_rejected_and_nothing_is_written(tmp_path):
    out = tmp_path / "lib64"
    tampered = _apkm(tmp_path, {"libA.so": b"alpha", "libB.so": b"other build"})
    with pytest.raises(wrapper_libs.WrongApk, match="build 1109"):
        wrapper_libs.install(tampered, _manifest(tmp_path), "x86_64", str(out))
    assert not out.exists()


def test_a_missing_library_names_the_build_needed(tmp_path):
    partial = _apkm(tmp_path, {"libA.so": b"alpha"})
    with pytest.raises(wrapper_libs.WrongApk, match="libB.so is missing"):
        wrapper_libs.install(partial, _manifest(tmp_path), "x86_64", str(tmp_path / "o"))


def test_handler_reports_a_non_apk_as_wrong_apk(tmp_path):
    junk = tmp_path / "song.mp3"
    junk.write_bytes(b"ID3")
    result = wrapper_libs.handle_install({
        "apk_path": str(junk), "libs_version": _manifest(tmp_path),
        "arch": "x86_64", "out_dir": str(tmp_path / "o"),
    }, lambda event: None)
    assert result["code"] == "wrong_apk"
