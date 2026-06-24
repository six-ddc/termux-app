# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Communication

- 默认使用中文回复。
- 如果用户明确要求并行处理、委托、subagent 或多代理模式，优先把可独立验证/审查的子任务交给 SubAgent，并避免让多个 agent 修改同一文件范围。

## Project Context

这是 Termux app fork 的 TermuxPlus 工作分支（`termuxplus-foundation`）。

- 应用身份保持上游默认：`com.termux`，`$PREFIX = /data/data/com.termux/files/usr`。
- 不要恢复旧 custom package/prefix 方案（`/data/data/com.yourcompany.termuxplus`）。

## Gradle 模块结构

| 模块 | 产物 | 说明 |
|------|------|------|
| `:app` | `com.termux` APK | 主终端 App，Java，包含 TermuxPlus UI 改动 |
| `:terminal-emulator` | 库 | 终端仿真核心（纯 Java，无 Android 依赖） |
| `:terminal-view` | 库 | 终端 Android View 渲染层 |
| `:termux-shared` | 库 | 跨模块共享常量、工具类、TermuxConstants |

## Build And Test

如果当前 agent 本身运行在 Android/Termux 环境内（例如 `$PREFIX` 是
`/data/data/com.termux/files/usr`，或 `uname -o` 显示 Android），不要直接跑裸的
`:app:assembleDebug`，也不要优先走 `adb install`。这种环境下默认 Gradle 可能会尝试使用
Maven 下载的 Linux `aapt2`，在 Android/Termux 内报 `AAPT2 ... Daemon startup failed`。
此时按 `docs/termux-android-build-environment.md` 使用 Termux 原生工具参数：

```sh
TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  -PcompileSdkVersion=34 \
  -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false
```

Android/Termux 本机构建的 APK 输出路径：

```sh
app/build/intermediates/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
```

在 Android/Termux 本机想直接安装时，用系统包安装器手动打开 APK，或者用另一台机器走 `adb install`。
如果安装替换的是当前正在运行的 `com.termux`，Termux/Codex 进程会被系统杀掉，以设备实际安装状态为准。

本地快速验证（只构建 aarch64）：

```sh
TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:assembleDebug
```

运行单元测试：

```sh
./gradlew :app:test
```

安装到设备：

```sh
adb devices -l
adb -s <device> install -r app/build/outputs/apk/debug/termux-app_apt-android-7-debug_arm64-v8a.apk
```

验证首启 bootstrap（必须清数据，覆盖安装不够）：

```sh
adb -s <device> shell pm clear com.termux
adb -s <device> shell am start -n com.termux/com.termux.app.TermuxActivity
```

## Bootstrap

- 只使用官方 Termux bootstrap zip。`app/build.gradle` 从 `termux/termux-packages` release 下载官方 `bootstrap-<arch>.zip`，并用固定 SHA-256 校验。
- 自定义 bootstrap 生成/注入流程已移除，不要恢复 `TERMUX_BOOTSTRAP_DIR`、`bootstrap-output/`、`.termuxplus-cache/` 或 `scripts/build-termuxplus-bootstrap.sh`。
- `TERMUX_BOOTSTRAP_ARCHS` 只用于选择构建/下载哪些官方架构 zip，以及控制 ABI split；它不是自定义 bootstrap 入口。
- TermuxPlus 自带内容通过 APK assets 打包，不写进 bootstrap zip：`bootstrap/home/` 首启后复制到 `$HOME`。
- 按需安装工具脚本位于 `bootstrap/home/.termuxplus/scripts/`，首启后同步到 `$HOME/.termuxplus/scripts/`。zsh、oh-my-zsh、Codex、Claude Code、Python 等都通过这些脚本由用户手动安装。
- App 首启**不**写入 `~/.zshrc`，也**不**联网安装 zsh/oh-my-zsh/Codex/Claude Code。

常用构建：

```sh
TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:assembleDebug
```

## App 模块架构（`:app`）

