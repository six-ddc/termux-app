# TermuxPlus Android Automation Agent — UX 完整方案

> 状态：v1 设计稿 · 落地按 Phase 推进 · 任何改动同步更新本文件
>
> 适用范围：`bootstrap/bin/tp-android` CLI、`:autotermux` companion、`bootstrap/home/.codex/skills/tp-android/` skill 三者构成的"手机端 Agent 自动化"链路。

---

## 0. 北极星目标

> 用户在手机的 Termux 里说一句话给 Agent，Agent 自动驾驶手机完成任务。整个过程中用户**永远知道发生了什么、永远能接管、永远能信任结果**——并且这条体验链不依赖外网、不依赖 root、不依赖特定 OEM。

满足这个目标，要解决 6 类问题：

1. **进程不死** —— Termux 和 AutoTermux 都不能在后台被 OEM 杀掉
2. **调用不堵** —— Python 冷启动 / Binder 容量 / OEM kill 之间的常态延迟可控
3. **动作准** —— selector + OCR + vision 多路兜底，元素 grounding 稳定
4. **状态透明** —— Agent 在干嘛、卡在哪、要不要人工，用户随时一眼看到
5. **用户可介入** —— 用户和 Agent 互不打架，互相礼貌让出
6. **错误可解释** —— 任何报错有明确的结构化 code + hint，Agent 可自愈，用户可手动修

---

## 1. 分层架构

