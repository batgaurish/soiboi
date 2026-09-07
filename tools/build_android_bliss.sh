#!/usr/bin/env bash
# Cross-compiles the bliss-audio mood-analysis extension for Android.
#
# Same problem and same solution as tools/build_android_muxer.sh, for a
# different crate: bliss-audio is pure Rust (a 220-track comparison verified
# it links only libc/libm/libgcc_s, no ffmpeg or C audio libraries), so it
# cross-compiles the same way gamdl's native muxer does. This crate lives in
# this repo (pipeline/native/bliss_analyze) rather than being fetched from a
# published sdist, since it's our own thin PyO3 wrapper.
#
# The same three gotchas as the muxer apply here:
#
#   1. manylinux wheels do NOT run on Android (Bionic libc, not glibc) --
#      the crate must be rebuilt against the NDK regardless of what's on
#      PyPI.
#   2. PyO3 links against libpython on Android, which must be the exact
#      version Chaquopy embeds -- taken from the same prebuilt zip the
#      muxer build downloads.
#   3. Both ABIs are needed: arm64-v8a for phones, x86_64 for the emulator.
#
# Output goes to build/android-native/<abi>/, where build_android_wheels.py
# picks it up and packages it into a standalone wheel. Run both through
# tools/build_android_pipeline.sh rather than calling this directly.
set -euo pipefail

# Must match the `version` in chaquopy { defaultConfig { ... } }.
PY_VER="${PY_VER:-3.12}"
CHAQUOPY_VER="${CHAQUOPY_VER:-3.12.12-0}"
ANDROID_API="${ANDROID_API:-24}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE="$ROOT/pipeline/native/bliss_analyze"
WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
NATIVE_OUT="${NATIVE_OUT:-$ROOT/build/android-native}"

: "${ANDROID_NDK:?set ANDROID_NDK, e.g. ~/Android/Sdk/ndk/28.2.13676358}"
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found at $TOOLCHAIN"; exit 1; }

rustup target add aarch64-linux-android x86_64-linux-android >/dev/null

build_abi() {
  local abi="$1" triple="$2" clang_prefix="$3" cargo_env="$4"

  echo "==> $abi"
  local libdir="$WORK/chaquopy-$abi/jniLibs/$abi"
  if [ ! -f "$libdir/libpython$PY_VER.so" ]; then
    curl -sL -o "$WORK/chaquopy-$abi.zip" \
      "https://repo.maven.apache.org/maven2/com/chaquo/python/target/$CHAQUOPY_VER/target-$CHAQUOPY_VER-$abi.zip"
    mkdir -p "$WORK/chaquopy-$abi"
    unzip -oq "$WORK/chaquopy-$abi.zip" -d "$WORK/chaquopy-$abi"
  fi
  [ -f "$libdir/libpython$PY_VER.so" ] || {
    echo "libpython$PY_VER.so missing for $abi"; return 1; }

  cd "$CRATE"
  env \
    "CARGO_TARGET_${cargo_env}_LINKER=$TOOLCHAIN/${clang_prefix}${ANDROID_API}-clang" \
    "CC_${triple//-/_}=$TOOLCHAIN/${clang_prefix}${ANDROID_API}-clang" \
    "AR_${triple//-/_}=$TOOLCHAIN/llvm-ar" \
    PYO3_CROSS_PYTHON_VERSION="$PY_VER" \
    PYO3_NO_PYTHON=1 \
    RUSTFLAGS="-L $libdir" \
    cargo build --release --target "$triple"

  mkdir -p "$NATIVE_OUT/$abi"
  # Named for the Python module, not the library: imported as
  # `_bliss_analyze`, never dlopened by an Android "lib*.so" lookup.
  cp "target/$triple/release/lib_bliss_analyze.so" "$NATIVE_OUT/$abi/_bliss_analyze.so"
  file "$NATIVE_OUT/$abi/_bliss_analyze.so" | sed 's/^/    /'
  nm -D --defined-only "$NATIVE_OUT/$abi/_bliss_analyze.so" | grep -q PyInit__bliss_analyze || {
    echo "    WARNING: PyInit__bliss_analyze not exported -- Python cannot import this"
    return 1
  }
  echo "    PyInit__bliss_analyze exported"
}

build_abi arm64-v8a aarch64-linux-android aarch64-linux-android AARCH64_LINUX_ANDROID
build_abi x86_64    x86_64-linux-android  x86_64-linux-android  X86_64_LINUX_ANDROID

echo
echo "==> built into $NATIVE_OUT"
