#!/usr/bin/env bash
# Cross-compiles gamdl's native decrypt/mux engine for Android arm64.
#
# This is the one component that blocks standalone downloading on Android.
# Everything else gamdl needs is pure Python and runs under Chaquopy unchanged,
# but `gamdl._ammuxer` is a Rust extension that does both FairPlay decryption
# and MP4 muxing -- there is no ffmpeg fallback to substitute for it.
#
# Two things make this work, and both are easy to get wrong:
#
#   1. manylinux aarch64 wheels do NOT run on Android. Android uses Bionic libc,
#      not glibc, so the published aarch64 wheel is useless here and the crate
#      must be rebuilt against the NDK.
#
#   2. PyO3 links against libpython on Android (unlike desktop Unix, where
#      symbols resolve from the host interpreter). So the link step needs a real
#      Android libpython -- which is exactly what Chaquopy ships.
#
# Verified: produces an ELF aarch64 shared object for Android 24 exporting
# PyInit__ammuxer, needing only libpython3.10.so, libdl.so and libc.so.
set -euo pipefail

PY_VER="${PY_VER:-3.10}"
CHAQUOPY_VER="${CHAQUOPY_VER:-3.10.6-1}"
ANDROID_API="${ANDROID_API:-24}"
GAMDL_VER="${GAMDL_VER:-3.8.5}"

WORK="${WORK:-$(mktemp -d)}"
OUT="${OUT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/android/app/src/main/jniLibs/arm64-v8a}"

: "${ANDROID_NDK:?set ANDROID_NDK, e.g. ~/Android/Sdk/ndk/28.2.13676358}"
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found at $TOOLCHAIN"; exit 1; }

echo "==> fetching gamdl $GAMDL_VER source (the wheel has no Rust in it)"
cd "$WORK"
pip download "gamdl==$GAMDL_VER" --no-deps --no-binary :all: -d . >/dev/null
tar xzf "gamdl-$GAMDL_VER.tar.gz"

echo "==> fetching Chaquopy libpython$PY_VER for arm64"
curl -sL -o chaquopy.zip \
  "https://repo.maven.apache.org/maven2/com/chaquo/python/target/$CHAQUOPY_VER/target-$CHAQUOPY_VER-arm64-v8a.zip"
mkdir -p chaquopy && unzip -oq chaquopy.zip -d chaquopy
LIBPY_DIR="$WORK/chaquopy/jniLibs/arm64-v8a"
[ -f "$LIBPY_DIR/libpython$PY_VER.so" ] || { echo "libpython$PY_VER.so missing"; exit 1; }

echo "==> building"
rustup target add aarch64-linux-android >/dev/null
cd "gamdl-$GAMDL_VER/gamdl/downloader/ammuxer"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android$ANDROID_API-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android$ANDROID_API-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export PYO3_CROSS_PYTHON_VERSION="$PY_VER"
export PYO3_NO_PYTHON=1
export RUSTFLAGS="-L $LIBPY_DIR"

cargo build --release --target aarch64-linux-android

SO="target/aarch64-linux-android/release/lib_ammuxer.so"
mkdir -p "$OUT"
cp "$SO" "$OUT/lib_ammuxer.so"

echo "==> built $OUT/lib_ammuxer.so"
file "$OUT/lib_ammuxer.so"
nm -D --defined-only "$OUT/lib_ammuxer.so" | grep PyInit || {
  echo "WARNING: PyInit__ammuxer not exported -- Python will not be able to import this"
  exit 1
}
