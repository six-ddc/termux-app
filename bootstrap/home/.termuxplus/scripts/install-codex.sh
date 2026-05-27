#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/termuxplus-installer-lib.sh"

tp_require_termux

TERMUX_CODEX_VERSION="${TERMUX_CODEX_VERSION:-latest}"
TERMUX_CODEX_NPM_CACHE="${TERMUX_CODEX_NPM_CACHE:-$HOME/.cache/termuxplus/npm-cache}"
TERMUX_CODEX_APT_PACKAGES="${TERMUX_CODEX_APT_PACKAGES:-nodejs npm proot ca-certificates}"
TERMUX_CODEX_TOOL_PACKAGES="${TERMUX_CODEX_TOOL_PACKAGES:-git ripgrep fd jq openssh make}"

write_node_cli_wrapper() {
  target_file="$1"
  cli_script="$2"
  temp_dir="$(tp_mktemp_dir)"
  temp_file="$temp_dir/$(basename "$target_file")"
  cat > "$temp_file" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
PREFIX="\${PREFIX:-/data/data/com.termux/files/usr}"
exec "\$PREFIX/bin/node" "\$PREFIX/$cli_script" "\$@"
EOF
  tp_replace_file "$temp_file" "$target_file" 700
  rm -rf "$temp_dir"
}

repair_npm_wrappers() {
  if [ ! -f "$PREFIX/lib/node_modules/npm/bin/npm-cli.js" ]; then
    return 0
  fi

  write_node_cli_wrapper "$PREFIX/bin/npm" "lib/node_modules/npm/bin/npm-cli.js"
  if [ -f "$PREFIX/lib/node_modules/npm/bin/npx-cli.js" ]; then
    write_node_cli_wrapper "$PREFIX/bin/npx" "lib/node_modules/npm/bin/npx-cli.js"
  fi
}

write_codex_wrapper() {
  target_file="$1"
  temp_dir="$(tp_mktemp_dir)"
  temp_file="$temp_dir/codex"
  cat > "$temp_file" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME="${HOME:-/data/data/com.termux/files/home}"
export PREFIX HOME

export SSL_CERT_FILE="${SSL_CERT_FILE:-$PREFIX/etc/tls/cert.pem}"

CODEX_JS="$PREFIX/lib/node_modules/@openai/codex/bin/codex.js"

for CODEX_NATIVE_BIN in "$PREFIX"/lib/node_modules/@openai/codex/node_modules/@openai/codex-linux-*/vendor/*/bin/codex; do
  if [ -f "$CODEX_NATIVE_BIN" ] && [ ! -x "$CODEX_NATIVE_BIN" ]; then
    chmod 700 "$CODEX_NATIVE_BIN" 2>/dev/null || true
  fi
done

if [ -x "$PREFIX/bin/proot" ] && [ -r "$PREFIX/etc/resolv.conf" ]; then
  exec "$PREFIX/bin/proot" -b "$PREFIX/etc/resolv.conf:/etc/resolv.conf" "$PREFIX/bin/node" "$CODEX_JS" "$@"
fi

exec "$PREFIX/bin/node" "$CODEX_JS" "$@"
EOF
  tp_replace_file "$temp_file" "$target_file" 700
  rm -rf "$temp_dir"
}

install_codex_package() {
  bootstrap_arch="$(tp_bootstrap_arch)"
  if ! codex_cpu="$(tp_codex_cpu_for_arch "$bootstrap_arch")"; then
    tp_die "Codex CLI install only supports aarch64/arm64 and x86_64/amd64; got '$bootstrap_arch'"
  fi
  codex_platform_package="$(tp_codex_platform_package_for_arch "$bootstrap_arch")"
  codex_vendor_dir="$(tp_codex_vendor_dir_for_arch "$bootstrap_arch")"

  temp_dir="$(tp_mktemp_dir)"
  npm_prefix="$temp_dir/npm-prefix"
  mkdir -p "$npm_prefix" "$TERMUX_CODEX_NPM_CACHE"

  tp_log "installing @openai/codex@$TERMUX_CODEX_VERSION for linux/$codex_cpu"
  npm --prefix "$npm_prefix" install -g "@openai/codex@$TERMUX_CODEX_VERSION" \
    --os=linux \
    --cpu="$codex_cpu" \
    --include=optional \
    --force \
    --cache "$TERMUX_CODEX_NPM_CACHE"

  codex_package_root="$npm_prefix/lib/node_modules/@openai/codex"
  codex_js="$codex_package_root/bin/codex.js"
  codex_native_bin="$codex_package_root/node_modules/@openai/$codex_platform_package/vendor/$codex_vendor_dir/bin/codex"

  if [ ! -f "$codex_js" ]; then
    rm -rf "$temp_dir"
    tp_die "Codex JavaScript entry was not installed at $codex_js"
  fi

  if [ -f "$codex_native_bin" ] && [ ! -x "$codex_native_bin" ]; then
    chmod 700 "$codex_native_bin" 2>/dev/null || true
  fi
  if [ ! -x "$codex_native_bin" ]; then
    rm -rf "$temp_dir"
    tp_die "Codex native binary was not installed at $codex_native_bin"
  fi

  mkdir -p "$PREFIX/lib/node_modules/@openai"
  tp_replace_dir_from "$codex_package_root" "$PREFIX/lib/node_modules/@openai/codex"
  chmod 700 "$PREFIX/lib/node_modules/@openai/codex/node_modules/@openai/$codex_platform_package/vendor/$codex_vendor_dir/bin/codex" 2>/dev/null || true
  write_codex_wrapper "$PREFIX/bin/codex"
  rm -rf "$temp_dir"
}

tp_require_command apt-get
tp_require_command dpkg-query

# Package names are controlled by these installer defaults and do not contain spaces.
# shellcheck disable=SC2086
tp_install_packages $TERMUX_CODEX_APT_PACKAGES $TERMUX_CODEX_TOOL_PACKAGES
tp_require_command npm
tp_require_command node
tp_require_command tar

repair_npm_wrappers
install_codex_package

tp_log "codex is ready: $("$PREFIX/bin/codex" --version 2>&1 || printf 'installed')"
