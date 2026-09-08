#!/usr/bin/env bash
# Installs Soiboi for the current user and puts it in the app launcher.
#
# Everything lands under ~/.local, so this needs no root: XDG says a user's
# own applications, icons and data live there, and every desktop environment
# reads them. Nothing is written outside $HOME.
#
# The Python runtime is rebuilt at the installed path rather than copied from a
# build directory. Not because it could not be copied — a --copies venv does
# relocate, see tools/_runtime_venv.sh — but because building it here compiles
# the native bliss wheel against the interpreter that will actually run it, and
# because the build tree may not have a venv at all.
#
# Applies tools/patches/ first: without the mpv Lua patch the app segfaults
# within seconds of launching, so installing an unpatched build would put a
# launcher icon there that does nothing but crash.
#
# Usage: tools/install_linux.sh [--debug]     (release by default)
#        tools/install_linux.sh --uninstall
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=tools/_runtime_venv.sh
source "$ROOT/tools/_runtime_venv.sh"

PREFIX="${PREFIX:-$HOME/.local}"
APP_DIR="$PREFIX/lib/soiboi"
BIN_LINK="$PREFIX/bin/soiboi"
DESKTOP="$PREFIX/share/applications/soiboi.desktop"
ICON="$PREFIX/share/icons/hicolor/512x512/apps/soiboi.png"

if [[ "${1:-}" == "--uninstall" ]]; then
  echo "==> removing $APP_DIR, $BIN_LINK, $DESKTOP, $ICON"
  rm -rf "$APP_DIR"
  rm -f "$BIN_LINK" "$DESKTOP" "$ICON"
  command -v update-desktop-database >/dev/null \
    && update-desktop-database "$PREFIX/share/applications" 2>/dev/null || true
  echo "Done. Your library and settings in ~/.local/share/soiboi are untouched."
  exit 0
fi

MODE="release"
[[ "${1:-}" == "--debug" ]] && MODE="debug"
BUNDLE="$ROOT/build/linux/x64/$MODE/bundle"

if [[ ! -x "$BUNDLE/soiboi" ]]; then
  echo "no bundle at $BUNDLE -- run: flutter build linux --$MODE" >&2
  exit 1
fi

# Checked here rather than trusted: without the mpv Lua patch the installed app
# segfaults seconds after launch, and a launcher icon that dies on click is
# worse than no icon. `flutter pub get` reverts it, so this is easy to lose.
# apply_patches.sh exits non-zero if it cannot apply, and set -e stops here.
# It does not rebuild: if it reports having just applied the patch, the bundle
# above predates it and needs `flutter build linux` run again.
"$ROOT/tools/apply_patches.sh"

echo "==> installing the app into $APP_DIR"
rm -rf "$APP_DIR"
mkdir -p "$(dirname "$APP_DIR")"
# The build tree may already contain a venv from tools/package_linux.sh. It is
# excluded deliberately: copying it here would produce an environment whose
# pyvenv.cfg points back at the build directory, which fails in ways that look
# like the download engine is broken rather than like a bad copy.
(cd "$BUNDLE" && tar --exclude='./data/.pipeline-venv' -cf - .) \
  | (mkdir -p "$APP_DIR" && cd "$APP_DIR" && tar -xf -)

echo "==> pipeline sources"
copy_pipeline_sources "$APP_DIR/data/pipeline" "$ROOT"

echo "==> python runtime, built at its final path"
build_runtime_venv "$APP_DIR/data/.pipeline-venv" "$ROOT"

echo "==> verifying the installed copy, not the build tree"
PYTHONPATH="$APP_DIR/data/pipeline" \
  "$APP_DIR/data/.pipeline-venv/bin/python" -m soiboi_pipeline capabilities

echo "==> launcher entry"
mkdir -p "$(dirname "$ICON")" "$(dirname "$DESKTOP")" "$PREFIX/bin"
cp "$ROOT/app_icons/win_linux.png" "$ICON"
ln -sfn "$APP_DIR/soiboi" "$BIN_LINK"

# StartupWMClass is the APPLICATION_ID from linux/CMakeLists.txt, which the
# runner passes to GTK. Without it the running window is not associated with
# this entry and docks show a second, unnamed icon.
cat > "$DESKTOP" <<'ENTRY'
[Desktop Entry]
Type=Application
Name=Soiboi
GenericName=Music Player
Comment=Local music player with streaming-to-offline archival
Exec=soiboi %U
Icon=soiboi
Terminal=false
Categories=AudioVideo;Audio;Player;Music;
Keywords=music;player;audio;offline;archive;
StartupNotify=true
StartupWMClass=com.batgaurish.soiboi
ENTRY

command -v update-desktop-database >/dev/null \
  && update-desktop-database "$PREFIX/share/applications" 2>/dev/null || true
command -v gtk-update-icon-cache >/dev/null \
  && gtk-update-icon-cache -qtf "$PREFIX/share/icons/hicolor" 2>/dev/null || true

cat <<NOTE

Installed. Soiboi should appear in your launcher; \`soiboi\` also runs it from
a terminal if $PREFIX/bin is on your PATH.

If can_download above is true, the archival pipeline in the installed copy
works. Re-run this script after pulling changes; \`--uninstall\` removes it and
leaves your library alone.
NOTE
