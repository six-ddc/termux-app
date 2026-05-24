package com.termux.autotermux.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import com.termux.autotermux.api.ApiHandler
import com.termux.autotermux.api.ApiResponse
import com.termux.autotermux.config.ConfigManager
import com.termux.autotermux.core.StateRepository
import com.termux.autotermux.input.AutoTermuxKeyboardIME
import com.termux.autotermux.keepalive.KeepAliveController
import org.json.JSONObject

class TermuxAutomationBridgeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TermuxBridgeReceiver"
        private const val ACTION_PREFIX = "com.termux.autotermux.bridge"
        const val ACTION_AUTH_TOKEN = "$ACTION_PREFIX.AUTH_TOKEN"
        const val ACTION_SET_NO_A11Y_MODE = "$ACTION_PREFIX.SET_NO_A11Y_MODE"
        const val ACTION_TOGGLE_SOCKET_SERVER = "$ACTION_PREFIX.TOGGLE_SOCKET_SERVER"
        const val ACTION_SOCKET_STATUS = "$ACTION_PREFIX.SOCKET_STATUS"
        const val ACTION_TOGGLE_WEBSOCKET_SERVER = "$ACTION_PREFIX.TOGGLE_WEBSOCKET_SERVER"
        const val ACTION_WEBSOCKET_STATUS = "$ACTION_PREFIX.WEBSOCKET_STATUS"
        const val ACTION_MODE_STATUS = "$ACTION_PREFIX.MODE_STATUS"
        const val ACTION_AUTO_ACCEPT_STATUS = "$ACTION_PREFIX.AUTO_ACCEPT_STATUS"
        const val ACTION_SET_AUTO_ACCEPT = "$ACTION_PREFIX.SET_AUTO_ACCEPT"
        const val ACTION_EVENT_STATUS = "$ACTION_PREFIX.EVENT_STATUS"
        const val ACTION_SET_EVENT = "$ACTION_PREFIX.SET_EVENT"
        const val ACTION_TOGGLE_SCREEN_KEEP_AWAKE = "$ACTION_PREFIX.TOGGLE_SCREEN_KEEP_AWAKE"
        const val ACTION_SCREEN_KEEP_AWAKE_STATUS = "$ACTION_PREFIX.SCREEN_KEEP_AWAKE_STATUS"
        const val ACTION_CALL = "$ACTION_PREFIX.CALL"

        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_TARGET = "target"
        const val EXTRA_EVENT = "event"
        const val EXTRA_METHOD = "method"
        const val EXTRA_PARAMS_JSON = "params_json"
        const val EXTRA_PORT = "port"
        const val EXTRA_RESULT_JSON = "result_json"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        if (intent.action == ACTION_CALL) {
            val pendingResult = goAsync()
            Thread({
                val response = try {
                    handleCall(appContext, intent)
                } catch (e: Exception) {
                    ApiResponse.Error(e.message ?: "Bridge command failed")
                }
                finish(pendingResult, response)
            }, "TermuxBridgeCall").start()
            return
        }

        val response = try {
            when (intent.action) {
                ACTION_AUTH_TOKEN -> ApiResponse.Text(configManager.authToken)

                ACTION_SOCKET_STATUS -> ApiResponse.RawObject(JSONObject().apply {
                    put("enabled", configManager.socketServerEnabled)
                    put("port", configManager.socketServerPort)
                })

                ACTION_WEBSOCKET_STATUS -> ApiResponse.RawObject(JSONObject().apply {
                    put("enabled", configManager.websocketEnabled)
                    put("port", configManager.websocketPort)
                })

                ACTION_MODE_STATUS -> ApiResponse.RawObject(JSONObject().apply {
                    put("no_a11y_mode", configManager.noA11yMode)
                    put("accessibility_service", AutoTermuxAccessibilityService.getInstance() != null)
                    put("local_automation_service", LocalAutomationService.getInstance() != null)
                    put("http_server_enabled", configManager.socketServerEnabled)
                    put("http_server_port", configManager.socketServerPort)
                    put("websocket_enabled", configManager.websocketEnabled)
                    put("websocket_port", configManager.websocketPort)
                    put("auto_accept", autoAcceptStatusJson(configManager))
                })

                ACTION_AUTO_ACCEPT_STATUS -> ApiResponse.RawObject(autoAcceptStatusJson(configManager))

                ACTION_EVENT_STATUS -> ApiResponse.RawObject(eventStatusJson(configManager))

                ACTION_SCREEN_KEEP_AWAKE_STATUS ->
                    ApiResponse.RawObject(KeepAliveController.getStatusJson(appContext))

                ACTION_SET_NO_A11Y_MODE -> handleNoA11yModeInsert(
                    providerContext = appContext,
                    configManager = configManager,
                    enabled = intent.getBooleanExtra(EXTRA_ENABLED, true),
                )

                ACTION_SET_AUTO_ACCEPT -> handleAutoAcceptInsert(
                    configManager = configManager,
                    target = intent.getStringExtra(EXTRA_TARGET) ?: "all",
                    enabled = intent.getBooleanExtra(EXTRA_ENABLED, true),
                )

                ACTION_SET_EVENT -> handleEventSetInsert(
                    configManager = configManager,
                    eventName = intent.getStringExtra(EXTRA_EVENT) ?: "",
                    enabled = intent.getBooleanExtra(EXTRA_ENABLED, true),
                )

                ACTION_TOGGLE_SCREEN_KEEP_AWAKE -> handleKeepScreenAwakeInsert(
                    providerContext = appContext,
                    enabled = intent.getBooleanExtra(EXTRA_ENABLED, true),
                )

                ACTION_TOGGLE_SOCKET_SERVER -> handleSocketServerToggleInsert(
                    configManager = configManager,
                    values = ContentValues().apply {
                        put(EXTRA_ENABLED, intent.getBooleanExtra(EXTRA_ENABLED, true))
                        if (intent.hasExtra(EXTRA_PORT)) {
                            put(EXTRA_PORT, intent.getIntExtra(EXTRA_PORT, configManager.socketServerPort))
                        }
                    },
                    ensureLocalServerHost = {
                        ensureLocalServerHostAvailableForEnable(appContext, configManager)
                    },
                )

                ACTION_TOGGLE_WEBSOCKET_SERVER -> handleWebSocketServerToggleInsert(
                    configManager = configManager,
                    values = ContentValues().apply {
                        put(EXTRA_ENABLED, intent.getBooleanExtra(EXTRA_ENABLED, true))
                        if (intent.hasExtra(EXTRA_PORT)) {
                            put(EXTRA_PORT, intent.getIntExtra(EXTRA_PORT, configManager.websocketPort))
                        }
                    },
                    ensureLocalServerHost = {
                        ensureLocalServerHostAvailableForEnable(appContext, configManager)
                    },
                )

                else -> ApiResponse.Error("Unknown bridge action: ${intent.action}")
            }
        } catch (e: Exception) {
            ApiResponse.Error(e.message ?: "Bridge command failed")
        }

        finish(response)
    }

    private fun finish(response: ApiResponse) {
        val json = response.toJson()
        val encoded = Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
        setResultCode(if (response is ApiResponse.Error) Activity.RESULT_CANCELED else Activity.RESULT_OK)
        setResultData(encoded)
        setResultExtras(Bundle().apply { putString(EXTRA_RESULT_JSON, json) })
    }

    private fun finish(pendingResult: PendingResult, response: ApiResponse) {
        val json = response.toJson()
        val encoded = Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
        try {
            pendingResult.setResultCode(if (response is ApiResponse.Error) Activity.RESULT_CANCELED else Activity.RESULT_OK)
            pendingResult.setResultData(encoded)
            pendingResult.setResultExtras(Bundle().apply { putString(EXTRA_RESULT_JSON, json) })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finish bridge call", e)
        } finally {
            pendingResult.finish()
        }
    }

    private fun handleCall(context: Context, intent: Intent): ApiResponse {
        val method = intent.getStringExtra(EXTRA_METHOD)?.trim().orEmpty()
        if (method.isBlank()) return ApiResponse.Error("Missing bridge method")

        val params = try {
            JSONObject(intent.getStringExtra(EXTRA_PARAMS_JSON).orEmpty().ifBlank { "{}" })
        } catch (e: Exception) {
            return ApiResponse.Error("Invalid params_json: ${e.message}")
        }

        val service = AutoTermuxAccessibilityService.getInstance()
        val handlerContext = service ?: context.applicationContext
        val handler = ApiHandler(
            stateRepo = StateRepository(service),
            getKeyboardIME = { AutoTermuxKeyboardIME.getInstance() },
            getPackageManager = { handlerContext.packageManager },
            appVersionProvider = { resolveVersionName(handlerContext) },
            context = handlerContext,
        )
        return ActionDispatcher(handler).dispatch(
            action = method,
            params = params,
            origin = ActionDispatcher.Origin.BRIDGE,
        )
    }

    private fun resolveVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
