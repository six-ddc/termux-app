# AGENTS.md

## Environment

- This is Termux on Android, not a normal Linux distro.
- Current home/workspace: `/data/data/com.termux/files/home`
- Shared storage root: `/storage/emulated/0`
- Device observed here: Android 16/API 36, aarch64, Termux `0.118.0`
- Run as normal Android app user (`u0_a428` here), not root.

## Core Rules

- Put repos, virtualenvs, `node_modules`, build outputs, and agent work under
  `$HOME`, preferably `~/projects`.
- Use shared storage only for user files/imports/exports:
  `/storage/emulated/0`, `/sdcard`, `~/storage/*`.
- Do not expect access to other apps' private data under `/data/data/<package>`.
- Prefer `pkg install ...` for tools. Use `rg`/`rg --files` for search.

## Storage Permission

If `~/storage` is missing or shared storage is not writable:

```sh
termux-setup-storage
```

The user must approve the Android permission dialog.

Useful checks:

```sh
ls -ld ~/storage ~/storage/downloads /storage/emulated/0
printf 'test\n' > ~/storage/downloads/termux-write-test.txt
```

Common links after setup:

```text
~/storage/shared    -> /storage/emulated/0
~/storage/downloads -> /storage/emulated/0/Download
~/storage/dcim      -> /storage/emulated/0/DCIM
~/storage/pictures  -> /storage/emulated/0/Pictures
```

## Symlink Pitfall

`~/storage/dcim` and similar paths are symlinks. `find` does not follow a
symlink argument unless told to.

Wrong or misleading:

```sh
find ~/storage/dcim -type f | wc -l
```

Use:

```sh
find -L ~/storage/dcim -type f | wc -l
find /storage/emulated/0/DCIM -type f | wc -l
```

## Android Gotchas

- Android shared storage is FUSE-backed; permissions, symlinks, executable bits,
  file watching, and performance can differ from `$HOME`.
- Android 13+ has separate image/video/audio permissions.
- Android 14+ may allow only selected photos/videos for some apps.
- Verify access with real file operations, not assumptions.
- Long-running jobs may be killed by Android power management.

For long jobs:

```sh
termux-wake-lock
# run job
termux-wake-unlock
```

## Tool Scripts

On-demand TermuxPlus tools live in `~/.termuxplus/scripts/`. Run the specific
script you need instead of assuming every heavier tool was placed in the
bootstrap:

```sh
~/.termuxplus/scripts/install-python.sh             # Termux-native python (bionic)
~/.termuxplus/scripts/install-zsh.sh
~/.termuxplus/scripts/install-codex.sh
~/.termuxplus/scripts/install-claude-code.sh
~/.termuxplus/scripts/install-android-build-env.sh

# glibc compatibility stack — for tools that ship only glibc Linux binaries:
~/.termuxplus/scripts/install-glibc-env.sh          # glibc-runner + patchelf + `glibcify` on PATH
~/.termuxplus/scripts/install-uv.sh                 # astral-sh/uv (Python package manager)
~/.termuxplus/scripts/install-python-glibc.sh       # python-build-standalone -> `python3.12-glibc`
```

The Codex installer also installs the bootstrap-era tool set: `nodejs`, `npm`,
`proot`, `ca-certificates`, `git`, `ripgrep` (`rg`), `fd`, `jq`, `openssh`,
and `make`. Set `TERMUX_CODEX_VERSION` before running it to pin a version;
otherwise it installs `latest`.

## glibc / manylinux on Termux

Termux is **bionic libc**, so generic Linux releases and PyPI manylinux wheels
do not run out of the box. Symptoms include `invalid ELF header` on `libc.so`,
missing `aarch64-unknown-linux-android` rustup target, "Could not detect either
glibc version nor musl libc version" from uv, etc.

The recipe is built in. Once you have run `install-glibc-env.sh` (the other
glibc-aware install scripts call it automatically), `glibcify` is on PATH:

```sh
glibcify --help
glibcify ~/.local/share/foo/foo foo        # patch + wrapper named `foo` on PATH
glibcify --check ~/.local/share/foo/foo    # print current ELF interpreter
```

For Python work specifically: `install-uv.sh` gives you `uv`/`uvx`,
`install-python-glibc.sh` gives you `python3.12-glibc`, then
`uv run --python python3.12-glibc <script>.py` runs anything with manylinux
wheels (duckdb, numpy, pandas, ...) inside an ephemeral venv.

Persistent env vars for these tools belong in `~/.zshenv` (not `~/.zshrc`,
which is TermuxPlus-managed by `install-zsh.sh` and gets refreshed). A common
one is `export UV_LINK_MODE=copy` to silence uv's cross-FS hardlink warning.

Install heavier project-specific toolchains only when needed:

```sh
pkg update
pkg install clang cmake pkg-config
```

Create a workspace for repositories:

```sh
mkdir -p ~/projects
```

## Useful Inspection

```sh
id
getprop ro.build.version.release
getprop ro.build.version.sdk
termux-info
cmd package dump com.termux | grep -E 'targetSdk|READ_MEDIA|READ_EXTERNAL|granted='
df -h "$HOME" /storage/emulated/0
```
