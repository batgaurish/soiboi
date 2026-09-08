#!/usr/bin/env bash
# Applies the patches in tools/patches/ to packages in the pub cache.
#
# Patching a dependency is not something to do lightly, and there is exactly
# one here: without it the Linux app segfaults seconds after startup and is
# unusable. The patch file explains the fault in full.
#
# It has to be re-applied after `flutter pub get`, which restores the cached
# package from the git ref. This script is idempotent, so running it more often
# than necessary is harmless -- tools/install_linux.sh calls it every time.
#
# The honest alternative is upstreaming the change to the media-kit fork this
# project already depends on, at which point this whole directory goes away.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The git dependency's cache directory carries the pinned commit in its name,
# so it is found rather than hard-coded -- a ref bump would otherwise leave
# this silently patching nothing.
MEDIA_KIT="$(find "$HOME/.pub-cache/git" -maxdepth 2 -type d -name media_kit \
  -path '*media-kit-*' 2>/dev/null | head -1)"

if [[ -z "$MEDIA_KIT" ]]; then
  echo "media_kit not in the pub cache -- run 'flutter pub get' first" >&2
  exit 1
fi

PATCH="$ROOT/tools/patches/media_kit-disable-mpv-lua.patch"
TARGET="$MEDIA_KIT/lib/src/player/native/player/real.dart"

if grep -q "load-stats-overlay" "$TARGET"; then
  echo "media_kit: mpv Lua patch already applied"
else
  # --forward so a re-run is a no-op rather than an interactive prompt, and
  # the leading text in the patch file is skipped by patch(1) automatically.
  patch -p1 -d "$MEDIA_KIT" --forward < "$PATCH"
  echo "media_kit: mpv Lua patch applied"
fi
