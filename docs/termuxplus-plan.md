# TermuxPlus Fork 二次封装方案

## 目标

将当前 Termux fork 成独立应用 `com.yourcompany.termuxplus`，定位为“本地终端工作台”。第一版保留 Termux 的本地 Linux 环境能力，优先吸收移动端终端工作流能力：多 session tabs、可切换输入栏、快捷键栏、snippets、本地 autocomplete 和图形化设置。

Java package 暂时保留 `com.termux.*`，只切换 Android 安装身份和运行目录，降低破坏面。

## 范围

- 应用身份隔离：`applicationId`、manifest placeholders、权限名、provider authorities、RUN_COMMAND action、快捷方式 target package、`TermuxConstants.TERMUX_PACKAGE_NAME`。
- `$PREFIX` 指向 `/data/data/com.yourcompany.termuxplus/files/usr`。
- 支持自定义 bootstrap 输入目录，避免误复用官方绑定 `/data/data/com.termux/files/usr` 的 bootstrap。
- 主界面增加横向 session tab strip，Drawer 转为工作台入口。
- 输入栏从 extra keys / text input 扩展为 `Keys`、`Command`、`Paste`、`Snippets` 模式。
- 内置 snippets 使用 JSON schema：`id`、`title`、`description`、`command`、`category`、`tags`、`mode`。
- Autocomplete 第一版仅做本地低延迟来源：history、paths、snippets、commands。
- 图形化设置继续扩展 Android Preference 页面。

## 不做

- 不实现 Termius 账号、云同步、SSH vault、SFTP 管理和密码建议。
- 第一版不做 split pane。
- 第一版不做 AI 命令生成。

## Bootstrap 要求

官方 bootstrap 内部路径绑定 `/data/data/com.termux/files/usr`，不能直接用于新包名。TermuxPlus 必须重新构建四架构 bootstrap，并确保包内二进制和 shebang 使用：

```text
/data/data/com.yourcompany.termuxplus/files/usr
```

构建 APK 时可通过 `TERMUXPLUS_BOOTSTRAP_DIR` 指定已构建的 bootstrap 输入目录，目录内文件名应为：

```text
bootstrap-aarch64.zip
bootstrap-arm.zip
bootstrap-i686.zip
bootstrap-x86_64.zip
```

## 预装内容

预装基础包建议包括：`apt`、`bash`、`zsh`、`openssh`、`git`、`curl`、`wget`、`vim`、`nano`、`tmux`、`python`、`nodejs`、证书、压缩工具。

内置配置建议放在 `$PREFIX/etc/termuxplus/`，用户配置放在 `~/.termuxplus/`，用户配置优先。

## UI 方案

- `TermuxService` / `TermuxSession` 仍作为 session 状态源。
- 顶部横向 tab strip 展示 session 名、运行/退出状态、关闭按钮、新建按钮。
- Drawer 保留 session list，同时逐步加入 `Sessions`、`Snippets`、`Settings`、`Packages/Presets`。
- 输入栏模式：
  - `Keys`：ESC、TAB、CTRL、ALT、方向键、HOME/END、PGUP/PGDN、粘贴、键盘开关。
  - `Command`：单行命令输入，候选提示，默认插入，显式执行才发送回车。
  - `Paste`：长文本编辑，用户确认后发送。
  - `Snippets`：搜索、分类、插入、执行。

## 当前快照状态

本分支先提交基础身份隔离、顶部 tab strip 骨架、输入栏多模式布局骨架和 bootstrap 构建入口。后续提交继续补齐 snippets 数据、autocomplete 逻辑、设置页扩展和完整构建验证。

## 测试计划

- Debug APK 包名为 `com.yourcompany.termuxplus`，可与官方 Termux 并存。
- 权限、provider authority、RUN_COMMAND action 不再占用 `com.termux`。
- 首启 `$PREFIX` 为 `/data/data/com.yourcompany.termuxplus/files/usr`。
- 自定义 bootstrap 可构建、嵌入并离线首启。
- 新建、切换、重命名、关闭 session 正常，tab 状态和 `TermuxService` session 列表一致。
- `Keys`、`Command`、`Paste`、`Snippets` 模式可切换，发送命令不会误执行。
- 设置项持久化并能在重启后生效。
