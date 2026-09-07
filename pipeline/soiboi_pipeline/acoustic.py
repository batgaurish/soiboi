"""Acoustic analysis: BPM, key and mood, computed from the audio file.

Ported from the reference ``acoustic_analyzer.py`` in the Apple Music archival
frontend, with two changes for the on-device pipeline:

  * **Per-file sidecars** instead of a per-directory index. The pipeline
    downloads one file at a time and writes a sidecar next to it, so the Dart
    side can read it during a metadata scan without parsing a shared JSON file
    that may be mid-write from another download.

  * **Essentia is optional.** On Android there is no manylinux wheel, so
    importing this module must not fail. Every caller degrades to "no
    features" when essentia is absent, and a track downloaded on the phone
    simply gets no mood data until it is analysed on a desktop.

The mood heuristics are labelled ``source: local`` to distinguish them from
trained classifiers. AcousticBrainz used Essentia's pretrained SVM models for
mood; without them these are directional estimates from loudness, onset rate,
spectral centroid and tempo. They cover the whole library and are
directionally sound, but a trained model would be better. Swapping in
Essentia's pretrained mood models needs only ``_mood_estimates`` to change.
"""

import json
import os
import time

# Essentia is an optional dependency: the pipeline must still import and
# run without it, and every caller degrades to "no features" when it is
# absent.
try:
    import essentia
    essentia.log.warningActive = False
    essentia.log.infoActive = False
    import essentia.standard as es
    ESSENTIA_AVAILABLE = True
except Exception:
    ESSENTIA_AVAILABLE = False

SIDECAR_SUFFIX = ".soiboi-acoustic.json"
SIDECAR_VERSION = 1

AUDIO_EXTENSIONS = {
    ".m4a", ".mp3", ".flac", ".ogg", ".opus", ".wav", ".aiff", ".alac",
}


def sidecar_path(audio_path):
    """The sidecar filename for [audio_path]."""
    return audio_path + SIDECAR_SUFFIX


def write_sidecar(audio_path, features):
    """Write features atomically to a sidecar next to [audio_path].

    A temp-then-rename keeps a half-written file from being read if the
    process is killed mid-write.
    """
    path = sidecar_path(audio_path)
    tmp = path + ".tmp"
    data = {"version": SIDECAR_VERSION, "updated": int(time.time())}
    if features:
        data.update(features)
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
    os.replace(tmp, path)


def read_sidecar(audio_path):
    """Read the sidecar for [audio_path], or ``None`` if absent or corrupt."""
    path = sidecar_path(audio_path)
    if not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def has_sidecar(audio_path):
    return os.path.exists(sidecar_path(audio_path))


def analyze_file(path):
    """Acoustic features for one audio file, or ``None`` if it cannot be
    analysed.

    Returns the same feature names the reference implementation produces, so
    the two sources are interchangeable to callers.
    """
    if not ESSENTIA_AVAILABLE:
        return None
    try:
        audio = es.MonoLoader(filename=path, sampleRate=44100)()
        if len(audio) < 44100:  # under a second: not worth trusting
            return None

        bpm, _beats, beat_confidence, _, _ = es.RhythmExtractor2013(
            method="multifeature"
        )(audio)
        key, scale, key_strength = es.KeyExtractor()(audio)

        features = {
            "bpm": round(float(bpm), 1),
            "bpm_confidence": round(float(beat_confidence), 2),
            "key": f"{key} {scale}",
            "key_strength": round(float(key_strength), 2),
            "source": "local",
        }
        features.update(_mood_estimates(audio, float(bpm)))
        return features
    except Exception:
        return None


def _mood_estimates(audio, bpm):
    """Mood axes derived from signal statistics.

    These are heuristics over loudness, spectral brightness, onset density
    and tempo -- not the trained classifiers AcousticBrainz used. They are
    labelled ``source: local`` precisely so the difference stays visible: the
    numbers are directionally sound and cover the whole library, but a trained
    model would be better. Swapping in Essentia's pretrained mood models is
    the obvious upgrade and needs only this function to change.
    """
    try:
        loudness = float(es.Loudness()(audio))
        onset_rate = float(es.OnsetRate()(audio)[1])
        centroid = float(es.Centroid(range=22050)(es.Spectrum()(
            es.Windowing(type="hann")(
                audio[:32768] if len(audio) >= 32768 else audio
            )
        )))
    except Exception:
        return {}

    def clamp(value):
        return round(max(0.0, min(1.0, value)), 3)

    # Brightness and density drive perceived energy; tempo reinforces it.
    energy = clamp((onset_rate / 6.0) * 0.6 + (bpm / 180.0) * 0.4)
    brightness = clamp(centroid / 4000.0)

    return {
        "energy": energy,
        "brightness": brightness,
        "aggressive": clamp(energy * 0.7 + brightness * 0.3),
        "relaxed": clamp(1.0 - energy),
        "danceable": clamp((bpm / 140.0) * 0.6 + (onset_rate / 6.0) * 0.4),
        "loudness": round(loudness, 3),
    }


def iter_audio_files(directory):
    """Yield audio file paths under [directory], recursively."""
    for root, _dirs, files in os.walk(directory):
        for name in files:
            if os.path.splitext(name)[1].lower() in AUDIO_EXTENSIONS:
                yield os.path.join(root, name)


def analyze_directory(directory, emit=None, limit=None):
    """Analyse files missing a sidecar and write one for each.

    Bounded by [limit] so a caller can run this in slices. Returns a summary
    of what was done. Used both as a backfill command and called from the
    downloader after a successful download.
    """
    if not ESSENTIA_AVAILABLE:
        return {
            "total": 0,
            "analysed": 0,
            "skipped": 0,
            "essentia_available": False,
        }

    pending = []
    total = 0
    for path in iter_audio_files(directory):
        total += 1
        if not has_sidecar(path):
            pending.append(path)

    if limit is not None:
        pending = pending[:limit]

    analysed = 0
    for i, path in enumerate(pending):
        features = analyze_file(path)
        if features is not None:
            write_sidecar(path, features)
            analysed += 1
        else:
            # Record failures as an empty sidecar so a corrupt or DRM-locked
            # file is not retried on every pass.
            write_sidecar(path, None)
        if emit:
            emit({
                "event": "progress",
                "progress": 90 + int(10 * (i + 1) / max(len(pending), 1)),
                "status": f"Analyzing audio ({i + 1}/{len(pending)})",
            })

    return {
        "total": total,
        "analysed": analysed,
        "skipped": total - len(pending),
        "pending": len(pending) - analysed,
        "essentia_available": True,
    }
