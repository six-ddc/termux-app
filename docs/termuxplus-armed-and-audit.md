# AutoTermux Armed Switch + Activity Log — Implementation Handoff

> 工作分支：`termuxplus-foundation`。本文档由上一段会话产出，目的是让下一位接手的 AI / 工程师能在不读上下文的情况下继续把这件事做完。

## 1. 背景与动机

用户的真实顾虑不是"反检测"，而是**对自己授权的 AutoTermux 在后台干什么缺乏可见性和可控性**。AutoTermux 持有无障碍权限后能：

- 监听所有窗口事件、抓 UI tree
- 通过 `TermuxAutomationBridgeReceiver`（signature-protected broadcast）接受外部 CALL
- 通过 `TriggerRuntime` 在定时/通知/前台变化/电量/解锁/SMS 等条件下自动调起 Termux 命令
- 自动确认 MediaProjection 截屏授权和 APK 安装弹框（`AutoAcceptGate`）

我们要给用户两把"刹车 + 仪表盘"：

1. **Armed/Disarmed 总开关**：权限留着，但所有"动作类"通路被关闭；用户可以一键切换。
2. **Activity audit log**：每次它替用户做了什么（trigger 触发、bridge 调用、auto-accept、armed 状态变更）都落日志，UI 直观展示。

## 2. 设计要点

### 2.1 Armed/Disarmed

- 默认 `armed = true`（兼容现状，不破坏老用户）
- 三层防御：
  1. `AutoTermuxAccessibilityService.onAccessibilityEvent` 头部短路：disarmed 时不 `emitDeviceEvent`、不跑 auto-accept 逻辑
  2. 监听 `ConfigManager.ConfigChangeListener.onArmedChanged`：disarmed 时调 `setServiceInfo(...)` 把 `eventTypes = 0`、`packageNames = emptyArray()`，让系统层就停止向我们投递事件；armed 时恢复完整 serviceInfo（与 `onServiceConnected` 中一致）
  3. `TriggerRuntime.handleDeviceEvent` / `handleScheduledRule` 头部 gate；disarmed 时记一条 audit `trigger_blocked_disarmed` 然后 return
  4. `ActionDispatcher.dispatch` 头部 gate：disarmed 时只放过白名单（`ping`、`mode_status`、`triggers/*` 全部 CRUD），其它返回 `ApiResponse.Error("AutoTermux is disarmed")`

- **不影响**：
  - `launchTest`（用户在 UI/CLI 主动触发的 trigger 测试）不受 armed 阻挡，但记 audit
  - 服务生命周期（`onServiceConnected`、`onUnbind`）正常走

### 2.2 Audit log

- 新包 `com.termux.autotermux.audit`，单例 `AuditLog`
- 持久化：`appContext.filesDir/autotermux-audit.jsonl`，单行一条 JSON
- 内存：固定容量 ring buffer，500 条；启动时从文件 tail 还原
- 文件轮转：超过 2 MB 时切到 `.jsonl.1`，保留 1 份备份
- 线程：单 `Executors.newSingleThreadExecutor()` 串行写盘；UI 读 ring buffer 是 lock-free（用 `synchronized` 数组拷贝）
- 监听：注册型 listener `interface AuditLogListener { fun onAuditUpdated() }`，每次 record 后回调（在 main thread 通过 `Handler(Looper.getMainLooper())` post）

#### 2.2.1 entry schema

```kotlin
data class AuditEntry(
    val timestampMs: Long,
    val kind: Kind,        // 枚举
    val summary: String,   // 单行人类可读
    val details: String?,  // 可选 JSON 字符串，UI 展开时显示
)

enum class Kind {
    ARMED_CHANGED,
    SERVICE_CONNECTED,
    SERVICE_DISCONNECTED,
    TRIGGER_FIRED,
    TRIGGER_BLOCKED_DISARMED,
    TRIGGER_FAILED,
    BRIDGE_ACTION,
    BRIDGE_BLOCKED_DISARMED,
    AUTO_ACCEPT_FIRED,
}
```

