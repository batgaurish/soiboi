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

import hashlib
import json
import os
import time
from collections.abc import Iterator

from .protocol import Emit, Event, JsonValue, Payload, done, error
from .protocol import missing_fields, progress

# The native extension is a top-level module (like gamdl's `_ammuxer`
# pattern, but not nested in a package -- bliss's build has no reason to
# be), installed by maturin on desktop and by a small standalone wheel on
# Android. Optional: the pipeline must still import and run without it, and
# every caller degrades to "no features" when it is absent (e.g. this
# platform/ABI has no compiled build yet).
try:
    import _bliss_analyze
    ANALYSIS_AVAILABLE = True
except ImportError:
    ANALYSIS_AVAILABLE = False

SIDECAR_SUFFIX = ".soiboi-acoustic.json"
SIDECAR_VERSION = 2

AUDIO_EXTENSIONS = {
    ".m4a", ".mp3", ".flac", ".ogg", ".opus", ".wav", ".aiff", ".alac",
}

# Full-scale values for the mood heuristics: a feature at or above one of
# these maps to 1.0.
_BPM_FULL_SCALE = 180.0
_ZCR_FULL_SCALE = 0.15
_FLATNESS_FULL_SCALE = 0.5
_CENTROID_FULL_SCALE_HZ = 4000.0

# Mastered tracks mostly sit in -20..-3 dB on bliss's loudness scale; louder
# (closer to 0) reads as more energetic.
_LOUDNESS_FLOOR_DB = -20.0
_LOUDNESS_RANGE_DB = 17.0

Features = dict[str, JsonValue]


def sidecar_path(audio_path: str) -> str:
    """The sidecar filename next to [audio_path]."""
    return audio_path + SIDECAR_SUFFIX


def private_sidecar_path(audio_path: str, store_dir: str) -> str:
    """Where the sidecar goes when the music folder cannot be written to.

    Android lets an app read audio in shared storage but not create a .json
    beside it without All-files access, so the app passes a private folder
    and the sidecar lands there, keyed by the audio file's path.
    """
    digest = hashlib.sha1(audio_path.encode("utf-8")).hexdigest()
    return os.path.join(store_dir, digest + ".json")


def _candidate_paths(audio_path: str, store_dir: str | None) -> list[str]:
    paths = [sidecar_path(audio_path)]
    if store_dir:
        paths.append(private_sidecar_path(audio_path, store_dir))
    return paths


def _write_json_atomically(path: str, data: Features) -> None:
    # Temp-then-rename, so a process killed mid-write leaves no half file.
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
    os.replace(tmp, path)


def write_sidecar(
    audio_path: str,
    features: Features | None,
    error_reason: str | None = None,
    store_dir: str | None = None,
) -> str:
    """Write a sidecar for [audio_path] and return where it went.

    Tries next to the file first, then [store_dir]. A failed analysis records
    [error_reason] so the app can say why a track has no features.
    """
    data: Features = {"version": SIDECAR_VERSION, "updated": int(time.time())}
    if features:
        data.update(features)
    elif error_reason:
        data["error"] = error_reason

    paths = _candidate_paths(audio_path, store_dir)
    for index, path in enumerate(paths):
        try:
            if index > 0:
                os.makedirs(os.path.dirname(path), exist_ok=True)
            _write_json_atomically(path, data)
            return path
        except OSError:
            if index == len(paths) - 1:
                raise
    raise AssertionError("unreachable: at least one candidate path")


def read_sidecar(audio_path: str, store_dir: str | None = None) -> Features | None:
    """The sidecar for [audio_path], or None if absent or corrupt."""
    for path in _candidate_paths(audio_path, store_dir):
        try:
            with open(path, "r", encoding="utf-8") as f:
                return json.load(f)
        except FileNotFoundError:
            continue
        except (OSError, ValueError):
            # Unreadable or half-written: treat it as absent.
            continue
    return None


def is_settled(sidecar: Features | None) -> bool:
    """Whether a sidecar means "done, do not analyse again".

    An empty sidecar from format version 1 is not settled. The version 1
    analyser could only decode AAC, so it marked every ALAC, FLAC and MP3 file
    as unreadable; each gets one more attempt with the current decoders.
    """
    if not sidecar:
        return False
    if "bpm" in sidecar:
        return True
    return sidecar.get("version", 1) >= SIDECAR_VERSION


