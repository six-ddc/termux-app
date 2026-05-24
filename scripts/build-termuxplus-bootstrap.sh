#!/usr/bin/env bash
set -euo pipefail

# Builds bootstraps for the upstream Termux package identity and prefix.
# The app now installs as com.termux, so generated archives must keep:
# /data/data/com.termux/files/usr

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TERMUXPLUS_CACHE_DIR="${TERMUXPLUS_CACHE_DIR:-$PROJECT_DIR/.termuxplus-cache}"
TERMUX_PACKAGE_NAME="${TERMUX_PACKAGE_NAME:-com.termux}"
TERMUX_APP_DATA_DIR="${TERMUX_APP_DATA_DIR:-/data/data/${TERMUX_PACKAGE_NAME}}"
TERMUX_PREFIX="${TERMUX_PREFIX:-${TERMUX_APP_DATA_DIR}/files/usr}"
TERMUX_PACKAGES_REPO="${TERMUX_PACKAGES_REPO:-https://github.com/termux/termux-packages.git}"
TERMUX_PACKAGES_REF="${TERMUX_PACKAGES_REF:-master}"
TERMUX_PACKAGES_DIR="${TERMUX_PACKAGES_DIR:-$TERMUXPLUS_CACHE_DIR/termux-packages}"
TERMUX_BOOTSTRAP_OUTPUT_DIR="${TERMUX_BOOTSTRAP_OUTPUT_DIR:-$PROJECT_DIR/bootstrap-output}"
TERMUX_BOOTSTRAP_ARCHS="${TERMUX_BOOTSTRAP_ARCHS:-aarch64,arm,i686,x86_64}"
TERMUX_BOOTSTRAP_REUSE_GENERATED="${TERMUX_BOOTSTRAP_REUSE_GENERATED:-auto}"
TERMUX_BOOTSTRAP_BASE_PACKAGES="${TERMUX_BOOTSTRAP_BASE_PACKAGES:-zsh,zsh-completions,python}"
TERMUX_BOOTSTRAP_ADD_PACKAGES="${TERMUX_BOOTSTRAP_ADD_PACKAGES:-}"
TERMUX_BOOTSTRAP_INCLUDE_OH_MY_ZSH="${TERMUX_BOOTSTRAP_INCLUDE_OH_MY_ZSH:-true}"
TERMUX_OH_MY_ZSH_REPO="${TERMUX_OH_MY_ZSH_REPO:-https://github.com/ohmyzsh/ohmyzsh.git}"
TERMUX_OH_MY_ZSH_REF="${TERMUX_OH_MY_ZSH_REF:-cb64103161b69d59e1efefeb761ac85564c44698}"
TERMUX_OH_MY_ZSH_DIR="${TERMUX_OH_MY_ZSH_DIR:-$TERMUXPLUS_CACHE_DIR/oh-my-zsh}"
TERMUX_BOOTSTRAP_INCLUDE_CODEX="${TERMUX_BOOTSTRAP_INCLUDE_CODEX:-auto}"
TERMUX_BOOTSTRAP_CODEX_APT_PACKAGES="${TERMUX_BOOTSTRAP_CODEX_APT_PACKAGES:-nodejs,npm,proot,ca-certificates}"
TERMUX_BOOTSTRAP_CODEX_TOOL_PACKAGES="${TERMUX_BOOTSTRAP_CODEX_TOOL_PACKAGES:-git,ripgrep,fd,jq,openssh,make}"
TERMUX_CODEX_VERSION="${TERMUX_CODEX_VERSION:-0.133.0}"
TERMUX_CODEX_NPM_CACHE="${TERMUX_CODEX_NPM_CACHE:-$TERMUXPLUS_CACHE_DIR/npm-cache}"
TERMUX_BOOTSTRAP_CACHE_SIGNATURE_FILE="$TERMUX_PACKAGES_DIR/.termuxplus-bootstrap-signature"
TERMUX_BOOTSTRAP_INCLUDE_HOME_AGENTS="${TERMUX_BOOTSTRAP_INCLUDE_HOME_AGENTS:-true}"
TERMUX_BOOTSTRAP_HOME_AGENTS_FILE="${TERMUX_BOOTSTRAP_HOME_AGENTS_FILE:-$PROJECT_DIR/bootstrap/home/AGENTS.md}"
TERMUX_BOOTSTRAP_HOME_CODEX_DIR="${TERMUX_BOOTSTRAP_HOME_CODEX_DIR:-$PROJECT_DIR/bootstrap/home/.codex}"
TERMUX_BOOTSTRAP_INCLUDE_ANDROID_CLI="${TERMUX_BOOTSTRAP_INCLUDE_ANDROID_CLI:-true}"
TERMUX_BOOTSTRAP_ANDROID_CLI_FILE="${TERMUX_BOOTSTRAP_ANDROID_CLI_FILE:-$PROJECT_DIR/bootstrap/bin/tp-android}"
TERMUX_BOOTSTRAP_CONTAINER_NAME="${TERMUX_BOOTSTRAP_CONTAINER_NAME:-termux-bootstrap-generator}"

