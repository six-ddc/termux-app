#!/data/data/com.termux/files/usr/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. "$SCRIPT_DIR/lib/termuxplus-installer-lib.sh"

tp_require_termux

TERMUX_OH_MY_ZSH_REPO="${TERMUX_OH_MY_ZSH_REPO:-https://github.com/ohmyzsh/ohmyzsh.git}"
TERMUX_OH_MY_ZSH_BRANCH="master"
TERMUX_OH_MY_ZSH_DIR="${TERMUX_OH_MY_ZSH_DIR:-$PREFIX/share/termuxplus/oh-my-zsh}"
TERMUX_INSTALL_OH_MY_ZSH="${TERMUX_INSTALL_OH_MY_ZSH:-}"
TERMUX_OH_MY_ZSH_PLUGINS="${TERMUX_OH_MY_ZSH_PLUGINS:-git command-not-found colored-man-pages extract z safe-paste}"
TERMUX_ZSH_AUTOSUGGESTIONS_REPO="${TERMUX_ZSH_AUTOSUGGESTIONS_REPO:-https://github.com/zsh-users/zsh-autosuggestions.git}"
TERMUX_ZSH_AUTOSUGGESTIONS_BRANCH="master"
TERMUX_ZSH_AUTOSUGGESTIONS_DIR="${TERMUX_ZSH_AUTOSUGGESTIONS_DIR:-$PREFIX/share/termuxplus/zsh-autosuggestions}"
TERMUX_INSTALL_ZSH_AUTOSUGGESTIONS="${TERMUX_INSTALL_ZSH_AUTOSUGGESTIONS:-}"
TERMUX_ZSH_SYNTAX_HIGHLIGHTING_REPO="${TERMUX_ZSH_SYNTAX_HIGHLIGHTING_REPO:-https://github.com/zsh-users/zsh-syntax-highlighting.git}"
TERMUX_ZSH_SYNTAX_HIGHLIGHTING_BRANCH="master"
TERMUX_ZSH_SYNTAX_HIGHLIGHTING_DIR="${TERMUX_ZSH_SYNTAX_HIGHLIGHTING_DIR:-$PREFIX/share/termuxplus/zsh-syntax-highlighting}"
TERMUX_INSTALL_ZSH_SYNTAX_HIGHLIGHTING="${TERMUX_INSTALL_ZSH_SYNTAX_HIGHLIGHTING:-}"

INSTALL_OH_MY_ZSH=false
INSTALL_ZSH_AUTOSUGGESTIONS=false
INSTALL_ZSH_SYNTAX_HIGHLIGHTING=false

env_bool_is_true() {
  case "$1" in
    true|yes|y|1|on) return 0 ;;
    false|no|n|0|off) return 1 ;;
    *) return 2 ;;
  esac
}

prompt_bool() {
  env_name="$1"
  prompt_text="$2"
  default_value="$3"
  env_value="$(eval "printf '%s' \"\${$env_name:-}\"")"

  if [ -n "$env_value" ]; then
    bool_status=0
    env_bool_is_true "$env_value" || bool_status=$?
    case "$bool_status" in
      0) return 0 ;;
      1) return 1 ;;
      *)
        case "$env_value" in
          ask|prompt) ;;
          *) tp_die "$env_name must be true, false, ask, or unset" ;;
        esac
        ;;
    esac
  fi

  case "$default_value" in
    true) prompt_suffix="[Y/n]" ;;
    false) prompt_suffix="[y/N]" ;;
    *) tp_die "invalid default value for $env_name: $default_value" ;;
  esac

  if [ ! -t 0 ]; then
    if [ "$default_value" = "true" ]; then
      return 0
    fi
    return 1
  fi

  while true; do
    printf '%s ' "[termuxplus] $prompt_text $prompt_suffix"
    if ! IFS= read -r answer; then
      answer=""
    fi

    case "$answer" in
      "")
        if [ "$default_value" = "true" ]; then
          return 0
        fi
        return 1
        ;;
      y|Y|yes|YES|Yes)
        return 0
        ;;
      n|N|no|NO|No)
        return 1
        ;;
      *)
        tp_warn "please answer yes or no"
        ;;
    esac
  done
}

select_zsh_options() {
  if prompt_bool TERMUX_INSTALL_OH_MY_ZSH "Install oh-my-zsh theme framework?" true; then
    INSTALL_OH_MY_ZSH=true
  fi

  if prompt_bool TERMUX_INSTALL_ZSH_AUTOSUGGESTIONS "Install zsh-autosuggestions plugin?" true; then
    INSTALL_ZSH_AUTOSUGGESTIONS=true
  fi

  if prompt_bool TERMUX_INSTALL_ZSH_SYNTAX_HIGHLIGHTING "Install zsh-syntax-highlighting plugin?" true; then
    INSTALL_ZSH_SYNTAX_HIGHLIGHTING=true
  fi
}

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

  if [ -x "\$PREFIX/bin/zsh" ]; then
    export SHELL="\$PREFIX/bin/zsh"
  fi

  export EDITOR="\${EDITOR:-nano}"
