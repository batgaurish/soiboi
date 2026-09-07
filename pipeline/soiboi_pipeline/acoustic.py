"""Acoustic analysis: BPM and mood, computed from the audio file.

Ported from the reference ``acoustic_analyzer.py`` in the Apple Music archival
frontend, with changes for the on-device pipeline:

  * **Per-file sidecars** instead of a per-directory index. The pipeline
    downloads one file at a time and writes a sidecar next to it, so the Dart
    side can read it during a metadata scan without parsing a shared JSON file
    that may be mid-write from another download.

  * **bliss-audio, not Essentia.** Essentia has no Android build, so mood
    analysis only ever ran on desktop. A 220-track comparison (see
    ``docs/bliss-vs-essentia-220.jsonl`` and ``docs/report.py``) found bliss
    viable on Android (pure Rust, cross-compiles the same way as gamdl's
    native muxer), 8x faster, and a fix for a real bug in the old brightness
    calculation (see below) -- at the cost of BPM disagreeing with Essentia on
    about a quarter of tracks, mostly genuine octave/metrical-ratio ambiguity
    rather than noise. Decided: use bliss on both platforms so a mood rule
    means the same thing regardless of which device did the download.

  * **The native extension is optional**, same posture as Essentia had: every
    caller degrades to "no features" when ``_bliss_analyze`` fails to import
    (e.g. the Android build for this ABI hasn't been produced yet).

The mood heuristics are labelled ``source: local`` to distinguish them from
trained classifiers -- they are directional estimates from bliss's spectral
and rhythmic features, not a pretrained model. Unlike the Essentia version,
bliss has no onset-rate output, so these formulas use zero-crossing rate and
spectral flatness as the busyness/noisiness signal instead. They were ported
without ground truth for the mood axes themselves (the 220-track comparison
validated BPM and spectral centroid, not energy/danceable) -- directionally
reasonable, not verified accurate.

Brightness now uses bliss's whole-track spectral centroid. The Essentia
version read a single ~0.74s window near the start of the track and
correlated at r=0.086 against a true whole-track mean -- it was measuring
noise. This is a straightforward improvement, not a behaviour change to
preserve.
"""

import json
import os
import time

# The native extension is a top-level module (like gamdl's `_ammuxer`
# pattern, but not nested in a package -- bliss's build has no reason to
# be), installed by maturin on desktop and by a small standalone wheel on
# Android. Optional: the pipeline must still import and run without it, and
# every caller degrades to "no features" when it is absent (e.g. this
# platform/ABI has no compiled build yet).
try:
    import _bliss_analyze
    ANALYSIS_AVAILABLE = True
except Exception:
    ANALYSIS_AVAILABLE = False

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
    """
    if not ANALYSIS_AVAILABLE:
        return None
    try:
        raw = _bliss_analyze.analyze(path)
        bpm = raw.get("bpm")
        if bpm is None:
            return None

        features = {
            "bpm": round(bpm, 1),
            "source": "local",
        }
        features.update(_mood_estimates(raw))
        return features
    except Exception:
        return None


def _mood_estimates(raw):
    """Mood axes derived from bliss's spectral and rhythmic features.

    These are heuristics, not the trained classifiers AcousticBrainz used --
    labelled ``source: local`` so the difference stays visible. bliss has no
    onset-rate equivalent (the Essentia version's energy/danceable signal),
    so zero-crossing rate and spectral flatness stand in as the
    busyness/noisiness proxy: a higher ZCR means more percussive or
    distorted content, and higher flatness means a more noise-like spectrum
    (tonal, harmonic music -- most dance music -- sits at the low end).

    Directionally reasonable, not accuracy-verified: the 220-track comparison
    against Essentia validated BPM and spectral centroid, not these derived
    axes.
    """

    def clamp(value):
        return round(max(0.0, min(1.0, value)), 3)

    bpm = raw.get("bpm", 0.0)
    zcr = raw.get("zcr", 0.0)
    flatness = raw.get("flatness", 0.0)
    loudness_db = raw.get("loudness_db", -30.0)
    centroid_hz = raw.get("centroid_hz")

    bpm_norm = clamp(bpm / 180.0)
    zcr_norm = clamp(zcr / 0.15)
    flatness_norm = clamp(flatness / 0.5)
    # Mastered tracks mostly sit in -20..-3 dB on bliss's loudness scale;
    # louder (closer to 0) reads as more energetic.
    loudness_norm = clamp((loudness_db + 20.0) / 17.0)

    energy = clamp(zcr_norm * 0.4 + loudness_norm * 0.3 + bpm_norm * 0.3)

    result = {
        "energy": energy,
        "aggressive": clamp(energy * 0.6 + flatness_norm * 0.4),
        "relaxed": clamp(1.0 - energy),
        # Danceable music tends to be tonal and rhythmic rather than noisy,
        # so flatness contributes inversely.
        "danceable": clamp(bpm_norm * 0.6 + (1.0 - flatness_norm) * 0.4),
        "loudness": round(loudness_db, 3),
    }
    if centroid_hz is not None:
        # Same scale factor the Essentia version used, but now over a
        # whole-track mean instead of a single window near the intro.
        result["brightness"] = clamp(centroid_hz / 4000.0)
    return result


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
    if not ANALYSIS_AVAILABLE:
        return {
            "total": 0,
            "analysed": 0,
            "skipped": 0,
            "analysis_available": False,
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
        "analysis_available": True,
    }