if [ "$TERMUX_PACKAGE_NAME" != "com.termux" ] || [ "$TERMUX_PREFIX" != "/data/data/com.termux/files/usr" ]; then
  echo "This bootstrap flow only supports com.termux and /data/data/com.termux/files/usr." >&2
  echo "Custom Android package/prefix builds were removed because official apt packages are prefix-bound." >&2
  exit 1
fi

case "$TERMUX_BOOTSTRAP_INCLUDE_CODEX" in
  auto|true|false) ;;
  *)
    echo "TERMUX_BOOTSTRAP_INCLUDE_CODEX must be auto, true, or false." >&2
    exit 1
    ;;
esac
case "$TERMUX_BOOTSTRAP_REUSE_GENERATED" in
  auto|true|false) ;;
  *)
    echo "TERMUX_BOOTSTRAP_REUSE_GENERATED must be auto, true, or false." >&2
    exit 1
    ;;
esac
case "$TERMUX_BOOTSTRAP_INCLUDE_HOME_AGENTS" in
  true|false) ;;
  *)
    echo "TERMUX_BOOTSTRAP_INCLUDE_HOME_AGENTS must be true or false." >&2
    exit 1
    ;;
esac
if [ "$TERMUX_BOOTSTRAP_INCLUDE_HOME_AGENTS" = "true" ] && [ ! -f "$TERMUX_BOOTSTRAP_HOME_AGENTS_FILE" ]; then
  echo "TERMUX_BOOTSTRAP_HOME_AGENTS_FILE does not exist: $TERMUX_BOOTSTRAP_HOME_AGENTS_FILE" >&2
  exit 1
fi
case "$TERMUX_BOOTSTRAP_INCLUDE_ANDROID_CLI" in
  true|false) ;;
  *)
    echo "TERMUX_BOOTSTRAP_INCLUDE_ANDROID_CLI must be true or false." >&2
    exit 1
    ;;
esac
if [ "$TERMUX_BOOTSTRAP_INCLUDE_ANDROID_CLI" = "true" ] && [ ! -f "$TERMUX_BOOTSTRAP_ANDROID_CLI_FILE" ]; then
  echo "TERMUX_BOOTSTRAP_ANDROID_CLI_FILE does not exist: $TERMUX_BOOTSTRAP_ANDROID_CLI_FILE" >&2
  exit 1
fi

trim_csv_value() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf "%s" "$value"
}

csv_contains_package() {
  local package_csv="$1"
  local expected_package="$2"
  local package_name

  IFS=',' read -ra package_names <<< "$package_csv"
  for package_name in "${package_names[@]}"; do
    package_name="$(trim_csv_value "$package_name")"
    if [ "$package_name" = "$expected_package" ]; then
      return 0
    fi
  done

  return 1
}

codex_cpu_for_arch() {
  case "$1" in
    aarch64) printf "arm64" ;;
    x86_64) printf "x64" ;;
    *) return 1 ;;
  esac
}

codex_platform_package_for_arch() {
  case "$1" in
    aarch64) printf "codex-linux-arm64" ;;
    x86_64) printf "codex-linux-x64" ;;
    *) return 1 ;;
  esac
}

codex_vendor_dir_for_arch() {
  case "$1" in
    aarch64) printf "aarch64-unknown-linux-musl" ;;
    x86_64) printf "x86_64-unknown-linux-musl" ;;
    *) return 1 ;;
  esac
}

codex_required_for_arch() {
  local bootstrap_arch="$1"

  if [ "$TERMUX_BOOTSTRAP_INCLUDE_CODEX" = "false" ]; then
    return 1
  fi

  if codex_cpu_for_arch "$bootstrap_arch" >/dev/null; then
    return 0
  fi

  if [ "$TERMUX_BOOTSTRAP_INCLUDE_CODEX" = "true" ]; then
    echo "Codex CLI bootstrap only supports aarch64 and x86_64, got '$bootstrap_arch'." >&2
    exit 1
  fi

  return 1
}

