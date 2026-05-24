package com.termux.autotermux.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import com.termux.autotermux.BuildConfig
import com.termux.autotermux.api.ApiHandler
import com.termux.autotermux.api.ApiResponse
import com.termux.autotermux.config.ConfigManager
import com.termux.autotermux.core.StateRepository
import com.termux.autotermux.events.model.EventType
import com.termux.autotermux.input.AutoTermuxKeyboardIME
import com.termux.autotermux.keepalive.KeepAliveController
import com.termux.autotermux.keepalive.KeepAliveStartupException
import com.termux.autotermux.triggers.TriggerApi
import org.json.JSONObject

internal fun handleKeepScreenAwakeInsert(
    providerContext: Context,
    enabled: Boolean,
): ApiResponse {
    return try {
        KeepAliveController.setEnabled(providerContext, enabled)
        ApiResponse.Success("Keep screen awake set to $enabled")
    } catch (e: KeepAliveStartupException) {
        ApiResponse.Error(e.reason)
    }
}

internal fun autoAcceptStatusJson(configManager: ConfigManager): JSONObject {
    return JSONObject().apply {
        put("media_projection_auto_accept", configManager.mediaProjectionAutoAcceptEnabled)
        put("screen_share_auto_accept", configManager.screenShareAutoAcceptEnabled)
        put("install_auto_accept", configManager.installAutoAcceptEnabled)
    }
}

internal fun handleAutoAcceptInsert(
    configManager: ConfigManager,
    target: String,
    enabled: Boolean,
): ApiResponse {
    when (target.trim().lowercase().replace("_", "-")) {
        "media", "media-projection", "screen", "screen-share", "screenshot" ->
            configManager.screenShareAutoAcceptEnabled = enabled
        "install", "apk", "package-install" ->
            configManager.installAutoAcceptEnabled = enabled
        "all" -> {
            configManager.screenShareAutoAcceptEnabled = enabled
            configManager.installAutoAcceptEnabled = enabled
        }
        else -> return ApiResponse.Error("Unsupported auto-accept target: $target")
    }
    return ApiResponse.RawObject(autoAcceptStatusJson(configManager).apply {
        put("target", target)
        put("enabled", enabled)
    })
}

internal fun eventStatusJson(configManager: ConfigManager): JSONObject {
    return JSONObject().apply {
        val events = org.json.JSONArray()
        EventType.entries.forEach { type ->
            events.put(JSONObject().apply {
                put("name", type.name)
                put("enabled", configManager.isEventEnabled(type))
                put("default_enabled", type.defaultEnabled)
            })
        }
        put("events", events)
    }
}

internal fun handleEventSetInsert(
    configManager: ConfigManager,
    eventName: String,
    enabled: Boolean,
): ApiResponse {
    val type = resolveEventType(eventName)
        ?: return ApiResponse.Error("Unsupported event type: $eventName")
    if (type == EventType.PONG || type == EventType.UNKNOWN) {
        return ApiResponse.Error("Event type cannot be configured: ${type.name}")
    }
    configManager.setEventEnabled(type, enabled)
    return ApiResponse.RawObject(JSONObject().apply {
        put("name", type.name)
        put("enabled", configManager.isEventEnabled(type))
        put("default_enabled", type.defaultEnabled)
    })
}

private fun resolveEventType(eventName: String): EventType? {
    val normalized = eventName.trim().uppercase().replace("-", "_")
    return EventType.entries.firstOrNull { it.name == normalized }
}

internal fun handleNoA11yModeInsert(
    providerContext: Context,
    configManager: ConfigManager,
    enabled: Boolean,
    accessibilityServiceAvailable: Boolean = AutoTermuxAccessibilityService.getInstance() != null,
    localAutomationServiceRunning: Boolean = LocalAutomationService.getInstance() != null,
    startLocalAutomationService: (Context) -> Unit = { context ->
        context.startForegroundService(Intent(context, LocalAutomationService::class.java))
    },
    stopLocalAutomationService: (Context) -> Unit = { context ->
        context.stopService(Intent(context, LocalAutomationService::class.java))
    },
): ApiResponse {
    if (enabled) {
        if (accessibilityServiceAvailable) {
            return ApiResponse.Error("Disable AccessibilityService first")
        }
        configManager.noA11yMode = true
        if (!localAutomationServiceRunning) {
            try {
                startLocalAutomationService(providerContext)
                Log.i("AutoTermuxContentProvider", "No-a11y mode enabled, LocalAutomationService starting")
            } catch (e: Exception) {
                Log.w(
                    "AutoTermuxContentProvider",
                    "startForegroundService failed (service may need manual start): ${e.message}",
                )
            }
        }
    } else {
        configManager.noA11yMode = false
        try {
            stopLocalAutomationService(providerContext)
        } catch (e: Exception) {
            Log.w("AutoTermuxContentProvider", "stopService failed: ${e.message}")
        }
    }
    return ApiResponse.Success("no_a11y_mode=$enabled")
}

