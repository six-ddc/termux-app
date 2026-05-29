#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${GHOSTTY_VT_CACHE_DIR:-"$ROOT_DIR/build/ghostty-vt"}"
ZIG_VERSION="${GHOSTTY_VT_ZIG_VERSION:-0.15.2}"
GHOSTTY_REPO="${GHOSTTY_VT_REPO:-https://github.com/ghostty-org/ghostty.git}"
GHOSTTY_REF="${GHOSTTY_VT_REF:-90175950d5004382abd3b0b9528e7be81b0b52ec}"
LIB_VERSION="${GHOSTTY_VT_LIB_VERSION:-1.3.1}"

if [ "$#" -gt 0 ]; then
  ABIS=("$@")
elif [ -n "${GHOSTTY_VT_ABIS:-}" ]; then
  # shellcheck disable=SC2206
  ABIS=(${GHOSTTY_VT_ABIS})
else
  ABIS=(arm64-v8a x86_64)
fi

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64) ZIG_HOST="aarch64-macos" ;;
  Darwin-x86_64) ZIG_HOST="x86_64-macos" ;;
  Linux-aarch64) ZIG_HOST="aarch64-linux" ;;
  Linux-x86_64) ZIG_HOST="x86_64-linux" ;;
  *) echo "Unsupported host for Zig download: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

case "$ZIG_HOST" in
  *-macos) ZIG_ARCHIVE="zig-$ZIG_HOST-$ZIG_VERSION.tar.xz" ;;
  *-linux) ZIG_ARCHIVE="zig-$ZIG_HOST-$ZIG_VERSION.tar.xz" ;;
esac

ZIG_DIR="$CACHE_DIR/zig-$ZIG_VERSION"
ZIG_BIN="$ZIG_DIR/${ZIG_ARCHIVE%.tar.xz}/zig"
if [ ! -x "$ZIG_BIN" ]; then
  mkdir -p "$ZIG_DIR"
  curl -fL "https://ziglang.org/download/$ZIG_VERSION/$ZIG_ARCHIVE" -o "$ZIG_DIR/$ZIG_ARCHIVE"
  tar -C "$ZIG_DIR" -xf "$ZIG_DIR/$ZIG_ARCHIVE"
fi

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
  NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
  NDK_DIR="$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
elif [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/ndk" ]; then
  NDK_DIR="$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
else
  echo "Set ANDROID_NDK_HOME or ANDROID_HOME/ANDROID_SDK_ROOT with an installed NDK." >&2
  exit 1
fi

GHOSTTY_DIR="$CACHE_DIR/ghostty"
if [ ! -d "$GHOSTTY_DIR/.git" ]; then
  git clone "$GHOSTTY_REPO" "$GHOSTTY_DIR"
fi
git -C "$GHOSTTY_DIR" fetch --depth 1 origin "$GHOSTTY_REF"
git -C "$GHOSTTY_DIR" checkout --detach FETCH_HEAD

# Reset any previously-applied local patches so re-runs start from the pinned
# upstream tree, then apply our tracked patches on top. These carry fixes we
# have not (yet) upstreamed; keep each patch minimal and conflict-resistant.
git -C "$GHOSTTY_DIR" checkout -- . 2>/dev/null || true
PATCH_DIR="$ROOT_DIR/scripts/ghostty-vt-patches"
if [ -d "$PATCH_DIR" ]; then
  for patch in "$PATCH_DIR"/*.patch; do
    [ -e "$patch" ] || continue
    echo "Applying ghostty-vt patch: $(basename "$patch")"
    git -C "$GHOSTTY_DIR" apply --verbose "$patch"
  done
fi

target_for_abi() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "arm-linux-androideabi" ;;
    x86) echo "i686-linux-android" ;;
    x86_64) echo "x86_64-linux-android" ;;
    *) echo "Unsupported Android ABI: $1" >&2; return 1 ;;
  esac
}

for ABI in "${ABIS[@]}"; do
  TARGET="$(target_for_abi "$ABI")"
  PREFIX="$CACHE_DIR/out/$ABI"
  ANDROID_NDK_HOME="$NDK_DIR" "$ZIG_BIN" build \
    --build-file "$GHOSTTY_DIR/build.zig" \
    -Demit-lib-vt=true \
    -Dtarget="$TARGET" \
    -Doptimize=ReleaseFast \
    -Dlib-version-string="$LIB_VERSION" \
    --prefix "$PREFIX" \
    --summary failures

  DEST="$ROOT_DIR/terminal-emulator/src/main/jniLibs/$ABI"
  mkdir -p "$DEST"
  cp -L "$PREFIX/lib/libghostty-vt.so" "$DEST/libghostty-vt.so"
  echo "Installed $DEST/libghostty-vt.so"
done
