# TermuxPlus Fork 二次封装方案

## 目标

将当前 Termux fork 成“本地终端工作台”。第一版保留 Termux 的本地 Linux 环境能力，优先吸收移动端终端工作流能力：多 session tabs、可切换输入栏、快捷键栏、snippets 和图形化设置。

当前决策是 Android 安装身份、运行目录和 bootstrap prefix 全部回到上游默认：`com.termux` 与 `/data/data/com.termux/files/usr`。这样可以直接使用官方 Termux bootstrap 和官方 apt 仓库，避免 custom prefix 导致的 `.deb` 路径不兼容。

## 范围

- 应用身份使用上游默认：`applicationId`、manifest placeholders、权限名、provider authorities、RUN_COMMAND action、快捷方式 target package、`TermuxConstants.TERMUX_PACKAGE_NAME` 均为 `com.termux` 派生。
- `$PREFIX` 指向 `/data/data/com.termux/files/usr`。
- 默认使用官方 bootstrap；如需预装额外内容，只允许使用同一官方 prefix 构建的 bootstrap 输入目录，Gradle 会拒绝旧 custom-prefix zip。
- 主界面增加横向 session tab strip，旧 Drawer/session list 页面已移除。
- 输入栏从 extra keys / text input 收敛为单一 `Keys` 工具栏，`Keys` 内部支持横向分页滑动切换多组快捷键，并通过第一组右上角图标功能键唤起 snippets。
- 内置 snippets 使用 JSON schema：`id`、`title`、`description`、`command`、`category`、`tags`、`mode`。
- Snippets 支持独立管理界面，用户级配置写入 `~/.termuxplus/snippets.json`。
- 图形化设置继续扩展 Android Preference 页面。

## 不做

- 不实现 Termius 账号、云同步、SSH vault、SFTP 管理和密码建议。
- 第一版不做 split pane。
- 第一版不做 AI 命令生成。

## Bootstrap 要求

官方 bootstrap 内部路径绑定 `/data/data/com.termux/files/usr`。当前应用身份已经回到 `com.termux`，因此默认可以直接使用官方 bootstrap 和官方 apt 源。构建时 Gradle 会校验本地 `app/src/main/cpp/bootstrap-*.zip` 的 sha256；如果发现旧 custom-prefix zip，会自动删除并重新下载官方 zip。使用 `TERMUX_BOOTSTRAP_DIR` 指定自定义 zip 时，Gradle 也会校验 zip 内容，必须包含官方 prefix 和官方 Termux apt 源，且不能包含旧 `/data/data/com.yourcompany.termuxplus` 路径。

```text
/data/data/com.termux/files/usr
```

本地只验证 arm64 模拟器或真机时可以临时设置 `TERMUX_BOOTSTRAP_ARCHS=aarch64` 来缩短构建时间；发布或完整 APK 构建使用默认四架构。

```sh
TERMUX_BOOTSTRAP_ARCHS=aarch64 ./gradlew :app:assembleDebug
```

如需预装 zsh 或其它包，可先构建同一官方 prefix 的 bootstrap，然后通过 `TERMUX_BOOTSTRAP_DIR` 指定输入目录，目录内文件名应为：

```text
bootstrap-aarch64.zip
bootstrap-arm.zip
bootstrap-i686.zip
bootstrap-x86_64.zip
```

本仓库保留 `scripts/build-termuxplus-bootstrap.sh` 作为官方 prefix bootstrap 构建入口。它不再改 Android package name，也不再禁用官方 apt 源；默认在 termux-packages Docker 环境中运行 `generate-bootstraps.sh`，从官方 apt 源生成 bootstrap，并可选注入 oh-my-zsh 和 Codex CLI：

```sh
scripts/build-termuxplus-bootstrap.sh
TERMUX_BOOTSTRAP_DIR=bootstrap-output ./gradlew :app:assembleDebug
```

`TERMUX_BOOTSTRAP_BASE_PACKAGES` 是基础预装包列表，默认 `zsh,zsh-completions,python`。`TERMUX_BOOTSTRAP_ADD_PACKAGES` 是追加包列表，例如 `TERMUX_BOOTSTRAP_ADD_PACKAGES=git,curl` 会在默认基础包之外追加 `git,curl`，不会改变 Android package name。脚本会把 `TERMUX_PACKAGES_DIR` clone 重置到 `TERMUX_PACKAGES_REF` 指向的干净状态，避免复用旧 custom-prefix 补丁或旧 bootstrap zip。默认缓存根目录是仓库内 `.termuxplus-cache/`，其中包含 `termux-packages/`、`oh-my-zsh/` 和 npm cache；默认输出目录是仓库内 `bootstrap-output/`。这些目录只作为本地构建缓存和产物，不提交到仓库。macOS 本机不直接解 `.deb`，避免系统 `ar` 与 Debian archive 的兼容问题。