JSONL 序列化字段：`t`(ms), `k`(kind.name), `s`(summary), `d`(details, optional)。

#### 2.2.2 写入点

| 位置 | Kind | summary 示例 |
|------|------|--------------|
| `ConfigManager.setArmedWithNotification` 之后调用 | `ARMED_CHANGED` | `"Armed"` / `"Disarmed"` |
| `AutoTermuxAccessibilityService.onServiceConnected` 末尾 | `SERVICE_CONNECTED` | `"Accessibility service connected"` |
| `AutoTermuxAccessibilityService.onUnbind` / `onDestroy` | `SERVICE_DISCONNECTED` | `"Accessibility service disconnected"` |
| `TriggerRuntime.evaluateRule` Success 分支 | `TRIGGER_FIRED` | `"<rule.name> → <rule.commandPath>"` |
| `TriggerRuntime.evaluateRule` Error 分支 | `TRIGGER_FAILED` | `"<rule.name> failed: <msg>"` |
| `TriggerRuntime.handleDeviceEvent` disarmed 早退 | `TRIGGER_BLOCKED_DISARMED` | `"Skipped <signal.source>"` |
| `ActionDispatcher.dispatch` armed 执行后（非 noisy 方法） | `BRIDGE_ACTION` | `"<method> from <origin>"` |
| `ActionDispatcher.dispatch` disarmed 早退 | `BRIDGE_BLOCKED_DISARMED` | `"<method> blocked"` |
| `MediaProjectionAutoAccept.tryAutoAccept` 成功时 | `AUTO_ACCEPT_FIRED` | `"MediaProjection auto-accepted"` |
| `PackageInstallerAutoAccept.tryAutoAccept` 成功时 | `AUTO_ACCEPT_FIRED` | `"APK install auto-accepted"` |

#### 2.2.3 ActionDispatcher noisy denylist

不写 audit 的"读操作"方法名（高频且无副作用）：

```
ping, mode_status, auth_token,
state, state_full, state/connection, connection/state,
phone_state, phone-state, ui/phone-state,
a11y_tree, tree, ui/tree, a11y_tree_full, tree/full, ui/tree/full,
a11y_tree/cache, tree/cache, ui/tree/cache
```

只要不在这个 denylist 里、armed 状态下 dispatch 成功的，都记一条 `BRIDGE_ACTION`。

### 2.3 UI 改造

#### 2.3.1 Runtime 卡片顶部增加 armed 控制行

在 `app/src/main/res/layout/activity_main.xml` 中 Runtime 卡片（line 54-299）顶部、`accessibility_status_panel`（line 67）之前加一个新的 LinearLayout `@+id/armed_status_panel`：

- 背景：armed 时用 `autotermux_permission_ready_bg`（绿色描边），disarmed 时用 `autotermux_permission_required_bg`（黄色描边）
- 左侧 icon（用现有 `ic_autotermux_accessibility_24` 或新加 shield icon，新增 drawable 不强求）
- 中间："Control" 小标题 + 当前状态值（"Armed" / "Disarmed"）
- 右侧 Button：armed 时显示 "Disarm"（warning 黄底），disarmed 时显示 "Arm"（primary 绿底）

#### 2.3.2 删除 CLI 卡片，换成 Activity 卡片

`activity_main.xml` 中 CLI 卡片是 line 301-324。整段替换为：

```xml
<LinearLayout
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginTop="18dp"
    android:background="@drawable/autotermux_panel_bg"
    android:orientation="vertical"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            style="@style/AutoTermux_SectionTitle"
            android:layout_width="0dp"
            android:layout_weight="1"
            android:layout_marginBottom="0dp"
            android:text="Activity" />

        <TextView
            android:id="@+id/activity_count"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/text_gray_light"
            android:textSize="11sp"
            android:layout_marginEnd="8dp" />

        <Button
            android:id="@+id/activity_clear"
            style="@style/AutoTermux_Button_Secondary"
            android:layout_width="wrap_content"
            android:layout_height="36dp"
            android:minWidth="0dp"
            android:paddingStart="10dp"
            android:paddingEnd="10dp"
            android:text="Clear"
            android:textSize="12sp" />
    </LinearLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/activity_log_list"
        android:layout_width="match_parent"
        android:layout_height="360dp"
        android:layout_marginTop="12dp"
        android:background="@drawable/autotermux_code_bg"
        android:padding="6dp"
        android:scrollbars="vertical" />

    <TextView
        android:id="@+id/activity_empty"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:paddingTop="24dp"
        android:paddingBottom="24dp"
        android:text="No activity yet"
        android:textColor="@color/text_gray_light"
        android:textSize="13sp"
        android:visibility="gone" />
</LinearLayout>
```

