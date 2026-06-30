#!/data/data/com.termux/files/usr/bin/sh
# Install astral-sh's uv (Python package manager) on Termux.
#
# uv has no Termux-native build (rustup has no aarch64-unknown-linux-android
# target). We install the prebuilt glibc release and run it via the glibc
# compatibility environment (install-glibc-env.sh, called automatically below).
#
# After this script, `uv` and `uvx` are on PATH. To also get a glibc Python
# that can install PyPI manylinux wheels (duckdb, numpy, pandas, ...), run
# install-python-glibc.sh and then `uv run --python python3.12-glibc ...`.
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/termuxplus-installer-lib.sh"

tp_require_termux

TERMUX_UV_VERSION="${TERMUX_UV_VERSION:-0.11.25}"
TERMUX_UV_RELEASE_BASE="${TERMUX_UV_RELEASE_BASE:-https://github.com/astral-sh/uv/releases/download}"
TERMUX_UV_INSTALL_ROOT="${TERMUX_UV_INSTALL_ROOT:-$HOME/.local/share/uv}"

temp_dir=""
cleanup() {
  if [ -n "$temp_dir" ]; then
    rm -rf "$temp_dir"
  fi
}
trap cleanup EXIT HUP INT TERM

uv_triple_for_arch() {
  case "$1" in
    aarch64|arm64) printf '%s\n' "aarch64-unknown-linux-gnu" ;;
    x86_64|amd64) printf '%s\n' "x86_64-unknown-linux-gnu" ;;
    *) return 1 ;;
  esac
}

install_uv_binaries() {
  arch="$(tp_bootstrap_arch)"
  if ! triple="$(uv_triple_for_arch "$arch")"; then
    tp_die "uv install only supports aarch64/arm64 and x86_64/amd64; got '$arch'"
  fi

  asset_name="uv-$triple.tar.gz"
  release_url="$TERMUX_UV_RELEASE_BASE/$TERMUX_UV_VERSION/$asset_name"
  sha_url="$release_url.sha256"

  tarball="$temp_dir/$asset_name"
  tp_log "downloading uv $TERMUX_UV_VERSION for $triple"
  curl -fL --retry 3 --retry-delay 2 "$release_url" -o "$tarball"

  sha_file="$temp_dir/$asset_name.sha256"
  tp_log "verifying SHA-256"
  curl -fL --retry 3 --retry-delay 2 "$sha_url" -o "$sha_file"
  (cd "$temp_dir" && sha256sum -c "$asset_name.sha256")

  tp_log "extracting"
  extract_dir="$temp_dir/extract"
  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"
  tar -C "$extract_dir" -xf "$tarball"
  src_dir="$extract_dir/uv-$triple"
  if [ ! -d "$src_dir" ]; then
    tp_die "expected '$src_dir' in tarball, not found"
  fi

  mkdir -p "$TERMUX_UV_INSTALL_ROOT"
  for tool in uv uvx; do
    src_bin="$src_dir/$tool"
    if [ ! -f "$src_bin" ]; then
      tp_die "expected $tool in uv tarball, not found at $src_bin"
    fi
    target_bin="$TERMUX_UV_INSTALL_ROOT/$tool"
    tp_replace_file "$src_bin" "$target_bin" 700
    tp_glibcify_bin "$target_bin"
    tp_write_glibc_launcher "$PREFIX/bin/$tool" "$target_bin"
  done
}

tp_require_command apt-get
tp_require_command dpkg-query
tp_install_packages curl coreutils tar

tp_ensure_glibc_env

temp_dir="$(tp_mktemp_dir)"
install_uv_binaries

tp_log "uv is ready: $(LD_PRELOAD= "$PREFIX/bin/uv" --version 2>&1)"
tp_log "tip: for manylinux wheels (duckdb, numpy, pandas, ...) also run install-python-glibc.sh"
