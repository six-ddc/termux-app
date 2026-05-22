#!/usr/bin/env bash
set -euo pipefail

# Builds bootstraps for the upstream Termux package identity and prefix.
# The app now installs as com.termux, so generated archives must keep:
# /data/data/com.termux/files/usr

TERMUX_PACKAGE_NAME="${TERMUX_PACKAGE_NAME:-com.termux}"
TERMUX_APP_DATA_DIR="${TERMUX_APP_DATA_DIR:-/data/data/${TERMUX_PACKAGE_NAME}}"
TERMUX_PREFIX="${TERMUX_PREFIX:-${TERMUX_APP_DATA_DIR}/files/usr}"
TERMUX_PACKAGES_REPO="${TERMUX_PACKAGES_REPO:-https://github.com/termux/termux-packages.git}"
TERMUX_PACKAGES_REF="${TERMUX_PACKAGES_REF:-master}"
TERMUX_PACKAGES_DIR="${TERMUX_PACKAGES_DIR:-/tmp/termux-packages-termuxplus}"
TERMUX_BOOTSTRAP_OUTPUT_DIR="${TERMUX_BOOTSTRAP_OUTPUT_DIR:-/tmp/termuxplus-bootstrap}"
TERMUX_BOOTSTRAP_ARCHS="${TERMUX_BOOTSTRAP_ARCHS:-aarch64,arm,i686,x86_64}"
TERMUX_BOOTSTRAP_BASE_PACKAGES="${TERMUX_BOOTSTRAP_BASE_PACKAGES:-zsh,zsh-completions}"
TERMUX_BOOTSTRAP_ADD_PACKAGES="${TERMUX_BOOTSTRAP_ADD_PACKAGES:-}"
TERMUX_BOOTSTRAP_INCLUDE_OH_MY_ZSH="${TERMUX_BOOTSTRAP_INCLUDE_OH_MY_ZSH:-true}"
TERMUX_OH_MY_ZSH_REPO="${TERMUX_OH_MY_ZSH_REPO:-https://github.com/ohmyzsh/ohmyzsh.git}"
TERMUX_OH_MY_ZSH_REF="${TERMUX_OH_MY_ZSH_REF:-cb64103161b69d59e1efefeb761ac85564c44698}"
TERMUX_OH_MY_ZSH_DIR="${TERMUX_OH_MY_ZSH_DIR:-/tmp/termuxplus-oh-my-zsh}"

if [ "$TERMUX_PACKAGE_NAME" != "com.termux" ] || [ "$TERMUX_PREFIX" != "/data/data/com.termux/files/usr" ]; then
  echo "This bootstrap flow only supports com.termux and /data/data/com.termux/files/usr." >&2
  echo "Custom Android package/prefix builds were removed because official apt packages are prefix-bound." >&2
  exit 1
fi

required_commands=(docker git strings tar unzip zip)

for command_name in "${required_commands[@]}"; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "$command_name is required to build Termux bootstrap packages." >&2
    exit 1
  fi
done

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

validate_bootstrap_zip() {
  local bootstrap_zip="$1"
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

  for required_entry in "${required_entries[@]}"; do
    if ! grep -Fx "$required_entry" "$zip_entries_file" >/dev/null; then
      echo "Required bootstrap entry '$required_entry' not found in $bootstrap_zip." >&2
      rm -f "$zip_entries_file" "$zip_content_file"
      return 1
    fi
  done

  rm -f "$zip_entries_file" "$zip_content_file"
}

if [ ! -d "$TERMUX_PACKAGES_DIR/.git" ]; then
  git clone "$TERMUX_PACKAGES_REPO" "$TERMUX_PACKAGES_DIR"
fi

cd "$TERMUX_PACKAGES_DIR"
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
bootstrap_packages=()
IFS=',' read -ra base_packages <<< "$TERMUX_BOOTSTRAP_BASE_PACKAGES"
for package_name in "${base_packages[@]}"; do
  package_name="${package_name#"${package_name%%[![:space:]]*}"}"
  package_name="${package_name%"${package_name##*[![:space:]]}"}"
  if [ -n "$package_name" ]; then
    bootstrap_packages+=("$package_name")
  fi
done
IFS=',' read -ra extra_packages <<< "$TERMUX_BOOTSTRAP_ADD_PACKAGES"
for package_name in "${extra_packages[@]}"; do
  package_name="${package_name#"${package_name%%[![:space:]]*}"}"
  package_name="${package_name%"${package_name##*[![:space:]]}"}"
  if [ -n "$package_name" ]; then
    bootstrap_packages+=("$package_name")
  fi
done
if [ "${#bootstrap_packages[@]}" -gt 0 ]; then
  add_packages_csv="$(IFS=,; echo "${bootstrap_packages[*]}")"
  generate_bootstrap_args+=(--add "$add_packages_csv")
fi

CONTAINER_NAME=termux-bootstrap-generator \
  ./scripts/run-docker.sh \
  env TERMUX_PACKAGE_FORMAT=debian \
  TERMUX_PACKAGE_MANAGER=apt \
  ./scripts/generate-bootstraps.sh "${generate_bootstrap_args[@]}"

if [ "$TERMUX_BOOTSTRAP_INCLUDE_OH_MY_ZSH" = "true" ]; then
  echo "Injecting oh-my-zsh files into bootstrap zips..."
  prepare_oh_my_zsh_source
  for bootstrap_zip in bootstrap-*.zip; do
    inject_oh_my_zsh_files "$PWD/$bootstrap_zip"
  done
fi

for bootstrap_zip in bootstrap-*.zip; do
  validate_bootstrap_zip "$PWD/$bootstrap_zip"
done

mkdir -p "$TERMUX_BOOTSTRAP_OUTPUT_DIR"
rm -f "$TERMUX_BOOTSTRAP_OUTPUT_DIR"/bootstrap-*.zip
cp bootstrap-*.zip "$TERMUX_BOOTSTRAP_OUTPUT_DIR"/

echo "Termux bootstrap zips copied to $TERMUX_BOOTSTRAP_OUTPUT_DIR"