注意外层是 ScrollView（line 2）。RecyclerView 嵌在 ScrollView 里要给固定高度（`360dp`）避免高度坍塌。

#### 2.3.3 新建 audit_log_item.xml

`autotermux/src/main/res/layout/audit_log_item.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:paddingStart="6dp"
    android:paddingEnd="6dp"
    android:paddingTop="6dp"
    android:paddingBottom="6dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <TextView
            android:id="@+id/audit_time"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:textColor="@color/text_gray_light"
            android:textSize="11sp" />

        <TextView
            android:id="@+id/audit_kind"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:paddingStart="6dp"
            android:paddingEnd="6dp"
            android:paddingTop="1dp"
            android:paddingBottom="1dp"
            android:textSize="10sp"
            android:textStyle="bold"
            android:textAllCaps="true" />

        <TextView
            android:id="@+id/audit_summary"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="8dp"
            android:textColor="@color/text_white"
            android:textSize="12sp"
            android:maxLines="1"
            android:ellipsize="end" />
    </LinearLayout>

    <TextView
        android:id="@+id/audit_details"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginTop="4dp"
        android:layout_marginStart="22dp"
        android:fontFamily="monospace"
        android:textColor="@color/text_gray_light"
        android:textSize="11sp"
        android:visibility="gone"
        android:textIsSelectable="true" />
</LinearLayout>
```

整个 item 点击展开/收起 `audit_details`。

#### 2.3.4 RecyclerView Adapter

新建 `autotermux/src/main/java/com/termux/autotermux/ui/AuditLogAdapter.kt`：

- 持有 `List<AuditEntry>`
- 时间戳格式：`HH:mm:ss`（用 `SimpleDateFormat`）
- kind chip 颜色映射：
  - `ARMED_CHANGED` → `autotermux_warning`
  - `SERVICE_CONNECTED`/`SERVICE_DISCONNECTED` → `text_gray_light`
  - `TRIGGER_FIRED` → `autotermux_primary_light` (绿)
  - `TRIGGER_BLOCKED_DISARMED` / `BRIDGE_BLOCKED_DISARMED` → `autotermux_warning`
  - `TRIGGER_FAILED` → 红色（新加 `autotermux_error = #FF6B6B`）
  - `BRIDGE_ACTION` → `autotermux_primary`
  - `AUTO_ACCEPT_FIRED` → `autotermux_warning`
- kind 显示用简短名字：`armed_changed` → `ARMED`，`trigger_fired` → `TRIGGER`，`bridge_action` → `BRIDGE` 等
- 默认按时间逆序显示（最新在上）；显示最近 50 条
- item 点击切换 `audit_details` 可见性

### 2.4 MainActivity 接线

`autotermux/src/main/java/com/termux/autotermux/ui/MainActivity.kt`：

1. 类实现 `ConfigManager.ConfigChangeListener`，`onArmedChanged` 中 `runOnUiThread { refreshStatus() }`
2. `onCreate` 末尾 `configManager.addListener(this)`；`onDestroy` 中 `configManager.removeListener(this)`
3. 新增字段：`armedStatusPanel`、`armedStatusView`、`armedActionButton`、`activityLogList: RecyclerView`、`activityCountView`、`activityClearButton`、`activityEmptyView`、`auditAdapter: AuditLogAdapter`
4. `bindViews()` 加 findViewById；`bindActions()`：
   - `armedActionButton.setOnClickListener { configManager.setArmedWithNotification(!configManager.armed) }`
   - `activityClearButton.setOnClickListener { AuditLog.getInstance(this).clear() }`