相对于上游 Termux 的主要 TermuxPlus 改动：

- **Session 管理**：旧 Drawer/session list 已删除，session 创建/切换/重命名/关闭集中在顶部横向 tab strip（`TermuxSessionTabStripController`）
- **输入栏**：extra-keys / text-input 两栏合并为单一 `Keys` 工具栏，内部横滑多组；第一组右上角图标键弹出 snippets 面板
- **Snippets**：内置 JSON schema（`app/src/main/assets/termuxplus/snippets.json`），用户配置写入 `~/.termuxplus/snippets.json`；字段：`id, title, description, command, category, tags, mode`（mode 只有 `insert` 和 `run` 两值）；Java 模型在 `terminal/io/TermuxPlusSnippet.java`，存储库在 `TermuxPlusSnippetRepository.java`
- **悬浮终端**：后台运行时出现 `TP` 气泡（`TermuxFloatingTerminalController`），点击展开半透明小终端，直接复用当前 TerminalSession
- **官方 Bootstrap 安装后处理**：`TermuxPlusHomeInstaller`（首启把 APK assets 中的 home 模板文件和按需安装脚本复制到 `$HOME`）

## 设置页架构

`SettingsActivity` 采用分层 PreferenceFragment：根片段 `TermuxPreferencesFragment` → `DebuggingPreferencesFragment`、`TerminalIOPreferencesFragment`、`TerminalViewPreferencesFragment`；各插件有独立片段（TermuxAPI、Float、Tasker、Widget）。XML 定义在 `app/src/main/res/xml/`。

## UI 风格基线

深色 terminal surface、低圆角紧凑按钮、monospace 文本、绿色选中描边、弱化分割线、30dp 级 tab/toolbar 高度，避免卡片化和大面积装饰。修改 UI 后必须用模拟器或真机实际安装验证。

## Git Hygiene

- 工作区可能包含用户改动，不要回滚未明确要求回滚的内容。
- 提交前运行与改动相关的最小验证：`git diff --check`、相关 shell 脚本 `sh -n`/`bash -n`、相关 Gradle 构建。

## 设备测试的陷阱

### `adb shell run-as <pkg>` ≠ 真正的 Termux app context

**这是一个反复踩坑的坑**：测试 Termux 端代码时不要用 `adb shell run-as com.termux <cmd>` 作为真实 Termux 行为的代理。

- `run-as` 进程的 **SELinux 上下文是 `runas_app:s0`**（vivo/MIUI 等 OEM 还会再加严限制），跟真 Termux session 的 `untrusted_app_27:s0:cXXX,cXXX,cXXX,cXXX` **不是同一回事**。
- 这两个 context 的差异会导致：
  - 共享存储读权限不一样（`/sdcard/Download/...` 在 run-as 下 EACCES，在真 Termux 下正常读）
  - `app_process`、`/system/bin/content` 等系统工具的 exec 策略不一样
  - 一些 ContentProvider 访问被加严
- 错把 `run-as` 当成 Termux 会得出"Termux 沙箱挡了文件读取"之类的错误结论，然后绕弯实现 ContentProvider openFile / 分块传输 / base64 inline 等"修复"，全是无效劳动。

**正确测试 Termux 行为的方式**：直接在 Termux 终端里跑命令（不要走 `adb shell run-as`），或通过 `RunCommandService`（`com.termux.RUN_COMMAND` intent）从 adb 触发，spawn 出来的进程才是真 Termux 的 untrusted_app context。验权限时至少把 `id` 和 `cat /proc/self/attr/current` 一起打出来比对 SELinux 上下文。

### `cmd content read --uri` 在 app 上下文不可用

untrusted_app SELinux 禁止 exec `app_process`，所以 `cmd content`、`am`、`pm` 这些 shell 工具从 Termux 跑会 `inaccessible or not found`。这条 IPC 通道只对 adb shell（uid 2000）和系统签名 app 开放。绕不过去，不要再尝试。
