#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/termuxplus-installer-lib.sh"

tp_require_termux

CLAUDE_CODE_VERSION="${CLAUDE_CODE_VERSION:-2.1.152}"
CLAUDE_CODE_RELEASE_BASE="${CLAUDE_CODE_RELEASE_BASE:-https://downloads.claude.ai/claude-code-releases}"
CLAUDE_CODE_NPM_METADATA_URL="${CLAUDE_CODE_NPM_METADATA_URL:-https://registry.npmjs.org/@anthropic-ai%2fclaude-code/latest}"
CLAUDE_CODE_INSTALL_ROOT="${CLAUDE_CODE_INSTALL_ROOT:-$HOME/.local/share/claude-code}"
CLAUDE_CODE_BINARY="${CLAUDE_CODE_BINARY:-$CLAUDE_CODE_INSTALL_ROOT/claude}"
CLAUDE_CODE_NATIVE_ROOT="${CLAUDE_CODE_NATIVE_ROOT:-$HOME/.local/share/claude}"
CLAUDE_CODE_WRAPPER="${CLAUDE_CODE_WRAPPER:-$HOME/.local/bin/claude}"
CLAUDE_CODE_PREFIX_WRAPPER="${CLAUDE_CODE_PREFIX_WRAPPER:-$PREFIX/bin/claude}"
CLAUDE_CODE_WRITE_PREFIX_WRAPPER="${CLAUDE_CODE_WRITE_PREFIX_WRAPPER:-true}"
CLAUDE_SETTINGS_FILE="${CLAUDE_SETTINGS_FILE:-$HOME/.claude/settings.json}"
TERMUX_EXEC_LD_PRELOAD="${TERMUX_EXEC_LD_PRELOAD:-$PREFIX/lib/libtermux-exec-ld-preload.so}"
CLAUDE_CODE_INSTALLED_VERSION=""

temp_dir=""
cleanup() {
  if [ -n "$temp_dir" ]; then
    rm -rf "$temp_dir"
  fi
}
trap cleanup EXIT HUP INT TERM

claude_platform_for_arch() {
  case "$1" in
    aarch64|arm64) printf '%s\n' "linux-arm64" ;;
    x86_64|amd64) printf '%s\n' "linux-x64" ;;
    *) return 1 ;;
  esac
}

glibc_ld_for_arch() {
  case "$1" in
    aarch64|arm64) printf '%s\n' "$PREFIX/glibc/lib/ld-linux-aarch64.so.1" ;;
    x86_64|amd64) printf '%s\n' "$PREFIX/glibc/lib/ld-linux-x86-64.so.2" ;;
    *) return 1 ;;
  esac
}

resolve_claude_version() {
  if [ "$CLAUDE_CODE_VERSION" != "latest" ]; then
    printf '%s\n' "$CLAUDE_CODE_VERSION"
    return 0
  fi

  tp_log "resolving latest Claude Code version"
  curl -fsSL "$CLAUDE_CODE_NPM_METADATA_URL" | jq -er '.version'
}

find_patchelf() {
  if tp_has_command patchelf; then
    command -v patchelf
    return 0
  fi
  if [ -x "$PREFIX/glibc/bin/patchelf" ]; then
    printf '%s\n' "$PREFIX/glibc/bin/patchelf"
    return 0
  fi
  if tp_has_command patchelf-glibc; then
    command -v patchelf-glibc
    return 0
  fi
  return 1
}

install_claude_dependencies() {
  tp_require_command apt-get
  tp_require_command dpkg-query

  tp_install_packages curl jq coreutils termux-exec glibc-repo

  # glibc-repo adds an apt source; refresh indexes after it is present.
  tp_require_apt_config
  tp_log "refreshing apt package indexes after glibc-repo"
  DEBIAN_FRONTEND=noninteractive apt-get update

  tp_install_packages glibc-runner patchelf-glibc
}

