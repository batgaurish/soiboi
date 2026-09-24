#!/usr/bin/env bash
# Builds wrapper-v2, the FairPlay service gamdl needs for ALAC, into
# build/wrapper/<arch>/ for bundling with the app.
#
# The bundle holds no Apple code. wrapper-v2's worker loads Apple Music's own
# native libraries at runtime, and the app extracts those from an Apple Music
# APK the user downloads (see lib/base/services/wrapper_service.dart).
#
# Needs: git, cargo, cmake, ninja, a C compiler, and Android NDK r23b, the
# version Apple built its libraries with. The worker links against r23b's
# libc++_shared.so for its SONAME; at runtime Apple's copy of the same ABI is
# what loads.
#
#   ANDROID_NDK_R23=~/Android/Sdk/ndk/23.1.7779620 tools/build_wrapper.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCH="${1:-x86_64}"
NDK="${ANDROID_NDK_R23:-$HOME/Android/Sdk/ndk/23.1.7779620}"

# Pinned: LIBS_VERSION.json in this commit fixes which Apple Music build the
# app asks for, so moving it means updating the download link as well.
WRAPPER_REPO="https://github.com/glomatico/wrapper-v2.git"
WRAPPER_COMMIT="100e0a864e883e03a3ac450a780dd9563fff5271"

case "$ARCH" in
  x86_64) NDK_TRIPLE="x86_64-linux-android" ;;
  arm64-v8a) NDK_TRIPLE="aarch64-linux-android" ;;
  *) echo "unsupported arch: $ARCH" >&2; exit 2 ;;
esac

if [[ ! -d "$NDK" ]]; then
  echo "Android NDK r23b not found at $NDK" >&2
  echo "Install it with: sdkmanager 'ndk;23.1.7779620'" >&2
  exit 1
fi

SRC="$ROOT/build/wrapper-src"
OUT="$ROOT/build/wrapper/$ARCH"

if [[ ! -d "$SRC/.git" ]]; then
  git clone --quiet "$WRAPPER_REPO" "$SRC"
fi
git -C "$SRC" fetch --quiet origin "$WRAPPER_COMMIT"
git -C "$SRC" checkout --quiet --force "$WRAPPER_COMMIT"
git -C "$SRC" apply "$ROOT/tools/patches/wrapper-v2-launcher-path.patch"

echo "==> staging AOSP system files ($ARCH)"
rm -rf "$SRC/rootfs/system/lib64"/*.so
bash "$SRC/tools/stage-system.sh" --arch "$ARCH" >/dev/null

# The worker's build links against rootfs/system/lib64/libc++_shared.so for
# its SONAME. Give it the NDK's copy instead of Apple's, so no Apple file is
# ever needed here, and remove it again before packaging.
NDK_LIBCXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$NDK_TRIPLE/libc++_shared.so"
cp "$NDK_LIBCXX" "$SRC/rootfs/system/lib64/libc++_shared.so"

echo "==> building worker and launcher"
rm -rf "$SRC/build"
ANDROID_NDK_HOME="$NDK" cmake -S "$SRC" -B "$SRC/build" -G Ninja \
  -DTARGET_ARCH="$ARCH" -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$SRC/build" >/dev/null
rm "$SRC/rootfs/system/lib64/libc++_shared.so"

echo "==> building supervisor"
cargo build --quiet --release --manifest-path "$SRC/Cargo.toml"

rm -rf "$OUT"
mkdir -p "$OUT/rootfs/etc/ssl/certs"
cp "$SRC/target/release/wrapperd" "$SRC/wrapper" "$SRC/LIBS_VERSION.json" "$OUT/"
cp -a "$SRC/rootfs/system" "$OUT/rootfs/"
# Apple's libcurl reads CA certificates from inside the chroot.
cp /etc/ssl/certs/ca-certificates.crt "$OUT/rootfs/etc/ssl/certs/"

echo "==> built $OUT (no Apple libraries; the app adds them at setup)"
