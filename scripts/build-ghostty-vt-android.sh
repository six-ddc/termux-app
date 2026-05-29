#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_DIR="${GHOSTTY_VT_CACHE_DIR:-"$ROOT_DIR/build/ghostty-vt"}"
ZIG_VERSION="${GHOSTTY_VT_ZIG_VERSION:-0.15.2}"
GHOSTTY_REPO="${GHOSTTY_VT_REPO:-https://github.com/ghostty-org/ghostty.git}"
GHOSTTY_REF="${GHOSTTY_VT_REF:-90175950d5004382abd3b0b9528e7be81b0b52ec}"
LIB_VERSION="${GHOSTTY_VT_LIB_VERSION:-1.3.1}"

require_tool() {
    command -v "$1" >/dev/null 2>&1 || { echo "Required tool not found on PATH: $1" >&2; exit 1; }
}
require_tool git
require_tool curl
require_tool tar

# Pinned SHA-256 of the official Zig release tarballs (from
# https://ziglang.org/download/index.json), mirroring how the project pins the
# Termux bootstrap zips. Keyed by "<version>-<host>"; unknown keys (e.g. a custom
# GHOSTTY_VT_ZIG_VERSION) skip verification with a warning.
expected_zig_sha256() {
    case "$1" in
        0.15.2-aarch64-macos) echo "3cc2bab367e185cdfb27501c4b30b1b0653c28d9f73df8dc91488e66ece5fa6b" ;;
        0.15.2-x86_64-macos)  echo "375b6909fc1495d16fc2c7db9538f707456bfc3373b14ee83fdd3e22b3d43f7f" ;;
        0.15.2-aarch64-linux) echo "958ed7d1e00d0ea76590d27666efbf7a932281b3d7ba0c6b01b0ff26498f667f" ;;
        0.15.2-x86_64-linux)  echo "02aa270f183da276e5b5920b1dac44a63f1a49e55050ebde3aecc9eb82f93239" ;;
        *) echo "" ;;
    esac
}

verify_sha256() {
    local file="$1" expected="$2" actual
    if command -v shasum >/dev/null 2>&1; then
        actual="$(shasum -a 256 "$file" | awk '{print $1}')"
    elif command -v sha256sum >/dev/null 2>&1; then
        actual="$(sha256sum "$file" | awk '{print $1}')"
    else
        echo "Neither shasum nor sha256sum is available to verify $file" >&2
        exit 1
    fi
    if [ "$actual" != "$expected" ]; then
        echo "SHA-256 mismatch for $file" >&2
        echo "  expected: $expected" >&2
        echo "  actual:   $actual" >&2
        rm -f "$file"
        exit 1
    fi
    echo "Verified SHA-256 of $(basename "$file")"
}

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
  ZIG_EXPECTED_SHA="$(expected_zig_sha256 "$ZIG_VERSION-$ZIG_HOST")"
  if [ -n "$ZIG_EXPECTED_SHA" ]; then
    verify_sha256 "$ZIG_DIR/$ZIG_ARCHIVE" "$ZIG_EXPECTED_SHA"
  else
    echo "WARNING: no pinned SHA-256 for Zig $ZIG_VERSION/$ZIG_HOST; skipping toolchain verification" >&2
  fi
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