```
┌──────────────────────────────────────────────────────────────┐
│ L6  Skill 文档 / Playbook / Prompt 模板                       │
├──────────────────────────────────────────────────────────────┤
│ L5  HUD / Overlay 视觉反馈层                                  │
│     状态条 · 介入对话框 · 元素编号 · 截图标注                  │
├──────────────────────────────────────────────────────────────┤
│ L4  Agent UX 协议（status / intervene / cancel / progress）   │
├──────────────────────────────────────────────────────────────┤
│ L3  复合操作（macro / digest / vision / OCR / DSL）           │
├──────────────────────────────────────────────────────────────┤
│ L2  原语（tap, swipe, dump, find, wait, type, key, ...）      │
├──────────────────────────────────────────────────────────────┤
│ L1  通信传输（bridge / batch / daemon / IPC 容量管理）         │
├──────────────────────────────────────────────────────────────┤
│ L0  进程保活 / 权限基线 / 首次安装引导                         │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. L0 — 进程保活、权限基线、首启引导

### 2.1 现状

- `tp-android doctor` 探活；`permissions status` 列权限
- 手动配置无障碍 / 通知监听 / 悬浮窗 / OEM 电池白名单
- `install-companion` 仅装 APK
- Termux 自身被 OEM 杀的情形完全没机制保护 Codex/Claude

### 2.2 要做的

**P0 · `tp-android setup` 引导向导**

```sh
tp-android setup            # 交互模式
tp-android setup --check    # 只检查不引导
tp-android setup --headless # 用于 CI/自动化场景
tp-android setup --resume <token>  # 中断后续接
```

顺序检查 8 项前置条件，未就绪自动通过 `app settings` 打开对应页 + 轮询 + TTS 提示：

1. companion 是否安装
2. 无障碍服务
3. 通知监听
4. 悬浮窗
5. `WRITE_SETTINGS`
6. SMS / contacts 等 runtime 权限
7. `allow-external-apps=true` in `termux.properties`（自动改 + `termux-reload-settings`）
8. 电池白名单（按 OEM 走深链）

**P0 · `tp-android keepalive ensure`**

检查 Termux + AutoTermux + a11y service + notification listener 四件套；任一掉线返回非零 + 具体项 + 修复指令；可选 `--restart` 自动恢复。

**P1 · `tp-android keepalive watch --interval 30`**

后台心跳，掉线时通过 HUD/通知双通道弹"Agent 后台需要拉起"。

**P1 · OEM 适配脚本**

`bootstrap/scripts/oem-battery/{vivo,xiaomi,oppo,huawei}.sh`，按 `ro.product.brand` 自动选。

**P2 · Termux 浮窗 ↔ AutoTermux 协议**

`:app` 注册 `com.termux.HIDE_FLOATING` / `SHOW_FLOATING` signature-protected broadcast；AutoTermux 在截图、`ui dump --exclude-self` 时临时收浮窗。

---

## 3. L1 — 通信传输

### 3.1 现状

- bridge broadcast，~1MB binder cap
- 每次调用都是新 Python 进程，200~500ms 冷启动
- 大 payload 走 `/sdcard/Download/.termuxplus/tp-android-cache/`

### 3.2 要做的

**P1 · `tp-android batch < ops.jsonl`**

```jsonl
{"op":"ui.click","args":{"text":"登录"}}
{"op":"ui.wait","args":{"text-contains":"验证码"}}
{"op":"status.intervene","args":{"reason":"需要输入验证码"}}
{"op":"ui.click","args":{"text":"确定"}}
```

- 一个 Python 进程顺序执行，逐行 emit envelope
- 支持 `--on-error stop|continue|abort`
- 支持 `--vars k=v` 模板填充 `{{var}}`
- 是 L4 status 协议的天然载体；macro 也复用此格式

**P1 · `tp-android daemon` + Unix socket**

```sh
tp-android daemon start --socket ~/.termuxplus/tp.sock
tp-android --socket ~/.termuxplus/tp.sock ui dump
```

客户端默认探测 socket 存在就走，不存在 fork。

**P1 · `tp-android exec-script` (Server-side DSL)**

```sh
tp-android exec-script <<'EOF'
find_click text="登录"
wait text_contains="验证码" timeout=15
EOF
```

把简单"找元素 / 点 / 等"打包成一次 bridge 调用，由 AutoTermux 端解释执行；避免 N 次 binder round-trip。

**P2 · IPC 容量自适应**

客户端预估 payload size，超阈值自动加 `--cache`；对 OEM 黑名单 ContentProvider 维护硬编码 limit 表。

---

## 4. L2 — 原语层（atomic operations）

### 4.1 现状

254 个 add_parser 入口，UI / Input / App / Clipboard / Screen / Files / SAF / APK / Dialog / Biometric / NFC / USB / Keystore / 57 个 Termux:API；默认超时表都有且都不死等。

### 4.2 要做的

**P1 · 默认超时调整**

| 命令 | 当前 | 建议 |
|---|---|---|
| `app wait` | 10s | **20s** |
| `app launch-interactive` | 10s | **15s** |
| `ui wait` | 10s | **15s** |

理由：中低端机 + 大 App 冷启常 8~12s。

**P1 · 复合等待**

```sh
tp-android ui click --text "登录" --wait-timeout 15
tp-android ui type --resource-id ... --text "x" --verify
```

`--verify` 不限于 click，扩展到 type/swipe，供 reflective agent 使用。

**P1 · 屏幕变化等待**

```sh
tp-android ui wait --change                     # 截图 hash diff
tp-android ui wait --tree-changed --package X   # a11y tree hash
tp-android ui wait --keyboard visible|hidden
```

**P2 · 元素图片切片**

```sh
tp-android ui element-image --resource-id com.x:id/avatar -o ./a.png
tp-android ui element-image --bounds "100,200,300,400" -o ./b.png
```

后端从最近一次截图 crop 或现拍。

**P2 · 多指 / 复合手势**

```sh
tp-android input pinch --center 540,1200 --from 200 --to 600 --duration-ms 500
tp-android input two-finger-swipe ...
```

**P3 · 屏幕坐标系明确化**

所有命令统一以"逻辑像素"为单位；`screen status` 返回 dpi/scale。

---

## 5. L3 — 复合操作 / 智能化

### 5.1 现状

没有 macro / digest / vision；selector 全靠 a11y 文本和 resource-id。

### 5.2 要做的

**P0 · `ui digest` — token-friendly 紧凑视图**

```sh
tp-android ui digest --package com.example --pretty
```

输出（目标 <500 tokens）：

```json
{
  "app": "com.example/.MainActivity",
  "keyboard": false,
  "focused": {"idx":3,"text":"用户名"},
  "elements": [
    {"idx":1,"text":"返回","bounds":"24,80,120,160","actions":["click"]},
    {"idx":2,"text":"小红书","bounds":"...","actions":["click","long-click"]},
    {"idx":3,"text":"","hint":"用户名","actions":["click","set-text"]}
  ],
  "scrollables":[{"idx":12,"actions":["scroll-forward","scroll-backward"]}]
}
```

只列可交互节点；Agent 可直接用 `idx`：`tp-android ui click --idx 2`。这是 LLM-cost 削减最大的一招。

**P0 · `ui screenshot --annotate`**

```sh
tp-android ui screenshot --annotate clickable --filter visible \
  -o ./s.png --map-out ./s.json
