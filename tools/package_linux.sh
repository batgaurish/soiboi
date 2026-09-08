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
# <exe>/data/pipeline and <exe>/data/.pipeline-venv, so one has to end up
# inside the bundle. It is created with --copies, which gives it a real
# interpreter binary instead of a symlink into the system one; the packaged
# tarball then survives being extracted anywhere, which was tested rather than
# assumed (see tools/_runtime_venv.sh).
#
# What it still depends on is the host having a compatible libpython. That is
# fine for the private distribution this project actually does — the user and
# friends, same distro family — and would not be for a general release, where
# the answer is bundling python-build-standalone. Not done here.
#
# Usage: tools/package_linux.sh [--release]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/_runtime_venv.sh
source "$ROOT/tools/_runtime_venv.sh"

MODE="debug"
[[ "${1:-}" == "--release" ]] && MODE="release"

BUNDLE="$ROOT/build/linux/x64/$MODE/bundle"
DATA="$BUNDLE/data"
VERSION="$(grep -m1 '^version:' "$ROOT/pubspec.yaml" | awk '{print $2}')"

if [[ ! -x "$BUNDLE/soiboi" ]]; then
  echo "no bundle at $BUNDLE -- run: flutter build linux --$MODE" >&2
  exit 1
fi

echo "==> pipeline sources into the bundle"
copy_pipeline_sources "$DATA/pipeline" "$ROOT"

echo "==> runtime venv, built at its final path"
VENV="$DATA/.pipeline-venv"
build_runtime_venv "$VENV" "$ROOT"

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
