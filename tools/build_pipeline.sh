#!/usr/bin/env bash
# Builds the bundled Python environment the app ships with.
#
# The pipeline runs inside the app now -- there is no server and no Docker -- so
# the interpreter and its packages have to be materialised somewhere the built
# binary can find them. This creates that environment; the Linux packaging
# scripts copy it into the bundle.
#
# Run once after checkout, and again whenever the dependency set changes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIPELINE="$ROOT/pipeline"
# Beside the package, not inside it: Chaquopy copies pipeline/ verbatim
# into the APK, and a virtualenv in there fails the Android build.
VENV="$ROOT/.pipeline-venv"

PYTHON="${PYTHON:-python3}"

echo "==> building pipeline environment in $VENV"
"$PYTHON" -m venv "$VENV"
"$VENV/bin/pip" install --quiet --upgrade pip

# gamdl pulls its own dependency tree (mutagen, yt-dlp, pywidevine, httpx,
# m3u8, click...). Its native decrypt/mux engine arrives as a prebuilt wheel
# for this platform.
"$VENV/bin/pip" install --quiet gamdl

# Acoustic analysis: bliss-audio for BPM and mood feature extraction, via a
# small PyO3 wrapper crate (pipeline/native/bliss_analyze) built locally --
# it is not published, so it can't come from PyPI like gamdl. maturin builds
# and installs it into this venv the same way pip installs any other wheel.
# The same crate is cross-compiled for Android by tools/build_android_pipeline.sh,
# so this is not desktop-only the way Essentia was.
"$VENV/bin/pip" install --quiet maturin
BLISS_WHEELHOUSE="$(mktemp -d)"
"$VENV/bin/maturin" build --release \
  -m "$ROOT/pipeline/native/bliss_analyze/Cargo.toml" \
  --interpreter "$VENV/bin/python" -o "$BLISS_WHEELHOUSE" --quiet
"$VENV/bin/pip" install --quiet --force-reinstall "$BLISS_WHEELHOUSE"/*.whl
rm -rf "$BLISS_WHEELHOUSE"

# For test_pipeline/. Not shipped -- the packaging scripts copy the runtime
# environment, and pytest is not part of it.
"$VENV/bin/pip" install --quiet pytest

echo "==> verifying"
PYTHONPATH="$PIPELINE" "$VENV/bin/python" -m soiboi_pipeline capabilities

cat <<'NOTE'

If can_download is true, the environment is ready.

Note for Android: this venv is desktop-only. Android cannot use manylinux
wheels (Bionic libc, not glibc) and cannot spawn a Python binary at all, so it
loads the same soiboi_pipeline package through Chaquopy with an
aarch64-linux-android build of the native muxer. See tools/build_android_muxer.sh.
NOTE
