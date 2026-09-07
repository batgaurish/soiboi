#!/usr/bin/env bash
# Prepares everything the Android build needs beyond plain Flutter.
#
# Android runs the same soiboi_pipeline package as the desktop, but it cannot
# use PyPI's Linux wheels (Bionic libc, not glibc) and cannot spawn a Python
# process at all -- it embeds CPython through Chaquopy instead. Three things
# therefore have to be produced locally before `flutter build apk` will yield a
# working downloader with mood analysis:
#
#   1. gamdl's Rust decrypt/mux engine, cross-compiled per ABI.
#   2. bliss-audio's mood-analysis extension, cross-compiled per ABI.
#   3. A small local wheel repository, because four packages in the dependency
#      tree do not install here as published.
#
# All steps are reproducible and idempotent; re-run after changing the pinned
# versions in tools/build_android_wheels.py.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bash "$ROOT/tools/build_android_muxer.sh"
bash "$ROOT/tools/build_android_bliss.sh"

python3 "$ROOT/tools/build_android_wheels.py" \
  --native "$ROOT/build/android-native" \
  --out "$ROOT/android/pip-repo"

echo
echo "==> ready; android/pip-repo is what Chaquopy installs from"