codex_enabled_for_any_arch() {
  local requested_arch
  IFS=',' read -ra requested_archs <<< "$TERMUX_BOOTSTRAP_ARCHS"
  for requested_arch in "${requested_archs[@]}"; do
    requested_arch="$(trim_csv_value "$requested_arch")"
    if [ -n "$requested_arch" ] && codex_required_for_arch "$requested_arch"; then
      return 0
    fi
  done

  return 1
}

bootstrap_arch_from_zip() {
  local bootstrap_zip_name
  bootstrap_zip_name="$(basename "$1")"
  bootstrap_zip_name="${bootstrap_zip_name#bootstrap-}"
  bootstrap_zip_name="${bootstrap_zip_name%.zip}"
  printf "%s" "$bootstrap_zip_name"
}

is_supported_bootstrap_arch() {
  case "$1" in
    aarch64|arm|i686|x86_64) return 0 ;;
    *) return 1 ;;
  esac
}

collect_generated_bootstrap_zips() {
  local bootstrap_zip
  local bootstrap_arch
  bootstrap_zips=()

  for bootstrap_zip in bootstrap-*.zip; do
    [ -e "$bootstrap_zip" ] || continue
    bootstrap_arch="$(bootstrap_arch_from_zip "$bootstrap_zip")"
    if is_supported_bootstrap_arch "$bootstrap_arch"; then
      bootstrap_zips+=("$bootstrap_zip")
    else
      echo "Ignoring unexpected bootstrap zip name '$bootstrap_zip'." >&2
    fi
  done

  if [ "${#bootstrap_zips[@]}" -eq 0 ]; then
    echo "No generated bootstrap-<arch>.zip files found in $PWD." >&2
    exit 1
  fi
}

append_bootstrap_package() {
  local package_name="$1"
  local existing_package
  for existing_package in "${bootstrap_packages[@]}"; do
    if [ "$existing_package" = "$package_name" ]; then
      return
    fi
  done
  bootstrap_packages+=("$package_name")
}

append_csv_packages() {
  local package_csv="$1"
  local package_name
  IFS=',' read -ra package_names <<< "$package_csv"
  for package_name in "${package_names[@]}"; do
    package_name="$(trim_csv_value "$package_name")"
    if [ -n "$package_name" ]; then
      append_bootstrap_package "$package_name"
    fi
  done
}

collect_bootstrap_packages() {
  bootstrap_packages=()
  append_csv_packages "$TERMUX_BOOTSTRAP_BASE_PACKAGES"
  append_csv_packages "$TERMUX_BOOTSTRAP_ADD_PACKAGES"
  if codex_enabled_for_any_arch; then
    append_csv_packages "$TERMUX_BOOTSTRAP_CODEX_APT_PACKAGES"
    append_csv_packages "$TERMUX_BOOTSTRAP_CODEX_TOOL_PACKAGES"
  fi
}

bootstrap_packages_csv() {
  collect_bootstrap_packages
  if [ "${#bootstrap_packages[@]}" -gt 0 ]; then
    (IFS=,; echo "${bootstrap_packages[*]}")
  fi
}

bootstrap_generation_signature() {
  printf "repo=%s\n" "$TERMUX_PACKAGES_REPO"
  printf "ref=%s\n" "$TERMUX_PACKAGES_REF"
  printf "archs=%s\n" "$TERMUX_BOOTSTRAP_ARCHS"
  printf "pm=apt\n"
  printf "packages=%s\n" "$(bootstrap_packages_csv)"
}

requested_generated_bootstraps_exist() {
  local requested_arch
  IFS=',' read -ra requested_archs <<< "$TERMUX_BOOTSTRAP_ARCHS"
  for requested_arch in "${requested_archs[@]}"; do
    requested_arch="$(trim_csv_value "$requested_arch")"
    if [ -n "$requested_arch" ] && [ ! -f "$TERMUX_PACKAGES_DIR/bootstrap-$requested_arch.zip" ]; then
      return 1
    fi
  done

  return 0
}

generated_bootstrap_cache_matches() {
  if ! requested_generated_bootstraps_exist; then
    return 1
  fi

  if [ ! -f "$TERMUX_BOOTSTRAP_CACHE_SIGNATURE_FILE" ]; then
    return 1
  fi

  if ! diff -q "$TERMUX_BOOTSTRAP_CACHE_SIGNATURE_FILE" <(bootstrap_generation_signature) >/dev/null; then
    return 1
  fi

  return 0
}

