#!/usr/bin/env bash
# Builds wrapper-v2 for Android and places it in android/app/src/main/jniLibs.
#
# Android 10+ will not execute a file an app wrote to its own storage, only
# what the package installer extracted to the app's native library folder.
# So the two programs ship as libwrapperd.so (the Rust server) and
# libwrapperworker.so (the worker), and gradle's legacy packaging extracts
# them. Apple's libraries are not here: the app installs those at setup from
# the Apple Music file the user downloads, and the worker dlopens them.
#
# On a real Android system there is no chroot. The worker runs directly with
# WRAPPER_NATIVE_ANDROID=1, which skips the chroot's private DNS setup.
#
#   ANDROID_NDK_R23=~/Android/Sdk/ndk/23.1.7779620 \
#   ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 tools/build_android_wrapper.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK23="${ANDROID_NDK_R23:-$HOME/Android/Sdk/ndk/23.1.7779620}"
NDK="${ANDROID_NDK:-$HOME/Android/Sdk/ndk/28.2.13676358}"
ABIS="${SOIBOI_ABIS:-arm64-v8a,x86_64}"

# Same checkout and patch as the Linux build.
SRC="$ROOT/build/wrapper-src"
WRAPPER_COMMIT="100e0a864e883e03a3ac450a780dd9563fff5271"
if [[ ! -d "$SRC/.git" ]]; then
  git clone --quiet https://github.com/glomatico/wrapper-v2.git "$SRC"
fi
git -C "$SRC" fetch --quiet origin "$WRAPPER_COMMIT"
git -C "$SRC" checkout --quiet --force "$WRAPPER_COMMIT"
git -C "$SRC" apply "$ROOT/tools/patches/wrapper-v2-launcher-path.patch"
git -C "$SRC" apply "$ROOT/tools/patches/wrapper-v2-native-android.patch"

BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

IFS=',' read -ra abi_list <<< "$ABIS"
for abi in "${abi_list[@]}"; do
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android; rust=aarch64-linux-android ;;
    x86_64)    triple=x86_64-linux-android;  rust=x86_64-linux-android ;;
    *) echo "unsupported abi: $abi" >&2; exit 2 ;;
  esac
  out="$ROOT/android/app/src/main/jniLibs/$abi"
  mkdir -p "$out"

  echo "==> worker ($abi, NDK r23b)"
  # The worker links against libc++_shared.so for its SONAME only; at runtime
  # the dynamic linker finds Apple's copy through LD_LIBRARY_PATH.
  link="$(mktemp -d)"
  cp "$NDK23/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$triple/libc++_shared.so" "$link/"
  build="$SRC/build-android-$abi"
  rm -rf "$build"
  cmake -S "$SRC/src/daemon" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK23/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release -DLIBS_DIR="$link" >/dev/null
  cmake --build "$build" >/dev/null
  cp "$build/main" "$out/libwrapperworker.so"
  rm -rf "$link"

  echo "==> server ($abi)"
  rustup target add "$rust" >/dev/null 2>&1 || true
  linker_var="CARGO_TARGET_$(echo "$rust" | tr 'a-z-' 'A-Z_')_LINKER"
  env "$linker_var=$BIN/${triple}24-clang" \
    cargo build --quiet --release --target "$rust" --manifest-path "$SRC/Cargo.toml"
  cp "$SRC/target/$rust/release/wrapperd" "$out/libwrapperd.so"
done

# The pinned hashes the app checks Apple's libraries against at setup.
cp "$SRC/LIBS_VERSION.json" "$ROOT/pipeline/soiboi_pipeline/wrapper_libs_version.json"
echo "==> wrapper binaries in android/app/src/main/jniLibs"
