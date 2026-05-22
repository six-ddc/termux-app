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

本仓库保留 `scripts/build-termuxplus-bootstrap.sh` 作为官方 prefix bootstrap 构建入口。它不再改 Android package name，也不再禁用官方 apt 源；默认在 termux-packages Docker 环境中运行 `generate-bootstraps.sh --add zsh,zsh-completions`，从官方 apt 源生成 bootstrap，并可选注入 oh-my-zsh：

```sh
scripts/build-termuxplus-bootstrap.sh
TERMUX_BOOTSTRAP_DIR=/tmp/termuxplus-bootstrap ./gradlew :app:assembleDebug
```

`TERMUX_BOOTSTRAP_BASE_PACKAGES` 是基础预装包列表，默认 `zsh,zsh-completions`。`TERMUX_BOOTSTRAP_ADD_PACKAGES` 是追加包列表，例如 `TERMUX_BOOTSTRAP_ADD_PACKAGES=git,curl` 会在默认 zsh 包之外追加 `git,curl`，不会改变 Android package name。脚本会把临时 `TERMUX_PACKAGES_DIR` clone 重置到 `TERMUX_PACKAGES_REF` 指向的干净状态，避免复用旧 custom-prefix 补丁或旧 bootstrap zip。默认位置是 `/tmp/termux-packages-termuxplus`，输出目录是 `/tmp/termuxplus-bootstrap`。macOS 本机不直接解 `.deb`，避免系统 `ar` 与 Debian archive 的兼容问题。

oh-my-zsh 不放进 app assets，也不由 App 首启写入 `$HOME`。构建脚本默认固定拉取 `TERMUX_OH_MY_ZSH_REF` 指向的 `ohmyzsh/ohmyzsh` commit，并把内容注入每个 bootstrap zip：

```text
$PREFIX/share/termuxplus/oh-my-zsh
$PREFIX/etc/zshrc
```

`$PREFIX/etc/zshrc` 负责设置 `ZSH="$PREFIX/share/termuxplus/oh-my-zsh"`、`SHELL="$PREFIX/bin/zsh"`，禁用 oh-my-zsh 自动更新，并启用默认主题。可通过 `TERMUX_BOOTSTRAP_INCLUDE_OH_MY_ZSH=false` 关闭注入，或通过 `TERMUX_OH_MY_ZSH_REF` 固定到新的上游 commit。

## 预装内容

当前自定义 bootstrap 默认预装 `zsh` 和 `zsh-completions`，并注入 oh-my-zsh。`apt`、`bash`、证书、压缩工具等来自 Termux 官方基础 bootstrap。`openssh`、`git`、`curl`、`wget`、`vim`、`tmux`、`python`、`nodejs` 等可通过 `TERMUX_BOOTSTRAP_ADD_PACKAGES` 追加。

内置配置建议放在 `$PREFIX/etc/termuxplus/` 或 `$PREFIX/etc/`，用户配置放在 `~/.termuxplus/`，用户配置优先。当前默认 shell 使用 zsh 优先，oh-my-zsh 和默认 zsh 配置都随 bootstrap 进入 `$PREFIX`；App 首启不再写入 `~/.zshrc`。

## UI 方案

- `TermuxService` / `TermuxSession` 仍作为 session 状态源。
- 顶部横向 tab strip 展示 session 名、运行/退出状态、关闭按钮、新建按钮。
- 旧 Drawer/session list 页面已删除；session 创建、切换、重命名、关闭都集中在顶部 tab strip。
- 输入栏：
  - `Keys`：默认包含 ESC、TAB、CTRL、ALT、方向键、HOME/END、PGUP/PGDN，并可横滑到常用符号组；代码仍支持用户在 `extra-keys` 中配置 `PASTE` 和 `KEYBOARD` 特殊键。
  - Snippets 不再作为独立 tab，而是通过第一组 Keys 顶部右侧的图标键弹出搜索、插入、执行面板；category 和 tags 作为搜索/展示元数据。上滑该图标键或点击 Manage 可打开独立管理界面新增、编辑、删除。
- 终端交互：双击终端发送 TAB，用于 shell 补全；软键盘默认采用字符型输入，降低英文联想和整词提交干扰。
- 视觉基线：深色 terminal surface、低圆角紧凑按钮、monospace 标签、绿色选中描边、弱化分割线和 30dp 级 tab/toolbar 高度，避免卡片化和大面积装饰。

## 当前快照状态

本分支已提交顶部 tab strip、Keys 多组输入栏、内置 snippets 数据、snippets 管理界面、设置页扩展、bootstrap 构建入口和 Gradle 自定义 bootstrap 校验。应用身份和 prefix 已回到 `com.termux`，后续不再沿用 custom prefix 方案。

## 测试计划

- Debug APK 包名为 `com.termux`，不能与官方 Termux 并存。
- 权限、provider authority、RUN_COMMAND action 使用 `com.termux` 派生值。
- 首启 `$PREFIX` 为 `/data/data/com.termux/files/usr`。
- 默认官方 bootstrap 可下载、嵌入并首启；如使用自定义 bootstrap，必须保持官方 prefix 且 apt 源可安装包。
- 新建、切换、重命名、关闭 session 正常，tab 状态和 `TermuxService` session 列表一致。
- Keys 组内横滑切换有连续分页过渡，snippets 图标键只在第一组出现，可弹出面板并插入/执行命令。
- 终端双击发送 TAB，软键盘英文输入默认不走整词联想。
- 设置项持久化并能在重启后生效。