```

PNG 上画红框 + 编号；JSON 是 `{idx: {bounds, text, actions}}`；vision-able LLM 看图选编号即可。

**P1 · OCR fallback**

```sh
tp-android ui find --ocr "登录" --limit 1 --pretty
```

端侧 ML Kit Text Recognition（中文+拉丁 offline ~6MB）；优先 a11y，找不到自动降级。

**P1 · Macro 录制 / 回放**

```sh
tp-android macro record signup --include-screenshots
tp-android macro stop
tp-android macro list
tp-android macro replay signup --vars phone=+1555
tp-android macro export signup -o ./signup.json
```

持久化到 `~/.termuxplus/macros/<name>.json`；回放优先 selector 重放，失败回退坐标。

**P2 · Vision-augmented selector**

```sh
tp-android ui click --vision "submit button"
```

multimodal LLM 截图 + prompt 返回 bounds 再点；慢但极稳。

**P2 · 简易 DSL（与 exec-script 同源）**

```yaml
- find: {text: 登录}
  click: true
- wait: {text-contains: 验证码, timeout: 15}
- intervene: {reason: 输入验证码}
- find: {text: 确定}
  click: true
```

人类可读、可 git diff、可 LLM 生成。

---

## 6. L4 — Agent UX 协议（最关键的一层）

### 6.1 设计原则

1. **Agent 主动汇报，不靠 hook** —— 兼容 Codex / Claude / 任意 CLI Agent
2. **状态语义精简** —— 6 个核心状态：running / waiting / needs-input / paused / error / done
3. **介入是阻塞调用** —— Agent 想问用户就停下来等返回，不要"先继续猜测着跑"
4. **Run 是顶层概念** —— 一个 run = 一个完整任务，有 id，可观测，可取消

### 6.2 接口

```sh
# === Run lifecycle ===
tp-android status start --task "<人话>" --total-steps N [--id <id>]
tp-android status finish --state success|cancelled|error [--summary "<...>"]

# === Step progression ===
tp-android status step --step N --label "<本步骤名>" \
  --state running|done|skipped|failed [--detail "<可选细节>"]
tp-android status progress --percent 60 [--detail "下载中"]

# === Intervention (阻塞) ===
tp-android status intervene --reason "需要输入短信验证码" \
  --choices "我已输入,跳过,取消" --wait-timeout 120
# -> {"choice":"我已输入"} or {"timeout":true}

tp-android status ask-input --prompt "请输入验证码" \
  --kind text|password|number --wait-timeout 120
# -> {"value":"123456"}

tp-android status confirm --prompt "确认转账 100 元给张三？" \
  --wait-timeout 60
# -> {"confirmed":true/false}

# === Error ===
tp-android status error --code <CODE> --message "<人话>" \
  [--recoverable true|false] [--hint "<修复指令>"]

# === Cancel (从外部) ===
tp-android status cancel [--id <id>]

