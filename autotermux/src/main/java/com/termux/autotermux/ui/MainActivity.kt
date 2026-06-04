package com.termux.autotermux.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.autotermux.R
import com.termux.autotermux.audit.AuditLog
import com.termux.autotermux.audit.AuditLogListener
import com.termux.autotermux.config.ConfigManager
import com.termux.autotermux.input.AutoTermuxKeyboardIME
import com.termux.autotermux.service.AutoTermuxAccessibilityService
import com.termux.autotermux.service.LocalAutomationService

/**
 * Main entry-point UI for AutoTermux.
 *
 * Layout: header with Armed switch on the right, then three cards:
 *   1. Permissions — every special-access + runtime perm the app actually uses.
 *      Each row probes its own state on resume and offers a one-tap deep link
 *      to the right Settings page. The aggregate `X/Y granted` counter at the
 *      top of the card is what a user should glance at first.
 *   2. Services — HTTP API toggle, bridge identity.
 *   3. Activity — audit log RecyclerView.
 */
class MainActivity : AppCompatActivity(), ConfigManager.ConfigChangeListener {

    private lateinit var configManager: ConfigManager
    private val auditListener = AuditLogListener { runOnUiThread { refreshAuditLog() } }

    private lateinit var summaryView: TextView
    private lateinit var armedSwitch: SwitchCompat
    private lateinit var armedStateLabel: TextView
    private lateinit var permissionsSummaryView: TextView

    // Permission rows (inflated via <include>)
    private lateinit var rowAccessibility: PermissionRow
    private lateinit var rowOverlay: PermissionRow
    private lateinit var rowNotifListener: PermissionRow
    private lateinit var rowStorage: PermissionRow
    private lateinit var rowKeyboardEnable: PermissionRow
    private lateinit var rowKeyboardSelect: PermissionRow
    private lateinit var rowRuntime: PermissionRow

    // Bridge identity (informational; tp-android always talks over the bridge)
    private lateinit var bridgeIcon: ImageView
    private lateinit var bridgeStatusView: TextView

    // Activity log
    private lateinit var activityLogList: RecyclerView
    private lateinit var activityCountView: TextView
    private lateinit var activityClearButton: Button
    private lateinit var activityEmptyView: TextView
    private lateinit var auditAdapter: AuditLogAdapter