EOF

  if [ "$INSTALL_OH_MY_ZSH" != "true" ]; then
    cat >> "$temp_file" <<'EOF'

  if autoload -Uz compinit 2>/dev/null; then
    compinit -d "$HOME/.zcompdump" 2>/dev/null || true
  fi
EOF
  fi

  if [ "$INSTALL_OH_MY_ZSH" = "true" ]; then
    cat >> "$temp_file" <<EOF

  export ZSH="\$PREFIX/share/termuxplus/oh-my-zsh"
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
EOF
  fi

  if [ "$INSTALL_ZSH_AUTOSUGGESTIONS" = "true" ]; then
    cat >> "$temp_file" <<'EOF'

  if [ -r "$PREFIX/share/termuxplus/zsh-autosuggestions/zsh-autosuggestions.zsh" ]; then
    source "$PREFIX/share/termuxplus/zsh-autosuggestions/zsh-autosuggestions.zsh"
  fi
EOF
  fi

  if [ "$INSTALL_ZSH_SYNTAX_HIGHLIGHTING" = "true" ]; then
    cat >> "$temp_file" <<'EOF'

  if [ -r "$PREFIX/share/termuxplus/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh" ]; then
    source "$PREFIX/share/termuxplus/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh"
  fi
EOF
  fi

  cat >> "$temp_file" <<'EOF'
fi
EOF
  tp_replace_file "$temp_file" "$target_file" "$file_mode"
  rm -rf "$temp_dir"
}

prepare_git_source() {
  source_name="$1"
  source_repo="$2"
  source_branch="$3"
  source_dir="$4"

  if [ -d "$source_dir/.git" ] &&
    ! git -C "$source_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    rm -rf "$source_dir"
  fi

  if [ ! -d "$source_dir/.git" ]; then
    rm -rf "$source_dir"
    mkdir -p "$(dirname "$source_dir")"
    git clone --depth=1 --branch "$source_branch" "$source_repo" "$source_dir"
  fi

  git -C "$source_dir" remote set-url origin "$source_repo"
  git -C "$source_dir" fetch origin \
    "+refs/heads/$source_branch:refs/remotes/origin/$source_branch" \
    --depth=1 >/dev/null
  git -C "$source_dir" checkout -q -f -B "$source_branch" "origin/$source_branch"

  git -C "$source_dir" clean -fdx >/dev/null
  tp_log "$source_name source is ready"
}

prepare_oh_my_zsh_source() {
  prepare_git_source "oh-my-zsh" \
    "$TERMUX_OH_MY_ZSH_REPO" \
    "$TERMUX_OH_MY_ZSH_BRANCH" \
    "$TERMUX_OH_MY_ZSH_DIR"
}

prepare_zsh_autosuggestions_source() {
  prepare_git_source "zsh-autosuggestions" \
    "$TERMUX_ZSH_AUTOSUGGESTIONS_REPO" \
    "$TERMUX_ZSH_AUTOSUGGESTIONS_BRANCH" \
    "$TERMUX_ZSH_AUTOSUGGESTIONS_DIR"
}

prepare_zsh_syntax_highlighting_source() {
  prepare_git_source "zsh-syntax-highlighting" \
    "$TERMUX_ZSH_SYNTAX_HIGHLIGHTING_REPO" \
    "$TERMUX_ZSH_SYNTAX_HIGHLIGHTING_BRANCH" \
    "$TERMUX_ZSH_SYNTAX_HIGHLIGHTING_DIR"
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

select_zsh_options

tp_install_packages zsh zsh-completions

if [ "$INSTALL_OH_MY_ZSH" = "true" ] ||
  [ "$INSTALL_ZSH_AUTOSUGGESTIONS" = "true" ] ||
  [ "$INSTALL_ZSH_SYNTAX_HIGHLIGHTING" = "true" ]; then
  tp_install_packages git ca-certificates
  tp_require_command git
fi

if [ "$INSTALL_OH_MY_ZSH" = "true" ]; then
  prepare_oh_my_zsh_source
else
  tp_log "skipping oh-my-zsh source install"
fi

if [ "$INSTALL_ZSH_AUTOSUGGESTIONS" = "true" ]; then
  prepare_zsh_autosuggestions_source
else
  tp_log "skipping zsh-autosuggestions source install"
fi

if [ "$INSTALL_ZSH_SYNTAX_HIGHLIGHTING" = "true" ]; then
  prepare_zsh_syntax_highlighting_source
else
  tp_log "skipping zsh-syntax-highlighting source install"
fi

write_zshrc "$PREFIX/etc/zshrc" 644
install_default_shell_if_missing
install_default_zshrc_if_missing

tp_log "zsh is ready: $("$PREFIX/bin/zsh" --version 2>&1)"
