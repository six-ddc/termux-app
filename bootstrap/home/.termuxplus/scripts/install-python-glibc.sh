#!/data/data/com.termux/files/usr/bin/sh
# Install astral-sh's python-build-standalone (a glibc Python 3.x) on Termux.
#
# Termux's native python is bionic — it cannot load PyPI manylinux wheels
# (duckdb, numpy, pandas, ...). This installs a parallel glibc Python and
# exposes it as `pythonX.Y-glibc` on PATH. Use it directly, or pass it to uv
# (`uv run --python python3.12-glibc ...`) so manylinux wheels install and
# import normally inside uv's ephemeral venvs.
#
# Coexists with install-python.sh (which installs the native Termux python);
# the two pythons live in separate prefixes and do not collide.
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/termuxplus-installer-lib.sh"

tp_require_termux

TERMUX_PYTHON_GLIBC_RELEASE="${TERMUX_PYTHON_GLIBC_RELEASE:-20260623}"
TERMUX_PYTHON_GLIBC_VERSION="${TERMUX_PYTHON_GLIBC_VERSION:-3.12.13}"
TERMUX_PYTHON_GLIBC_RELEASE_BASE="${TERMUX_PYTHON_GLIBC_RELEASE_BASE:-https://github.com/astral-sh/python-build-standalone/releases/download}"
TERMUX_PYTHON_GLIBC_INSTALL_ROOT="${TERMUX_PYTHON_GLIBC_INSTALL_ROOT:-$HOME/.local/share/python-glibc}"

temp_dir=""
cleanup() {
  if [ -n "$temp_dir" ]; then
    rm -rf "$temp_dir"
  fi
}
trap cleanup EXIT HUP INT TERM

python_triple_for_arch() {
  case "$1" in
    aarch64|arm64) printf '%s\n' "aarch64-unknown-linux-gnu" ;;
    x86_64|amd64) printf '%s\n' "x86_64-unknown-linux-gnu" ;;
    *) return 1 ;;
  esac
}

python_minor_version() {
  printf '%s\n' "$TERMUX_PYTHON_GLIBC_VERSION" | awk -F. '{printf "%s.%s\n", $1, $2}'
}

install_python_glibc() {
  arch="$(tp_bootstrap_arch)"
  if ! triple="$(python_triple_for_arch "$arch")"; then
    tp_die "python-build-standalone install only supports aarch64/arm64 and x86_64/amd64; got '$arch'"
  fi

  asset_name="cpython-${TERMUX_PYTHON_GLIBC_VERSION}+${TERMUX_PYTHON_GLIBC_RELEASE}-${triple}-install_only.tar.gz"
  release_url="$TERMUX_PYTHON_GLIBC_RELEASE_BASE/${TERMUX_PYTHON_GLIBC_RELEASE}/$asset_name"
  # python-build-standalone ships one SHA256SUMS file per release (not a
  # per-asset .sha256 sidecar like uv); pull the line for our asset out of it.
  sums_url="$TERMUX_PYTHON_GLIBC_RELEASE_BASE/${TERMUX_PYTHON_GLIBC_RELEASE}/SHA256SUMS"

  tarball="$temp_dir/$asset_name"
  tp_log "downloading python-build-standalone $TERMUX_PYTHON_GLIBC_VERSION+$TERMUX_PYTHON_GLIBC_RELEASE for $triple"
  curl -fL --retry 3 --retry-delay 2 "$release_url" -o "$tarball"

  sums_file="$temp_dir/SHA256SUMS"
  expected_line_file="$temp_dir/$asset_name.sha256"
  tp_log "verifying SHA-256"
  curl -fL --retry 3 --retry-delay 2 "$sums_url" -o "$sums_file"
  # `+` in the asset name is a regex metachar; match the file name field
  # exactly with awk instead of building a regex.
  awk -v name="$asset_name" '$2 == name { print; found=1 } END { exit !found }' \
    "$sums_file" > "$expected_line_file" \
    || tp_die "SHA256SUMS contains no entry for $asset_name (release ${TERMUX_PYTHON_GLIBC_RELEASE})"
  (cd "$temp_dir" && sha256sum -c "$asset_name.sha256")

  tp_log "extracting (~150MB uncompressed)"
  extract_dir="$temp_dir/extract"
  rm -rf "$extract_dir"
  mkdir -p "$extract_dir"
  tar -C "$extract_dir" -xf "$tarball"
  src_root="$extract_dir/python"
  if [ ! -d "$src_root" ]; then
    tp_die "expected 'python/' directory in tarball, not found at $src_root"
  fi

  tp_replace_dir_from "$src_root" "$TERMUX_PYTHON_GLIBC_INSTALL_ROOT/python"

  minor="$(python_minor_version)"
  real_python="$TERMUX_PYTHON_GLIBC_INSTALL_ROOT/python/bin/python${minor}"
  if [ ! -f "$real_python" ]; then
    tp_die "expected python${minor} binary at $real_python"
  fi

  tp_glibcify_bin "$real_python"
  tp_write_glibc_launcher "$PREFIX/bin/python${minor}-glibc" "$real_python"
}

tp_require_command apt-get
tp_require_command dpkg-query
tp_install_packages curl coreutils tar

tp_ensure_glibc_env

temp_dir="$(tp_mktemp_dir)"
install_python_glibc

minor="$(python_minor_version)"
tp_log "python${minor}-glibc is ready: $(LD_PRELOAD= "$PREFIX/bin/python${minor}-glibc" --version 2>&1)"
tp_log "tip: with uv, pass it explicitly:  uv run --python python${minor}-glibc <script>.py"