# === Observability ===
tp-android status current --pretty
tp-android status history --limit 20
tp-android status tail --follow
```

`status start` 输出 `run_id` 写入 `$TP_RUN_ID` env，后续命令默认读取。

### 6.3 SKILL.md 强制 envelope

> Any tp-android automation that takes >2 logical steps **must** wrap with `status start / step / finish`. Any branch needing user judgment **must** use `status intervene` or `status ask-input` and route on the returned choice. Never silently continue when ambiguous.

### 6.4 错误码归一表

| Code | 含义 | hint 字段例子 |
|---|---|---|
| `AUTOTERMUX_KILLED` | companion 进程不在 | `Launch AutoTermux to foreground` |
| `TERMUX_NOT_FOREGROUND` | Termux 被切到后台后死了 | `tp-android keepalive ensure --restart` |
| `A11Y_DISABLED` | 无障碍服务关 | `tp-android setup --resume a11y` |
| `A11Y_TIMEOUT` | service alive 但无响应 | `Restart accessibility service` |
| `NOTIF_LISTENER_DISABLED` | 通知监听关 | `tp-android setup --resume notif` |
| `PERMISSION_MISSING:<p>` | runtime perm 被拒 | `tp-android permissions request <p>` |
| `OVERLAY_BLOCKED` | SYSTEM_ALERT_WINDOW 关 | `tp-android setup --resume overlay` |
| `SCREENSHOT_CONSENT_DENIED` | MediaProjection 拒绝 | `Re-grant on next attempt` |
| `TARGET_NOT_FOUND` | selector 命中 0 | `Run ui digest to see available elements` |
| `TARGET_NOT_CLICKABLE` | 找到但不可点 | `Check element actions field` |
| `SELECTOR_AMBIGUOUS` | 多个匹配 | `Add resource-id or bounds filter` |
| `USER_CANCELLED` | HUD 用户取消 | `(terminal)` |
| `USER_INTERRUPTED` | 用户触摸打断 | `Check status intervene response` |
| `INPUT_TIMEOUT` | wait 超时 | `Increase --wait-timeout or check pre-conditions` |
| `BINDER_OVERFLOW` | payload > 1MB | `Re-run with --cache` |
| `OEM_BATTERY_KILL` | 反复 result=0 | `Whitelist AutoTermux in battery settings` |
| `TERMUX_PROP_MISSING` | allow-external-apps=false | `tp-android setup --resume termux-prop` |

每个错误必须返回 `hint` 字段，**直接是用户/Agent 可执行的下一步**。

---

## 7. L5 — HUD / Overlay 视觉反馈

### 7.1 现状

- `OverlayManager` 已有 WindowManager overlay + 元素 rect 画框 + 颜色方案 + 显隐切换
- 缺"状态条 / 介入气泡 / 取消按钮"模式

### 7.2 要做的

**P0 · HUD 三种模式**

1. **状态条** (status bar) —— 顶部 36dp 半透明
   - `[●运行中] 登录小红书 · 3/7 · 上一步: 点击登录 · 12s`
   - 右侧 `❚❚ 暂停` `✕ 取消` 按钮
   - 点击展开详情卡片
2. **介入气泡** (intervention dialog)
   - 屏幕中央卡片，覆盖目标 App
   - reason + 选项按钮 / 输入框
   - 用户选择后立刻收起，返回 choice 给 Agent
   - 长按背景 = 取消整个 run
3. **元素标注** (annotation overlay)
   - `ui screenshot --annotate` 短暂叠加红框 + 编号
   - 默认关闭

**P0 · HUD 控制接口**

```sh
tp-android hud show --mode status-bar
tp-android hud hide
tp-android hud position --edge top|bottom --side left|center|right
tp-android hud theme --opacity 0.85 --color auto|dark|light
```

**P1 · 系统通知双通道**

长跑任务推 sticky notification；锁屏 / 状态栏可见；通知 action: 暂停 / 取消 / 查看。HUD 被全屏 App 挡住时通知是兜底。

**P2 · TTS 通道**

```sh
tp-android status intervene --tts "请确认转账"
```

盲操友好（开车场景）。

**P3 · 远程 mirror**

HUD 状态可选 WebSocket 推到 host PC 浏览器。

---

## 8. L5.5 — 用户干扰处理（agent-mode）

### 8.1 干扰治理矩阵

| 干扰 | 检测手段 | 响应策略 |
|---|---|---|
| 浮窗遮挡 | 截图前后调用 hide-overlay | 自动 hide / restore |
| 用户单点 | a11y `TYPE_VIEW_CLICKED` 不在 Agent gesture log 里 | 标记 USER_INTERRUPTED |
| 切前台 | `FOREGROUND_APP_CHANGED` 且不是 Agent 触发 | pause + HUD 提示 |
| 拉通知栏 | a11y window state 变化 | pause + 等收回 |
| 屏幕熄灭 | screen state | pause + 自动唤醒（可配） |

### 8.2 接口

```sh
tp-android agent-mode start \
  --on-user-touch pause|continue|abort \
  --on-app-switch pause|continue|abort \
  --on-screen-off keep-awake|pause \
  --hard-lock false

