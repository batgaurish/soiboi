"""The library scan and the artwork cache read tags through the pipeline, so both platforms agree on what a file is."""
import os

import mutagen.id3 as id3
import pytest
from mutagen.mp3 import MP3

from soiboi_pipeline import art, tags
from soiboi_pipeline.__main__ import handle

# A minimal valid MPEG-1 Layer III stream: 40 frames of 128 kbit/s, 44.1 kHz silence, so mutagen can parse length and bitrate.
_FRAME = bytes([0xFF, 0xFB, 0x90, 0x00]) + bytes(413)


def _make_mp3(path, *, title="Song", artist="Artist", album="Album", picture=None):
    with open(path, "wb") as handle_:
        handle_.write(_FRAME * 40)
    audio = MP3(path)
    audio.add_tags()
    audio.tags.add(id3.TIT2(encoding=3, text=title))
    audio.tags.add(id3.TPE1(encoding=3, text=artist))
    audio.tags.add(id3.TALB(encoding=3, text=album))
    audio.tags.add(id3.TRCK(encoding=3, text="3/12"))
    audio.tags.add(id3.TDRC(encoding=3, text="2019-05-01"))
    audio.tags.add(id3.USLT(encoding=3, lang="eng", desc="", text="hello\nworld"))
    if picture:
        audio.tags.add(id3.APIC(encoding=3, mime="image/jpeg", type=3, desc="", data=picture))
    audio.save()
    return str(path)


def test_reads_the_fields_the_app_stores(tmp_path):
    path = _make_mp3(tmp_path / "a.mp3")
    record = tags.read_one(path)
    assert record["title"] == "Song" and record["artist"] == "Artist" and record["album"] == "Album"
    assert record["track"] == 3 and record["year"] == 2019
    assert record["format"] == "MP3" and record["samplerate"] == 44100
    assert record["lyrics"] == "hello\nworld"
    assert record["duration_ms"] and record["bitrate"]


def test_unreadable_files_are_left_out_not_fatal(tmp_path):
    good = _make_mp3(tmp_path / "ok.mp3")
    junk = tmp_path / "junk.mp3"
    junk.write_bytes(b"not audio")
    events = []
    result = handle("read_tags", {"paths": [good, str(junk), str(tmp_path / "missing.mp3")]}, events.append)
    assert result["event"] == "done" and [t["path"] for t in result["tracks"]] == [good]


def test_requires_paths():
    assert handle("read_tags", {})["event"] == "error"


def test_extracts_cover_once_and_remembers_coverless_files(tmp_path):
    jpeg = b"\xff\xd8\xff\xe0" + bytes(64) + b"\xff\xd9"
    with_art = _make_mp3(tmp_path / "art.mp3", picture=jpeg)
    plain = _make_mp3(tmp_path / "plain.mp3")
    out = str(tmp_path / "cache")
    first = handle("cover", {"path": with_art, "out_dir": out})
    assert first["cover"] and open(first["cover"], "rb").read() == jpeg
    assert handle("cover", {"path": with_art, "out_dir": out})["cover"] == first["cover"]
    assert handle("cover", {"path": plain, "out_dir": out})["cover"] is None
    assert any(name.endswith(".none") for name in os.listdir(out))


def test_missing_file_has_no_cover(tmp_path):
    assert handle("cover", {"path": str(tmp_path / "nope.mp3"), "out_dir": str(tmp_path)})["cover"] is None