internal fun ensureLocalServerHostAvailableForEnable(
    providerContext: Context?,
    configManager: ConfigManager,
    accessibilityServiceAvailable: Boolean = AutoTermuxAccessibilityService.getInstance() != null,
    localAutomationServiceRunning: Boolean = LocalAutomationService.getInstance() != null,
    startLocalAutomationService: (Context) -> Unit = { context ->
        context.startForegroundService(Intent(context, LocalAutomationService::class.java))
    },
): ApiResponse? {
    if (accessibilityServiceAvailable) return null
    if (!configManager.noA11yMode) {
        return ApiResponse.Error("AccessibilityService or no-a11y mode required to enable local servers")
    }
    if (localAutomationServiceRunning) return null
    val context = providerContext ?: return ApiResponse.Error("context unavailable")
    return try {
        startLocalAutomationService(context)
        null
    } catch (e: Exception) {
        ApiResponse.Error(e.message ?: "LocalAutomationService start failed")
    }
}

internal fun handleSocketServerToggleInsert(
    configManager: ConfigManager,
    values: ContentValues?,
    ensureLocalServerHost: () -> ApiResponse?,
): ApiResponse {
    val port = values?.getAsInteger("port") ?: configManager.socketServerPort
    val enabled = values?.getAsBoolean("enabled") ?: true
    val hasPort = values?.containsKey("port") == true
    val wasEnabled = configManager.socketServerEnabled
    val currentPort = configManager.socketServerPort

    if (enabled) {
        ensureLocalServerHost()?.let { return it }
        if (hasPort && port != currentPort) {
            if (wasEnabled) configManager.setSocketServerPortWithNotification(port)
            else configManager.socketServerPort = port
        }
        if (!wasEnabled) configManager.setSocketServerEnabledWithNotification(true)
    } else {
        if (wasEnabled) configManager.setSocketServerEnabledWithNotification(false)
        if (hasPort && port != currentPort) configManager.socketServerPort = port
    }
    return ApiResponse.Success("HTTP server ${if (enabled) "enabled" else "disabled"} on port $port")
}

internal fun handleWebSocketServerToggleInsert(
    configManager: ConfigManager,
    values: ContentValues?,
    ensureLocalServerHost: () -> ApiResponse?,
): ApiResponse {
    val port = values?.getAsInteger("port") ?: configManager.websocketPort
    val enabled = values?.getAsBoolean("enabled") ?: true
    val hasPort = values?.containsKey("port") == true
    val wasEnabled = configManager.websocketEnabled
    val currentPort = configManager.websocketPort

    if (enabled) {
        ensureLocalServerHost()?.let { return it }
        if (hasPort && port != currentPort) {
            if (wasEnabled) configManager.setWebSocketPortWithNotification(port)
            else configManager.websocketPort = port
        }
        if (!wasEnabled) configManager.setWebSocketEnabledWithNotification(true)
    } else {
        if (wasEnabled) configManager.setWebSocketEnabledWithNotification(false)
        if (hasPort && port != currentPort) configManager.websocketPort = port
    }
    return ApiResponse.Success("WebSocket server ${if (enabled) "enabled" else "disabled"} on port $port")
}

class AutoTermuxContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "AutoTermuxContentProvider"
        private val AUTHORITY: String = BuildConfig.APPLICATION_ID

        private const val A11Y_TREE = 1
        private const val PHONE_STATE = 2
        private const val PING = 3
        private const val KEYBOARD_ACTIONS = 4
        private const val STATE = 5
        private const val OVERLAY_OFFSET = 6
        private const val PACKAGES = 7
        private const val A11Y_TREE_FULL = 8
        private const val VERSION = 9
        private const val STATE_FULL = 10
        private const val SOCKET_PORT = 11
        private const val OVERLAY_VISIBLE = 12
        private const val TOGGLE_WEBSOCKET_SERVER = 13
        private const val AUTH_TOKEN = 14
        private const val TOGGLE_SOCKET_SERVER = 15
        private const val TOGGLE_SCREEN_KEEP_AWAKE = 16
        private const val SCREEN_KEEP_AWAKE_STATUS = 17
        private const val SET_NO_A11Y_MODE = 18
        private const val CLIPBOARD_GET = 19
        private const val CLIPBOARD_SET = 20
        private const val ACTION_1 = 21
        private const val ACTION_2 = 22
        private const val ACTION_3 = 23
        private const val MODE_STATUS = 24
        private const val AUTO_ACCEPT_STATUS = 25
        private const val SET_AUTO_ACCEPT = 26
        private const val EVENT_STATUS = 27
        private const val SET_EVENT = 28
        private const val WEBSOCKET_STATUS = 29
        private const val TRIGGERS_CATALOG = 30
        private const val TRIGGERS_STATUS = 31
        private const val TRIGGERS_RULES = 32
        private const val TRIGGERS_RULE = 33
        private const val TRIGGERS_RUNS = 34
        private const val TRIGGERS_RULE_SAVE = 35
        private const val TRIGGERS_RULE_DELETE = 36
        private const val TRIGGERS_RULE_SET_ENABLED = 37
        private const val TRIGGERS_RULE_TEST = 38
        private const val TRIGGERS_RUN_DELETE = 39
        private const val TRIGGERS_RUN_CLEAR = 40

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "a11y_tree", A11Y_TREE)
            addURI(AUTHORITY, "a11y_tree_full", A11Y_TREE_FULL)
            addURI(AUTHORITY, "phone_state", PHONE_STATE)
            addURI(AUTHORITY, "ping", PING)
            addURI(AUTHORITY, "keyboard/*", KEYBOARD_ACTIONS)
            addURI(AUTHORITY, "state", STATE)
            addURI(AUTHORITY, "state_full", STATE_FULL)
            addURI(AUTHORITY, "overlay_offset", OVERLAY_OFFSET)
            addURI(AUTHORITY, "packages", PACKAGES)
            addURI(AUTHORITY, "version", VERSION)
            addURI(AUTHORITY, "socket_port", SOCKET_PORT)
            addURI(AUTHORITY, "overlay_visible", OVERLAY_VISIBLE)
            addURI(AUTHORITY, "toggle_websocket_server", TOGGLE_WEBSOCKET_SERVER)
            addURI(AUTHORITY, "auth_token", AUTH_TOKEN)
            addURI(AUTHORITY, "toggle_socket_server", TOGGLE_SOCKET_SERVER)
            addURI(AUTHORITY, "toggle_screen_keep_awake", TOGGLE_SCREEN_KEEP_AWAKE)
            addURI(AUTHORITY, "screen_keep_awake_status", SCREEN_KEEP_AWAKE_STATUS)
            addURI(AUTHORITY, "set_no_a11y_mode", SET_NO_A11Y_MODE)
            addURI(AUTHORITY, "clipboard/get", CLIPBOARD_GET)
            addURI(AUTHORITY, "clipboard/set", CLIPBOARD_SET)
            addURI(AUTHORITY, "mode_status", MODE_STATUS)
            addURI(AUTHORITY, "auto_accept_status", AUTO_ACCEPT_STATUS)
            addURI(AUTHORITY, "set_auto_accept", SET_AUTO_ACCEPT)
            addURI(AUTHORITY, "event_status", EVENT_STATUS)
            addURI(AUTHORITY, "set_event", SET_EVENT)
            addURI(AUTHORITY, "websocket_status", WEBSOCKET_STATUS)
            addURI(AUTHORITY, "triggers/catalog", TRIGGERS_CATALOG)
            addURI(AUTHORITY, "triggers/status", TRIGGERS_STATUS)
            addURI(AUTHORITY, "triggers/rules", TRIGGERS_RULES)
            addURI(AUTHORITY, "triggers/rules/save", TRIGGERS_RULE_SAVE)
            addURI(AUTHORITY, "triggers/rules/delete", TRIGGERS_RULE_DELETE)
            addURI(AUTHORITY, "triggers/rules/set_enabled", TRIGGERS_RULE_SET_ENABLED)
            addURI(AUTHORITY, "triggers/rules/test", TRIGGERS_RULE_TEST)
            addURI(AUTHORITY, "triggers/rules/*", TRIGGERS_RULE)
            addURI(AUTHORITY, "triggers/runs", TRIGGERS_RUNS)
            addURI(AUTHORITY, "triggers/runs/delete", TRIGGERS_RUN_DELETE)
            addURI(AUTHORITY, "triggers/runs/clear", TRIGGERS_RUN_CLEAR)
            addURI(AUTHORITY, "action/*", ACTION_1)
            addURI(AUTHORITY, "action/*/*", ACTION_2)
            addURI(AUTHORITY, "action/*/*/*", ACTION_3)
        }
    }

    private lateinit var configManager: ConfigManager
    private val apiHandlerCache = ServiceInstanceCache<AutoTermuxAccessibilityService, ApiHandler>()

    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        configManager = ConfigManager.getInstance(appContext)
        return true
    }

    private fun getHandler(): ApiHandler? {
        val service = AutoTermuxAccessibilityService.getInstance()
        return service?.let {
            apiHandlerCache.get(it) { svc: AutoTermuxAccessibilityService ->
                ApiHandler(
                    StateRepository(svc),
                    getKeyboardIME = { AutoTermuxKeyboardIME.getInstance() },
                    getPackageManager = { svc.packageManager },
                    appVersionProvider = { resolveVersionName(svc) },
                    context = svc,
                )
            }
        } ?: if (configManager.noA11yMode) createHeadlessHandler() else null
    }

    private fun getHeadlessCapableHandler(): ApiHandler? {
        return AutoTermuxAccessibilityService.getInstance()?.let {
            ApiHandler(
                StateRepository(it),
                getKeyboardIME = { AutoTermuxKeyboardIME.getInstance() },
                getPackageManager = { it.packageManager },
                appVersionProvider = { resolveVersionName(it) },
                context = it,
            )
        } ?: createHeadlessHandler()
    }

    private fun createHeadlessHandler(): ApiHandler? {
        val appContext = context?.applicationContext ?: return null
        return ApiHandler(
            StateRepository(null),
            getKeyboardIME = { AutoTermuxKeyboardIME.getInstance() },
            getPackageManager = { appContext.packageManager },
            appVersionProvider = { resolveVersionName(appContext) },
            context = appContext,
        )
    }

    private fun resolveVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!isCallerAllowed()) {
            return null
        }
        val match = uriMatcher.match(uri)
        val response = when (match) {
            AUTH_TOKEN -> ApiResponse.Text(configManager.authToken)
            SOCKET_PORT -> ApiResponse.RawObject(JSONObject().apply {
                put("enabled", configManager.socketServerEnabled)
                put("port", configManager.socketServerPort)
            })
            WEBSOCKET_STATUS -> ApiResponse.RawObject(JSONObject().apply {
                put("enabled", configManager.websocketEnabled)
                put("port", configManager.websocketPort)
            })
            OVERLAY_VISIBLE -> ApiResponse.RawObject(JSONObject().apply {
                put("visible", configManager.overlayVisible)
            })
            SCREEN_KEEP_AWAKE_STATUS ->
                ApiResponse.RawObject(KeepAliveController.getStatusJson(requireProviderContext()))
            MODE_STATUS -> ApiResponse.RawObject(JSONObject().apply {
                put("no_a11y_mode", configManager.noA11yMode)
                put("accessibility_service", AutoTermuxAccessibilityService.getInstance() != null)
                put("local_automation_service", LocalAutomationService.getInstance() != null)
                put("http_server_enabled", configManager.socketServerEnabled)
                put("http_server_port", configManager.socketServerPort)
                put("websocket_enabled", configManager.websocketEnabled)
                put("websocket_port", configManager.websocketPort)
                put("auto_accept", autoAcceptStatusJson(configManager))
            })
            AUTO_ACCEPT_STATUS -> ApiResponse.RawObject(autoAcceptStatusJson(configManager))
            EVENT_STATUS -> ApiResponse.RawObject(eventStatusJson(configManager))
            TRIGGERS_CATALOG -> ApiResponse.RawObject(TriggerApi(requireProviderContext()).catalog())
            TRIGGERS_STATUS -> ApiResponse.RawObject(TriggerApi(requireProviderContext()).status())
            TRIGGERS_RULES -> ApiResponse.RawArray(TriggerApi(requireProviderContext()).listRules())
            TRIGGERS_RULE -> {
                val ruleId = uri.lastPathSegment.orEmpty()
                when (val result = TriggerApi(requireProviderContext()).getRule(ruleId)) {
                    is com.termux.autotermux.triggers.TriggerApiResult.Success ->
                        ApiResponse.RawObject(result.value)
                    is com.termux.autotermux.triggers.TriggerApiResult.Error ->
                        ApiResponse.Error(result.message)
                }
            }
            TRIGGERS_RUNS -> ApiResponse.RawArray(
                TriggerApi(requireProviderContext()).listRuns(uri.getQueryParameter("limit")?.toIntOrNull() ?: 50),
            )
            CLIPBOARD_GET -> getHeadlessCapableHandler()?.getClipboard()
                ?: ApiResponse.Error("Provider context unavailable")
            A11Y_TREE, A11Y_TREE_FULL, PHONE_STATE, PING, STATE, STATE_FULL, PACKAGES, VERSION -> {
                val handler = getHandler()
                if (handler == null) {
                    ApiResponse.Error("AccessibilityService not available")
                } else {
                    when (match) {
                        A11Y_TREE -> handler.getTree()
                        A11Y_TREE_FULL -> handler.getTreeFull(parseFilter(uri))
                        PHONE_STATE -> handler.getPhoneState()
                        PING -> handler.ping()
                        STATE -> handler.getState()
                        STATE_FULL -> handler.getStateFull(parseFilter(uri))
                        PACKAGES -> handler.getPackages()
                        VERSION -> handler.getVersion()
                        else -> ApiResponse.Error("Unknown endpoint: ${uri.path}")
                    }
                }
            }
            else -> ApiResponse.Error("Unknown endpoint: ${uri.path}")
        }
        return responseToCursor(response)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (!isCallerAllowed()) {
            return responseToResultUri(ApiResponse.Error("Access denied"))
        }
        val match = uriMatcher.match(uri)
        val appContext = context?.applicationContext
            ?: return responseToResultUri(ApiResponse.Error("Provider context unavailable"))
        val response = when (match) {
            SET_NO_A11Y_MODE -> handleNoA11yModeInsert(
                providerContext = appContext,
                configManager = configManager,
                enabled = values?.getAsBoolean("enabled") ?: true,
            )
            TOGGLE_SOCKET_SERVER -> handleSocketServerToggleInsert(
                configManager = configManager,
                values = values,
                ensureLocalServerHost = {
                    ensureLocalServerHostAvailableForEnable(appContext, configManager)
                },
            )
            TOGGLE_WEBSOCKET_SERVER -> handleWebSocketServerToggleInsert(
                configManager = configManager,
                values = values,
                ensureLocalServerHost = {
                    ensureLocalServerHostAvailableForEnable(appContext, configManager)
                },
            )
            TOGGLE_SCREEN_KEEP_AWAKE -> handleKeepScreenAwakeInsert(
                providerContext = appContext,
                enabled = values?.getAsBoolean("enabled") ?: true,
            )
            SET_AUTO_ACCEPT -> handleAutoAcceptInsert(
                configManager = configManager,
                target = values?.getAsString("target") ?: "all",
                enabled = values?.getAsBoolean("enabled") ?: true,
            )
            SET_EVENT -> handleEventSetInsert(
                configManager = configManager,
                eventName = values?.getAsString("event") ?: "",
                enabled = values?.getAsBoolean("enabled") ?: true,
            )
            TRIGGERS_RULE_SAVE -> {
                val ruleJson = values?.getAsString("rule_json")
                    ?: values?.getAsString("ruleJson")
                    ?: values?.getAsString("rule_json_base64")?.let { decodeUtf8Base64(it) }
                    ?: ""
                TriggerApi(appContext).dispatch("triggers/rules/save", JSONObject().put("rule_json", ruleJson))
            }
            TRIGGERS_RULE_DELETE -> TriggerApi(appContext).dispatch(
                "triggers/rules/delete",
                JSONObject().put("ruleId", values?.getAsString("rule_id") ?: values?.getAsString("ruleId") ?: ""),
            )
            TRIGGERS_RULE_SET_ENABLED -> TriggerApi(appContext).dispatch(
                "triggers/rules/setEnabled",
                JSONObject()
                    .put("ruleId", values?.getAsString("rule_id") ?: values?.getAsString("ruleId") ?: "")
                    .put("enabled", values?.getAsBoolean("enabled") ?: true),
            )
            TRIGGERS_RULE_TEST -> TriggerApi(appContext).dispatch(
                "triggers/rules/test",
                JSONObject().put("ruleId", values?.getAsString("rule_id") ?: values?.getAsString("ruleId") ?: ""),
            )
            TRIGGERS_RUN_DELETE -> TriggerApi(appContext).dispatch(
                "triggers/runs/delete",
                JSONObject().put("runId", values?.getAsString("run_id") ?: values?.getAsString("runId") ?: ""),
            )
            TRIGGERS_RUN_CLEAR -> TriggerApi(appContext).dispatch("triggers/runs/clear", JSONObject())
            ACTION_1, ACTION_2, ACTION_3 -> handleActionInsert(uri, values)
            else -> handleHandlerInsert(match, uri, values)
        }
        return responseToResultUri(response)
    }

    private fun handleActionInsert(uri: Uri, values: ContentValues?): ApiResponse {
        val handler = getHeadlessCapableHandler()
            ?: return ApiResponse.Error("Provider context unavailable")
        val action = uri.pathSegments.drop(1).joinToString("/")
        if (action.isBlank()) {
            return ApiResponse.Error("Missing action path")
        }
        return ActionDispatcher(handler).dispatch(
            action = action,
            params = contentValuesToJson(values ?: ContentValues()),
            origin = ActionDispatcher.Origin.PROVIDER,
        )
    }

    private fun handleHandlerInsert(match: Int, uri: Uri, values: ContentValues?): ApiResponse {
        val vals = values ?: ContentValues()
        val handler = getHandler()
        if (handler == null) {
            return ApiResponse.Error("AccessibilityService not available")
        }
        return when (match) {
            KEYBOARD_ACTIONS -> when (uri.lastPathSegment) {
                "input" -> handler.keyboardInput(vals.getAsString("base64_text") ?: "", vals.getAsBoolean("clear") ?: true)
                "clear" -> handler.keyboardClear()
                "key" -> handler.keyboardKey(vals.getAsInteger("key_code") ?: 0)
                else -> ApiResponse.Error("Unsupported keyboard action")
            }
            CLIPBOARD_SET -> {
                val text = vals.getAsString("text")
                    ?: vals.getAsString("text_base64")?.let { decodeUtf8Base64(it) }
                    ?: return ApiResponse.Error("Missing required value: text")
                handler.setClipboard(text)
            }
            OVERLAY_OFFSET -> handler.setOverlayOffset(vals.getAsInteger("offset") ?: 0)
            SOCKET_PORT -> handler.setSocketPort(vals.getAsInteger("port") ?: 0)
            OVERLAY_VISIBLE -> handler.setOverlayVisible(vals.getAsBoolean("visible") ?: false)
            else -> ApiResponse.Error("Unsupported insert endpoint")
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String = "application/json"

    private fun responseToCursor(response: ApiResponse): Cursor {
        val cursor = MatrixCursor(arrayOf("result"))
        cursor.addRow(arrayOf(response.toJson()))
        return cursor
    }

    private fun responseToResultUri(response: ApiResponse): Uri {
        val status = if (response is ApiResponse.Error) "error" else "success"
        return "content://$AUTHORITY/result"
            .toUri()
            .buildUpon()
            .appendQueryParameter("status", status)
            .appendQueryParameter("message", response.toJson())
            .build()
    }

    private fun parseFilter(uri: Uri): Boolean {
        val raw = uri.getQueryParameter("filter") ?: return true
        return raw.equals("true", ignoreCase = true) || raw == "1"
    }

    private fun decodeUtf8Base64(base64: String): String? {
        return try {
            String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun contentValuesToJson(values: ContentValues): JSONObject {
        return JSONObject().apply {
            for (key in values.keySet()) {
                put(key, normalizeContentValue(values.get(key)))
            }
        }
    }

    private fun normalizeContentValue(value: Any?): Any? {
        if (value !is String) return value
        val trimmed = value.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return value
        return try {
            org.json.JSONTokener(trimmed).nextValue()
        } catch (_: Exception) {
            value
        }
    }

    private fun isCallerAllowed(): Boolean {
        val appContext = context?.applicationContext ?: return false
        return ContentProviderAccessPolicy.isUidAllowed(
            appContext,
            Binder.getCallingUid(),
            appContext.applicationInfo.uid,
        )
    }

    private fun requireProviderContext(): Context =
        context?.applicationContext ?: error("Provider context unavailable")
}