ensure_bootstrap_container_mount() {
  local container_name="$1"
  local mounted_source

  if ! docker container inspect "$container_name" >/dev/null 2>&1; then
    return 0
  fi

  mounted_source="$(docker container inspect \
    --format '{{range .Mounts}}{{if eq .Destination "/home/builder/termux-packages"}}{{.Source}}{{end}}{{end}}' \
    "$container_name" 2>/dev/null || true)"

  if [ "$mounted_source" != "$TERMUX_PACKAGES_DIR" ]; then
    echo "Recreating Docker container '$container_name' because its termux-packages mount is '$mounted_source', expected '$TERMUX_PACKAGES_DIR'."
    docker rm -f "$container_name" >/dev/null
  fi
}

should_reuse_generated_bootstraps=false
if [ "$TERMUX_BOOTSTRAP_REUSE_GENERATED" = "true" ]; then
  should_reuse_generated_bootstraps=true
elif [ "$TERMUX_BOOTSTRAP_REUSE_GENERATED" = "auto" ] && generated_bootstrap_cache_matches; then
  should_reuse_generated_bootstraps=true
elif [ "$TERMUX_BOOTSTRAP_REUSE_GENERATED" = "auto" ] && requested_generated_bootstraps_exist; then
  echo "Existing generated bootstrap cache does not match requested package set; regenerating."
fi

required_commands=(diff git strings tar unzip zip)
if [ "$should_reuse_generated_bootstraps" = "false" ]; then
  required_commands+=(docker)
fi
if codex_enabled_for_any_arch; then
  required_commands+=(npm)
fi

for command_name in "${required_commands[@]}"; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "$command_name is required to build Termux bootstrap packages." >&2
    exit 1
  fi
done

mkdir -p "$TERMUXPLUS_CACHE_DIR" \
  "$(dirname "$TERMUX_PACKAGES_DIR")" \
  "$(dirname "$TERMUX_OH_MY_ZSH_DIR")" \
  "$TERMUX_CODEX_NPM_CACHE"

prepare_oh_my_zsh_source() {
  if [ -d "$TERMUX_OH_MY_ZSH_DIR/.git" ] &&
    ! git -C "$TERMUX_OH_MY_ZSH_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    rm -rf "$TERMUX_OH_MY_ZSH_DIR"
  fi

  if [ ! -d "$TERMUX_OH_MY_ZSH_DIR/.git" ]; then
    rm -rf "$TERMUX_OH_MY_ZSH_DIR"
    git clone "$TERMUX_OH_MY_ZSH_REPO" "$TERMUX_OH_MY_ZSH_DIR"
  fi

  git -C "$TERMUX_OH_MY_ZSH_DIR" remote set-url origin "$TERMUX_OH_MY_ZSH_REPO"
  if git -C "$TERMUX_OH_MY_ZSH_DIR" fetch origin "$TERMUX_OH_MY_ZSH_REF" --depth=1 >/dev/null 2>&1; then
    git -C "$TERMUX_OH_MY_ZSH_DIR" checkout --force FETCH_HEAD >/dev/null
  elif git -C "$TERMUX_OH_MY_ZSH_DIR" fetch origin >/dev/null &&
    git -C "$TERMUX_OH_MY_ZSH_DIR" rev-parse --verify "$TERMUX_OH_MY_ZSH_REF^{commit}" >/dev/null 2>&1; then
    git -C "$TERMUX_OH_MY_ZSH_DIR" checkout --force "$TERMUX_OH_MY_ZSH_REF" >/dev/null
  else
    echo "Unable to resolve oh-my-zsh ref '$TERMUX_OH_MY_ZSH_REF'." >&2
    exit 1
  fi
  git -C "$TERMUX_OH_MY_ZSH_DIR" clean -fdx >/dev/null
}

write_zshrc() {
  local target_file="$1"

  cat > "$target_file" <<EOF
if [ -z "\${PREFIX:-}" ]; then
  export PREFIX="$TERMUX_PREFIX"
fi

export ZSH="\$PREFIX/share/termuxplus/oh-my-zsh"
if [ -x "\$PREFIX/bin/zsh" ]; then
  export SHELL="\$PREFIX/bin/zsh"
fi

ZSH_THEME="robbyrussell"
plugins=()

DISABLE_AUTO_UPDATE="true"
DISABLE_UPDATE_PROMPT="true"
ZSH_DISABLE_COMPFIX="true"
ENABLE_CORRECTION="false"
COMPLETION_WAITING_DOTS="true"

if [ -r "\$ZSH/oh-my-zsh.sh" ]; then
  source "\$ZSH/oh-my-zsh.sh"
fi

export EDITOR="\${EDITOR:-nano}"
EOF
}