`TERMUX_BOOTSTRAP_REUSE_GENERATED` 默认是 `auto`。如果 `.termuxplus-cache/termux-packages/` 已有请求架构和包列表匹配的 `bootstrap-*.zip`，脚本会复用它并重新执行 oh-my-zsh、Termux home AGENTS、npm wrapper 和 Codex 注入；没有缓存或请求包列表变化时才跑 Docker 全量生成。需要强制刷新上游包时设置 `TERMUX_BOOTSTRAP_REUSE_GENERATED=false`。

oh-my-zsh 不放进 app assets，也不由 App 首启写入 `$HOME`。构建脚本默认固定拉取 `TERMUX_OH_MY_ZSH_REF` 指向的 `ohmyzsh/ohmyzsh` commit，并把内容注入每个 bootstrap zip：

```text
$PREFIX/share/termuxplus/oh-my-zsh
$PREFIX/etc/zshrc
```

`$PREFIX/etc/zshrc` 负责设置 `ZSH="$PREFIX/share/termuxplus/oh-my-zsh"`、`SHELL="$PREFIX/bin/zsh"`，禁用 oh-my-zsh 自动更新，并启用默认主题。可通过 `TERMUX_BOOTSTRAP_INCLUDE_OH_MY_ZSH=false` 关闭注入，或通过 `TERMUX_OH_MY_ZSH_REF` 固定到新的上游 commit。

Codex CLI 不是 Termux apt 包。构建脚本默认使用 `TERMUX_BOOTSTRAP_INCLUDE_CODEX=auto`：对 `aarch64` 和 `x86_64` bootstrap 注入 Codex，对 `arm` 和 `i686` 跳过；如果设置为 `true`，遇到不支持的架构会直接失败，避免发布出行为不一致的包。Codex 版本固定为 `TERMUX_CODEX_VERSION=0.133.0`，可按需覆盖。

Codex 的基础运行依赖仍来自官方 apt 包。只要请求的架构里包含 Codex 支持架构，脚本会自动追加 `TERMUX_BOOTSTRAP_CODEX_APT_PACKAGES`，默认值是：

```text
nodejs,npm,proot,ca-certificates
```

Codex 推荐的轻量自动化工具和 Codex 运行时依赖分开配置。只要启用 Codex，脚本还会自动追加 `TERMUX_BOOTSTRAP_CODEX_TOOL_PACKAGES`，默认值是：

```text
git,ripgrep,fd,jq,openssh,make
```

其中 `ripgrep` 提供用户 shell 可直接调用的 `rg`。Codex npm 包内部也自带 bundled `rg`，所以 `codex doctor` 的 search 检查可以在没有系统 `rg` 时通过；但给 shell 预装 `ripgrep` 能让 agent 生成的普通命令和用户手动排查都更一致。`python` 作为多数 agent 编码/脚本场景的常用运行时放在基础预装包里；Termux 包名是 `python`，会随当前官方源提供对应的 `python`/`python3` 版本。`clang`、`cmake` 这类更重的原生构建工具不放默认工具层，按项目需要通过 `TERMUX_BOOTSTRAP_ADD_PACKAGES` 追加。

随后脚本在主机上用 npm 下载 Linux 平台产物：

```sh
npm install -g @openai/codex@$TERMUX_CODEX_VERSION --os=linux --cpu=<arm64|x64> --include=optional --force
```

产物注入到：

```text
$PREFIX/lib/node_modules/@openai/codex
$PREFIX/bin/codex
```

`$PREFIX/bin/codex` 是 Termux 专用包装脚本，不直接使用 npm 生成的 `/usr/bin/env node` shebang。它会设置 `SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"`，并用 `proot` 绑定 `$PREFIX/etc/resolv.conf:/etc/resolv.conf` 后启动 Codex，解决 Codex 内置 Linux musl 二进制在 Android 上找不到 `/etc/resolv.conf` 和系统 CA 的问题。