tp-android agent-mode stop
tp-android agent-mode status --pretty
```

### 8.3 注入 gesture 打 tag

`GestureController.dispatchGesture(label="agent:<run_id>:<step>")`；a11y 收事件回查 tag，无 tag = 用户；200ms 滑动窗口去重防误判。

### 8.4 接管 / 让出

pause 状态下 HUD 显示 "用户已接管 · 长按 1 秒继续 Agent / 点击取消"；用户做完事可让 Agent 继续，run 不重启从下一步走。

---

## 9. L6 — Skill 文档与 Playbook

### 9.1 重构后的目录

```
bootstrap/home/.codex/skills/tp-android/
├── SKILL.md                 # 80 行精华：何时用、核心 envelope、recovery
├── reference/
│   ├── ui.md
│   ├── input.md
│   ├── triggers.md
│   ├── notifications.md
│   ├── status-protocol.md   # ⭐ L4 协议详解
│   └── errors.md            # ⭐ 错误码 + hint 表
├── playbooks/
│   ├── 01-read-sms-code.md
│   ├── 02-trigger-on-notification.md
│   ├── 03-fill-form-then-confirm.md
│   ├── 04-scrape-feed-with-scroll.md
│   ├── 05-multi-step-with-intervention.md
│   └── 99-recovery-when-stuck.md
├── prompts/
│   ├── pre-flight.txt
│   └── status-envelope.txt
└── agents/
    ├── openai.yaml
    └── claude.yaml
