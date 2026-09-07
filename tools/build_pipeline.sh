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

# Acoustic analysis: Essentia for BPM, key and mood feature extraction.
# Desktop-only: Essentia publishes a manylinux wheel for CPython 3.14 but
# not for Android. The pipeline degrades gracefully on Android (no sidecars,
# no mood features).
"$VENV/bin/pip" install --quiet essentia

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
