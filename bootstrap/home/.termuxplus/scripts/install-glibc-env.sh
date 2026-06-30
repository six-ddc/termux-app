#!/data/data/com.termux/files/usr/bin/sh
# Install the glibc compatibility environment on Termux.
#
# Termux uses bionic libc. Many tools the user wants — uv, python-build-standalone,
# Claude Code, PyPI manylinux wheels — ship only glibc binaries. This script
# sets up the runtime + tooling that makes them work:
#
#   * apt packages: glibc-repo, glibc-runner, patchelf-glibc
#       provides $PREFIX/glibc/lib/ld-linux-aarch64.so.1 (the glibc loader)
#       and $PREFIX/glibc/bin/patchelf (patchelf built against glibc)
#
#   * $PREFIX/bin/glibcify
#       standalone helper that patchelf's any glibc binary's interpreter and
#       (optionally) writes an LD_PRELOAD-unsetting wrapper on PATH.
#       Usage: `glibcify --help`
#
# Idempotent — safe to re-run. Called automatically by install-claude-code.sh,
# install-uv.sh and install-python-glibc.sh; run it directly when you want to
# glibcify a custom binary.
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/termuxplus-installer-lib.sh"

tp_require_termux
tp_ensure_glibc_env

arch="$(tp_bootstrap_arch)"
loader="$(tp_glibc_loader_for_arch "$arch" 2>/dev/null || printf '(unsupported arch %s)' "$arch")"
patchelf_bin="$(tp_find_patchelf 2>/dev/null || printf '(not found)')"

tp_log "glibc compatibility environment is ready"
tp_log "  loader:   $loader"
tp_log "  patchelf: $patchelf_bin"
tp_log "  helper:   $PREFIX/bin/glibcify (try: glibcify --help)"
