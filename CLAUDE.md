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
| `:autotermux` | `com.termux.autotermux` APK | AutoTermux 独立配套 App，Kotlin，最低 API 26 |
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

在 Android/Termux 本机安装 APK 时，`tp-android apk install` 只接受 `http(s)` URL，不接受本地相对路径或
`file://` URI。用 localhost 临时服务安装：

```sh
cd app/build/intermediates/apk/debug
python3 -m http.server 8765 --bind 127.0.0.1
```

另一个 shell 中执行：

```sh
tp-android apk install "http://127.0.0.1:8765/termux-app_apt-android-7-debug_arm64-v8a.apk" --pretty
```

安装后停止 `python3 -m http.server`。如果安装替换的是当前正在运行的 `com.termux`，
Termux/Codex 或 bridge 可能被系统杀掉；`tp-android` 可能显示 `Broadcast completed: result=0`，
但只要本地 HTTP 服务出现 APK 的 `GET ... 200` 且系统安装器/自动确认完成，就先以设备实际安装状态为准。

本地快速验证（只构建 aarch64）：

```sh
TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:assembleDebug
```

使用自定义 bootstrap 时须指定包含 `bootstrap-aarch64.zip` 等文件的目录：

```sh
TERMUX_BOOTSTRAP_DIR=bootstrap-output TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:assembleDebug
```

构建 AutoTermux：

```sh
./gradlew :autotermux:assembleDebug
```

运行单元测试：