5. `refreshStatus()` 末尾增加 `applyArmedStatus(configManager.armed)`，同时把 `status_summary` 在 disarmed 时显示 "DISARMED — automation paused, audit log running"
6. `onResume()` 中调 `auditAdapter.submit(AuditLog.getInstance(this).snapshot(50))`，并注册 `AuditLog.addListener(uiListener)`；`onPause()` 中移除
7. `applyArmedStatus(armed: Boolean)` 写法参照现有的 `applyAccessibilityStatus`：armed → 绿底 + "Disarm" 按钮（warning 黄），disarmed → 黄底 + "Arm" 按钮（primary 绿）

### 2.5 AuditLog 写盘格式（防止 next AI 写歪）

```
{"t":1716543210123,"k":"TRIGGER_FIRED","s":"battery_low → /data/data/com.termux/files/home/scripts/.battery_low.sh","d":"{\"rule_id\":\"abc\",\"signal_source\":\"BATTERY_LOW\"}"}
```

读盘恢复 ring buffer 时按行解析，宽容失败行（continue）；末尾追加用 append-mode FileOutputStream。

## 3. 当前进度

| Task | 状态 | 说明 |
|------|------|------|
| 1. ConfigManager: armed 字段 | ✅ 已完成 | 见下面"已落地的改动"|
| 2. AuditLog 模块 | 🟡 进行中 | 目录 `autotermux/src/main/java/com/termux/autotermux/audit/` 已创建，**空目录**，无任何 `.kt` 文件 |
| 3. AccessibilityService: gate + soft-pause | ⏳ 未开始 | |
| 4. TriggerRuntime: gate + audit | ⏳ 未开始 | |
| 5. ActionDispatcher: gate + audit | ⏳ 未开始 | |
| 6. UI 布局重构 | ⏳ 未开始 | |
| 7. MainActivity 接线 | ⏳ 未开始 | |
| 8. 构建验证 `:autotermux:assembleDebug` | ⏳ 未开始 | |

## 4. 已落地的改动（截至本次会话结束）

仅一处：`autotermux/src/main/java/com/termux/autotermux/config/ConfigManager.kt`，4 个 chunk：

1. line 24：常量声明
   ```kotlin
   private const val KEY_ARMED = "armed"
   ```

2. line 127-131：属性
   ```kotlin
   var armed: Boolean
       get() = sharedPrefs.getBoolean(KEY_ARMED, true)
       set(value) {
           sharedPrefs.edit(commit = true) { putBoolean(KEY_ARMED, value) }
       }
   ```

3. line 307-311：带通知的 setter
   ```kotlin
   fun setArmedWithNotification(armedValue: Boolean) {
       if (armed == armedValue) return
       armed = armedValue
       listeners.forEach { it.onArmedChanged(armedValue) }
   }
   ```

4. line 405：listener 默认实现
   ```kotlin
   fun onArmedChanged(armed: Boolean) {}
   ```

`grep -n "armed\|KEY_ARMED\|onArmedChanged\|setArmedWithNotification" autotermux/src/main/java/com/termux/autotermux/config/ConfigManager.kt` 输出可以验证。

## 5. 关键参考文件路径与行号

