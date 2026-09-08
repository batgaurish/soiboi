#!/usr/bin/env bash
# Assembles a runnable Linux bundle: the Flutter build plus the Python
# pipeline it needs at runtime.
#
# tools/build_pipeline.sh has always referred to "the Linux packaging scripts"
# and they did not exist -- generate_deb.sh and generate_rpm.sh package the
# binary without the pipeline, so a downloaded build could play music but never
# archive any. This is that missing script.
#
# The awkward part is the virtualenv. DesktopPipelineRunner looks for
# <exe>/data/pipeline and <exe>/data/.pipeline-venv, but a venv is NOT
# relocatable: bin/python is a symlink into the system interpreter and
# pyvenv.cfg holds absolute paths, so copying .pipeline-venv into the bundle
# produces an environment that cannot start. Two ways out:
#
#   * --copies, which puts a real interpreter binary in the venv but still
#     depends on the host having a compatible libpython. Fine for the private
#     distribution this project actually does (the user and friends, same
#     distro family), and what this script uses.
#   * bundling a relocatable interpreter (python-build-standalone). Correct for
#     wider distribution, and much heavier. Not done here.
#
# Because of that, the venv is built *directly at its final path* rather than
# built elsewhere and copied.
#
# Usage: tools/package_linux.sh [--release]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="debug"
[[ "${1:-}" == "--release" ]] && MODE="release"

BUNDLE="$ROOT/build/linux/x64/$MODE/bundle"
DATA="$BUNDLE/data"
VERSION="$(grep -m1 '^version:' "$ROOT/pubspec.yaml" | awk '{print $2}')"
PYTHON="${PYTHON:-python3}"

if [[ ! -x "$BUNDLE/soiboi" ]]; then
  echo "no bundle at $BUNDLE -- run: flutter build linux --$MODE" >&2
  exit 1
fi

echo "==> pipeline sources into the bundle"
rm -rf "$DATA/pipeline"
# Excluding caches and the native crate's build tree: the compiled wheel is
# installed into the venv below, and target/ is gigabytes of Rust artifacts.
mkdir -p "$DATA/pipeline"
(cd "$ROOT/pipeline" && tar --exclude='__pycache__' --exclude='native/*/target' -cf - .) \
  | (cd "$DATA/pipeline" && tar -xf -)

echo "==> runtime venv at its final path (not relocatable, so not copied)"
VENV="$DATA/.pipeline-venv"
rm -rf "$VENV"
"$PYTHON" -m venv --copies "$VENV"
"$VENV/bin/python" -m pip install --quiet --upgrade pip

# Same dependency set as tools/build_pipeline.sh, minus pytest: the test
# dependency is not part of the runtime and has no business shipping.
"$VENV/bin/python" -m pip install --quiet gamdl

# bliss-audio's wrapper crate is not on PyPI, so it is built here the same way
# the dev environment builds it.
"$VENV/bin/python" -m pip install --quiet maturin
WHEELHOUSE="$(mktemp -d)"
trap 'rm -rf "$WHEELHOUSE"' EXIT
"$VENV/bin/maturin" build --release \
  -m "$ROOT/pipeline/native/bliss_analyze/Cargo.toml" \
  --interpreter "$VENV/bin/python" -o "$WHEELHOUSE" --quiet
"$VENV/bin/python" -m pip install --quiet --force-reinstall "$WHEELHOUSE"/*.whl

echo "==> verifying the bundled environment, not the dev one"
PYTHONPATH="$DATA/pipeline" "$VENV/bin/python" -m soiboi_pipeline capabilities

ARCHIVE="$ROOT/build/soiboi-$MODE-linux-x64-v$VERSION.tar.gz"
echo "==> $ARCHIVE"
rm -f "$ARCHIVE"
tar -czf "$ARCHIVE" -C "$(dirname "$BUNDLE")" "$(basename "$BUNDLE")"
ls -lh "$ARCHIVE"

cat <<'NOTE'

Extract and run ./bundle/soiboi. If can_download above is true, the archival
pipeline inside the bundle is functional.

The venv was built with --copies, so it carries its own interpreter binary but
still links against the host's libpython. That is fine for this project's
private distribution and would not be for a general release -- see the header.
NOTE
