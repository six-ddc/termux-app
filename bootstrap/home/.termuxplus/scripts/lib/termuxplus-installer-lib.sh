#!/data/data/com.termux/files/usr/bin/sh

TERMUXPLUS_EXPECTED_PREFIX="${TERMUXPLUS_EXPECTED_PREFIX:-/data/data/com.termux/files/usr}"
TERMUXPLUS_EXPECTED_HOME="${TERMUXPLUS_EXPECTED_HOME:-/data/data/com.termux/files/home}"

tp_log() {
  printf '%s\n' "[termuxplus] $*"
}

tp_warn() {
  printf '%s\n' "[termuxplus] warning: $*" >&2
}

tp_die() {
  printf '%s\n' "[termuxplus] error: $*" >&2
  exit 1
}

tp_has_command() {
  command -v "$1" >/dev/null 2>&1
}

tp_require_termux() {
  PREFIX="${PREFIX:-$TERMUXPLUS_EXPECTED_PREFIX}"
  HOME="${HOME:-$TERMUXPLUS_EXPECTED_HOME}"
  export PREFIX HOME

  if [ "$PREFIX" != "$TERMUXPLUS_EXPECTED_PREFIX" ]; then
    tp_die "unsupported PREFIX '$PREFIX'; expected '$TERMUXPLUS_EXPECTED_PREFIX'"
  fi
  if [ ! -d "$PREFIX" ]; then
    tp_die "PREFIX does not exist: $PREFIX"
  fi

  mkdir -p "$HOME"
  PATH="$PREFIX/bin:$PATH"
  export PATH

  if [ -n "${ANDROID_ROOT:-}" ] && [ ! -d /data/data/com.termux ]; then
    tp_die "this script must run inside the com.termux app sandbox"
  fi
}

tp_require_command() {
  if ! tp_has_command "$1"; then
    tp_die "required command not found: $1"
  fi
}

tp_package_installed() {
  dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q 'install ok installed'
}

tp_apt_update_once() {
  if [ "${TERMUXPLUS_APT_UPDATED:-false}" = "true" ]; then
    return 0
  fi

  tp_require_apt_config
  tp_log "updating apt package indexes"
  DEBIAN_FRONTEND=noninteractive apt-get update
  TERMUXPLUS_APT_UPDATED=true
  export TERMUXPLUS_APT_UPDATED
}

tp_install_packages() {
  missing_packages=""
  for package_name in "$@"; do
    if [ -z "$package_name" ]; then
      continue
    fi
    if ! tp_package_installed "$package_name"; then
      missing_packages="$missing_packages $package_name"
    fi
  done

  if [ -z "$missing_packages" ]; then
    tp_log "packages already installed: $*"
    return 0
  fi

  tp_apt_update_once
  # Package names are controlled by these installer scripts and do not contain spaces.
  # shellcheck disable=SC2086
  DEBIAN_FRONTEND=noninteractive apt-get install -y $missing_packages
}

tp_mktemp_dir() {
  temp_root="${TMPDIR:-$PREFIX/tmp}"
  mkdir -p "$temp_root"
  mktemp -d "$temp_root/termuxplus.XXXXXXXX" 2>/dev/null || mktemp -d
}

tp_replace_file() {
  source_file="$1"
  target_file="$2"
  file_mode="$3"
  target_dir="$(dirname "$target_file")"
  target_base="$(basename "$target_file")"
  temp_file="$target_dir/.$target_base.tmp.$$"

  mkdir -p "$target_dir"
  rm -f "$temp_file"
  cp "$source_file" "$temp_file"
  chmod "$file_mode" "$temp_file"
  mv -f "$temp_file" "$target_file"
}

tp_require_apt_config() {
  apt_dir="$PREFIX/etc/apt"
  trusted_dir="$apt_dir/trusted.gpg.d"

  if [ ! -s "$apt_dir/sources.list" ]; then
    tp_die "missing apt sources.list at $apt_dir/sources.list; the official bootstrap should create it"
  fi

  has_trusted_key=false
  if [ -d "$trusted_dir" ]; then
    for trusted_key in "$trusted_dir"/*.gpg; do
      if { [ -f "$trusted_key" ] || [ -L "$trusted_key" ]; } && [ -r "$trusted_key" ]; then
        has_trusted_key=true
        break
      fi
    done
  fi

  if [ "$has_trusted_key" != "true" ]; then
    tp_die "missing apt trusted keyring under $trusted_dir; the official bootstrap should create it"
  fi
}

tp_replace_dir_from() {
  source_dir="$1"
  target_dir="$2"
  target_parent="$(dirname "$target_dir")"
  target_base="$(basename "$target_dir")"
  new_dir="$target_parent/.$target_base.new.$$"
  backup_dir="$target_parent/.$target_base.old.$$"

  [ -d "$source_dir" ] || tp_die "source directory does not exist: $source_dir"
  mkdir -p "$target_parent"
  rm -rf "$new_dir" "$backup_dir"
  mkdir -p "$new_dir"

  (
    cd "$source_dir"
    tar -cf - .
  ) | (
    cd "$new_dir"
    tar -xf -
  )

  if [ -e "$target_dir" ] || [ -L "$target_dir" ]; then
    mv "$target_dir" "$backup_dir"
  fi

  if mv "$new_dir" "$target_dir"; then
    rm -rf "$backup_dir"
  else
    if [ -e "$backup_dir" ] || [ -L "$backup_dir" ]; then
      mv "$backup_dir" "$target_dir"
    fi
    rm -rf "$new_dir"
    tp_die "failed to replace directory: $target_dir"
  fi
}

tp_bootstrap_arch() {
  if tp_has_command dpkg; then
    dpkg --print-architecture
  else
    uname -m
  fi
}

tp_codex_cpu_for_arch() {
  case "$1" in
    aarch64|arm64) printf '%s\n' "arm64" ;;
    x86_64|amd64) printf '%s\n' "x64" ;;
    *) return 1 ;;
  esac
}

tp_codex_platform_package_for_arch() {
  case "$1" in
    aarch64|arm64) printf '%s\n' "codex-linux-arm64" ;;
    x86_64|amd64) printf '%s\n' "codex-linux-x64" ;;
    *) return 1 ;;
  esac
}

tp_codex_vendor_dir_for_arch() {
  case "$1" in
    aarch64|arm64) printf '%s\n' "aarch64-unknown-linux-musl" ;;
    x86_64|amd64) printf '%s\n' "x86_64-unknown-linux-musl" ;;
    *) return 1 ;;
  esac
}

tp_codex_supported_arch() {
  tp_codex_cpu_for_arch "$1" >/dev/null 2>&1
}

tp_script_dir() {
  CDPATH= cd -- "$(dirname -- "$0")" && pwd
}
