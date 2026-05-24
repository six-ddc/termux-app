package com.termux.autotermux.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.termux.autotermux.events.model.EventType

class ConfigManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "autotermux_config"
        private const val DEVICE_PREFS_NAME = "autotermux_device"
        private const val SECRET_PREFS_NAME = "autotermux_secrets"

        private const val KEY_OVERLAY_VISIBLE = "overlay_visible"
        private const val KEY_OVERLAY_OFFSET = "overlay_offset"
        private const val KEY_AUTO_OFFSET_ENABLED = "auto_offset_enabled"
        private const val KEY_AUTO_OFFSET_CALCULATED = "auto_offset_calculated"
        private const val KEY_SOCKET_SERVER_ENABLED = "socket_server_enabled"
        private const val KEY_SOCKET_SERVER_PORT = "socket_server_port"
        private const val KEY_WEBSOCKET_ENABLED = "websocket_enabled"
        private const val KEY_WEBSOCKET_PORT = "websocket_port"
        private const val KEY_NO_A11Y_MODE = "no_a11y_mode"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MEDIA_PROJECTION_AUTO_ACCEPT_ENABLED =
            "media_projection_auto_accept_enabled"
        private const val KEY_INSTALL_AUTO_ACCEPT_ENABLED = "install_auto_accept_enabled"
        private const val KEY_KEEP_SCREEN_AWAKE_ENABLED = "keep_screen_awake_enabled"
        private const val KEY_KEEP_ALIVE_LAST_RECOVERY_AT_MS = "keep_alive_last_recovery_at_ms"
        private const val KEY_KEEP_ALIVE_LAST_RECOVERY_ATTEMPT_AT_MS =
            "keep_alive_last_recovery_attempt_at_ms"
        private const val KEY_KEEP_ALIVE_CONSECUTIVE_RECOVERY_FAILURES =
            "keep_alive_consecutive_recovery_failures"
        private const val KEY_KEEP_ALIVE_DEGRADED_REASON = "keep_alive_degraded_reason"
        private const val KEY_KEEP_ALIVE_NEXT_RECOVERY_TOKEN = "keep_alive_next_recovery_token"
        private const val KEY_KEEP_ALIVE_ACTIVE_RECOVERY_TOKEN =
            "keep_alive_active_recovery_token"
        private const val KEY_KEEP_ALIVE_RECOVERY_ACTIVITY_IN_FLIGHT =
            "keep_alive_recovery_activity_in_flight"
        private const val KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN =
            "keep_alive_pending_recovery_result_token"
        private const val KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS =
            "keep_alive_pending_recovery_result_success"
        private const val KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON =
            "keep_alive_pending_recovery_result_reason"
        private const val KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS =
            "keep_alive_pending_recovery_result_at_ms"
        private const val KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID =
            "keep_alive_recovery_owner_session_id"
        private const val PREFIX_EVENT_ENABLED = "event_enabled_"

        private const val DEFAULT_OFFSET = 0
        private const val DEFAULT_SOCKET_PORT = 8080
        private const val DEFAULT_WEBSOCKET_PORT = 8081

        @Volatile
        private var INSTANCE: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConfigManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val sharedPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val devicePrefs: SharedPreferences =
        appContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
    private val secretsPrefs: SharedPreferences =
        appContext.getSharedPreferences(SECRET_PREFS_NAME, Context.MODE_PRIVATE)
    private val listeners = mutableSetOf<ConfigChangeListener>()

    val authToken: String
        get() {
            var token = secretsPrefs.getString(KEY_AUTH_TOKEN, null)
            if (token == null) {
                token = java.util.UUID.randomUUID().toString()
                secretsPrefs.edit { putString(KEY_AUTH_TOKEN, token) }
            }
            return token
        }

    val deviceID: String
        get() {
            var id = devicePrefs.getString(KEY_DEVICE_ID, null)
            if (id == null) {
                id = java.util.UUID.randomUUID().toString()
                devicePrefs.edit { putString(KEY_DEVICE_ID, id) }
            }
            return id
        }

    var overlayVisible: Boolean
        get() = sharedPrefs.getBoolean(KEY_OVERLAY_VISIBLE, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_OVERLAY_VISIBLE, value) }
        }

    var overlayOffset: Int
        get() = sharedPrefs.getInt(KEY_OVERLAY_OFFSET, DEFAULT_OFFSET)
        set(value) {
            sharedPrefs.edit { putInt(KEY_OVERLAY_OFFSET, value) }
        }

    var autoOffsetEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_OFFSET_ENABLED, true)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_AUTO_OFFSET_ENABLED, value) }
        }

    var autoOffsetCalculated: Boolean
        get() = sharedPrefs.getBoolean(KEY_AUTO_OFFSET_CALCULATED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_AUTO_OFFSET_CALCULATED, value) }
        }

    var noA11yMode: Boolean
        get() = sharedPrefs.getBoolean(KEY_NO_A11Y_MODE, false)
        set(value) {
            sharedPrefs.edit(commit = true) { putBoolean(KEY_NO_A11Y_MODE, value) }
        }

    var socketServerEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_SOCKET_SERVER_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_SOCKET_SERVER_ENABLED, value) }
        }

    var socketServerPort: Int
        get() = sharedPrefs.getInt(KEY_SOCKET_SERVER_PORT, DEFAULT_SOCKET_PORT)
        set(value) {
            sharedPrefs.edit { putInt(KEY_SOCKET_SERVER_PORT, value) }
        }

    var websocketEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_WEBSOCKET_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_WEBSOCKET_ENABLED, value) }
        }

    var websocketPort: Int
        get() = sharedPrefs.getInt(KEY_WEBSOCKET_PORT, DEFAULT_WEBSOCKET_PORT)
        set(value) {
            sharedPrefs.edit { putInt(KEY_WEBSOCKET_PORT, value) }
        }

    var mediaProjectionAutoAcceptEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_MEDIA_PROJECTION_AUTO_ACCEPT_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_MEDIA_PROJECTION_AUTO_ACCEPT_ENABLED, value) }
        }

    var screenShareAutoAcceptEnabled: Boolean
        get() = mediaProjectionAutoAcceptEnabled
        set(value) {
            mediaProjectionAutoAcceptEnabled = value
        }

    var installAutoAcceptEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_INSTALL_AUTO_ACCEPT_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_INSTALL_AUTO_ACCEPT_ENABLED, value) }
        }

    var keepScreenAwakeEnabled: Boolean
        get() = sharedPrefs.getBoolean(KEY_KEEP_SCREEN_AWAKE_ENABLED, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_KEEP_SCREEN_AWAKE_ENABLED, value) }
        }

    var keepAliveLastRecoveryAtMs: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_LAST_RECOVERY_AT_MS, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_LAST_RECOVERY_AT_MS, value) }
        }

    var keepAliveLastRecoveryAttemptAtMs: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_LAST_RECOVERY_ATTEMPT_AT_MS, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_LAST_RECOVERY_ATTEMPT_AT_MS, value) }
        }

    var keepAliveConsecutiveRecoveryFailures: Int
        get() = sharedPrefs.getInt(KEY_KEEP_ALIVE_CONSECUTIVE_RECOVERY_FAILURES, 0)
        set(value) {
            sharedPrefs.edit { putInt(KEY_KEEP_ALIVE_CONSECUTIVE_RECOVERY_FAILURES, value) }
        }

    var keepAliveDegradedReason: String?
        get() = sharedPrefs.getString(KEY_KEEP_ALIVE_DEGRADED_REASON, null)
        set(value) {
            sharedPrefs.edit {
                if (value == null) remove(KEY_KEEP_ALIVE_DEGRADED_REASON)
                else putString(KEY_KEEP_ALIVE_DEGRADED_REASON, value)
            }
        }

    var keepAliveActiveRecoveryToken: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_ACTIVE_RECOVERY_TOKEN, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_ACTIVE_RECOVERY_TOKEN, value) }
        }

    var keepAliveRecoveryActivityInFlight: Boolean
        get() = sharedPrefs.getBoolean(KEY_KEEP_ALIVE_RECOVERY_ACTIVITY_IN_FLIGHT, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_KEEP_ALIVE_RECOVERY_ACTIVITY_IN_FLIGHT, value) }
        }

    var keepAlivePendingRecoveryResultToken: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN, value) }
        }

    var keepAlivePendingRecoveryResultSuccess: Boolean
        get() = sharedPrefs.getBoolean(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS, false)
        set(value) {
            sharedPrefs.edit { putBoolean(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS, value) }
        }

    var keepAlivePendingRecoveryResultReason: String?
        get() = sharedPrefs.getString(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON, null)
        set(value) {
            sharedPrefs.edit {
                if (value == null) remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON)
                else putString(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON, value)
            }
        }

    var keepAlivePendingRecoveryResultAtMs: Long
        get() = sharedPrefs.getLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS, 0L)
        set(value) {
            sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS, value) }
        }

    var keepAliveRecoveryOwnerSessionId: String?
        get() = sharedPrefs.getString(KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID, null)
        set(value) {
            sharedPrefs.edit {
                if (value == null) remove(KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID)
                else putString(KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID, value)
            }
        }

    fun addListener(listener: ConfigChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ConfigChangeListener) {
        listeners.remove(listener)
    }

    fun isEventEnabled(type: EventType): Boolean =
        sharedPrefs.getBoolean(PREFIX_EVENT_ENABLED + type.name, type.defaultEnabled)

    fun setEventEnabled(type: EventType, enabled: Boolean) {
        sharedPrefs.edit { putBoolean(PREFIX_EVENT_ENABLED + type.name, enabled) }
    }

    fun setOverlayVisibleWithNotification(visible: Boolean) {
        overlayVisible = visible
        listeners.forEach { it.onOverlayVisibilityChanged(visible) }
    }

    fun setOverlayOffsetWithNotification(offset: Int) {
        overlayOffset = offset
        listeners.forEach { it.onOverlayOffsetChanged(offset) }
    }

    fun setSocketServerEnabledWithNotification(enabled: Boolean) {
        socketServerEnabled = enabled
        listeners.forEach { it.onSocketServerEnabledChanged(enabled) }
    }

    fun setSocketServerPortWithNotification(port: Int) {
        socketServerPort = port
        listeners.forEach { it.onSocketServerPortChanged(port) }
    }

    fun setWebSocketEnabledWithNotification(enabled: Boolean) {
        websocketEnabled = enabled
        listeners.forEach { it.onWebSocketEnabledChanged(enabled) }
    }

    fun setWebSocketPortWithNotification(port: Int) {
        websocketPort = port
        listeners.forEach { it.onWebSocketPortChanged(port) }
    }

    fun setKeepScreenAwakeEnabledWithNotification(enabled: Boolean) {
        if (keepScreenAwakeEnabled == enabled) return
        keepScreenAwakeEnabled = enabled
        listeners.forEach { it.onKeepScreenAwakeEnabledChanged(enabled) }
    }

    fun nextKeepAliveRecoveryToken(): Long {
        val next = sharedPrefs.getLong(KEY_KEEP_ALIVE_NEXT_RECOVERY_TOKEN, 0L) + 1L
        sharedPrefs.edit { putLong(KEY_KEEP_ALIVE_NEXT_RECOVERY_TOKEN, next) }
        return next
    }

    fun saveKeepAlivePendingRecoveryResult(
        token: Long,
        success: Boolean,
        reason: String?,
        completedAtMs: Long,
    ) {
        sharedPrefs.edit {
            putLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN, token)
            putBoolean(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS, success)
            if (reason == null) remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON)
            else putString(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON, reason)
            putLong(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS, completedAtMs)
        }
    }

    fun clearKeepAlivePendingRecoveryResult() {
        sharedPrefs.edit {
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS)
        }
    }

    fun clearKeepAliveRecoveryHandoffState() {
        sharedPrefs.edit {
            remove(KEY_KEEP_ALIVE_ACTIVE_RECOVERY_TOKEN)
            remove(KEY_KEEP_ALIVE_RECOVERY_ACTIVITY_IN_FLIGHT)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS)
            remove(KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID)
        }
    }

    fun clearKeepAliveRuntimeState() {
        sharedPrefs.edit {
            remove(KEY_KEEP_ALIVE_LAST_RECOVERY_AT_MS)
            remove(KEY_KEEP_ALIVE_LAST_RECOVERY_ATTEMPT_AT_MS)
            remove(KEY_KEEP_ALIVE_CONSECUTIVE_RECOVERY_FAILURES)
            remove(KEY_KEEP_ALIVE_DEGRADED_REASON)
            remove(KEY_KEEP_ALIVE_ACTIVE_RECOVERY_TOKEN)
            remove(KEY_KEEP_ALIVE_RECOVERY_ACTIVITY_IN_FLIGHT)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_TOKEN)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_SUCCESS)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_REASON)
            remove(KEY_KEEP_ALIVE_PENDING_RECOVERY_RESULT_AT_MS)
            remove(KEY_KEEP_ALIVE_RECOVERY_OWNER_SESSION_ID)
        }
    }

    fun getCurrentConfiguration(): Configuration =
        Configuration(
            overlayVisible = overlayVisible,
            overlayOffset = overlayOffset,
            autoOffsetEnabled = autoOffsetEnabled,
            autoOffsetCalculated = autoOffsetCalculated,
            socketServerEnabled = socketServerEnabled,
            socketServerPort = socketServerPort,
            websocketEnabled = websocketEnabled,
            websocketPort = websocketPort,
            authToken = authToken,
        )

    data class Configuration(
        val overlayVisible: Boolean,
        val overlayOffset: Int,
        val autoOffsetEnabled: Boolean,
        val autoOffsetCalculated: Boolean,
        val socketServerEnabled: Boolean,
        val socketServerPort: Int,
        val websocketEnabled: Boolean,
        val websocketPort: Int,
        val authToken: String,
    )

    interface ConfigChangeListener {
        fun onOverlayVisibilityChanged(visible: Boolean)
        fun onOverlayOffsetChanged(offset: Int)
        fun onSocketServerEnabledChanged(enabled: Boolean)
        fun onSocketServerPortChanged(port: Int)
        fun onWebSocketEnabledChanged(enabled: Boolean) {}
        fun onWebSocketPortChanged(port: Int) {}
        fun onKeepScreenAwakeEnabledChanged(enabled: Boolean) {}
        fun onProductionModeChanged(enabled: Boolean) {}
    }
}
