//! Analyse audio files with bliss-audio and print one JSON object per line.
//!
//! Built to answer one question: if Soiboi analysed tracks with this instead of
//! Essentia, how different would the numbers be? So it reports features in real
//! units rather than bliss's own space.
//!
//! bliss normalises every feature to [-1, 1] for its similarity metric:
//!
//!     normalize(v) = 2 * (v - MIN) / (MAX - MIN) - 1
//!
//! Index 0 is therefore *not* BPM, and comparing it to Essentia's BPM directly
//! would show a disagreement that is pure unit mismatch. Each feature is
//! inverted below using the constants bliss declares for it.

use bliss_audio::decoder::symphonia::SymphoniaDecoder as Decoder;
use bliss_audio::decoder::Decoder as DecoderTrait;
use serde::Serialize;
use std::env;
use std::path::Path;

/// bliss's normalisation, inverted.
fn denorm(n: f32, min: f32, max: f32) -> f32 {
    (n + 1.0) / 2.0 * (max - min) + min
}

// Constants copied from the bliss source, not guessed:
//   temporal.rs  BPMDesc              0 .. 206
//   timbral.rs   SpectralDesc         0 .. SAMPLE_RATE/2, and SAMPLE_RATE = 22050
//   timbral.rs   flatness             0 .. 1   (normalised inline, not via the trait)
//   timbral.rs   ZeroCrossingRateDesc 0 .. 1
//   misc.rs      LoudnessDesc       -90 .. 0
const BPM_MAX: f32 = 206.0;
const SPECTRAL_MAX: f32 = 22050.0 / 2.0;
const LOUDNESS_MIN: f32 = -90.0;

#[derive(Serialize)]
struct Output {
    path: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,

    // Real units.
    #[serde(skip_serializing_if = "Option::is_none")]
    bpm: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    zcr: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    centroid_hz: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    centroid_std_hz: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    rolloff_hz: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    flatness: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    loudness_db: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    duration_seconds: Option<f64>,

    /// The untouched [-1, 1] vector, so nothing is lost to the conversion above.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    raw: Vec<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    analyze_seconds: Option<f64>,
}

fn failure(path: &str, message: String) -> Output {
    Output {
        path: path.to_string(),
        error: Some(message),
        bpm: None,
        zcr: None,
        centroid_hz: None,
        centroid_std_hz: None,
        rolloff_hz: None,
        flatness: None,
        loudness_db: None,
        duration_seconds: None,
        raw: vec![],
        analyze_seconds: None,
    }
}

fn main() {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: bliss_analyze <file> [file ...]");
        std::process::exit(1);
    }

    for arg in &args[1..] {
        let started = std::time::Instant::now();
        let out = match Decoder::song_from_path(Path::new(arg)) {
            Err(e) => failure(arg, format!("{e}")),
            Ok(song) => {
                let raw = song.analysis.as_vec();
                let at = |i: usize| raw.get(i).copied();
                Output {
                    path: arg.clone(),
                    error: None,
                    bpm: at(0).map(|v| denorm(v, 0.0, BPM_MAX)),
                    zcr: at(1).map(|v| denorm(v, 0.0, 1.0)),
                    centroid_hz: at(2).map(|v| denorm(v, 0.0, SPECTRAL_MAX)),
                    centroid_std_hz: at(3).map(|v| denorm(v, 0.0, SPECTRAL_MAX)),
                    rolloff_hz: at(4).map(|v| denorm(v, 0.0, SPECTRAL_MAX)),
                    flatness: at(6).map(|v| denorm(v, 0.0, 1.0)),
                    loudness_db: at(8).map(|v| denorm(v, LOUDNESS_MIN, 0.0)),
                    duration_seconds: Some(song.duration.as_secs_f64()),
                    raw,
                    analyze_seconds: Some(started.elapsed().as_secs_f64()),
                }
            }
        };
        println!("{}", serde_json::to_string(&out).unwrap());
    }
}