| 文件 | 用途 |
|------|------|
| `autotermux/src/main/java/com/termux/autotermux/config/ConfigManager.kt` | 已改 |
| `autotermux/src/main/java/com/termux/autotermux/service/AutoTermuxAccessibilityService.kt:259-305` | `onServiceConnected` 配置 serviceInfo，要参考它写"完整 serviceInfo"还原逻辑 |
| `autotermux/src/main/java/com/termux/autotermux/service/AutoTermuxAccessibilityService.kt:307-405` | `onAccessibilityEvent` 头部 gate 位置 |
| `autotermux/src/main/java/com/termux/autotermux/triggers/TriggerRuntime.kt:143-159` | `handleScheduledRule` gate 位置 |
| `autotermux/src/main/java/com/termux/autotermux/triggers/TriggerRuntime.kt:167-179` | `handleDeviceEvent` gate 位置 |
| `autotermux/src/main/java/com/termux/autotermux/triggers/TriggerRuntime.kt:252-295` | `evaluateRule` 成功/失败要写 audit |
| `autotermux/src/main/java/com/termux/autotermux/service/ActionDispatcher.kt:27-32` | `dispatch` gate 位置 |
| `autotermux/src/main/java/com/termux/autotermux/service/ActionDispatcher.kt:51-` | switch-when 表，记 audit 时机在每个 case 返回前；更简洁的做法是在 `dispatch` 末尾统一记 |
| `autotermux/src/main/java/com/termux/autotermux/api/ApiHandler.kt:99-100` | `applicationContext` 可用 |
| `autotermux/src/main/res/layout/activity_main.xml` | UI 改造目标，原 CLI 卡片 line 301-324 |
| `autotermux/src/main/res/values/themes.xml:31-70` | 现有 style 复用 |
| `autotermux/src/main/res/values/colors.xml` | 现有色板，可能要补 `autotermux_error` |
| `app/build.gradle` 或 `autotermux/build.gradle` | 确认 RecyclerView 依赖（应该已有 androidx，没有的话加 `androidx.recyclerview:recyclerview`） |

## 6. 实施顺序建议

按 Task 1→8 顺序执行。当前进度：Task 1 已完成，Task 2 进行中（仅建目录）。

下一位 AI 推荐从 Task 2 开始：先把 `AuditLog.kt` 这个独立的、无外部依赖的工具类写完测试通过，再依次接入 service / TriggerRuntime / ActionDispatcher 的 gate 点。UI 放最后做。

## 7. 验证清单

实施完成后应当：

```sh
# 编译
./gradlew :autotermux:assembleDebug

# 装到设备
adb -s <device> install -r autotermux/build/outputs/apk/debug/autotermux-debug.apk

# 手测
# 1. 打开 AutoTermux 主界面，看到 Runtime 卡片顶部"Control"行
# 2. 看到 Activity 卡片（替换了 CLI 卡片），RecyclerView 可滚动
# 3. 点 Disarm：状态行变黄、Button 变绿 "Arm"、status_summary 文案变化
# 4. 安排一个 trigger（如 BATTERY_LOW），在 disarmed 下手动 inject 一个匹配信号
#    （或简单点：用 tp-android trigger rule test <id> 测试 launch）
#    → Activity 列表新增一条 TRIGGER_BLOCKED_DISARMED
# 5. 点 Arm，再次触发 → 看到 TRIGGER_FIRED
# 6. 重启 App，audit log 应当从磁盘恢复
# 7. 点 Clear，列表清空
```

## 8. 不要做的事

- 不要改包名、APK 身份。
- 不要去尝试"让别的 App 检测不到无障碍服务"——已经在前序讨论中说明做不到（要 `WRITE_SECURE_SETTINGS`）。
- 不要把 `armed` 默认改成 `false`——会破坏现有用户的自动化。
- 不要把 audit log 写到 Termux home（`/data/data/com.termux/files/home`）——那是另一个 App 的私有目录，AutoTermux 没权限直接写。用 AutoTermux 自己的 `filesDir`。
- 不要给 audit log 加加密——目前是用户本机可见的诊断信息，加密会让"用户自查"的初衷反转。
- 不要在 `onAccessibilityEvent` 里做同步 I/O 写 audit——会拖慢事件分发。所有 audit.record 必须 fire-and-forget 异步落盘。

## 9. 后续 Backlog（本期不做）

- `tp-android a11y arm/disarm/status` CLI 子命令（通过新增 bridge action `ACTION_SET_ARMED` 实现）
- 通知栏常驻 chip 显示 armed 状态 + 一键切换（vivo 后台 kill 严，常驻通知顺带护命）
- audit log 导出 (`adb pull` 当然能拿，但 UI 加个"Export" 按钮可分享 JSON 更友好)
- audit log 过滤（按 Kind / 按时间）
- 用户自定义"高敏 trigger 需要确认"模式（Plan 中的 Part 3，本期暂不做）