inject_oh_my_zsh_files() {
  local bootstrap_zip="$1"
  local temp_dir
  local delete_entries=()
  local zip_entry

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/termux-bootstrap-files.XXXXXXXX")"
  mkdir -p "$temp_dir/etc" "$temp_dir/share/termuxplus/oh-my-zsh"
  write_zshrc "$temp_dir/etc/zshrc"

  (
    cd "$TERMUX_OH_MY_ZSH_DIR"
    tar --exclude='./.git' -cf - .
  ) | (
    cd "$temp_dir/share/termuxplus/oh-my-zsh"
    tar -xf -
  )

  while IFS= read -r zip_entry; do
    delete_entries+=("$zip_entry")
  done < <(unzip -Z1 "$bootstrap_zip" | grep -E '^(etc/zshrc|share/termuxplus/oh-my-zsh/)' || true)

  if [ "${#delete_entries[@]}" -gt 0 ]; then
    zip -q -d "$bootstrap_zip" "${delete_entries[@]}" >/dev/null
  fi

  (
    cd "$temp_dir"
    zip -q -r "$bootstrap_zip" etc share
  )
  rm -rf "$temp_dir"
}

inject_home_agents_file() {
  local bootstrap_zip="$1"
  local temp_dir
  local delete_entries=()
  local zip_entry

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/termux-home-agents-files.XXXXXXXX")"
  mkdir -p "$temp_dir/share/termuxplus/home"
  cp "$TERMUX_BOOTSTRAP_HOME_AGENTS_FILE" "$temp_dir/share/termuxplus/home/AGENTS.md"
  chmod 600 "$temp_dir/share/termuxplus/home/AGENTS.md"
  if [ -d "$TERMUX_BOOTSTRAP_HOME_CODEX_DIR" ]; then
    cp -R "$TERMUX_BOOTSTRAP_HOME_CODEX_DIR" "$temp_dir/share/termuxplus/home/.codex"
  fi

  while IFS= read -r zip_entry; do
    delete_entries+=("$zip_entry")
  done < <(unzip -Z1 "$bootstrap_zip" | grep -E '^(share/termuxplus/home/AGENTS\.md|share/termuxplus/home/\.codex/)' || true)

  if [ "${#delete_entries[@]}" -gt 0 ]; then
    zip -q -d "$bootstrap_zip" "${delete_entries[@]}" >/dev/null
  fi

  (
    cd "$temp_dir"
    zip -q -r "$bootstrap_zip" share
  )
  rm -rf "$temp_dir"
}

inject_android_cli_file() {
  local bootstrap_zip="$1"
  local temp_dir
  local delete_entries=()
  local zip_entry

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/termux-android-cli-files.XXXXXXXX")"
  mkdir -p "$temp_dir/bin"
  cp "$TERMUX_BOOTSTRAP_ANDROID_CLI_FILE" "$temp_dir/bin/tp-android"
  chmod 755 "$temp_dir/bin/tp-android"

  while IFS= read -r zip_entry; do
    delete_entries+=("$zip_entry")
  done < <(unzip -Z1 "$bootstrap_zip" | grep -E '^bin/tp-android$' || true)

  if [ "${#delete_entries[@]}" -gt 0 ]; then
    zip -q -d "$bootstrap_zip" "${delete_entries[@]}" >/dev/null
  fi

  (
    cd "$temp_dir"
    zip -q -r "$bootstrap_zip" bin
  )
  rm -rf "$temp_dir"
}

write_codex_wrapper() {
  local target_file="$1"

  cat > "$target_file" <<'EOF'
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
  chmod 755 "$target_file"
}