同理，`npm` 包在 bootstrap 中默认会生成 `bin/npm -> lib/node_modules/npm/bin/npm-cli.js` 这类 symlink；Termux App 解压 bootstrap 时不会给 `lib/node_modules` 下的 JS 目标自动加执行权限。因此脚本会把 `bin/npm` 和 `bin/npx` 改写成实体 wrapper，用 `$PREFIX/bin/node` 直接启动对应 CLI，保证首启后 `npm --version` 和 `npx --version` 可用。

Termux 环境说明文件来自 `bootstrap/home/AGENTS.md`，内置 Codex skills 来自 `bootstrap/home/.codex/skills/`。构建脚本默认把这些 home 模板注入到：

```text
$PREFIX/share/termuxplus/home/AGENTS.md
```

App 首次 bootstrap 安装成功后，`TermuxInstaller` 会把 `AGENTS.md` 复制为：

```text
$HOME/AGENTS.md
```

并设置为 `0600`，同时把内置 skills 复制到 `$HOME/.codex/skills/`。这样新安装环境里 agent 能读取这些 Termux/Android 约束和 `tp-android` 操作 skill，用户也可以直接在 Termux 内编辑。可通过 `TERMUX_BOOTSTRAP_INCLUDE_HOME_AGENTS=false` 关闭，或通过 `TERMUX_BOOTSTRAP_HOME_AGENTS_FILE=/path/to/AGENTS.md`、`TERMUX_BOOTSTRAP_HOME_CODEX_DIR=/path/to/.codex` 指向其它模板。

## 预装内容

当前自定义 bootstrap 默认预装 `zsh`、`zsh-completions` 和 `python`，并注入 oh-my-zsh；在 `aarch64` 和 `x86_64` 上还会注入 Codex CLI，并通过官方 apt 包带入 `nodejs`、`npm`、`proot`、`ca-certificates` 以及 `git`、`ripgrep`、`fd`、`jq`、`openssh`、`make`。`apt`、`bash`、证书、压缩工具等来自 Termux 官方基础 bootstrap。`curl`、`wget`、`vim`、`tmux`、`clang`、`cmake` 等可通过 `TERMUX_BOOTSTRAP_ADD_PACKAGES` 追加。

内置配置建议放在 `$PREFIX/etc/termuxplus/` 或 `$PREFIX/etc/`，用户配置放在 `~/.termuxplus/`，用户配置优先。当前默认 shell 使用 zsh 优先，oh-my-zsh 和默认 zsh 配置都随 bootstrap 进入 `$PREFIX`；App 首启不再写入 `~/.zshrc`。

## tp-android CLI

`tp-android` 是 Termux 内 agent 控制 Android 的稳定入口。CLI 采用 Git 风格分组命令，旧的 `observe`、`tap`、`launch` 等顶层命令保留为兼容别名，新用法优先使用：

```sh
tp-android doctor
tp-android ping
tp-android version
tp-android device id
tp-android ui dump --pretty
tp-android ui click --text "OK"
tp-android input tap 540 1200
tp-android app launch com.android.settings
tp-android call tap -p x=540 -p y=1200
```

默认输出是一行机器可解析 JSON envelope，成功时写 stdout，运行时错误写 stderr，退出码表达成败：

```json
{"ok":true,"schema_version":"tp-android.v1","command":"input.tap","result":{}}
```

全局参数包括 `--config`、`--base-url`、`--authority`、`--user`、`--token`、`--transport bridge|provider|http`、`--timeout`、`--format json|pretty|raw|none`。默认 transport 是 signature-protected Android broadcast bridge，通过 AutoTermux bridge 的 `CALL` 分发能力执行小型控制命令，避免为了每次 tap/key/app list 都启动 loopback HTTP；`provider` 作为本地 ContentProvider 兼容通道保留。截图、文件读写和可选的大 JSON 输出不自动启动 HTTP：AutoTermux 先把内容写入共享 transfer cache（`/storage/emulated/0/Download/.termuxplus/tp-android-cache`），再通过 bridge JSON 返回 cache path，CLI 从本机路径完成复制、导入或回传 path。需要直接访问底层 API 时使用 `tp-android api get|post` 或 `tp-android call`。