    // Runtime permissions that AutoTermux actually requests at use-time.
    // Keeping these together so the row summary stays accurate.
    private val runtimePermissions = listOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BODY_SENSORS,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configManager = ConfigManager.getInstance(applicationContext)
        bindViews()
        bindActions()
        configManager.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        AuditLog.getInstance(this).addListener(auditListener)
        refreshAll()
        // Termux's floating-terminal bubble would otherwise sit over our top-right
        // (covering the Armed switch and any banners). Hide it while AutoTermux's
        // main UI is up; restore on pause so the user's normal workflow continues
        // when they leave. This uses the signature-protected action we already
        // wired into TermuxService.onStartCommand.
        sendFloatingControl("com.termux.HIDE_FLOATING")
    }

    override fun onPause() {
        AuditLog.getInstance(this).removeListener(auditListener)
        sendFloatingControl("com.termux.SHOW_FLOATING")
        super.onPause()
    }

    private fun sendFloatingControl(action: String) {
        try {
            // Routes through TermuxFloatingControlReceiver in :app (signature-
            // protected with com.termux.permission.RUN_COMMAND, which we hold)
            // so we don't need TermuxService itself to be exported.
            val intent = Intent(action)
                .setClassName("com.termux", "com.termux.app.TermuxFloatingControlReceiver")
            sendBroadcast(intent)
        } catch (_: Throwable) {
            // Termux may not be installed; ignore silently.
        }
    }

    override fun onDestroy() {
        configManager.removeListener(this)
        super.onDestroy()
    }

    override fun onArmedChanged(armed: Boolean) {
        runOnUiThread {
            armedSwitch.setOnCheckedChangeListener(null)
            armedSwitch.isChecked = armed
            armedSwitch.setOnCheckedChangeListener { _, checked ->
                configManager.setArmedWithNotification(checked)
            }
            refreshSummary()
        }
    }

    // ---- view binding ------------------------------------------------------

    private fun bindViews() {
        summaryView = findViewById(R.id.status_summary)
        armedSwitch = findViewById(R.id.armed_switch)
        armedStateLabel = findViewById(R.id.armed_state_label)
        permissionsSummaryView = findViewById(R.id.permissions_summary)

        rowAccessibility = PermissionRow(findViewById(R.id.perm_accessibility))
        rowOverlay = PermissionRow(findViewById(R.id.perm_overlay))
        rowNotifListener = PermissionRow(findViewById(R.id.perm_notif_listener))
        rowStorage = PermissionRow(findViewById(R.id.perm_storage))
        rowKeyboardEnable = PermissionRow(findViewById(R.id.perm_keyboard_enable))
        rowKeyboardSelect = PermissionRow(findViewById(R.id.perm_keyboard_select))
        rowRuntime = PermissionRow(findViewById(R.id.perm_runtime))

        bridgeIcon = findViewById(R.id.bridge_icon)
        bridgeStatusView = findViewById(R.id.bridge_status)

        activityLogList = findViewById(R.id.activity_log_list)
        activityCountView = findViewById(R.id.activity_count)
        activityClearButton = findViewById(R.id.activity_clear)
        activityEmptyView = findViewById(R.id.activity_empty)
        auditAdapter = AuditLogAdapter()
        activityLogList.layoutManager = LinearLayoutManager(this)
        activityLogList.adapter = auditAdapter
    }

    private fun bindActions() {
        armedSwitch.setOnCheckedChangeListener { _, checked ->
            configManager.setArmedWithNotification(checked)
        }
        activityClearButton.setOnClickListener {
            AuditLog.getInstance(this).clear()
            refreshAuditLog()
        }
    }

    // ---- refresh ----------------------------------------------------------

    private fun refreshAll() {
        refreshSummary()
        refreshPermissions()
        refreshServices()
        refreshAuditLog()
    }

    private fun refreshSummary() {
        val armed = configManager.armed
        armedSwitch.setOnCheckedChangeListener(null)
        armedSwitch.isChecked = armed
        armedSwitch.setOnCheckedChangeListener { _, checked ->
            configManager.setArmedWithNotification(checked)
        }
        armedStateLabel.text = if (armed) "ARMED" else "DISARMED"
        armedStateLabel.setTextColor(
            color(if (armed) R.color.autotermux_primary_light else R.color.autotermux_warning),
        )

        val accessibilityOk = AutoTermuxAccessibilityService.getInstance() != null
            || isAccessibilityEnabledInSettings()
        summaryView.text = when {
            !armed -> "DISARMED — automation paused, audit log running"
            accessibilityOk -> "Ready for local Termux control"
            else -> "Enable Accessibility for full device control"
        }
        summaryView.setTextColor(
            color(
                when {
                    !armed -> R.color.autotermux_warning
                    accessibilityOk -> R.color.text_gray_light
                    else -> R.color.autotermux_warning
                },
            ),
        )
    }

    private fun refreshPermissions() {
        // 1. Accessibility
        val a11yBound = AutoTermuxAccessibilityService.getInstance() != null
        val a11yEnabled = isAccessibilityEnabledInSettings()
        val a11yState = when {
            a11yBound -> RowState.OK
            a11yEnabled -> RowState.OK_STANDBY
            else -> RowState.MISSING
        }
        rowAccessibility.render(
            icon = R.drawable.ic_autotermux_accessibility_24,
            label = "Accessibility",
            status = when (a11yState) {
                RowState.OK -> "Ready · UI inspection, gestures"
                RowState.OK_STANDBY -> "Standby · rebinds on next event"
                RowState.MISSING -> "Required — UI control disabled"
                else -> "—"
            },
            state = a11yState,
            actionLabel = if (a11yState == RowState.MISSING) "Grant" else "Settings",
        ) { openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        // 2. Overlay (SYSTEM_ALERT_WINDOW)
        val overlayOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
        rowOverlay.render(
            icon = R.drawable.ic_autotermux_overlay_24,
            label = "Display overlay",
            status = if (overlayOk) "Granted · HUD status bar, intervene bubble" else "Required — HUD won't render",
            state = if (overlayOk) RowState.OK else RowState.MISSING,
            actionLabel = if (overlayOk) "Settings" else "Grant",
        ) {
            openSettings(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .setData(Uri.parse("package:$packageName")),
            )
        }

        // 3. Notification listener
        val listenerOk = isNotificationListenerEnabled()
        rowNotifListener.render(
            icon = R.drawable.ic_autotermux_notification_24,
            label = "Notification listener",
            status = if (listenerOk) "Active · sees notifications, triggers fire" else "Optional — notification APIs disabled",
            state = if (listenerOk) RowState.OK else RowState.OPTIONAL_MISSING,
            actionLabel = if (listenerOk) "Settings" else "Grant",
        ) { openSettings(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }

        // 4. All files (MANAGE_EXTERNAL_STORAGE)
        val storageOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        rowStorage.render(
            icon = R.drawable.ic_autotermux_storage_24,
            label = "All files access",
            status = if (storageOk) "Granted · screenshot cache, transfer files" else "Required — large file transfers will fail",
            state = if (storageOk) RowState.OK else RowState.MISSING,
            actionLabel = if (storageOk) "Settings" else "Grant",
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSettings(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName")),
                )
            }
        }

        // (Battery-whitelist row was removed: PowerManager.isIgnoringBatteryOptimizations
        //  is meaningless on vivo/MIUI/Honor — the OEM's private "high-power"
        //  controls are what actually matter and the standard API can't read
        //  them. Process survival is now handled by AutoTermuxBackgroundService,
        //  a foreground service tied to active tp-android runs.)

        // 5. Keyboard IME — enable
        val keyboardEnabled = AutoTermuxKeyboardIME.isEnabled(this)
        val keyboardSelected = AutoTermuxKeyboardIME.isSelected(this)
        rowKeyboardEnable.render(
            icon = R.drawable.ic_autotermux_keyboard_24,
            label = "Keyboard IME · enabled",
            status = if (keyboardEnabled) "Enabled" else "Optional — text injection needs this",
            state = if (keyboardEnabled) RowState.OK else RowState.OPTIONAL_MISSING,
            actionLabel = if (keyboardEnabled) "Settings" else "Enable",
        ) { openSettings(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }

        // 7. Keyboard IME — selected as current
        rowKeyboardSelect.render(
            icon = R.drawable.ic_autotermux_keyboard_24,
            label = "Keyboard IME · selected",
            status = when {
                keyboardSelected -> "Current input method"
                keyboardEnabled -> "Not selected"
                else -> "Enable first"
            },
            state = when {
                keyboardSelected -> RowState.OK
                keyboardEnabled -> RowState.OPTIONAL_MISSING
                else -> RowState.DISABLED
            },
            actionLabel = if (keyboardSelected) "Change" else "Select",
        ) {
            if (keyboardEnabled) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }

        // 8. Runtime perms aggregate
        val runtimeGranted = countGrantedRuntimePerms()
        val total = runtimePermissions.size
        val allRuntime = runtimeGranted == total
        rowRuntime.render(
            icon = R.drawable.ic_autotermux_runtime_24,
            label = "Runtime perms",
            status = "$runtimeGranted/$total granted · SMS, contacts, camera, mic, location…",
            state = when {
                runtimeGranted == total -> RowState.OK
                runtimeGranted == 0 -> RowState.OPTIONAL_MISSING
                else -> RowState.PARTIAL
            },
            actionLabel = if (allRuntime) "Settings" else "Manage",
        ) {
            openSettings(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName")),
            )
        }

        // Summary counter (7 rows after removing battery)
        val checks = listOf(
            a11yState == RowState.OK || a11yState == RowState.OK_STANDBY,
            overlayOk,
            listenerOk,
            storageOk,
            keyboardEnabled,
            keyboardSelected,
            allRuntime,
        )
        val grantedCount = checks.count { it }
        permissionsSummaryView.text = "$grantedCount/${checks.size} granted"
        permissionsSummaryView.setTextColor(
            color(if (grantedCount == checks.size) R.color.autotermux_primary_light else R.color.text_gray_light),
        )
    }

    private fun refreshServices() {
        // tp-android only talks to us over the bridge — no HTTP toggle on the
        // home screen. The bridge identity is shown for diagnostics.
        bridgeStatusView.text = packageName
        applyIcon(bridgeIcon, R.color.text_gray_light)
    }

    private fun refreshAuditLog() {
        val auditLog = AuditLog.getInstance(this)
        val entries = auditLog.snapshot(50)
        auditAdapter.submit(entries)
        activityCountView.text = "${entries.size}/${auditLog.size()}"
        activityEmptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---- probes -----------------------------------------------------------

    private fun isAccessibilityEnabledInSettings(): Boolean {
        val expected = ComponentName(this, AutoTermuxAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        return flat.split(':').any { it.contains(packageName) }
    }

    private fun countGrantedRuntimePerms(): Int {
        var n = 0
        for (perm in runtimePermissions) {
            if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
                n++
            }
        }
        return n
    }

    private fun openSettings(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Throwable) {
            // Fall back to the app's own details page so the user can navigate manually.
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Throwable) {
                // best-effort
            }
        }
    }

    // ---- visual helpers ---------------------------------------------------

    private fun color(resourceId: Int): Int = ContextCompat.getColor(this, resourceId)
    private fun colorStateList(resourceId: Int): ColorStateList = ColorStateList.valueOf(color(resourceId))

    private fun applyIcon(icon: ImageView, colorResourceId: Int) {
        icon.imageTintList = colorStateList(colorResourceId)
    }

    private fun applyPrimaryButton(button: Button, text: String) {
        button.text = text
        button.typeface = Typeface.MONOSPACE
        button.isEnabled = true
        button.alpha = 1.0f
        button.backgroundTintList = colorStateList(R.color.autotermux_primary)
        button.setTextColor(color(R.color.background_primary))
    }

    private fun applySecondaryButton(button: Button, text: String) {
        button.text = text
        button.typeface = Typeface.MONOSPACE
        button.isEnabled = true
        button.alpha = 1.0f
        button.backgroundTintList = colorStateList(R.color.background_tertiary)
        button.setTextColor(color(R.color.autotermux_primary))
    }

    private fun applyWarningButton(button: Button, text: String) {
        button.text = text
        button.typeface = Typeface.MONOSPACE
        button.isEnabled = true
        button.alpha = 1.0f
        button.backgroundTintList = colorStateList(R.color.autotermux_warning_button)
        button.setTextColor(color(R.color.black))
    }

    private fun applyDisabledButton(button: Button, text: String) {
        button.text = text
        button.typeface = Typeface.MONOSPACE
        button.isEnabled = false
        button.alpha = 0.45f
        button.backgroundTintList = colorStateList(R.color.background_tertiary)
        button.setTextColor(color(R.color.text_gray_light))
    }

    // ---- inner types ------------------------------------------------------

    private enum class RowState {
        OK, OK_STANDBY, PARTIAL, OPTIONAL_MISSING, MISSING, DISABLED, UNKNOWN
    }

    /**
     * Bind helper for a single permission row in the layout.
     * Each row exposes an icon, a label, a one-line status, and a primary button.
     * State affects the background tint and button style only — not the layout.
     */
    private inner class PermissionRow(val root: View) {
        private val iconView: ImageView = root.findViewById(R.id.row_icon)
        private val labelView: TextView = root.findViewById(R.id.row_label)
        private val statusView: TextView = root.findViewById(R.id.row_status)
        private val actionButton: Button = root.findViewById(R.id.row_action)

        fun render(
            icon: Int,
            label: String,
            status: String,
            state: RowState,
            actionLabel: String,
            onAction: () -> Unit,
        ) {
            iconView.setImageResource(icon)
            labelView.text = label
            statusView.text = status
            when (state) {
                RowState.OK -> {
                    root.setBackgroundResource(R.drawable.autotermux_permission_ready_bg)
                    applyIcon(iconView, R.color.autotermux_primary_light)
                    statusView.setTextColor(color(R.color.autotermux_primary_light))
                    applySecondaryButton(actionButton, actionLabel)
                }
                RowState.OK_STANDBY -> {
                    root.setBackgroundResource(R.drawable.autotermux_permission_ready_bg)
                    applyIcon(iconView, R.color.autotermux_primary_light)
                    statusView.setTextColor(color(R.color.autotermux_primary_light))
                    applySecondaryButton(actionButton, actionLabel)
                }
                RowState.PARTIAL -> {
                    root.setBackgroundResource(R.drawable.autotermux_status_row_bg)
                    applyIcon(iconView, R.color.text_white)
                    statusView.setTextColor(color(R.color.text_gray_light))
                    applySecondaryButton(actionButton, actionLabel)
                }
                RowState.OPTIONAL_MISSING -> {
                    root.setBackgroundResource(R.drawable.autotermux_status_row_bg)
                    applyIcon(iconView, R.color.text_gray_light)
                    statusView.setTextColor(color(R.color.text_gray_light))
                    applySecondaryButton(actionButton, actionLabel)
                }
                RowState.MISSING -> {
                    root.setBackgroundResource(R.drawable.autotermux_permission_required_bg)
                    applyIcon(iconView, R.color.autotermux_warning)
                    statusView.setTextColor(color(R.color.autotermux_warning))
                    applyWarningButton(actionButton, actionLabel)
                }
                RowState.DISABLED -> {
                    root.setBackgroundResource(R.drawable.autotermux_status_row_bg)
                    applyIcon(iconView, R.color.text_gray_light)
                    statusView.setTextColor(color(R.color.text_gray_light))
                    applyDisabledButton(actionButton, actionLabel)
                }
                RowState.UNKNOWN -> {
                    root.setBackgroundResource(R.drawable.autotermux_status_row_bg)
                    applyIcon(iconView, R.color.text_gray_light)
                    statusView.setTextColor(color(R.color.text_gray_light))
                    applySecondaryButton(actionButton, actionLabel)
                }
            }
            actionButton.setOnClickListener { onAction() }
        }
    }
}
