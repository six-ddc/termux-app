#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/termuxplus-installer-lib.sh"

tp_require_termux

TERMUX_OH_MY_ZSH_REPO="${TERMUX_OH_MY_ZSH_REPO:-https://github.com/ohmyzsh/ohmyzsh.git}"
TERMUX_OH_MY_ZSH_BRANCH="master"
TERMUX_OH_MY_ZSH_DIR="${TERMUX_OH_MY_ZSH_DIR:-$PREFIX/share/termuxplus/oh-my-zsh}"
TERMUX_INSTALL_OH_MY_ZSH="${TERMUX_INSTALL_OH_MY_ZSH:-true}"
TERMUX_OH_MY_ZSH_PLUGINS="${TERMUX_OH_MY_ZSH_PLUGINS:-git}"

write_zshrc() {
  target_file="$1"
  file_mode="$2"
  temp_dir="$(tp_mktemp_dir)"
  temp_file="$temp_dir/zshrc"
  cat > "$temp_file" <<EOF
# Created by TermuxPlus. Re-run install-zsh.sh to refresh this managed default.
if [ -z "\${TERMUXPLUS_ZSHRC_LOADED:-}" ]; then
  export TERMUXPLUS_ZSHRC_LOADED=1

  if [ -z "\${PREFIX:-}" ]; then
    export PREFIX="$PREFIX"
  fi

  export ZSH="\$PREFIX/share/termuxplus/oh-my-zsh"
  if [ -x "\$PREFIX/bin/zsh" ]; then
    export SHELL="\$PREFIX/bin/zsh"
  fi

  ZSH_THEME="robbyrussell"
  plugins=($TERMUX_OH_MY_ZSH_PLUGINS)

  DISABLE_AUTO_UPDATE="true"
  DISABLE_UPDATE_PROMPT="true"
  ZSH_DISABLE_COMPFIX="true"
  ENABLE_CORRECTION="false"
  COMPLETION_WAITING_DOTS="true"

  if [ -r "\$ZSH/oh-my-zsh.sh" ]; then
    source "\$ZSH/oh-my-zsh.sh"
  fi

  # Termux runs as an Android app uid (u0_aNNN), so oh-my-zsh's default
  # %n@%m:%~ title is noisy. Keep tab titles focused on the current directory.
  ZSH_THEME_TERM_TAB_TITLE_IDLE="%~"
  ZSH_THEME_TERM_TITLE_IDLE="%~"

  export EDITOR="\${EDITOR:-nano}"
fi
EOF
  tp_replace_file "$temp_file" "$target_file" "$file_mode"
  rm -rf "$temp_dir"
}

prepare_oh_my_zsh_source() {
  if [ -d "$TERMUX_OH_MY_ZSH_DIR/.git" ] &&
    ! git -C "$TERMUX_OH_MY_ZSH_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    rm -rf "$TERMUX_OH_MY_ZSH_DIR"
  fi

  if [ ! -d "$TERMUX_OH_MY_ZSH_DIR/.git" ]; then
    rm -rf "$TERMUX_OH_MY_ZSH_DIR"
    mkdir -p "$(dirname "$TERMUX_OH_MY_ZSH_DIR")"
    git clone --depth=1 --branch "$TERMUX_OH_MY_ZSH_BRANCH" "$TERMUX_OH_MY_ZSH_REPO" "$TERMUX_OH_MY_ZSH_DIR"
  fi

  git -C "$TERMUX_OH_MY_ZSH_DIR" remote set-url origin "$TERMUX_OH_MY_ZSH_REPO"
  git -C "$TERMUX_OH_MY_ZSH_DIR" fetch origin \
    "+refs/heads/$TERMUX_OH_MY_ZSH_BRANCH:refs/remotes/origin/$TERMUX_OH_MY_ZSH_BRANCH" \
    --depth=1 >/dev/null
  git -C "$TERMUX_OH_MY_ZSH_DIR" checkout -q -f -B \
    "$TERMUX_OH_MY_ZSH_BRANCH" "origin/$TERMUX_OH_MY_ZSH_BRANCH"

  git -C "$TERMUX_OH_MY_ZSH_DIR" clean -fdx >/dev/null
}

install_default_shell_if_missing() {
  shell_dir="$HOME/.termux"
  shell_link="$shell_dir/shell"
  temp_link="$shell_dir/.shell.tmp.$$"

  if [ ! -x "$PREFIX/bin/zsh" ] || [ -e "$shell_link" ] || [ -L "$shell_link" ]; then
    return 0
  fi

  mkdir -p "$shell_dir"
  rm -f "$temp_link"
  ln -s "$PREFIX/bin/zsh" "$temp_link"
  mv -f "$temp_link" "$shell_link"
  chmod 700 "$shell_dir"
}

has_user_zsh_startup_file() {
  for startup_file in "$HOME/.zshenv" "$HOME/.zprofile" "$HOME/.zshrc" "$HOME/.zlogin"; do
    if [ -e "$startup_file" ] || [ -L "$startup_file" ]; then
      return 0
    fi
  done
  return 1
}

has_termuxplus_managed_zshrc() {
  if [ ! -f "$HOME/.zshrc" ]; then
    return 1
  fi

  grep -q "Created by TermuxPlus" "$HOME/.zshrc" 2>/dev/null
}

install_default_zshrc_if_missing() {
  if has_user_zsh_startup_file && ! has_termuxplus_managed_zshrc; then
    return 0
  fi

  write_zshrc "$HOME/.zshrc" 600
}

tp_require_command apt-get
tp_require_command dpkg-query

tp_install_packages zsh zsh-completions

case "$TERMUX_INSTALL_OH_MY_ZSH" in
  true)
    tp_install_packages git ca-certificates
    tp_require_command git
    prepare_oh_my_zsh_source
    ;;
  false)
    tp_log "skipping oh-my-zsh source install"
    ;;
  *)
    tp_die "TERMUX_INSTALL_OH_MY_ZSH must be true or false"
    ;;
esac

write_zshrc "$PREFIX/etc/zshrc" 644
install_default_shell_if_missing
install_default_zshrc_if_missing

tp_log "zsh is ready: $("$PREFIX/bin/zsh" --version 2>&1)"
