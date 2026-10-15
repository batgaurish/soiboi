#!/usr/bin/env bash
# Installs Soiboi for the current user on Linux: the app image, the Python pipeline beside it, and a launcher entry.
#
#   tools/install_linux.sh            build and install under ~/.local/opt/soiboi
#   PREFIX=/opt/soiboi tools/install_linux.sh
#
# Needs JDK 21, python3 (with venv) and mpv. The lossless wrapper is optional and is built by tools/build_wrapper.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="${PREFIX:-$HOME/.local/opt/soiboi}"
BIN="${BIN:-$HOME/.local/bin}"
APPS="${APPS:-$HOME/.local/share/applications}"

command -v mpv >/dev/null || echo "warning: mpv is not installed; Soiboi plays audio through it"

echo "==> building the app image"
(cd "$ROOT" && ./gradlew :app-desktop:createDistributable --console=plain)
IMAGE="$ROOT/app-desktop/build/compose/binaries/main/app/soiboi"
[ -d "$IMAGE" ] || { echo "no app image at $IMAGE" >&2; exit 1; }

echo "==> installing to $PREFIX"
rm -rf "$PREFIX"
mkdir -p "$PREFIX" "$BIN" "$APPS"
cp -a "$IMAGE" "$PREFIX/app"
rsync -a --exclude native --exclude __pycache__ "$ROOT/pipeline/" "$PREFIX/pipeline/"
if [ -d "$ROOT/build/wrapper" ]; then mkdir -p "$PREFIX/build" && cp -a "$ROOT/build/wrapper" "$PREFIX/build/wrapper"; fi

echo "==> building the Python environment"
VENV="$PREFIX/.pipeline-venv" bash "$ROOT/tools/build_pipeline.sh"

cp "$ROOT/assets/app_icon.png" "$PREFIX/soiboi.png"
cat > "$BIN/soiboi" <<EOF
#!/usr/bin/env bash
export SOIBOI_HOME="$PREFIX"
exec "$PREFIX/app/bin/soiboi" "\$@"
EOF
chmod +x "$BIN/soiboi"
cat > "$APPS/soiboi.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Soiboi
Comment=Plays the music you own and downloads the music you do not yet
Exec=$BIN/soiboi
Icon=$PREFIX/soiboi.png
Categories=AudioVideo;Audio;Player;
Terminal=false
EOF
echo "Installed. Run soiboi, or find it in your launcher."
