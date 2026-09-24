"""tools/cover_colors.py: average colours for the readable-palettes test."""
import io
import json

import pytest

PIL = pytest.importorskip("PIL")
from PIL import Image  # noqa: E402

import cover_colors  # noqa: E402


def _jpeg(colour, size=40):
    buffer = io.BytesIO()
    Image.new("RGB", (size, size), colour).save(buffer, "PNG")
    return buffer.getvalue()


def test_average_of_a_flat_cover_is_its_colour():
    assert cover_colors.average(_jpeg((200, 40, 10))) == "#c8280a"


def test_transparent_pixels_count_as_mid_grey():
    buffer = io.BytesIO()
    Image.new("RGBA", (20, 20), (0, 0, 0, 0)).save(buffer, "PNG")
    assert cover_colors.average(buffer.getvalue()) == "#808080"


def test_unreadable_art_is_skipped():
    assert cover_colors.average(b"not an image") is None


def test_writes_colours_only(tmp_path, monkeypatch):
    for i in range(3):
        album = tmp_path / f"album{i}"
        album.mkdir()
        (album / "cover.jpg").write_bytes(_jpeg((i * 60, 100, 200)))
    output = tmp_path / "fixture.json"
    monkeypatch.setattr(
        "sys.argv",
        ["cover_colors.py", str(tmp_path), "--count", "3", "--output", str(output)],
    )
    cover_colors.main()
    written = json.loads(output.read_text())
    assert written["colors"] == ["#0064c8", "#3c64c8", "#7864c8"]
    assert set(written) == {"source", "colors"}


def test_refuses_too_few_covers(tmp_path, monkeypatch):
    monkeypatch.setattr("sys.argv", ["cover_colors.py", str(tmp_path)])
    with pytest.raises(SystemExit):
        cover_colors.main()
