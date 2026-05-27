#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/termuxplus-installer-lib.sh"

tp_require_termux
tp_require_command apt-get
tp_require_command dpkg-query

tp_install_packages python

if [ ! -x "$PREFIX/bin/python" ]; then
  tp_die "python package installed, but $PREFIX/bin/python is not executable"
fi

tp_log "python is ready: $("$PREFIX/bin/python" --version 2>&1)"
