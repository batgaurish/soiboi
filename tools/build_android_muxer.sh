#!/usr/bin/env bash
# Cross-compiles gamdl's native decrypt/mux engine for Android.
#
# This is the component that blocks standalone downloading on Android.
# Everything else gamdl needs is pure Python and runs under Chaquopy unchanged,
# but `gamdl._ammuxer` is a Rust extension doing both FairPlay decryption and
# MP4 muxing -- there is no ffmpeg fallback to substitute for it.
#
# Three things make this work, and each is easy to get wrong:
#
#   1. manylinux wheels do NOT run on Android. Android uses Bionic libc, not
#      glibc, so the published aarch64 wheel is useless here and the crate must
#      be rebuilt against the NDK.
#
#   2. PyO3 links against libpython on Android (unlike desktop Unix, where
#      symbols resolve from the host interpreter). The link step therefore
#      needs a real Android libpython -- which is what Chaquopy ships, and it
#      must be the *same version* the app embeds, or the SONAME will not
#      resolve at load time.
#
#   3. Both ABIs are needed in practice: arm64-v8a for phones, x86_64 for the
#      emulator. Building only arm64 means the feature silently fails every
#      time it is tested on an emulator.
#
# Output goes to build/android-native/<abi>/, where build_android_wheels.py
# picks it up and embeds it in a per-ABI gamdl wheel. Run both through
# tools/build_android_pipeline.sh rather than calling this directly.
#
# Verified: produces ELF shared objects exporting PyInit__ammuxer, needing only
# libpython, libdl and libc.
set -euo pipefail

# Must match the `version` in chaquopy { defaultConfig { ... } }.
PY_VER="${PY_VER:-3.12}"
CHAQUOPY_VER="${CHAQUOPY_VER:-3.12.12-0}"
ANDROID_API="${ANDROID_API:-24}"
GAMDL_VER="${GAMDL_VER:-3.8.5}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$WORK"
NATIVE_OUT="${NATIVE_OUT:-$ROOT/build/android-native}"

: "${ANDROID_NDK:?set ANDROID_NDK, e.g. ~/Android/Sdk/ndk/28.2.13676358}"
TOOLCHAIN="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$TOOLCHAIN" ] || { echo "NDK toolchain not found at $TOOLCHAIN"; exit 1; }

echo "==> fetching gamdl $GAMDL_VER source (the wheel contains no Rust)"
cd "$WORK"
if [ ! -d "gamdl-$GAMDL_VER" ]; then
  pip download "gamdl==$GAMDL_VER" --no-deps --no-binary :all: -d . >/dev/null
  tar xzf "gamdl-$GAMDL_VER.tar.gz"
fi
CRATE="$WORK/gamdl-$GAMDL_VER/gamdl/downloader/ammuxer"

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
  # Named for the Python module, not the library: it is imported as
  # gamdl._ammuxer, never dlopened by an Android "lib*.so" lookup.
  cp "target/$triple/release/lib_ammuxer.so" "$NATIVE_OUT/$abi/_ammuxer.so"
  file "$NATIVE_OUT/$abi/_ammuxer.so" | sed 's/^/    /'
  nm -D --defined-only "$NATIVE_OUT/$abi/_ammuxer.so" | grep -q PyInit__ammuxer || {
    echo "    WARNING: PyInit__ammuxer not exported -- Python cannot import this"
    return 1
  }
  echo "    PyInit__ammuxer exported"
}

build_abi arm64-v8a aarch64-linux-android aarch64-linux-android AARCH64_LINUX_ANDROID
build_abi x86_64    x86_64-linux-android  x86_64-linux-android  X86_64_LINUX_ANDROID

echo
echo "==> built into $NATIVE_OUT"
