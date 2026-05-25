package com.termux.autotermux.ui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity(), ConfigManager.ConfigChangeListener {

    private lateinit var configManager: ConfigManager
    private val auditListener = AuditLogListener {
        runOnUiThread { refreshAuditLog() }
    }

    private lateinit var summaryView: TextView
    private lateinit var armedStatusPanel: View
    private lateinit var accessibilityStatusPanel: View
    private lateinit var keyboardStatusPanel: View
    private lateinit var localApiStatusPanel: View
    private lateinit var bridgeStatusPanel: View
    private lateinit var armedIcon: ImageView
    private lateinit var accessibilityIcon: ImageView
    private lateinit var keyboardIcon: ImageView
    private lateinit var localApiIcon: ImageView
    private lateinit var bridgeIcon: ImageView
    private lateinit var armedStatusView: TextView
    private lateinit var accessibilityStatusView: TextView
    private lateinit var keyboardEnableStatusView: TextView
    private lateinit var keyboardSelectStatusView: TextView
    private lateinit var httpStatusView: TextView
    private lateinit var bridgeStatusView: TextView
    private lateinit var armedActionButton: Button
    private lateinit var openAccessibilityButton: Button
    private lateinit var keyboardEnableButton: Button
    private lateinit var keyboardSelectButton: Button
    private lateinit var toggleHttpButton: Button
    private lateinit var activityLogList: RecyclerView
    private lateinit var activityCountView: TextView
    private lateinit var activityClearButton: Button
    private lateinit var activityEmptyView: TextView
    private lateinit var auditAdapter: AuditLogAdapter

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
        refreshStatus()
        refreshAuditLog()
    }

    override fun onPause() {
        AuditLog.getInstance(this).removeListener(auditListener)
        super.onPause()
    }

    override fun onDestroy() {
        configManager.removeListener(this)
        super.onDestroy()
    }

    private fun bindViews() {
        summaryView = findViewById(R.id.status_summary)
        armedStatusPanel = findViewById(R.id.armed_status_panel)
        accessibilityStatusPanel = findViewById(R.id.accessibility_status_panel)
        keyboardStatusPanel = findViewById(R.id.keyboard_status_panel)
        localApiStatusPanel = findViewById(R.id.local_api_status_panel)
        bridgeStatusPanel = findViewById(R.id.bridge_status_panel)
        armedIcon = findViewById(R.id.armed_icon)
        accessibilityIcon = findViewById(R.id.accessibility_icon)
        keyboardIcon = findViewById(R.id.keyboard_icon)
        localApiIcon = findViewById(R.id.local_api_icon)
        bridgeIcon = findViewById(R.id.bridge_icon)
        armedStatusView = findViewById(R.id.armed_status)
        accessibilityStatusView = findViewById(R.id.accessibility_status)
        keyboardEnableStatusView = findViewById(R.id.keyboard_enable_status)
        keyboardSelectStatusView = findViewById(R.id.keyboard_select_status)
        httpStatusView = findViewById(R.id.http_status)
        bridgeStatusView = findViewById(R.id.bridge_status)
        armedActionButton = findViewById(R.id.armed_action)
        openAccessibilityButton = findViewById(R.id.open_accessibility_settings)
        keyboardEnableButton = findViewById(R.id.keyboard_enable_action)
        keyboardSelectButton = findViewById(R.id.keyboard_select_action)
        toggleHttpButton = findViewById(R.id.toggle_http_server)
        activityLogList = findViewById(R.id.activity_log_list)
        activityCountView = findViewById(R.id.activity_count)
        activityClearButton = findViewById(R.id.activity_clear)
        activityEmptyView = findViewById(R.id.activity_empty)
        auditAdapter = AuditLogAdapter()
        activityLogList.layoutManager = LinearLayoutManager(this)
        activityLogList.adapter = auditAdapter
        localApiStatusPanel.visibility = View.GONE
    }

    private fun bindActions() {
        armedActionButton.setOnClickListener {
            configManager.setArmedWithNotification(!configManager.armed)
        }
        openAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        toggleHttpButton.setOnClickListener {
            setHttpServerEnabled(!configManager.socketServerEnabled)
        }
        activityClearButton.setOnClickListener {
            AuditLog.getInstance(this).clear()
            refreshAuditLog()
        }
    }

    private fun refreshStatus() {
        val accessibilityBound = AutoTermuxAccessibilityService.getInstance() != null
        val accessibilityEnabledInSettings = isAccessibilityEnabledInSettings()
        val accessibilityStatus = when {
            accessibilityBound -> AccessibilityStatus.READY
            accessibilityEnabledInSettings -> AccessibilityStatus.STANDBY
            else -> AccessibilityStatus.REQUIRED
        }
        val keyboardEnabled = AutoTermuxKeyboardIME.isEnabled(this)
        val keyboardSelected = AutoTermuxKeyboardIME.isSelected(this)
        val httpEnabled = configManager.socketServerEnabled
        val port = configManager.socketServerPort
        val socketStatus = AutoTermuxAccessibilityService.getInstance()?.getSocketServerStatus()
            ?: LocalAutomationService.getInstance()?.getSocketServerStatus()
            ?: if (httpEnabled) "Starting or waiting for service" else "Disabled"

        summaryView.text = when (accessibilityStatus) {
            AccessibilityStatus.READY -> "Ready for local Termux control"
            AccessibilityStatus.STANDBY -> "Accessibility enabled (waiting for first event to rebind)"
            AccessibilityStatus.REQUIRED -> "Enable Accessibility for full device control"
        }
        applyAccessibilityStatus(accessibilityStatus)
        applyKeyboardStatus(keyboardEnabled, keyboardSelected)
        applyLocalApiStatus(httpEnabled, port, socketStatus)
        bridgeStatusView.text = "com.termux.autotermux"
        applyIcon(bridgeIcon, R.color.text_gray_light)
        bridgeStatusPanel.setBackgroundResource(R.drawable.autotermux_status_row_bg)
        applyArmedStatus(configManager.armed)
        if (!configManager.armed) {
            summaryView.text = "DISARMED - automation paused, audit log running"
            summaryView.setTextColor(color(R.color.autotermux_warning))
        }
    }

    override fun onArmedChanged(armed: Boolean) {
        runOnUiThread { refreshStatus() }
    }

    private enum class AccessibilityStatus { READY, STANDBY, REQUIRED }

    private fun isAccessibilityEnabledInSettings(): Boolean {
        val expected = android.content.ComponentName(
            this,
            AutoTermuxAccessibilityService::class.java,
        ).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun applyAccessibilityStatus(status: AccessibilityStatus) {
        val statusColor: Int
        val statusText: String
        when (status) {
            AccessibilityStatus.READY -> {
                accessibilityStatusPanel.setBackgroundResource(R.drawable.autotermux_permission_ready_bg)
                statusText = "Ready"
                statusColor = R.color.autotermux_primary_light
                applySecondaryButton(openAccessibilityButton, "Settings")
                summaryView.setTextColor(color(R.color.text_gray_light))
            }
            AccessibilityStatus.STANDBY -> {
                // OEMs (vivo/MIUI etc.) periodically unbind accessibility services
                // to save battery even though the toggle remains on in Settings.
                // Treat this as a soft-ready state instead of "Permission required".
                accessibilityStatusPanel.setBackgroundResource(R.drawable.autotermux_permission_ready_bg)
                statusText = "Standby (rebinds on next event)"
                statusColor = R.color.autotermux_primary_light
                applySecondaryButton(openAccessibilityButton, "Settings")
                summaryView.setTextColor(color(R.color.text_gray_light))
            }
            AccessibilityStatus.REQUIRED -> {
                accessibilityStatusPanel.setBackgroundResource(R.drawable.autotermux_permission_required_bg)
                statusText = "Permission required"
                statusColor = R.color.autotermux_warning
                applyWarningButton(openAccessibilityButton, "Grant")
                summaryView.setTextColor(color(R.color.autotermux_warning))
            }
        }
        accessibilityStatusView.text = statusText
        applyIcon(accessibilityIcon, statusColor)
        accessibilityStatusView.setTextColor(color(statusColor))
        accessibilityStatusView.setTypeface(
            null,
            if (status == AccessibilityStatus.REQUIRED) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    private fun applyKeyboardStatus(keyboardEnabled: Boolean, keyboardSelected: Boolean) {
        keyboardStatusPanel.setBackgroundResource(
            if (keyboardSelected) R.drawable.autotermux_permission_ready_bg
            else R.drawable.autotermux_permission_required_bg,
        )
        applyIcon(
            keyboardIcon,
            if (keyboardSelected) R.color.autotermux_primary_light else R.color.autotermux_warning,
        )

        keyboardEnableStatusView.text = if (keyboardEnabled) "Enabled" else "Not enabled"
        keyboardEnableStatusView.setTextColor(
            color(if (keyboardEnabled) R.color.autotermux_primary_light else R.color.autotermux_warning),
        )
        keyboardEnableStatusView.setTypeface(null, if (keyboardEnabled) Typeface.NORMAL else Typeface.BOLD)
        applySecondaryButton(keyboardEnableButton, if (keyboardEnabled) "Settings" else "Enable")
        keyboardEnableButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        keyboardSelectStatusView.text = when {
            keyboardSelected -> "Selected"
            keyboardEnabled -> "Not selected"
            else -> "Enable first"
        }
        keyboardSelectStatusView.setTextColor(
            color(
                when {
                    keyboardSelected -> R.color.autotermux_primary_light
                    keyboardEnabled -> R.color.autotermux_warning
                    else -> R.color.text_gray_light
                },
            ),
        )
        keyboardSelectStatusView.setTypeface(
            null,
            if (keyboardEnabled && !keyboardSelected) Typeface.BOLD else Typeface.NORMAL,
        )

        if (keyboardEnabled) {
            applyWarningButton(keyboardSelectButton, if (keyboardSelected) "Change" else "Select")
            keyboardSelectButton.isEnabled = true
            keyboardSelectButton.alpha = 1.0f
            keyboardSelectButton.setOnClickListener {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        } else {
            applyDisabledButton(keyboardSelectButton, "Select")
            keyboardSelectButton.setOnClickListener(null)
        }
    }

    private fun applyLocalApiStatus(httpEnabled: Boolean, port: Int, socketStatus: String) {
        if (httpEnabled) {
            httpStatusView.text = "127.0.0.1:$port - $socketStatus"
            localApiStatusPanel.setBackgroundResource(R.drawable.autotermux_permission_ready_bg)
            httpStatusView.setTextColor(color(R.color.autotermux_primary_light))
            applyIcon(localApiIcon, R.color.autotermux_primary_light)
            applySecondaryButton(toggleHttpButton, "Stop")
        } else {
            httpStatusView.text = "Off - Termux bridge active"
            localApiStatusPanel.setBackgroundResource(R.drawable.autotermux_status_row_bg)
            httpStatusView.setTextColor(color(R.color.text_white))
            applyIcon(localApiIcon, R.color.text_gray_light)
            applyPrimaryButton(toggleHttpButton, "Start")
        }
    }

    private fun applyArmedStatus(armed: Boolean) {
        if (armed) {
            armedStatusPanel.setBackgroundResource(R.drawable.autotermux_permission_ready_bg)
            armedStatusView.text = "Armed"
            armedStatusView.setTextColor(color(R.color.autotermux_primary_light))
            armedStatusView.setTypeface(null, Typeface.NORMAL)
            applyIcon(armedIcon, R.color.autotermux_primary_light)
            applyWarningButton(armedActionButton, "Disarm")
        } else {
            armedStatusPanel.setBackgroundResource(R.drawable.autotermux_permission_required_bg)
            armedStatusView.text = "Disarmed"
            armedStatusView.setTextColor(color(R.color.autotermux_warning))
            armedStatusView.setTypeface(null, Typeface.BOLD)
            applyIcon(armedIcon, R.color.autotermux_warning)
            applyPrimaryButton(armedActionButton, "Arm")
        }
    }

    private fun refreshAuditLog() {
        val auditLog = AuditLog.getInstance(this)
        val entries = auditLog.snapshot(50)
        auditAdapter.submit(entries)
        activityCountView.text = "${entries.size}/${auditLog.size()}"
        activityEmptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setHttpServerEnabled(enabled: Boolean) {
        configManager.noA11yMode = enabled && AutoTermuxAccessibilityService.getInstance() == null
        configManager.setSocketServerEnabledWithNotification(enabled)
        if (enabled && configManager.noA11yMode) {
            val intent = Intent(this, LocalAutomationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else if (!enabled && configManager.noA11yMode) {
            stopService(Intent(this, LocalAutomationService::class.java))
            configManager.noA11yMode = false
        }
        refreshStatus()
    }

    private fun color(resourceId: Int): Int = ContextCompat.getColor(this, resourceId)

    private fun colorStateList(resourceId: Int): ColorStateList =
        ColorStateList.valueOf(color(resourceId))

    private fun applyIcon(icon: ImageView, colorResourceId: Int) {
        icon.imageTintList = colorStateList(colorResourceId)
    }

    private fun applyPrimaryButton(button: Button, text: String) {
        button.text = text
        button.isEnabled = true
        button.alpha = 1.0f
        button.backgroundTintList = colorStateList(R.color.autotermux_primary)
        button.setTextColor(color(R.color.white))
    }

    private fun applySecondaryButton(button: Button, text: String) {
        button.text = text
        button.isEnabled = true
        button.alpha = 1.0f
        button.backgroundTintList = colorStateList(R.color.background_tertiary)
        button.setTextColor(color(R.color.white))
    }

    private fun applyWarningButton(button: Button, text: String) {
        button.text = text
        button.isEnabled = true
        button.alpha = 1.0f
        button.backgroundTintList = colorStateList(R.color.autotermux_warning_button)
        button.setTextColor(color(R.color.black))
    }

    private fun applyDisabledButton(button: Button, text: String) {
        button.text = text
        button.isEnabled = false
        button.alpha = 0.45f
        button.backgroundTintList = colorStateList(R.color.background_tertiary)
        button.setTextColor(color(R.color.white))
    }
}