```sh
./gradlew :app:test
./gradlew :autotermux:test
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

- 入口脚本：`scripts/build-termuxplus-bootstrap.sh`
- 缓存根目录：`.termuxplus-cache/`（不提交）
- 输出目录：`bootstrap-output/`（不提交）
- `TERMUX_BOOTSTRAP_REUSE_GENERATED=auto`：复用包列表匹配的已生成 zip；需强制刷新时设为 `false`
- 默认基础包：`zsh,zsh-completions,python`；追加包用 `TERMUX_BOOTSTRAP_ADD_PACKAGES`
- oh-my-zsh 注入到 `$PREFIX/share/termuxplus/oh-my-zsh` 和 `$PREFIX/etc/zshrc`
- Codex CLI 对 aarch64/x86_64 以 `TERMUX_BOOTSTRAP_INCLUDE_CODEX=auto` 注入，含 `nodejs,npm,proot,ca-certificates` 和工具层 `git,ripgrep,fd,jq,openssh,make`
- Termux home 模板来自 `bootstrap/home/`，首启后复制为 `$HOME/AGENTS.md`（0600）和 `$HOME/.codex/skills/`；`tp-android` Codex skill 定义在 `bootstrap/home/.codex/skills/tp-android/SKILL.md`
- App 首启**不**写入 `~/.zshrc`，也**不**联网安装 zsh/oh-my-zsh/Codex

常用构建：

```sh
TERMUX_BOOTSTRAP_ARCHS=aarch64 ./scripts/build-termuxplus-bootstrap.sh
TERMUX_BOOTSTRAP_DIR=bootstrap-output TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:assembleDebug
```

## App 模块架构（`:app`）

相对于上游 Termux 的主要 TermuxPlus 改动：

- **Session 管理**：旧 Drawer/session list 已删除，session 创建/切换/重命名/关闭集中在顶部横向 tab strip（`TermuxSessionTabStripController`）
- **输入栏**：extra-keys / text-input 两栏合并为单一 `Keys` 工具栏，内部横滑多组；第一组右上角图标键弹出 snippets 面板
- **Snippets**：内置 JSON schema（`app/src/main/assets/termuxplus/snippets.json`），用户配置写入 `~/.termuxplus/snippets.json`；字段：`id, title, description, command, category, tags, mode`（mode 只有 `insert` 和 `run` 两值）；Java 模型在 `terminal/io/TermuxPlusSnippet.java`，存储库在 `TermuxPlusSnippetRepository.java`
- **悬浮终端**：后台运行时出现 `TP` 气泡（`TermuxFloatingTerminalController`），点击展开半透明小终端，直接复用当前 TerminalSession
- **AutoTermux 集成**：`app/src/main/java/com/termux/app/autotermux/` 包含安装引导（`AutoTermuxInstallActivity`、`AutoTermuxInstallReceiver`）和 APK provider（`AutoTermuxApkProvider`）
- **Bootstrap 安装后处理**：`TermuxPlusCliInstaller`（安装 `tp-android` CLI）、`TermuxPlusHomeInstaller`（复制 home 模板文件）

## AutoTermux 模块架构（`:autotermux`）

独立 App（`com.termux.autotermux`），通过 Android 跨进程机制向 Termux 提供设备自动化能力。

**通信协议**：
- `TermuxAutomationBridgeReceiver`：`signature`-protected broadcast bridge，是 `tp-android` CLI 默认 transport，支持 `CALL` 分发小型控制命令
- `AutoTermuxContentProvider`：ContentProvider passthrough，保留用于直接 URI 访问
- `LocalAutomationService` + `SocketServer`：可选本地 HTTP/WebSocket RPC 服务（显式启动后默认 8080）
- `AutoTermuxWebSocketServer`：本地 WebSocket 事件推送（device-event）

**核心服务**：
- `AutoTermuxAccessibilityService`：无障碍服务，驱动 UI tree 遍历和手势注入
- `StateRepository`：无障碍状态中心，提供 getVisibleElements/getPhoneState/takeScreenshot/inputText，是其他服务读取 UI 状态的唯一入口
- `EventHub`：中央事件总线，36+ 事件类型（APP_FOREGROUND、FOREGROUND_APP_CHANGED、NOTIFICATION_POSTED、BATTERY_LEVEL_CHANGED、SMS_RECEIVED 等）
- `NotificationAccessService`：通知监听器
- `ScreenCaptureService`：MediaProjection 截图
- `GestureController`：坐标手势（tap/swipe/drag/edge-swipe 等）
- `ActionDispatcher`：bridge CALL 命令分发路由
- `AutoAcceptGate`：受保护的截图授权和 APK 安装自动确认开关
- `AutoTermuxKeyboardIME`：配套 IME，用于文本注入

**配置管理**（`ConfigManager`）：三套 SharedPreferences：
- `PREFS_NAME`：主配置（overlay、server 端口、noA11yMode、autoAccept 等）
- `DEVICE_PREFS_NAME`：设备身份（deviceId）
- `SECRET_PREFS_NAME`：认证 token

**保活机制**（`keepalive/`）：多层恢复策略（`KeepAliveController` → `KeepAliveRecoveryPolicy` → `KeepAliveRecoveryActivity`），状态由 `KeepAliveStatus` 追踪（consecutiveRecoveryFailures、degradedReason）。

**触发器系统**（`triggers/`）：
- `TriggerRuntime` + `TriggerRepository`：持久化规则存储和运行
- `TriggerScheduler` + `TriggerAlarmReceiver`：定时触发（AlarmManager）
- `TriggerBootReceiver`：开机自启触发
- `TriggerTermuxCommandLauncher`：通过 `com.termux.permission.RUN_COMMAND` 启动 Termux 本地命令
- `TriggerTemplateRenderer`：把 `{{trigger.*}}` 模板渲染到命令参数
- 触发源：定时、通知、前台应用、activity、电量（<15%）、充电、解锁、网络、短信

**UI 桥接 Activity**（透明 Activity，无历史记录）：
- `ScreenCaptureActivity`、`DialogBridgeActivity`、`FingerprintBridgeActivity`、`NfcBridgeActivity`、`SpeechToTextActivity`、`StorageGetActivity`、`SafManageActivity`

**大数据 transfer cache**：`/storage/emulated/0/Download/.termuxplus/tp-android-cache`，截图/大 JSON/文件传输通过此路径桥接，bridge 仅返回 cache path。

## tp-android CLI

`bootstrap/bin/tp-android` 是 227KB Python 脚本（v0.5.0），构建时注入到 `$PREFIX/bin/tp-android`。默认 transport 是 signature-protected broadcast bridge（`TermuxAutomationBridgeReceiver`）；HTTP transport 仅在显式使用 `tp-android server` 命令或 `--transport=http` 时才启动（`127.0.0.1:8080`）。用户配置读自 `~/.termuxplus/android-automation.json`。完整命令集运行 `tp-android --help` 查看。

## 设置页架构

`SettingsActivity` 采用分层 PreferenceFragment：根片段 `TermuxPreferencesFragment` → `DebuggingPreferencesFragment`、`TerminalIOPreferencesFragment`、`TerminalViewPreferencesFragment`；各插件有独立片段（TermuxAPI、Float、Tasker、Widget）。XML 定义在 `app/src/main/res/xml/`。

## UI 风格基线

深色 terminal surface、低圆角紧凑按钮、monospace 文本、绿色选中描边、弱化分割线、30dp 级 tab/toolbar 高度，避免卡片化和大面积装饰。修改 UI 后必须用模拟器或真机实际安装验证。

## Git Hygiene

- 工作区可能包含用户改动，不要回滚未明确要求回滚的内容。
- 提交前运行与改动相关的最小验证：`bash -n scripts/build-termuxplus-bootstrap.sh`、`git diff --check`、相关 Gradle 构建。

## 设备测试的陷阱

### `adb shell run-as <pkg>` ≠ 真正的 Termux app context

**这是一个反复踩坑的坑**：测试 Termux 端代码时不要用 `adb shell run-as com.termux <cmd>` 作为真实 Termux 行为的代理。

- `run-as` 进程的 **SELinux 上下文是 `runas_app:s0`**（vivo/MIUI 等 OEM 还会再加严限制），跟真 Termux session 的 `untrusted_app_27:s0:cXXX,cXXX,cXXX,cXXX` **不是同一回事**。
- 这两个 context 的差异会导致：
  - 共享存储读权限不一样（`/sdcard/Download/...` 在 run-as 下 EACCES，在真 Termux 下正常读）
  - `app_process`、`/system/bin/content` 等系统工具的 exec 策略不一样
  - 一些 ContentProvider 访问被加严
- 错把 `run-as` 当成 Termux 会得出"Termux 沙箱挡了文件读取"之类的错误结论，然后绕弯实现 ContentProvider openFile / 分块传输 / base64 inline 等"修复"，全是无效劳动。

**正确测试 Termux 行为的方式**：
1. 让脚本写到 `/data/data/com.termux/files/home/` 下，赋 +x。
2. 用 trigger（`tp-android trigger rule add ... --command <script-path>` + `tp-android trigger rule test <id>`）触发，TriggerTermuxCommandLauncher 走 `RunCommandService`，spawn 出来的进程 **是真 Termux 的 untrusted_app context**。
3. 让脚本把 `id`、`cat /proc/self/attr/current`、命令结果都 dump 到 Termux home 的 log 文件，事后 `adb shell run-as com.termux cat <log>` 读。
4. 如果只是想验权限，至少 `cat /proc/self/attr/current` 跟 `id` 一起打出来比对 SELinux 上下文。

### Binder 事务大小上限 ~1MB

跨进程 IPC（broadcast `setResultData`、ContentProvider query/insert 等）的单次事务受 `IBinder.MAX_IPC_SIZE` 限制（约 1 MB 包含头部和 base64 膨胀）。**call-log 全量返回、screenshot/screenrec base64 inline 都会爆**：

- AOSP 部分 ContentProvider（如 vivo 的 `CallLogProvider`）会**忽略 `QUERY_ARG_LIMIT` Bundle 参数**，返回全部行。客户端必须自己在 cursor 迭代时按 limit 截断，不能只靠 Bundle 参数。
- 大文件不要 base64 inline 经 broadcast 返回。当前方案：文件落到 `Download/.termuxplus/tp-android-cache/` 共享路径，bridge 返 path，**真 Termux** 直接读（不要在 `runas_app` 上下文 fallback）。
- 真出现 result=0 错误时，先 `adb logcat --pid $(pidof com.termux.autotermux)` 找 `Binder transaction failure` / `Large outgoing transaction` 关键字。

### vivo / OEM 后台 kill 策略

vivo 在 1-2 分钟内会回收 AutoTermux 进程，导致 bridge broadcast 收不到响应（`Broadcast completed: result=0`，无 logcat）。要长跑时：
- 设置 → 电池 → 后台高耗电应用 → AutoTermux → 允许后台运行/自启动（用户手动）
- 测试时把所有 bridge 调用塞进一个 batch，AutoTermux 唤起后立即跑完，不留缝隙

### `cmd content read --uri` 在 app 上下文不可用

untrusted_app SELinux 禁止 exec `app_process`，所以 `cmd content`、`am`、`pm` 这些 shell 工具从 Termux 跑会 `inaccessible or not found`。这条 IPC 通道只对 adb shell（uid 2000）和系统签名 app 开放。绕不过去，不要再尝试。
