#!/usr/bin/env python3
"""Writes test/fixtures/cover_colors.json from the covers in a music folder.

The readable-palettes test runs real covers through the app's colour code,
but only needs each cover's average colour, not the artwork: the app itself
reduces a cover to that one colour before building a palette from it. So
the fixture holds colours and nothing else, no titles or file names.

The average is taken the way the app takes it: the cover scaled to 20 by 20,
fully transparent pixels counted as mid grey, each channel averaged.

Usage (from the repo root, with the pipeline's own environment, which has
Pillow and mutagen):

    .pipeline-venv/bin/python tools/cover_colors.py ~/Music [--count 50]
"""
import argparse
import io
import json
import os
import random
import sys

try:
    from PIL import Image
    import mutagen
    from mutagen.flac import FLAC
    from mutagen.id3 import ID3
    from mutagen.mp4 import MP4
except ImportError as exc:  # pragma: no cover - a setup problem, said plainly
    sys.exit(f"Needs Pillow and mutagen ({exc}); use .pipeline-venv/bin/python")

AUDIO = {".m4a", ".mp4", ".aac", ".alac", ".mp3", ".flac", ".ogg", ".opus"}
FOLDER_ART = ("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg")
FIXTURE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "test", "fixtures",
    "cover_colors.json",
)


def embedded_art(path: str) -> bytes | None:
    """The first embedded picture in an audio file, if it has one."""
    try:
        audio = mutagen.File(path)
    except Exception:  # unreadable or not audio after all
        return None
    if isinstance(audio, MP4):
        covers = (audio.tags or {}).get("covr") or []
        return bytes(covers[0]) if covers else None
    if isinstance(audio, FLAC):
        return audio.pictures[0].data if audio.pictures else None
    tags = getattr(audio, "tags", None)
    if isinstance(tags, ID3):
        for frame in tags.getall("APIC"):
            return frame.data
    pictures = getattr(audio, "pictures", None)  # Ogg Vorbis and Opus
    return pictures[0].data if pictures else None


def average(data: bytes) -> str | None:
    """#RRGGBB, averaged the way the app averages a cover."""
    try:
        image = Image.open(io.BytesIO(data)).convert("RGBA")
    except Exception:
        return None
    image = image.resize((20, 20), Image.Resampling.BILINEAR)
    totals = [0, 0, 0]
    flat = getattr(image, "get_flattened_data", None)  # Pillow 12.3 and later
    pixels = list(flat() if flat else image.getdata())
    for red, green, blue, alpha in pixels:
        for i, value in enumerate((red, green, blue) if alpha else (128, 128, 128)):
            totals[i] += value
    return "#" + "".join(f"{round(t / len(pixels)):02x}" for t in totals)


def covers(root: str):
    """One average colour per folder, from folder art or an embedded cover."""
    for folder, _dirs, files in os.walk(root):
        names = {name.lower(): name for name in files}
        data = None
        for candidate in FOLDER_ART:
            if candidate in names:
                with open(os.path.join(folder, names[candidate]), "rb") as fh:
                    data = fh.read()
                break
        if data is None:
            for name in sorted(files):
                if os.path.splitext(name)[1].lower() in AUDIO:
                    data = embedded_art(os.path.join(folder, name))
                    if data:
                        break
        if data:
            colour = average(data)
            if colour:
                yield colour


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("music_folder")
    parser.add_argument("--count", type=int, default=50)
    parser.add_argument("--output", default=FIXTURE)
    args = parser.parse_args()

    found = sorted(set(covers(os.path.expanduser(args.music_folder))))
    if len(found) < args.count:
        sys.exit(f"Only {len(found)} covers found; the test wants {args.count}.")
    # A spread across the library, the same each run for the same library.
    chosen = sorted(random.Random(0).sample(found, args.count))
    with open(args.output, "w", encoding="utf-8") as fh:
        json.dump(
            {"source": "tools/cover_colors.py", "colors": chosen},
            fh,
            indent=2,
        )
        fh.write("\n")
    print(f"Wrote {len(chosen)} cover colours to {os.path.normpath(args.output)}")


if __name__ == "__main__":
    main()