```

### 9.2 SKILL.md 顶部 ≤80 行必含

1. 一句话说明
2. **Pre-flight 4 行命令** (`doctor` / `setup --check` / `agent-mode start` / `status start`)
3. **Workflow envelope 三段示例**
4. **5 条铁律**：
   - 多步任务必 wrap status
   - 模糊判断必 intervene 不要猜
   - selector 优先，坐标兜底
   - 截图前 hide overlay
   - 错误看 code 不看 message
5. 指向 reference / playbook 路径

### 9.3 Playbook 写法

每个 playbook = 一个完整可粘贴的 `tp-android batch` 脚本 + 文字说明 + 失败兜底分支。Agent 找最像的 playbook → 改变量 → 跑，比从 0 编排稳得多。

---

## 10. 阶段规划

### Phase 1 — "状态透明 + 不死人"（4~6 周）

**目标**：Agent 跑任务时用户随时知道在干嘛，且进程不会半路死。

- L0: `tp-android setup` 引导向导
- L0: `tp-android keepalive ensure` + Termux foreground 保活
- L4: `tp-android status` 完整接口
- L4: 错误码归一 + hint 表
- L5: HUD 状态条 + 介入气泡（复用 OverlayManager）
- L5: 系统通知双通道
- L6: SKILL.md 重构 + 3 个 playbook

**用户感知**：装好就能用；HUD 永远在显示状态；卡住弹气泡问，不自作主张。

### Phase 2 — "动作准 + 不打架"（3~4 周）

- L2: 默认超时调整 + 复合 click/type/verify
- L3: `ui digest`
- L3: `ui screenshot --annotate` + element-image
- L5.5: `agent-mode` + 用户触摸检测
- L0/L5: Termux 浮窗 ↔ AutoTermux 隐藏协议
- L6: 再 3 个 playbook

### Phase 3 — "性能 + 可复用"（3~4 周）

- L1: `tp-android batch`
- L1: `tp-android daemon` + socket
- L1: `tp-android exec-script` (server-side DSL)
- L3: macro 录制 / 回放
- L1: IPC 容量自适应

### Phase 4 — "智能化兜底"（4~6 周）

- L3: OCR fallback (ML Kit)
- L3: Vision-augmented selector
- L2: 多指/复合手势
- L5: TTS 通道 / 远程 mirror
- L6: 完整 playbook 库（10+ 个常见 App 场景）

---

## 11. 反模式（不做的事）

| 不做 | 理由 |
|---|---|
| HTTP/WS 服务作为默认 transport | bridge 已够稳；多开端口暴露面 |
| 把 hook 系统加到 tp-android 上 | Agent 主动汇报 (L4) 已覆盖；hook 调试地狱 |
| 全屏黑屏阻塞用户 touch | 体验粗暴；用 HUD + agent-mode pause 软处理 |
| root-only 能力 | 用户群不在 root 设备上 |
| 在 tp-android 里塞 LLM 推理 | tp-android 是 CLI；LLM 在 Agent 层；保持职责清晰 |
| Tasker 风格 GUI 规则编辑器 | trigger 系统 + macro JSON 已足够 |
| 远程跨设备控制 | 当前 bridge 同机；跨设备另起产品 |
| 给每个 ContentProvider 都做 typed wrapper | `provider query/insert` 通道够用；按需逐升 |
| tp-android 拆多包 | 单文件 5404 行部署简单；daemon/batch 解决性能 |

---

## 12. 衡量标准

| 指标 | 来源 | 目标 |
|---|---|---|
| 首启到能跑第一个 task 的时间 | 真人计时 | < 3 分钟（含权限授权） |
| 10 步自动化全程通过率 | 7 大主流 App × 20 次 | > 90% |
| HUD 上识别"卡住要介入"的延迟 | 真人测试 | < 5 秒 |
| Agent 单次 prompt 内 tp-android 调用平均耗时 | 自动埋点 | batch 模式下 < 50ms/调用 |
| 错误返回时 Agent 自愈率 | 注入 12 类错误 | > 80% |
| 同任务 token 消耗 | A/B `ui dump` vs `ui digest` | 减少 ≥ 60% |
| OEM 黑名单设备长跑 10 分钟存活率 | keepalive on vs off | on 后 > 95% |

---

## 13. 一句话总览

> **一切围绕"Agent 主动告诉用户在干嘛、用户随时能接管不会打架、错误总是可解释可恢复"三件事建。其它都是支撑。**

---

## 14. 实施进度（live）

> 落地时持续更新此节。每个条目格式：`[状态] 子项 — commit/PR 链接 — 备注`

### Phase 1
- [x] L6 · SKILL.md 重构 — 80 行精华 + reference/full-reference.md 保留全文 + reference/{status-protocol,errors}.md
- [x] L4 · 错误码归一表 + `tp-android status error` 输出规范 — 22 个 codes，每个含 hint + recoverable
- [x] L4 · `tp-android status` CLI 子命令（start/step/progress/intervene/ask-input/confirm/error/cancel/finish/current/history/tail）
- [x] L4 · 跨进程读取 — `~/.termuxplus/runs/` 原子写文件 + `~/.termuxplus/runs/current` 指针；HUD-control marker 经共享存储跨进程传递
- [x] L5 · HUD 状态条渲染 — 新增 `HudOverlay.kt`（独立 WindowManager view），在 `AutoTermuxAccessibilityService.onCreate` 初始化，`hud/{show,hide,update,state}` bridge actions
- [x] L5 · HUD 介入气泡 — 复用 `DialogBridgeActivity`，`status intervene` 路由到 `dialog radio`、`ask-input` → `dialog text`、`confirm` → `dialog confirm`（零 Kotlin 改动）
- [x] L5 · 系统通知双通道 — `_status_notification_publish` 在每次 status 状态变化时调用现有 termux-api 的 `notification` 推送，`--notify` flag 开关
- [x] L0 · `tp-android setup` 引导向导 — 8 项检查（termux/companion/a11y/notif-listener/overlay/runtime-perms/termux.properties/battery），`--check/--headless/--resume <step>`，自动改 termux.properties
- [x] L0 · `tp-android keepalive ensure` + Termux foreground 保活 — 4 件套探针，`--restart` 自动拉前台 AutoTermux，`keepalive watch` JSONL 心跳
- [x] L6 · 3 个 playbook — `playbooks/{01-read-sms-code,02-form-with-intervention,03-multi-step-agent-loop}.md`

### Phase 2
- [x] L2 · 默认超时调整 — `app wait` 20s / `ui wait` 15s / `app launch-interactive` 15s
- [x] L2 · 复合 `--verify` — type/swipe 现在也支持 `--expect-text` / `--expect-package` / `--verify-timeout`
- [x] L2 · `ui wait --change` (截图 hash) / `--tree-changed` (a11y tree hash) / `--keyboard visible|hidden`
- [x] L3 · `ui digest` — 紧凑可交互节点列表，缓存到 `~/.termuxplus/ui-digest.json`，`ui click --idx N` 直接命中
- [x] L3 · `ui screenshot --annotate clickable` + `--map-out` + `ui element-image` — 依赖 Pillow，端侧画框/裁剪
- [x] L5.5 · `agent-mode start/stop/status` 子命令 + `AgentGestureTagger.kt`（注入打 tag）+ `AutoTermuxAccessibilityService` TYPE_VIEW_CLICKED 比对 + user-touch marker + tp-android `--on-user-touch pause` 策略
- [x] L0/L5 · `com.termux.HIDE_FLOATING` / `SHOW_FLOATING` signature-protected actions（TermuxService.onStartCommand）+ AutoTermux `floating/hide` `floating/show` bridge actions + tp-android `ui screenshot --exclude-overlays`

### Phase 3
- [x] L1 · `tp-android batch < ops.jsonl` — 单 Python 进程串行执行，`--on-error stop|continue|abort`、`--vars k=v`
- [x] L1 · `tp-android daemon start/stop/status` + Unix socket，`main()` 自动探测 `TP_DAEMON_SOCK` 转发，省 Python 冷启动
- [x] L1 · `tp-android exec-script <file>` — YAML/JSON 列表，支持 find_click/wait/type/key/scroll/intervene/ask_input/confirm/sleep/screenshot/done，`--task` 自动包 status，`{{var}}` 模板
- [ ] L3 · macro 录制 / 回放（未做）
- [x] L1 · IPC 容量自适应 — 已知重 endpoint 自动加 `cache=True`，OEM 黑名单 hard limit，BINDER_OVERFLOW 自动重试 with cache

### Phase 4
- [x] L3 · OCR fallback — `ui find-ocr --ocr "text" [--click]`，调用 `$TP_OCR_CMD`（默认 tesseract）parse TSV 输出
- [x] L3 · Vision-augmented selector — `ui click-vision <query>` 编排 annotate + dialog/terminal 询问 idx + tap
- [ ] L2 · 多指手势（未做）
- [ ] L5 · TTS / 远程 mirror（未做）
- [ ] L6 · 完整 playbook 库（未做）

---