面向 Agent 的一等命令覆盖坐标输入和语义化无障碍节点操作：`tp-android input tap|double-tap|swipe|drag|edge-swipe|long-press|text|key|global` 覆盖点击、双击、滑动、慢速拖拽、从屏幕边缘内滑、长按、文本输入、按键和系统全局动作，组合手势仍复用 `/tap`、`/swipe`、`/screen/status` bridge 调用，不绕开桥通信；`tp-android ui tree|phone-state` 可拆分读取 UI 树和当前 app/输入状态，`tp-android ui find|focused` 可按 overlay index、文本、content description、resource id、class name、scrollable 条件只查询目标节点或当前焦点节点，并返回 bounds、center、状态布尔值和可用 action 列表，便于 Agent 先做定位/判断再执行动作；`tp-android ui click/action` 可按同一 selector 执行 click、long-click、scroll、focus、copy/paste/cut、set-text、set-selection 等节点 action，`tp-android ui wait` 可轮询等待目标节点出现或用 `--gone` 等待目标节点消失，`tp-android ui scroll-until` 可对可滚动容器循环滚动直到目标 selector 出现。`tp-android ui dump|tree|phone-state|find|focused -o PATH` 和 `--cache` 使用 bridge path 化返回，避免大 UI 树或大匹配结果塞进 broadcast result。应用控制除 `app list|launch|stop` 外，还提供 `app current|wait`、`app info`、`app open-url`、`app intent`、`app settings`、`app uninstall`，用于读取/等待前台包和 activity、读取包元数据、打开 deeplink/URL、发起显式或隐式 activity intent、跳转应用设置/权限/通知页，以及进入系统卸载确认流；`app list` 和 `app info` 支持 `--cache`/`-o PATH`，大型包列表或 manifest 元数据通过 transfer cache path 桥接。

本地服务控制拆分为 `tp-android server start|stop|status`（HTTP）和 `tp-android server websocket start|stop|status`（本地 WebSocket RPC/事件），`tp-android server token` 可读取本地 HTTP/WebSocket token，`tp-android server port set PORT` 可调整运行中的 HTTP 端口。`tp-android mode status` 和 `tp-android mode no-a11y enable|disable` 用于显式管理无障碍/无无障碍本地服务模式。对由 AutoTermux 主动触发的系统确认弹窗，`tp-android mode auto-accept enable|disable screenshot|install|all` 暴露受 `AutoAcceptGate` 保护的截图授权和 APK 安装自动确认开关。`tp-android event list|status|enable|disable|watch|wait` 暴露本地 WebSocket device-event 过滤、监听和等待能力，便于 Agent 打开或关闭高频 UI 事件，按 event type、package/packageName、title、text/content/body、from 和任意 payload 字段过滤前台 app、窗口、通知、短信、电量、网络等事件；`tp-android sms wait` 和 `tp-android notification wait` 是短信/通知场景的一等等待命令，用于替代持久 trigger runtime 之外的 CLI 脚本同步点。`tp-android trigger catalog|status|rule|run` 对齐 mobilerun-portal 的 `triggers/*` 管理面，支持持久化规则、运行历史、ContentProvider 查询/变更和 WebSocket/bridge JSON-RPC；触发源覆盖定时、通知、前台应用、activity、电量、充电、解锁、网络和短信，规则命中后通过 Termux `RunCommandService` 启动本地命令，并把 `{{trigger.*}}` 模板渲染到参数或 stdin，便于把事件交给本机脚本或 Codex/Phone Agent 处理；命令触发要求 AutoTermux 已获得 `com.termux.permission.RUN_COMMAND` 且 Termux 配置 `allow-external-apps=true`。`tp-android screen status|wake|lock|orientation|keep-awake` 暴露屏幕交互状态、锁屏状态、display metrics、旋转策略、短时唤醒、无障碍锁屏和 keep-awake 控制；旋转锁定遵守 Android `WRITE_SETTINGS` 特殊授权，未授权时返回明确提示。`tp-android screen record start|stop|status` 用 MediaProjection + MediaRecorder 录制屏幕到 H264/MP4，落盘到 AutoTermux 私有 `cacheDir/screenrec/recording-<epochms>.mp4`，遵循 bridge-mode 大内容路径化原则：CLI 不流式回传视频字节，而是在 `start` 时拉起一次性 MediaProjection 授权（受 `AutoAcceptGate` 自动确认控制）并立即返回 `STARTING` 状态，在 `stop` 或达到 `--max-duration`（默认 60 秒、上限 10 分钟）时返回 `FINISHED` 与绝对路径，Agent 再通过 `adb pull` 或本机 ffmpeg 处理；可选 `--bit-rate`（默认 6 Mbps）、`--frame-rate`（默认 30 fps）、`--include-mic`（默认关闭，开启后启用 AAC 麦克风音轨）。`tp-android provider query|insert` 保留原生 ContentProvider passthrough，用于访问尚未产品化为高层命令的直接 URI。`tp-android config remote show` 和 `tp-android device identity|id` 用于读取 AutoTermux 运行配置和稳定设备身份。