download_and_patch_claude() {
  arch="$(tp_bootstrap_arch)"
  if ! platform="$(claude_platform_for_arch "$arch")"; then
    tp_die "Claude Code native install only supports aarch64/arm64 and x86_64/amd64; got '$arch'"
  fi
  if ! glibc_ld="$(glibc_ld_for_arch "$arch")"; then
    tp_die "unsupported glibc loader architecture: $arch"
  fi
  if [ ! -r "$glibc_ld" ]; then
    tp_die "glibc-runner loader not found: $glibc_ld"
  fi
  if ! patchelf_bin="$(find_patchelf)"; then
    tp_die "patchelf command not found after installing patchelf-glibc"
  fi

  version="$(resolve_claude_version)"
  release_url="$CLAUDE_CODE_RELEASE_BASE/$version"
  manifest_file="$temp_dir/manifest.json"
  raw_binary="$temp_dir/claude"

  tp_log "downloading Claude Code manifest for $version"
  curl -fsSL "$release_url/manifest.json" -o "$manifest_file"

  binary_name="$(jq -er --arg platform "$platform" '.platforms[$platform].binary // "claude"' "$manifest_file")"
  expected_checksum="$(jq -er --arg platform "$platform" '.platforms[$platform].checksum' "$manifest_file")"

  tp_log "downloading Claude Code $version for $platform"
  curl -fL --retry 3 --retry-delay 2 "$release_url/$platform/$binary_name" -o "$raw_binary"

  actual_checksum="$(sha256sum "$raw_binary" | cut -d' ' -f1)"
  if [ "$actual_checksum" != "$expected_checksum" ]; then
    rm -f "$raw_binary"
    tp_die "checksum mismatch for Claude Code $version: $actual_checksum != $expected_checksum"
  fi

  chmod 700 "$raw_binary"
  tp_log "patching ELF interpreter to $glibc_ld"
  LD_PRELOAD= "$patchelf_bin" --set-interpreter "$glibc_ld" "$raw_binary"

  mkdir -p "$(dirname "$CLAUDE_CODE_BINARY")"
  tp_replace_file "$raw_binary" "$CLAUDE_CODE_BINARY" 700
  printf '%s\n' "$version" > "$CLAUDE_CODE_INSTALL_ROOT/version"
  printf '%s\n' "$actual_checksum" > "$CLAUDE_CODE_INSTALL_ROOT/upstream.sha256"
  CLAUDE_CODE_INSTALLED_VERSION="$version"
}

write_claude_launcher() {
  target_file="$1"
  target_binary="$2"
  temp_launcher="$temp_dir/$(basename "$target_file").launcher"

  cat > "$temp_launcher" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
export DISABLE_AUTOUPDATER="\${DISABLE_AUTOUPDATER:-1}"
export DISABLE_UPDATES="\${DISABLE_UPDATES:-1}"
unset LD_PRELOAD
exec /system/bin/sh -c 'exec "\$0" "\$@"' "$target_binary" "\$@"
EOF
  tp_replace_file "$temp_launcher" "$target_file" 700
}

write_claude_wrappers() {
  if [ -z "$CLAUDE_CODE_INSTALLED_VERSION" ]; then
    tp_die "internal error: Claude Code version was not resolved before writing wrappers"
  fi

  native_version_launcher="$CLAUDE_CODE_NATIVE_ROOT/versions/$CLAUDE_CODE_INSTALLED_VERSION"

  write_claude_launcher "$CLAUDE_CODE_WRAPPER" "$CLAUDE_CODE_BINARY"
  # Claude's native layout may relink ~/.local/bin/claude to this version path.
  # Keep the version path as a launcher too, so that relink remains Termux-safe.
  write_claude_launcher "$native_version_launcher" "$CLAUDE_CODE_BINARY"

  case "$CLAUDE_CODE_WRITE_PREFIX_WRAPPER" in
    true)
      write_claude_launcher "$CLAUDE_CODE_PREFIX_WRAPPER" "$CLAUDE_CODE_BINARY"
      ;;
    false) ;;
    *) tp_die "CLAUDE_CODE_WRITE_PREFIX_WRAPPER must be true or false" ;;
  esac
}

write_claude_settings() {
  if [ ! -r "$TERMUX_EXEC_LD_PRELOAD" ]; then
    tp_die "termux-exec preload library not found: $TERMUX_EXEC_LD_PRELOAD"
  fi

  settings_dir="$(dirname "$CLAUDE_SETTINGS_FILE")"
  mkdir -p "$settings_dir"

  current_settings="$temp_dir/current-settings.json"
  next_settings="$temp_dir/next-settings.json"
  if [ -s "$CLAUDE_SETTINGS_FILE" ]; then
    if ! jq . "$CLAUDE_SETTINGS_FILE" > "$current_settings"; then
      backup_file="$CLAUDE_SETTINGS_FILE.invalid.$(date +%Y%m%d%H%M%S)"
      mv "$CLAUDE_SETTINGS_FILE" "$backup_file"
      tp_warn "invalid Claude settings JSON was moved to $backup_file"
      printf '%s\n' '{}' > "$current_settings"
    fi
  else
    printf '%s\n' '{}' > "$current_settings"
  fi

  jq --arg preload "$TERMUX_EXEC_LD_PRELOAD" \
    '.autoUpdates = false
      | .env = (.env // {})
      | .env.LD_PRELOAD = $preload
      | .env.DISABLE_AUTOUPDATER = "1"
      | .env.DISABLE_UPDATES = "1"' \
    "$current_settings" > "$next_settings"
  tp_replace_file "$next_settings" "$CLAUDE_SETTINGS_FILE" 600
}

verify_claude_install() {
  tp_log "verifying Claude Code install"
  LD_PRELOAD= "$CLAUDE_CODE_WRAPPER" --version >/dev/null
  tp_log "claude is ready: $(LD_PRELOAD= "$CLAUDE_CODE_WRAPPER" --version 2>&1)"
}

temp_dir="$(tp_mktemp_dir)"
install_claude_dependencies
download_and_patch_claude
write_claude_wrappers
write_claude_settings
verify_claude_install