inject_codex_files() {
  local bootstrap_zip="$1"
  local bootstrap_arch="$2"
  local codex_cpu
  local codex_platform_package
  local codex_vendor_dir
  local temp_dir
  local npm_prefix
  local payload_dir
  local delete_entries=()
  local zip_entry

  codex_cpu="$(codex_cpu_for_arch "$bootstrap_arch")"
  codex_platform_package="$(codex_platform_package_for_arch "$bootstrap_arch")"
  codex_vendor_dir="$(codex_vendor_dir_for_arch "$bootstrap_arch")"

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/termux-codex-files.XXXXXXXX")"
  npm_prefix="$temp_dir/npm-prefix"
  payload_dir="$temp_dir/payload"
  mkdir -p "$npm_prefix" "$payload_dir/bin" "$payload_dir/lib/node_modules/@openai"

  npm --prefix "$npm_prefix" install -g "@openai/codex@$TERMUX_CODEX_VERSION" \
    --os=linux \
    --cpu="$codex_cpu" \
    --include=optional \
    --force \
    --cache "$TERMUX_CODEX_NPM_CACHE"

  local codex_package_root="$npm_prefix/lib/node_modules/@openai/codex"
  local codex_js="$codex_package_root/bin/codex.js"
  local codex_native_bin="$codex_package_root/node_modules/@openai/$codex_platform_package/vendor/$codex_vendor_dir/bin/codex"

  if [ ! -f "$codex_js" ]; then
    echo "Codex CLI JavaScript entry was not installed at $codex_js." >&2
    rm -rf "$temp_dir"
    return 1
  fi

  if [ ! -x "$codex_native_bin" ]; then
    echo "Codex CLI native binary was not installed at $codex_native_bin." >&2
    rm -rf "$temp_dir"
    return 1
  fi

  (
    cd "$npm_prefix/lib/node_modules/@openai"
    tar -cf - codex
  ) | (
    cd "$payload_dir/lib/node_modules/@openai"
    tar -xf -
  )
  write_codex_wrapper "$payload_dir/bin/codex"

  while IFS= read -r zip_entry; do
    delete_entries+=("$zip_entry")
  done < <(unzip -Z1 "$bootstrap_zip" | grep -E '^(bin/codex|lib/node_modules/@openai/codex(/|$))' || true)

  if [ "${#delete_entries[@]}" -gt 0 ]; then
    zip -q -d "$bootstrap_zip" "${delete_entries[@]}" >/dev/null
  fi

  (
    cd "$payload_dir"
    zip -q -r "$bootstrap_zip" bin lib
  )
  rm -rf "$temp_dir"
}

write_node_cli_wrapper() {
  local target_file="$1"
  local cli_script="$2"

  cat > "$target_file" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
PREFIX="\${PREFIX:-/data/data/com.termux/files/usr}"
exec "\$PREFIX/bin/node" "\$PREFIX/$cli_script" "\$@"
EOF
  chmod 755 "$target_file"
}

inject_npm_wrappers() {
  local bootstrap_zip="$1"
  local temp_dir
  local zip_entry
  local delete_entries=()

  if ! unzip -Z1 "$bootstrap_zip" | grep -Fx "lib/node_modules/npm/bin/npm-cli.js" >/dev/null; then
    return 0
  fi

  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/termux-npm-wrapper-files.XXXXXXXX")"
  mkdir -p "$temp_dir/bin"
  unzip -p "$bootstrap_zip" SYMLINKS.txt |
    grep -Ev '←\./bin/(npm|npx)$' > "$temp_dir/SYMLINKS.txt"
  write_node_cli_wrapper "$temp_dir/bin/npm" "lib/node_modules/npm/bin/npm-cli.js"
  write_node_cli_wrapper "$temp_dir/bin/npx" "lib/node_modules/npm/bin/npx-cli.js"

  while IFS= read -r zip_entry; do
    delete_entries+=("$zip_entry")
  done < <(unzip -Z1 "$bootstrap_zip" | grep -E '^(SYMLINKS.txt|bin/npm|bin/npx)$' || true)

  if [ "${#delete_entries[@]}" -gt 0 ]; then
    zip -q -d "$bootstrap_zip" "${delete_entries[@]}" >/dev/null
  fi

  (
    cd "$temp_dir"
    zip -q -r "$bootstrap_zip" SYMLINKS.txt bin
  )
  rm -rf "$temp_dir"
}

