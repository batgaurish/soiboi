"""Tests for the pure parts of the Android wheel builder.

The builder itself downloads from PyPI, so only the pieces that decide what
goes into a wheel are exercised: pin relaxing and wheel assembly.
"""
import base64
import csv
import hashlib
import io
import zipfile

import build_android_wheels as builder


def test_relax_rewrites_only_the_pins_chaquopy_cannot_meet():
    assert builder.relax("pillow>=12.0") == builder.RELAXED_PINS["pillow"]
    assert builder.relax("Pycryptodome>=3.23; python_version>'3'") == (
        builder.RELAXED_PINS["pycryptodome"]
    )
    assert builder.relax("httpx>=0.28") == "httpx>=0.28"


def test_write_wheel_records_every_file_with_its_hash(tmp_path):
    body = b"print('hi')\n"
    path = builder.write_wheel(
        tmp_path, "demo", "1.0", {"demo/__init__.py": body}, b"Metadata-Version: 2.1\n"
    )
    assert path.name == "demo-1.0-py3-none-any.whl"

    with zipfile.ZipFile(path) as wheel:
        names = set(wheel.namelist())
        record = wheel.read("demo-1.0.dist-info/RECORD").decode()
        wheel_meta = wheel.read("demo-1.0.dist-info/WHEEL").decode()

    assert names == {
        "demo/__init__.py",
        "demo-1.0.dist-info/METADATA",
        "demo-1.0.dist-info/WHEEL",
        "demo-1.0.dist-info/RECORD",
    }
    rows = {row[0]: row for row in csv.reader(io.StringIO(record))}
    digest = base64.urlsafe_b64encode(hashlib.sha256(body).digest()).rstrip(b"=")
    assert rows["demo/__init__.py"] == [
        "demo/__init__.py", "sha256=" + digest.decode(), str(len(body)),
    ]
    # RECORD cannot hash itself, so its own row is left blank.
    assert rows["demo-1.0.dist-info/RECORD"] == ["demo-1.0.dist-info/RECORD", "", ""]
    assert "Root-Is-Purelib: true" in wheel_meta


def test_platform_wheels_are_not_purelib(tmp_path):
    path = builder.write_wheel(
        tmp_path, "native", "1.0", {"_x.so": b"\x7fELF"}, b"",
        tag="cp312-cp312-android_21_arm64_v8a",
    )
    with zipfile.ZipFile(path) as wheel:
        assert "Root-Is-Purelib: false" in wheel.read("native-1.0.dist-info/WHEEL").decode()
