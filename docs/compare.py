"""Run Essentia and bliss over the same tracks and record both results.

The question this answers: if Android analysed with bliss (pure Rust, so it
cross-compiles) instead of Essentia (no Android build), how different would the
numbers be? Disagreement here is what a user would see as a track landing in the
wrong mood playlist depending on which device downloaded it.

Writes one JSON object per track to the output file, so a run can be interrupted
and still analysed.
"""
import json
import os
import random
import subprocess
import sys
import time
from collections import defaultdict

BLISS = "/tmp/bliss-compare/target/release/bliss_analyze"

import essentia
essentia.log.warningActive = False
essentia.log.infoActive = False
import essentia.standard as es


def stratified_sample(root, total, per_artist=2, seed=7):
    """Spread the sample across artist folders rather than taking the first N.

    A contiguous slice of a library sorted by artist is a sample of one or two
    genres, which is exactly the case where a tempo estimator looks best or
    worst. Sampling across folders keeps the Bollywood, EDM and rock in.
    """
    by_artist = defaultdict(list)
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if name.lower().endswith(".m4a"):
                rel = os.path.relpath(dirpath, root)
                artist = rel.split(os.sep)[0] if rel != "." else "_"
                by_artist[artist].append(os.path.join(dirpath, name))

    rng = random.Random(seed)
    picked = []
    for artist in sorted(by_artist):
        tracks = sorted(by_artist[artist])
        rng.shuffle(tracks)
        picked.extend(tracks[:per_artist])
    rng.shuffle(picked)
    return picked[:total], len(by_artist)


def essentia_features(path):
    started = time.time()
    audio = es.MonoLoader(filename=path, sampleRate=44100)()
    if len(audio) < 44100:
        return None

    bpm, _beats, confidence, _, _ = es.RhythmExtractor2013(method="multifeature")(audio)
    key, scale, key_strength = es.KeyExtractor()(audio)
    loudness = float(es.Loudness()(audio))
    onset_rate = float(es.OnsetRate()(audio)[1])

    # Exactly what acoustic.py does today: one 32768-sample window from the
    # start of the track. Kept identical so the comparison reflects the
    # shipping behaviour, not an improved version of it.
    centroid_head = float(es.Centroid(range=22050)(es.Spectrum()(
        es.Windowing(type="hann")(
            audio[:32768] if len(audio) >= 32768 else audio
        )
    )))

    # A whole-track mean, which is what bliss reports, so the two can be
    # compared on equal terms as well.
    frames = []
    spectrum, window = es.Spectrum(), es.Windowing(type="hann")
    centroid = es.Centroid(range=22050)
    for frame in es.FrameGenerator(audio, frameSize=2048, hopSize=1024):
        frames.append(float(centroid(spectrum(window(frame)))))
    centroid_mean = sum(frames) / len(frames) if frames else 0.0

    return {
        "bpm": float(bpm),
        "bpm_confidence": float(confidence),
        "key": f"{key} {scale}",
        "key_strength": float(key_strength),
        "loudness": loudness,
        "onset_rate": onset_rate,
        "centroid_head_hz": centroid_head,
        "centroid_mean_hz": centroid_mean,
        "duration_seconds": len(audio) / 44100.0,
        "seconds": time.time() - started,
    }


def bliss_features(path):
    started = time.time()
    result = subprocess.run([BLISS, path], capture_output=True, text=True, timeout=180)
    if result.returncode != 0 or not result.stdout.strip():
        return {"error": result.stderr.strip()[:200] or "no output"}
    data = json.loads(result.stdout.strip().splitlines()[0])
    data["seconds"] = time.time() - started
    return data


def main():
    root = os.path.expanduser("~/Music")
    total = int(sys.argv[1]) if len(sys.argv) > 1 else 200
    out_path = sys.argv[2] if len(sys.argv) > 2 else "pairs.jsonl"

    tracks, artists = stratified_sample(root, total)
    print(f"{len(tracks)} tracks sampled across {artists} artist folders", flush=True)

    with open(out_path, "w", encoding="utf-8") as out:
        for i, path in enumerate(tracks, 1):
            row = {"path": os.path.relpath(path, root)}
            try:
                row["essentia"] = essentia_features(path)
            except Exception as exc:
                row["essentia"] = {"error": str(exc)[:200]}
            try:
                row["bliss"] = bliss_features(path)
            except Exception as exc:
                row["bliss"] = {"error": str(exc)[:200]}
            out.write(json.dumps(row) + "\n")
            out.flush()

            e, b = row.get("essentia") or {}, row.get("bliss") or {}
            if "bpm" in e and "bpm" in b:
                print(
                    f"[{i}/{len(tracks)}] essentia {e['bpm']:6.1f} | "
                    f"bliss {b['bpm']:6.1f} | {row['path'][:58]}",
                    flush=True,
                )
            else:
                print(f"[{i}/{len(tracks)}] FAILED {row['path'][:58]}", flush=True)


if __name__ == "__main__":
    main()