validate_bootstrap_zip() {
  local bootstrap_zip="$1"
  local bootstrap_arch="$2"
  local zip_entries_file
  local zip_content_file

  zip_entries_file="$(mktemp "${TMPDIR:-/tmp}/termux-bootstrap-entries.XXXXXXXX")"
  zip_content_file="$(mktemp "${TMPDIR:-/tmp}/termux-bootstrap-content.XXXXXXXX")"
  unzip -Z1 "$bootstrap_zip" > "$zip_entries_file"
  unzip -p "$bootstrap_zip" SYMLINKS.txt bin/pkg etc/apt/sources.list etc/zshrc 'var/lib/dpkg/info/*.list' > "$zip_content_file" 2>/dev/null || true

  if grep -Eq '/data/data/com\.yourcompany\.termuxplus' "$zip_content_file"; then
    echo "Old custom package prefix found in $bootstrap_zip." >&2
    rm -f "$zip_entries_file" "$zip_content_file"
    return 1
  fi

  if ! grep -Eq '/data/data/com\.termux/files/usr' "$zip_content_file"; then
    echo "Expected Termux prefix not found in $bootstrap_zip." >&2
    rm -f "$zip_entries_file" "$zip_content_file"
    return 1
  fi

  if ! grep -Eq 'packages(-cf)?\.termux\.dev|packages\.termux\.org|termux-main' "$zip_content_file"; then
    echo "Official Termux apt source not found in $bootstrap_zip." >&2
    rm -f "$zip_entries_file" "$zip_content_file"
    return 1
  fi

  local required_entries=(
    bin/zsh \
    etc/zshrc \
    var/lib/dpkg/info/zsh.list \
    var/lib/dpkg/info/zsh-completions.list
  )
  if [ "$TERMUX_BOOTSTRAP_INCLUDE_OH_MY_ZSH" = "true" ]; then
    required_entries+=(share/termuxplus/oh-my-zsh/oh-my-zsh.sh)
  fi
  if [ "$TERMUX_BOOTSTRAP_INCLUDE_HOME_AGENTS" = "true" ]; then
    required_entries+=(share/termuxplus/home/AGENTS.md)
    if [ -d "$TERMUX_BOOTSTRAP_HOME_CODEX_DIR/skills/tp-android" ]; then
      required_entries+=(share/termuxplus/home/.codex/skills/tp-android/SKILL.md)
    fi
  fi
  if [ "$TERMUX_BOOTSTRAP_INCLUDE_ANDROID_CLI" = "true" ]; then
    required_entries+=(bin/tp-android)
  fi
  if csv_contains_package "$(bootstrap_packages_csv)" "python"; then
    required_entries+=(var/lib/dpkg/info/python.list)
  fi
  if codex_required_for_arch "$bootstrap_arch"; then
    local codex_platform_package
    local codex_vendor_dir
    codex_platform_package="$(codex_platform_package_for_arch "$bootstrap_arch")"
    codex_vendor_dir="$(codex_vendor_dir_for_arch "$bootstrap_arch")"
    required_entries+=(
      bin/codex \
      bin/npm \
      bin/npx \
      lib/node_modules/@openai/codex/bin/codex.js \
      lib/node_modules/@openai/codex/node_modules/@openai/"$codex_platform_package"/vendor/"$codex_vendor_dir"/bin/codex \
      var/lib/dpkg/info/ca-certificates.list \
      var/lib/dpkg/info/nodejs.list \
      var/lib/dpkg/info/npm.list \
      var/lib/dpkg/info/proot.list
    )
    for codex_tool_package in git ripgrep fd jq openssh make; do
      if csv_contains_package "$TERMUX_BOOTSTRAP_CODEX_TOOL_PACKAGES" "$codex_tool_package"; then
        required_entries+=(var/lib/dpkg/info/"$codex_tool_package".list)
      fi
    done
  fi

  for required_entry in "${required_entries[@]}"; do
    if ! grep -Fx "$required_entry" "$zip_entries_file" >/dev/null; then
      echo "Required bootstrap entry '$required_entry' not found in $bootstrap_zip." >&2
      rm -f "$zip_entries_file" "$zip_content_file"
      return 1
    fi
  done

  rm -f "$zip_entries_file" "$zip_content_file"
}

if [ ! -d "$TERMUX_PACKAGES_DIR/.git" ] && [ "$should_reuse_generated_bootstraps" = "true" ]; then
  echo "Cannot reuse generated bootstraps because $TERMUX_PACKAGES_DIR is not a git checkout." >&2
  exit 1
fi

if [ ! -d "$TERMUX_PACKAGES_DIR/.git" ]; then
  git clone "$TERMUX_PACKAGES_REPO" "$TERMUX_PACKAGES_DIR"
fi

cd "$TERMUX_PACKAGES_DIR"
if [ "$should_reuse_generated_bootstraps" = "true" ]; then
  if ! compgen -G "bootstrap-*.zip" >/dev/null; then
    echo "No bootstrap-*.zip files found in $TERMUX_PACKAGES_DIR to reuse." >&2
    exit 1
  fi
  echo "Reusing existing generated bootstrap zips from $TERMUX_PACKAGES_DIR."
  collect_generated_bootstrap_zips
