//! PyO3 wrapper around bliss-audio, imported as `_bliss_analyze` from
//! `soiboi_pipeline.acoustic` on both desktop and Android -- the same shape
//! gamdl's `_ammuxer` already uses so this needs no new plumbing on the
//! Python or Dart side.
//!
//! The analysis logic (which raw indices map to which feature, and how to
//! undo bliss's [-1, 1] normalisation) is ported unchanged from
//! `docs/bliss-probe/main.rs`, which was verified against a 220-track
//! comparison against Essentia. See that file's comments for the source of
//! the normalisation constants.

use bliss_audio::decoder::symphonia::SymphoniaDecoder as Decoder;
use bliss_audio::decoder::Decoder as DecoderTrait;
use pyo3::exceptions::PyRuntimeError;
use pyo3::prelude::*;
use std::collections::HashMap;
use std::path::Path;

/// bliss's normalisation, inverted: normalize(v) = 2*(v-min)/(max-min) - 1.
fn denorm(n: f32, min: f32, max: f32) -> f32 {
    (n + 1.0) / 2.0 * (max - min) + min
}

// Constants copied from the bliss source, not guessed -- see
// docs/bliss-probe/main.rs for the exact source locations.
const BPM_MAX: f32 = 206.0;
const SPECTRAL_MAX: f32 = 22050.0 / 2.0;
const LOUDNESS_MIN: f32 = -90.0;

/// Acoustic features for one audio file, in real units (not bliss's [-1, 1]
/// analysis space). Raises on any decode failure -- the caller decides
/// whether that means "skip this track" or "retry later".
#[pyfunction]
fn analyze(path: &str) -> PyResult<HashMap<String, f64>> {
    let song = Decoder::song_from_path(Path::new(path))
        .map_err(|e| PyRuntimeError::new_err(format!("{e}")))?;

    let raw = song.analysis.as_vec();
    let at = |i: usize| raw.get(i).copied();

    let mut out = HashMap::new();
    if let Some(v) = at(0) {
        out.insert("bpm".to_string(), denorm(v, 0.0, BPM_MAX) as f64);
    }
    if let Some(v) = at(1) {
        out.insert("zcr".to_string(), denorm(v, 0.0, 1.0) as f64);
    }
    if let Some(v) = at(2) {
        out.insert("centroid_hz".to_string(), denorm(v, 0.0, SPECTRAL_MAX) as f64);
    }
    if let Some(v) = at(3) {
        out.insert("centroid_std_hz".to_string(), denorm(v, 0.0, SPECTRAL_MAX) as f64);
    }
    if let Some(v) = at(4) {
        out.insert("rolloff_hz".to_string(), denorm(v, 0.0, SPECTRAL_MAX) as f64);
    }
    if let Some(v) = at(6) {
        out.insert("flatness".to_string(), denorm(v, 0.0, 1.0) as f64);
    }
    if let Some(v) = at(8) {
        out.insert("loudness_db".to_string(), denorm(v, LOUDNESS_MIN, 0.0) as f64);
    }
    // song.duration is unreliable for M4A/AAC via symphonia (reads 0 on every
    // real file tested) and unused by the mood formulas, so it is not
    // exposed here.

    Ok(out)
}

#[pymodule]
fn _bliss_analyze(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(analyze, m)?)?;
    Ok(())
}
