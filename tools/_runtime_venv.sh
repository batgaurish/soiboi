#!/usr/bin/env bash
# Builds the Python runtime the app needs, at a path given by the caller.
#
# Shared by tools/package_linux.sh (which builds it inside a tarball's bundle)
# and tools/install_linux.sh (which builds it inside an installed copy), so the
# two cannot drift on what "the runtime" means.
#
# On relocatability, since the earlier note here was wrong and it matters:
# a venv built WITHOUT --copies has bin/python as a symlink into the system
# interpreter, and moving that tree breaks it. With --copies the venv owns a
# real interpreter binary and Python derives sys.prefix from that binary's own
# location, so the whole tree *does* survive being moved — verified by
# extracting a packaged tarball to an unrelated path and running the pipeline
# from it. What does not survive are the bin/* console-script shebangs (pip,
# maturin), which hold absolute paths; nothing at runtime uses them, because
# the app invokes `<venv>/bin/python -m soiboi_pipeline` directly.
#
# Each caller still builds at the final path rather than copying, which is
# belt-and-braces now rather than a requirement: it also guarantees the native
# bliss wheel is compiled against the interpreter that will run it.
#
# Usage: build_runtime_venv <venv-path> <repo-root>

build_runtime_venv() {
  local venv="$1"
  local root="$2"
  local python="${PYTHON:-python3}"

  rm -rf "$venv"
  # --copies puts a real interpreter in the venv instead of a symlink to the
  # one that happened to build it. It still links against the host's
  # libpython, which is fine for this project's private distribution and would
  # not be for a general release (python-build-standalone is the answer there).
  "$python" -m venv --copies "$venv"
  "$venv/bin/python" -m pip install --quiet --upgrade pip

  # gamdl pulls the rest of the tree (mutagen, yt-dlp, httpx, m3u8, click,
  # pywidevine) through its own metadata, and ships its native decrypt/mux
  # engine as a prebuilt wheel.
  "$venv/bin/python" -m pip install --quiet gamdl

  # bliss-audio's wrapper crate is not published, so it is built here the same
  # way the dev environment builds it.
  "$venv/bin/python" -m pip install --quiet maturin
  local wheelhouse
  wheelhouse="$(mktemp -d)"
  "$venv/bin/maturin" build --release \
    -m "$root/pipeline/native/bliss_analyze/Cargo.toml" \
    --interpreter "$venv/bin/python" -o "$wheelhouse" --quiet
  "$venv/bin/python" -m pip install --quiet --force-reinstall "$wheelhouse"/*.whl
  rm -rf "$wheelhouse"
}

# Copies the pipeline package to [dest], without the caches and the Rust build
# tree — the compiled wheel is installed into the venv, and target/ is
# gigabytes of intermediate artifacts.
copy_pipeline_sources() {
  local dest="$1"
  local root="$2"
  rm -rf "$dest"
  mkdir -p "$dest"
  (cd "$root/pipeline" \
    && tar --exclude='__pycache__' --exclude='native/*/target' -cf - .) \
    | (cd "$dest" && tar -xf -)
}