`tp-android` 还提供 Termux:API 兼容设备能力层，统一通过现有 bridge/provider/http dispatcher 调用 AutoTermux 后端，而不是依赖外部 `termux-api` 包或绕开桥通信。官方脚本名可通过 `tp-android termux-api <name>` 调用，例如 `tp-android termux-api sms-list --message-type all --message-limit=20 --cache --pretty`、`tp-android termux-api notification --title Done --content "Task finished"`、`tp-android termux-api storage-get ./picked.bin`；CLI 会把官方脚本风格参数翻译成 bridge JSON，并允许 `--pretty`、`--raw`、`--cache`、`--data`、`-p KEY=VALUE` 等 tp-android 参数放在官方参数之后。高频能力同时提供一等分组命令：`permissions status|open`、`battery status`、`audio info`、`volume get|set`、`brightness get|set`、`contacts list`、`call-log list`、`sms list|send|watch`、`notification list|post|remove|channel|tap|action|reply|watch`、`dialog`、`fingerprint`、`nfc status|read|write`、`usb list|permission|open`、`keystore list|generate|delete|sign|verify`、`job-scheduler pending|schedule|cancel|cancel-all`、`storage get`、`saf managedir|dirs|ls|stat|create|mkdir|read|write|rm`、`location get`、`sensor list|read`、`telephony deviceinfo|cellinfo|call`、`wifi connectioninfo|scaninfo|enable`、`infrared frequencies|transmit`、`torch set`、`vibrate`、`toast`、`speech-to-text`、`tts engines|speak`、`wallpaper set`、`camera info|photo`、`media scan|player`、`microphone record`、`download`、`share` 等。通知 `list/watch` 会暴露第三方通知 action 元数据，`tap/action/reply` 可以点击通知、执行通知按钮或发送 inline reply；`post` 支持按钮、inline reply、LED、图片和媒体动作，点击、清除或回复会作为 `NOTIFICATION_ACTION`/`NOTIFICATION_REPLY` 事件进入 `tp-android notification watch`，由 Agent 再决定后续动作。`dialog` 提供 confirm/text/checkbox/radio/sheet/spinner/counter/date/time/speech 等人工确认和输入桥接。短信、通知、联系人、通话记录、基站、Wi-Fi scan、传感器等大 JSON 结果支持 `--cache`，由 AutoTermux 写入 transfer cache 并返回 `{path, bytes, content_type, source}`；`storage get` 通过系统文件选择器取得用户选择的内容并写入 transfer cache，CLI 可用 `-o` 或官方 `storage-get output-file` 复制到 Termux 本地路径；`saf managedir` 通过系统目录选择器持久授权，`saf read/write` 通过 transfer cache 传递大文件内容；`keystore sign/verify` 会把输入和签名经 transfer cache 桥接；`camera photo` 默认把 JPEG 写入 transfer cache 并返回图片路径，也可通过 `-o` 指定 Android 本地路径。

敏感设备 API 必须遵守 Android 授权模型：短信/联系人/通话记录/定位/相机/录音/电话/Wi-Fi scan/传感器/生物认证等需要对应 runtime permission 或系统授权，通知读取需要用户在系统设置中授予 AutoTermux notification listener access，系统亮度写入需要 `WRITE_SETTINGS` 特殊授权。`tp-android permissions status --pretty` 统一报告 runtime permission 和 notification listener、WRITE_SETTINGS、all-files、accessibility 等特殊授权状态，`tp-android permissions open <target>` 可打开对应系统设置页。USB 的 list/permission 能通过 bridge 返回 JSON；官方 `termux-usb -e` 那类把 raw file descriptor 注入子进程的能力不能通过 JSON bridge 直接跨应用传递，因此 `usb open` 会显式返回 fd 桥接限制说明，而不是伪装成可被 Termux 进程直接使用。

## UI 方案