else
  git remote set-url origin "$TERMUX_PACKAGES_REPO"
  if git fetch origin "$TERMUX_PACKAGES_REF" --depth=1 >/dev/null 2>&1; then
    git -c advice.detachedHead=false checkout --force FETCH_HEAD >/dev/null
  elif git fetch origin >/dev/null &&
    git rev-parse --verify "$TERMUX_PACKAGES_REF^{commit}" >/dev/null 2>&1; then
    git -c advice.detachedHead=false checkout --force "$TERMUX_PACKAGES_REF" >/dev/null
  else
    echo "Unable to resolve termux-packages ref '$TERMUX_PACKAGES_REF'." >&2
    exit 1
  fi
  git clean -fdx >/dev/null
  rm -f bootstrap-*.zip

  generate_bootstrap_args=(--architectures "$TERMUX_BOOTSTRAP_ARCHS" --pm apt)
  collect_bootstrap_packages
  if [ "${#bootstrap_packages[@]}" -gt 0 ]; then
    add_packages_csv="$(IFS=,; echo "${bootstrap_packages[*]}")"
    generate_bootstrap_args+=(--add "$add_packages_csv")
  fi

  ensure_bootstrap_container_mount "$TERMUX_BOOTSTRAP_CONTAINER_NAME"
  CONTAINER_NAME="$TERMUX_BOOTSTRAP_CONTAINER_NAME" \
    ./scripts/run-docker.sh \
    env TERMUX_PACKAGE_FORMAT=debian \
    TERMUX_PACKAGE_MANAGER=apt \
    ./scripts/generate-bootstraps.sh "${generate_bootstrap_args[@]}"
  bootstrap_generation_signature > "$TERMUX_BOOTSTRAP_CACHE_SIGNATURE_FILE"
  collect_generated_bootstrap_zips
fi

if [ "$TERMUX_BOOTSTRAP_INCLUDE_OH_MY_ZSH" = "true" ]; then
  echo "Injecting oh-my-zsh files into bootstrap zips..."
  prepare_oh_my_zsh_source
  for bootstrap_zip in "${bootstrap_zips[@]}"; do
    inject_oh_my_zsh_files "$PWD/$bootstrap_zip"
  done
fi

if [ "$TERMUX_BOOTSTRAP_INCLUDE_HOME_AGENTS" = "true" ]; then
  echo "Injecting Termux home AGENTS.md and Codex skills into bootstrap zips..."
  for bootstrap_zip in "${bootstrap_zips[@]}"; do
    inject_home_agents_file "$PWD/$bootstrap_zip"
  done
fi

if [ "$TERMUX_BOOTSTRAP_INCLUDE_ANDROID_CLI" = "true" ]; then
  echo "Injecting TermuxPlus Android automation CLI into bootstrap zips..."
  for bootstrap_zip in "${bootstrap_zips[@]}"; do
    inject_android_cli_file "$PWD/$bootstrap_zip"
  done
fi

for bootstrap_zip in "${bootstrap_zips[@]}"; do
  bootstrap_arch="$(bootstrap_arch_from_zip "$bootstrap_zip")"
  if codex_required_for_arch "$bootstrap_arch"; then
    echo "Injecting Codex CLI $TERMUX_CODEX_VERSION files into $bootstrap_zip..."
    inject_npm_wrappers "$PWD/$bootstrap_zip"
    inject_codex_files "$PWD/$bootstrap_zip" "$bootstrap_arch"
  elif [ "$TERMUX_BOOTSTRAP_INCLUDE_CODEX" = "auto" ]; then
    echo "Skipping Codex CLI injection for unsupported bootstrap architecture '$bootstrap_arch'."
  fi
done

for bootstrap_zip in "${bootstrap_zips[@]}"; do
  bootstrap_arch="$(bootstrap_arch_from_zip "$bootstrap_zip")"
  validate_bootstrap_zip "$PWD/$bootstrap_zip" "$bootstrap_arch"
done

mkdir -p "$TERMUX_BOOTSTRAP_OUTPUT_DIR"
rm -f "$TERMUX_BOOTSTRAP_OUTPUT_DIR"/bootstrap-*.zip
cp "${bootstrap_zips[@]}" "$TERMUX_BOOTSTRAP_OUTPUT_DIR"/

echo "Termux bootstrap zips copied to $TERMUX_BOOTSTRAP_OUTPUT_DIR"
