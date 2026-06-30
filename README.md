# TermuxPlus

TermuxPlus is a fork of [Termux](https://github.com/termux/termux-app) that keeps Termux's full terminal capabilities while reworking the terminal rendering and UI around running AI agents (Codex / Claude Code) in a phone terminal workflow.

The app identity is unchanged from upstream: package name `com.termux`, `$PREFIX = /data/data/com.termux/files/usr`, and it uses the **official Termux bootstrap** directly, staying compatible with the upstream ecosystem.

> This file only documents what this fork changes relative to upstream. For Termux itself — introduction, installation, plugins, package management, license — see the upstream docs:
> **[termux/termux-app › README](https://github.com/termux/termux-app/blob/master/README.md)**

---

## Changes relative to upstream

### Terminal rendering — migrated to the Ghostty GPU stack

Terminal emulation is now driven by the prebuilt **libghostty-vt** (`terminal-emulator/src/main/jniLibs/*/libghostty-vt.so`), bridged via JNI (`terminal-emulator/src/main/jni/ghostty_bridge.c` + `GhosttyTerminalEngine`), feeding render state straight to the OpenGL ES renderer `terminal-view/src/main/java/com/termux/view/TerminalGpuRenderer.java` (glyph atlas, LRU cache, Kitty graphics protocol).

- **Font fallback chain**: primary font (user `~/.termux/font.ttf`, or the bundled JetBrains Mono Nerd Font) → bundled symbol font `TermuxPlusSymbolsFallback-Regular.ttf` (a subset of Noto Sans Symbols 2, computed as the set difference against the primary font's cmap) → system font. This restores symbols that every Nerd Font lacks and that some ROM system fonts also lack (e.g. codex's `⏵` prompt).
- **Color emoji** are rasterized at full size, then fit-inside scaled and centered, so they are no longer clipped/squashed by a single-column cell.
- `TermuxPlusOscInterceptor` recovers OSC 52 (clipboard) and OSC 9/777 (notifications) from the raw PTY byte stream.

### Terminal UI — session tab strip + unified toolbar + HUD look

- A horizontal **session tab strip** at the top (`TermuxSessionTabStripController`) replaces upstream's side drawer / session list, centralizing switch / new / rename / close.
- extra-keys and text input are merged into a single paged **"Keys" toolbar** (`TerminalToolbarViewPager` / `TermuxTerminalExtraKeys`) with a built-in snippets shortcut.
- A unified visual system (phosphor-green accent, near-black surfaces, stroke-drawn vector icons): `app/src/main/java/com/termux/app/ui/` (`TpChrome` / `TpIconView`).
- Action-center BottomSheet (`TermuxPlusActionSheet`) and a session grid overview (`TermuxPlusTabOverview`).

### Snippets — command snippets

A built-in schema `app/src/main/assets/termuxplus/snippets.json`, with user config written to `~/.termuxplus/snippets.json`; the model / repository are `TermuxPlusSnippet` / `TermuxPlusSnippetRepository`; insert (`insert`) or run (`run`) from the toolbar.

### Workspace — agent workspaces

Scans Codex / Claude Code session history and aggregates it into a "workspace" view, to quickly return to an agent session in a given directory: `app/src/main/java/com/termux/app/workspace/`.

### Floating terminal

A floating mini terminal window that appears while running in the background, reusing the current session: `TermuxFloatingTerminalController`.

### First-launch & bootstrap

- Uses **only the official Termux bootstrap zip** (downloaded with a pinned SHA-256 in `app/build.gradle`); no custom bootstrap.
- On first launch, `TermuxPlusHomeInstaller` copies the home templates (`AGENTS.md`, `.gitconfig`, `.config/git/ignore`) and on-demand install scripts from the APK assets into `$HOME`; it does **not** write `~/.zshrc` and does **not** install anything over the network.
- On-demand install scripts (`~/.termuxplus/scripts/`, run manually by the user): zsh + oh-my-zsh, Codex, Claude Code, Python (native bionic), the Android build environment, plus a glibc compatibility stack — `install-glibc-env.sh` (glibc-runner + patchelf + a `glibcify` helper on PATH), `install-uv.sh` (astral-sh/uv) and `install-python-glibc.sh` (python-build-standalone exposed as `python3.12-glibc`) — for tools that ship only glibc Linux binaries / PyPI manylinux wheels.

### Settings

Layered PreferenceFragment structure: `SettingsActivity` + `app/src/main/java/com/termux/app/fragments/settings/`.

---

## Build

See [`CLAUDE.md`](CLAUDE.md) and [`docs/termux-android-build-environment.md`](docs/termux-android-build-environment.md). Quick local build (aarch64):

```sh
TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:assembleDebug
```

Modules:

| Module | Description |
|--------|-------------|
| `:app` | `com.termux` main app (Java, TermuxPlus UI) |
| `:terminal-emulator` | Terminal emulation core + Ghostty engine (JNI) |
| `:terminal-view` | Terminal Android View, GPU rendering layer |
| `:termux-shared` | Shared constants, utilities, `TermuxConstants` |

---

## Upstream & license

This project is forked from [termux/termux-app](https://github.com/termux/termux-app) and keeps its license; see [`LICENSE.md`](LICENSE.md).