- `TermuxService` / `TermuxSession` 仍作为 session 状态源。
- 顶部横向 tab strip 展示 session 名、运行/退出状态、关闭按钮、新建按钮。
- 旧 Drawer/session list 页面已删除；session 创建、切换、重命名、关闭都集中在顶部 tab strip。
- 输入栏：
  - `Keys`：默认包含 ESC、TAB、CTRL、ALT、方向键、HOME/END、PGUP/PGDN，并可横滑到常用符号组；代码仍支持用户在 `extra-keys` 中配置 `PASTE` 和 `KEYBOARD` 特殊键。
  - Snippets 不再作为独立 tab，而是通过第一组 Keys 顶部右侧的图标键弹出搜索、插入、执行面板；category 和 tags 作为搜索/展示元数据。上滑该图标键或点击 Manage 可打开独立管理界面新增、编辑、删除。
- 终端交互：双击终端发送 TAB，用于 shell 补全；软键盘默认采用字符型输入，降低英文联想和整词提交干扰。
- 后台控制：Termux 退到后台且存在运行中 session 时，由 `TermuxService` 创建轻量悬浮终端。默认是可拖动 `TP` 气泡，点击后展开为半透明小终端，直接复用当前 `TerminalSession` 显示输出和接收键盘输入。浮窗放在 Termux 主进程而不是 AutoTermux accessibility overlay：AutoTermux 的 overlay 只负责无焦点的自动化标注，交互式 CLI 输入需要普通 `TYPE_APPLICATION_OVERLAY` 和 Termux session 生命周期。
- Android 系统设置、权限弹窗等敏感页面可能设置 `mForceHideNonSystemOverlayWindow=true`，系统会强制隐藏第三方悬浮窗；切回桌面或普通应用后同一浮窗会恢复显示。需要连续下发控制指令时，优先让 AutoTermux 切到普通目标应用页面，避开系统禁止 overlay 的页面。
- 视觉基线：深色 terminal surface、低圆角紧凑按钮、monospace 标签、绿色选中描边、弱化分割线和 30dp 级 tab/toolbar 高度，避免卡片化和大面积装饰。

## 当前快照状态

本分支已提交顶部 tab strip、Keys 多组输入栏、内置 snippets 数据、snippets 管理界面、设置页扩展、bootstrap 构建入口和 Gradle 自定义 bootstrap 校验。应用身份和 prefix 已回到 `com.termux`，后续不再沿用 custom prefix 方案。

## 测试计划

- Debug APK 包名为 `com.termux`，不能与官方 Termux 并存。
- 权限、provider authority、RUN_COMMAND action 使用 `com.termux` 派生值。
- 首启 `$PREFIX` 为 `/data/data/com.termux/files/usr`。
- 默认官方 bootstrap 可下载、嵌入并首启；如使用自定义 bootstrap，必须保持官方 prefix 且 apt 源可安装包。
- 自定义 bootstrap 中 `zsh --version`、`codex --version` 可运行；未登录时 `codex doctor --summary --ascii` 允许只剩认证相关失败或 WebSocket 401 警告。
- `tp-android doctor` 输出 `schema_version=tp-android.v1` 的 JSON envelope；`tp-android input tap ...`、`tp-android ui dump --pretty`、`tp-android ui screenshot -o ...` 能在启用 AutoTermux 后正常执行。
- `tp-android termux-api support --pretty` 可列出 Termux:API 兼容矩阵；`tp-android battery status --pretty`、`tp-android notification post ...` 等无敏感或已授权能力能通过默认 bridge 执行；短信、通知读取、联系人、通话记录、定位等未授权时应返回明确权限提示，授权后大列表命令的 `--cache` 返回共享 transfer cache 路径。
- 新建、切换、重命名、关闭 session 正常，tab 状态和 `TermuxService` session 列表一致。
- Keys 组内横滑切换有连续分页过渡，snippets 图标键只在第一组出现，可弹出面板并插入/执行命令。
- 终端双击发送 TAB，软键盘英文输入默认不走整词联想。
- 授权 `SYSTEM_ALERT_WINDOW` 后，从 Termux 切到 Launcher 或普通目标应用会出现 `TP` 悬浮气泡；点击气泡可展开小终端，点击终端区域能唤起软键盘，输入 `pwd` 等命令能写回当前 session；点击 `Open` 会回到 Termux 主界面并隐藏浮窗。
- 切到 Android Settings 时，确认浮窗 window 存在但被系统策略隐藏；切回 Launcher 后浮窗恢复可见。
- 设置项持久化并能在重启后生效。