def has_sidecar(audio_path: str, store_dir: str | None = None) -> bool:
    return is_settled(read_sidecar(audio_path, store_dir))


def _analyze(path: str) -> tuple[Features | None, str | None]:
    """(features, None) on success, or (None, why it failed)."""
    if not ANALYSIS_AVAILABLE:
        return None, "analysis unavailable"
    try:
        raw = _bliss_analyze.analyze(path)
    except Exception as exc:
        # bliss raises for anything it cannot decode: corrupt, DRM-locked or
        # an unsupported codec such as Opus.
        return None, str(exc) or type(exc).__name__
    bpm = raw.get("bpm")
    if bpm is None:
        return None, "no tempo detected"
    features: Features = {"bpm": round(bpm, 1), "source": "local"}
    features.update(_mood_estimates(raw))
    return features, None


def analyze_file(path: str) -> Features | None:
    """Acoustic features for one audio file, or None if it cannot be analysed."""
    return _analyze(path)[0]


def _mood_estimates(raw: dict[str, float]) -> Features:
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

    def clamp(value: float) -> float:
        return round(max(0.0, min(1.0, value)), 3)

    bpm = raw.get("bpm", 0.0)
    zcr = raw.get("zcr", 0.0)
    flatness = raw.get("flatness", 0.0)
    loudness_db = raw.get("loudness_db", -30.0)
    centroid_hz = raw.get("centroid_hz")

    bpm_norm = clamp(bpm / _BPM_FULL_SCALE)
    zcr_norm = clamp(zcr / _ZCR_FULL_SCALE)
    flatness_norm = clamp(flatness / _FLATNESS_FULL_SCALE)
    loudness_norm = clamp((loudness_db - _LOUDNESS_FLOOR_DB) / _LOUDNESS_RANGE_DB)

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
        result["brightness"] = clamp(centroid_hz / _CENTROID_FULL_SCALE_HZ)
    return result


def iter_audio_files(directory: str) -> Iterator[str]:
    """Yield audio file paths under [directory], recursively."""
    for root, _dirs, files in os.walk(directory):
        for name in files:
            if os.path.splitext(name)[1].lower() in AUDIO_EXTENSIONS:
                yield os.path.join(root, name)


def analyze_directory(
    directory: str,
    emit: Emit | None = None,
    limit: int | None = None,
    store_dir: str | None = None,
    progress_range: tuple[int, int] = (90, 100),
) -> dict[str, JsonValue]:
    """Analyse files without a settled sidecar and write one for each.

    Bounded by [limit] so a caller can run this in slices. [progress_range]
    maps the work onto the caller's progress bar: the tail of a download, or
    all of a library analyse. One file failing, even to write its sidecar,
    never stops the rest.
    """
    if not ANALYSIS_AVAILABLE:
        return {"total": 0, "analysed": 0, "skipped": 0, "analysis_available": False}

    files = list(iter_audio_files(directory))
    pending = [path for path in files if not has_sidecar(path, store_dir)]
    if limit is not None:
        pending = pending[:limit]

    analysed = failed = unwritable = 0
    start, end = progress_range
    for i, path in enumerate(pending):
        features, reason = _analyze(path)
        try:
            write_sidecar(path, features, error_reason=reason, store_dir=store_dir)
        except OSError:
            unwritable += 1
        if features is not None:
            analysed += 1
        else:
            failed += 1
        if emit:
            emit(progress(
                start + int((end - start) * (i + 1) / len(pending)),
                f"Analyzing audio ({i + 1}/{len(pending)})",
            ))

    return {
        "total": len(files),
        "analysed": analysed,
        "skipped": len(files) - len(pending),
        "pending": failed,
        "unwritable": unwritable,
        "analysis_available": True,
    }


def handle_analyze(payload: Payload, emit: Emit) -> Event:
    problem = missing_fields(payload, "directory")
    if problem:
        return problem
    if not ANALYSIS_AVAILABLE:
        return error("no_analysis", "Acoustic analysis is not available on this device.")
    return done(**analyze_directory(
        payload["directory"],
        emit=emit,
        store_dir=payload.get("store_dir"),
        progress_range=(0, 100),
    ))
