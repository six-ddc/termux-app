package com.termux.autotermux.api

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.Manifest
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.net.Uri
import android.provider.Settings
import com.termux.autotermux.input.AutoTermuxKeyboardIME
import com.termux.autotermux.core.JsonBuilders
import com.termux.autotermux.core.StateRepository
import com.termux.autotermux.state.ConnectionStateManager
import com.termux.autotermux.service.GestureController
import com.termux.autotermux.service.AutoTermuxAccessibilityService
import com.termux.autotermux.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.net.toUri
import com.termux.autotermux.keepalive.KeepAliveController
import com.termux.autotermux.keepalive.KeepAliveStartupException
import com.termux.autotermux.service.AutoAcceptGate
import com.termux.autotermux.service.FileOperations
import com.termux.autotermux.service.ScreenRecorderService
import com.termux.autotermux.model.ElementNode
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.termux.autotermux.state.AppVisibilityTracker
import com.termux.autotermux.ui.PermissionDialogActivity
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

class ApiHandler(
    private val stateRepo: StateRepository,
    private val getKeyboardIME: () -> AutoTermuxKeyboardIME?,
    private val getPackageManager: () -> PackageManager,
    private val appVersionProvider: () -> String,
    private val context: Context,
) {
    companion object {
        private const val SCREENSHOT_TIMEOUT_SECONDS = 5L
        private const val TAG = "ApiHandler"
        private const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB
        private const val INSTALL_FREE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024 // 200 MiB
        private const val INSTALL_UI_DELAY_MS = 1000L
        private const val APP_LAUNCH_VERIFY_TIMEOUT_MS = 2500L
        private const val APP_LAUNCH_VERIFY_INTERVAL_MS = 100L
        private const val MAX_ERROR_BODY_SIZE = 2048
        private const val ENABLE_UI_STOP_FALLBACK = true
        private const val FORCE_STOP_SCREEN_READY_TIMEOUT_MS = 5000L
        private const val ACCESSIBILITY_SERVICE_NOT_AVAILABLE = "Accessibility service not available"
        private const val APP_LAUNCH_REQUIRES_ACCESSIBILITY = "App launch requires Accessibility service"
        const val ACTION_INSTALL_RESULT = "com.termux.autotermux.action.INSTALL_RESULT"
        const val EXTRA_INSTALL_SUCCESS = "install_success"
        const val EXTRA_INSTALL_MESSAGE = "install_message"
        const val EXTRA_INSTALL_PACKAGE = "install_package"
        private const val INSTALL_NOTIFICATION_CHANNEL_ID = "install_result_channel"
        private const val INSTALL_NOTIFICATION_ID = 4001
    }

    private val installLock = Any()
    private val fileOperations: FileOperations by lazy { FileOperations() }
    private val termuxApiCompat: TermuxApiCompat by lazy {
        TermuxApiCompat(applicationContext, fileOperations)
    }
    val applicationContext: Context
        get() = context.applicationContext

    private fun getAvailableInternalBytes(): Long? {
        return try {
            StatFs(Environment.getDataDirectory().absolutePath).availableBytes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read free space", e)
            null
        }
    }

    private class SizeLimitedInputStream(
        inputStream: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(inputStream) {
        private var totalRead: Long = 0

        private fun onBytesRead(count: Int) {
            if (count <= 0) return
            totalRead += count.toLong()
            if (totalRead > maxBytes)
                throw IOException("APK exceeds max allowed size (${maxBytes} bytes)")

        }

        override fun read(): Int {
            val value = super.read()
            if (value != -1) onBytesRead(1)
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) onBytesRead(count)
            return count
        }
    }

    // Queries
    fun ping() = ApiResponse.Success("pong")

    private fun requireAccessibilityService(): ApiResponse.Error? {
        return if (stateRepo.hasAccessibilityService) {
            null
        } else {
            ApiResponse.Error(ACCESSIBILITY_SERVICE_NOT_AVAILABLE)
        }
    }

    fun getTree(packageName: String? = null): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val elements = stateRepo.getVisibleElements(packageName)
        val json = elements.map { JsonBuilders.elementNodeToJson(it) }
        return ApiResponse.Success(JSONArray(json).toString())
    }

    fun getTreeFull(filter: Boolean, packageName: String? = null): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val tree = stateRepo.getFullTree(filter, packageName)
            ?: return ApiResponse.Error(noTreeMessage(packageName))
        return ApiResponse.Success(tree.toString())
    }

    fun getPhoneState(): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val state = stateRepo.getPhoneState()
        return ApiResponse.Success(JsonBuilders.phoneStateToJson(state).toString())
    }

    fun getState(packageName: String? = null): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val elements = stateRepo.getVisibleElements(packageName)
        val treeJson = elements.map { JsonBuilders.elementNodeToJson(it) }
        val phoneStateJson = JsonBuilders.phoneStateToJson(stateRepo.getPhoneState())

        val combined = JSONObject().apply {
            put("a11y_tree", JSONArray(treeJson))
            put("phone_state", phoneStateJson)
            packageName?.let { put("package_filter", it) }
        }
        return ApiResponse.Success(combined.toString())
    }

    fun getStateFull(filter: Boolean, packageName: String? = null): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val tree = stateRepo.getFullTree(filter, packageName)
            ?: return ApiResponse.Error(noTreeMessage(packageName))
        val phoneStateJson = JsonBuilders.phoneStateToJson(stateRepo.getPhoneState())
        val deviceContext = stateRepo.getDeviceContext()

        val combined = JSONObject().apply {
            put("a11y_tree", tree)
            put("phone_state", phoneStateJson)
            put("device_context", deviceContext)
            packageName?.let { put("package_filter", it) }
        }
        return ApiResponse.RawObject(combined)
    }

    private fun noTreeMessage(packageName: String?): String {
        return if (packageName.isNullOrBlank()) {
            "No active window or root filtered out"
        } else {
            "No visible accessibility window for package $packageName"
        }
    }

    fun getVersion() = ApiResponse.Success(appVersionProvider())


    fun getPackages(): ApiResponse {
        Log.d(TAG, "getPackages called")
        return try {
            val pm = getPackageManager()
            val mainIntent =
                Intent(android.content.Intent.ACTION_MAIN, null).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }

            val resolvedApps: List<android.content.pm.ResolveInfo> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(mainIntent, 0)
                }

            Log.d("ApiHandler", "Found ${resolvedApps.size} raw resolved apps")

            val arr = JSONArray()

            for (resolveInfo in resolvedApps) {
                try {
                    val pkgInfo = try {
                        pm.getPackageInfo(resolveInfo.activityInfo.packageName, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(
                            "ApiHandler",
                            "Package not found: ${resolveInfo.activityInfo.packageName}",
                        )
                        continue
                    }

                    val label = try {
                        resolveInfo.loadLabel(pm).toString()
                    } catch (e: Exception) {
                        Log.w(
                            "ApiHandler",
                            "Label load failed for ${pkgInfo.packageName}: ${e.message}",
                        )
                        // Fallback to package name if label load fails (Samsung resource error with ARzone or something)
                        pkgInfo.packageName
                    }

                    val appInfo = resolveInfo.activityInfo.applicationInfo
                    val obj = JSONObject()

                    obj.put("packageName", pkgInfo.packageName)
                    obj.put("label", label)
                    obj.put("versionName", pkgInfo.versionName ?: JSONObject.NULL)

                    val versionCode =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pkgInfo.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            pkgInfo.versionCode.toLong()
                        }
                    obj.put("versionCode", versionCode)

                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    obj.put("isSystemApp", isSystem)

                    arr.put(obj)
                } catch (e: Exception) {
                    Log.w(
                        "ApiHandler",
                        "Skipping package ${resolveInfo.activityInfo.packageName}: ${e.message}",
                    )
                }
            }

            Log.d("ApiHandler", "Returning ${arr.length()} packages")

            ApiResponse.RawArray(arr)

        } catch (e: Exception) {
            Log.e("ApiHandler", "getPackages failed", e)
            ApiResponse.Error("Failed to enumerate launchable apps: ${e.message}")
        }
    }

    fun getAppInfo(packageName: String): ApiResponse {
        if (packageName.isBlank()) return ApiResponse.Error("Missing required param: 'package'")
        return try {
            ApiResponse.RawObject(packageInfoJson(packageName))
        } catch (e: PackageManager.NameNotFoundException) {
            ApiResponse.Error("Package not installed: $packageName")
        } catch (e: Exception) {
            ApiResponse.Error("Failed to inspect package $packageName: ${e.message}")
        }
    }

    fun cacheAppInfo(packageName: String): ApiResponse {
        return cacheJsonResponse("app-info", getAppInfo(packageName))
    }

    // Keyboard actions
    fun keyboardInput(base64Text: String, clear: Boolean): ApiResponse {
        val ime = getKeyboardIME()
        if (ime != null) {
            if (ime.inputB64Text(base64Text, clear)) {
                return ApiResponse.Success("input done via IME (clear=$clear)")
            }
        }

        // Fallback to accessibility services if IME is not active or failed
        try {
            val textBytes = android.util.Base64.decode(base64Text, android.util.Base64.DEFAULT)
            val text = String(textBytes, java.nio.charset.StandardCharsets.UTF_8)

            if (stateRepo.inputText(text, clear))
                return ApiResponse.Success("input done via Accessibility (clear=$clear)")

        } catch (e: Exception) {
            Log.e("ApiHandler", "Accessibility input fallback failed: ${e.message}")
        }

        return ApiResponse.Error("input failed (IME not active and Accessibility fallback failed)")
    }

    fun keyboardClear(): ApiResponse {
        val ime = getKeyboardIME()

        if (ime != null && ime.hasInputConnection()) {
            if (ime.clearText()) {
                return ApiResponse.Success("Text cleared via IME")
            }
            Log.w(TAG, "IME clearText() failed, falling back to Accessibility")
        }

        return if (stateRepo.inputText("", clear = true)) {
            ApiResponse.Success("Text cleared via Accessibility")
        } else {
            ApiResponse.Error("Clear failed (IME not active and Accessibility fallback failed)")
        }
    }

    fun getClipboard(): ApiResponse {
        if (!isKeyboardImeActiveAndSelected()) {
            return ApiResponse.Error("Clipboard read requires AutoTermux Keyboard to be selected")
        }

        val ime = getKeyboardIME() ?: AutoTermuxKeyboardIME.getInstance()
            ?: return ApiResponse.Error("Clipboard read requires AutoTermux Keyboard to be active")
        val text = ime.getClipboardText()
            ?: return ApiResponse.Error("Clipboard is empty or access was denied")

        return ApiResponse.Success(text)
    }

    fun setClipboard(text: String): ApiResponse {
        return try {
            val ime = getKeyboardIME() ?: AutoTermuxKeyboardIME.getInstance()
            if (ime != null && ime.setClipboardText(text)) {
                return ApiResponse.Success("Clipboard set")
            }

            val fallbackSet = runOnMainThreadBlocking {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@runOnMainThreadBlocking false
                clipboard.setPrimaryClip(ClipData.newPlainText("text", text))
                true
            } ?: false
            if (!fallbackSet) return ApiResponse.Error("Clipboard service unavailable")
            ApiResponse.Success("Clipboard set")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set clipboard", e)
            ApiResponse.Error("Failed to set clipboard: ${e.message}")
        }
    }

    private fun <T> runOnMainThreadBlocking(block: () -> T): T? {
        if (!shouldUseMainThreadClipboardAccess()) {
            return block()
        }

        var result: T? = null
        var failure: Throwable? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw IllegalStateException("Timed out waiting for main thread clipboard access")
        }
        failure?.let { throw it }
        return result
    }

    private fun shouldUseMainThreadClipboardAccess(): Boolean {
        return Build.VERSION.SDK_INT > 0 &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 &&
            Looper.myLooper() == null
    }

    /**
     * Helper to check if AutoTermuxKeyboardIME is both available and selected as the system default.
     * Matches the pattern used in ScrcpyControlChannel.
     */
    private fun isKeyboardImeActiveAndSelected(): Boolean {
        if (!AutoTermuxKeyboardIME.isAvailable()) return false
        return AutoTermuxKeyboardIME.isSelected(applicationContext)
    }

    fun keyboardKey(keyCode: Int): ApiResponse {
        // System navigation keys - use global actions (no IME needed)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            KeyEvent.KEYCODE_HOME -> return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            KeyEvent.KEYCODE_APP_SWITCH -> return performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }

        // ENTER key: prefer direct IME dispatch, then ACTION_IME_ENTER, then newline insertion
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (isKeyboardImeActiveAndSelected()) {
                val keyboard = AutoTermuxKeyboardIME.getInstance()
                if (keyboard != null && keyboard.sendKeyEventDirect(keyCode)) {
                    return ApiResponse.Success("Enter sent via IME")
                }
            }

            val state = stateRepo.getPhoneState()
            val focusedNode = state.focusedElement

            try {
                if (focusedNode != null &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                ) {
                    if (focusedNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                        return ApiResponse.Success("Enter performed via Accessibility")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Accessibility enter failed", e)
            } finally {
                try {
                    focusedNode?.recycle()
                } catch (_: Exception) {
                }
            }

            // Fallback: some multiline fields accept newline via ACTION_SET_TEXT
            return if (stateRepo.inputText("\n", clear = false))
                ApiResponse.Success("Newline inserted via Accessibility")
            else
                ApiResponse.Success("Enter handled (no focused element)")
        }

        // DEL key: prefer IME direct dispatch, then fall back to accessibility
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            if (isKeyboardImeActiveAndSelected()) {
                val keyboard = getKeyboardIME() ?: AutoTermuxKeyboardIME.getInstance()
                if (keyboard != null && keyboard.sendKeyEventDirect(keyCode)) {
                    return ApiResponse.Success("Delete handled")
                }
            }
            val service =
                AutoTermuxAccessibilityService.getInstance()
                    ?: return ApiResponse.Success("Delete handled (no service)")
            service.deleteText(1)
            return ApiResponse.Success("Delete handled")
        }

        // Forward DEL key: accessibility only
        if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            val service = AutoTermuxAccessibilityService.getInstance()
                ?: return ApiResponse.Success("Forward delete handled (no service)")
            service.deleteText(1, forward = true)
            return ApiResponse.Success("Forward delete handled")
        }

        // TAB key: try IME if available and selected, else use accessibility
        // If nothing is focused, just succeed silently (noop)
        if (keyCode == KeyEvent.KEYCODE_TAB) {
            if (isKeyboardImeActiveAndSelected()) {
                val keyboard = AutoTermuxKeyboardIME.getInstance()
                if (keyboard != null && keyboard.sendKeyEventDirect(keyCode)) {
                    return ApiResponse.Success("Tab sent via IME")
                }
            }
            // Fallback to accessibility - if it fails (nothing focused), just succeed as noop
            stateRepo.inputText("\t", clear = false)
            return ApiResponse.Success("Tab handled")
        }

        // For other keycodes: try IME first, then convert to unicode character
        if (isKeyboardImeActiveAndSelected()) {
            val keyboard = AutoTermuxKeyboardIME.getInstance()
            if (keyboard != null && keyboard.sendKeyEventDirect(keyCode)) {
                return ApiResponse.Success("Key event sent via IME - code: $keyCode")
            }
        }

        // Fallback: convert keycode to character using KeyEvent
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val unicodeChar = keyEvent.getUnicodeChar(0)

        if (unicodeChar > 0) {
            val char = unicodeChar.toChar()
            return if (stateRepo.inputText(char.toString(), clear = false))
                ApiResponse.Success("Character '$char' inserted via Accessibility")
            else
                ApiResponse.Error("Failed to insert character")
        }

        return ApiResponse.Error("Unsupported key code: $keyCode (no unicode mapping and IME not available)")
    }

    // Overlay
    fun setOverlayOffset(offset: Int): ApiResponse {
        return if (stateRepo.setOverlayOffset(offset)) {
            ApiResponse.Success("Overlay offset updated to $offset")
        } else {
            ApiResponse.Error("Failed to update overlay offset")
        }
    }

    fun setOverlayVisible(visible: Boolean): ApiResponse {
        return if (stateRepo.setOverlayVisible(visible)) {
            ApiResponse.Success("Overlay visibility set to $visible")
        } else {
            ApiResponse.Error("Failed to set overlay visibility")
        }
    }

    fun isOverlayVisible(): ApiResponse {
        return ApiResponse.RawObject(JSONObject().apply {
            put("visible", stateRepo.isOverlayVisible())
        })
    }

    fun getOverlayAutoOffsetStatus(): ApiResponse {
        val config = ConfigManager.getInstance(applicationContext)
        return ApiResponse.RawObject(JSONObject().apply {
            put("enabled", config.autoOffsetEnabled)
            put("calculated", config.autoOffsetCalculated)
            put("overlay_offset", config.overlayOffset)
            put("current_applied_offset", if (stateRepo.hasAccessibilityService) {
                AutoTermuxAccessibilityService.getInstance()?.getCurrentAppliedOffset() ?: JSONObject.NULL
            } else {
                JSONObject.NULL
            })
        })
    }

    fun setOverlayAutoOffsetEnabled(enabled: Boolean): ApiResponse {
        requireAccessibilityService()?.let { return it }
        return if (stateRepo.setAutoOffsetEnabled(enabled)) {
            getOverlayAutoOffsetStatus()
        } else {
            ApiResponse.Error("Failed to set overlay auto-offset to $enabled")
        }
    }

    // Background keepalive — starts a foreground service tied to an active
    // tp-android run so OEM battery managers won't kill the process mid-flow.
    // The same notification carries Pause / Cancel action buttons.
    fun backgroundStart(params: JSONObject): ApiResponse = backgroundUpdate(params)

    fun backgroundUpdate(params: JSONObject): ApiResponse {
        val runId = params.optStringOrNull("run_id") ?: params.optStringOrNull("runId")
        com.termux.autotermux.service.AutoTermuxBackgroundService.startOrUpdate(
            applicationContext,
            runId,
            params.optStringOrNull("task"),
            params.optInt("step", 0),
            params.optInt("total_steps", params.optInt("totalSteps", 0)),
            params.optStringOrNull("step_label") ?: params.optStringOrNull("stepLabel"),
            params.optString("state", "running"),
            params.optStringOrNull("pending_prompt") ?: params.optStringOrNull("pendingPrompt"),
            if (params.has("percent")) params.optDouble("percent").toFloat() else null,
        )
        return ApiResponse.RawObject(JSONObject().apply {
            put("running", true)
            put("run_id", runId ?: JSONObject.NULL)
        })
    }

    fun backgroundStop(): ApiResponse {
        com.termux.autotermux.service.AutoTermuxBackgroundService.stop(applicationContext)
        return ApiResponse.Success("Background service stopped")
    }

    // Termux floating-window hide/show (signature-protected actions handled by
    // TermuxService's onStartCommand). Used by screenshot/ui-dump flows that
    // want the target app's UI alone.
    fun floatingHide(): ApiResponse = floatingDispatch("com.termux.HIDE_FLOATING")
    fun floatingShow(): ApiResponse = floatingDispatch("com.termux.SHOW_FLOATING")

    private fun floatingDispatch(action: String): ApiResponse {
        return try {
            // Goes through TermuxFloatingControlReceiver — see MainActivity.sendFloatingControl
            // for the same trampoline.
            val intent = Intent(action)
                .setClassName("com.termux", "com.termux.app.TermuxFloatingControlReceiver")
            applicationContext.sendBroadcast(intent)
            ApiResponse.Success("dispatched $action")
        } catch (t: Throwable) {
            Log.w(TAG, "floating dispatch $action failed: ${t.message}")
            ApiResponse.Error("Failed to dispatch $action: ${t.message}")
        }
    }

    // HUD (status overlay shown while a tp-android agent run is active) ------
    fun hudShow(): ApiResponse {
        val svc = AutoTermuxAccessibilityService.getInstance()
            ?: return ApiResponse.Error(ACCESSIBILITY_SERVICE_NOT_AVAILABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(applicationContext)) {
            return ApiResponse.Error("OVERLAY_BLOCKED: Display-over-other-apps permission denied for AutoTermux. Open Settings → Apps → AutoTermux → Display over other apps.")
        }
        svc.getHudOverlay().show()
        return ApiResponse.Success("HUD shown")
    }

    fun hudHide(): ApiResponse {
        val svc = AutoTermuxAccessibilityService.getInstance()
            ?: return ApiResponse.Error(ACCESSIBILITY_SERVICE_NOT_AVAILABLE)
        svc.getHudOverlay().hide()
        return ApiResponse.Success("HUD hidden")
    }

    fun hudUpdate(params: JSONObject): ApiResponse {
        val svc = AutoTermuxAccessibilityService.getInstance()
            ?: return ApiResponse.Error(ACCESSIBILITY_SERVICE_NOT_AVAILABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(applicationContext)) {
            return ApiResponse.Error("OVERLAY_BLOCKED: Display-over-other-apps permission denied for AutoTermux. Open Settings → Apps → AutoTermux → Display over other apps.")
        }
        val hud = svc.getHudOverlay()
        val newState = com.termux.autotermux.ui.overlay.HudOverlay.State(
            runId = params.optStringOrNull("run_id") ?: params.optStringOrNull("runId"),
            task = params.optStringOrNull("task"),
            step = params.optInt("step", 0),
            totalSteps = params.optInt("total_steps", params.optInt("totalSteps", 0)),
            stepLabel = params.optStringOrNull("step_label") ?: params.optStringOrNull("stepLabel"),
            percent = if (params.has("percent")) params.optDouble("percent").toFloat() else null,
            state = params.optString("state", "running"),
            pendingPrompt = params.optStringOrNull("pending_prompt") ?: params.optStringOrNull("pendingPrompt"),
        )
        hud.update(newState)
        // Auto-show on first update so callers don't need to remember.
        if (!hud.isShowing()) hud.show()
        return ApiResponse.RawObject(JSONObject().apply {
            put("showing", true)
            put("state", newState.state)
            put("run_id", newState.runId ?: JSONObject.NULL)
        })
    }

    fun hudState(): ApiResponse {
        val svc = AutoTermuxAccessibilityService.getInstance()
            ?: return ApiResponse.Error(ACCESSIBILITY_SERVICE_NOT_AVAILABLE)
        val hud = svc.getHudOverlay()
        val s = hud.snapshot()
        return ApiResponse.RawObject(JSONObject().apply {
            put("showing", hud.isShowing())
            put("run_id", s.runId ?: JSONObject.NULL)
            put("task", s.task ?: JSONObject.NULL)
            put("step", s.step)
            put("total_steps", s.totalSteps)
            put("step_label", s.stepLabel ?: JSONObject.NULL)
            put("percent", s.percent?.toDouble() ?: JSONObject.NULL)
            put("state", s.state)
            put("pending_prompt", s.pendingPrompt ?: JSONObject.NULL)
        })
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val raw = if (has(key)) optString(key, "") else return null
        return raw.takeIf { it.isNotEmpty() && it != "null" }
    }

    fun setSocketPort(port: Int): ApiResponse {
        return if (stateRepo.updateSocketServerPort(port)) {
            ApiResponse.Success("Socket server port updated to $port")
        } else {
            ApiResponse.Error("Failed to update socket server port to $port (bind failed or invalid)")
        }
    }

    private fun captureScreenshotBase64(hideOverlay: Boolean): Result<String> {
        return try {
            val future = stateRepo.takeScreenshot(hideOverlay)
            val result =
                future.get(SCREENSHOT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)

            if (result.startsWith("error:")) {
                Result.failure(IllegalStateException(result.substring(7)))
            } else {
                Result.success(result)
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Result.failure(IllegalStateException("Screenshot timeout - operation took too long"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getScreenshot(hideOverlay: Boolean): ApiResponse {
        return captureScreenshotBase64(hideOverlay).fold(
            onSuccess = { result ->
                // Keep the legacy JSON/base64 response for raw API callers.
                ApiResponse.Text(result)
            },
            onFailure = { error ->
                ApiResponse.Error(error.message ?: "Failed to get screenshot")
            }
        )
    }

    fun cacheScreenshot(hideOverlay: Boolean): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        return captureScreenshotBase64(hideOverlay).fold(
            onSuccess = { base64 ->
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    fileOperations.writeTransferCache(bytes, "png").fold(
                        onSuccess = { cached ->
                            ApiResponse.RawObject(JSONObject().apply {
                                put("path", cached.path)
                                put("bytes", cached.bytes)
                                put("content_type", "image/png")
                            })
                        },
                        onFailure = { error -> fileFailureResponse("cache screenshot", error) },
                    )
                } catch (e: Exception) {
                    ApiResponse.Error("Failed to decode screenshot: ${e.message}")
                }
            },
            onFailure = { error ->
                ApiResponse.Error(error.message ?: "Failed to get screenshot")
            }
        )
    }

    fun cacheTree(full: Boolean, filter: Boolean, packageName: String? = null): ApiResponse {
        return cacheJsonResponse(
            source = if (full) "a11y_tree_full" else "a11y_tree",
            response = if (full) getTreeFull(filter, packageName) else getTree(packageName),
        )
    }

    fun cachePhoneState(): ApiResponse {
        return cacheJsonResponse("phone_state", getPhoneState())
    }

    fun cachePackages(): ApiResponse {
        return cacheJsonResponse("packages", getPackages())
    }

    fun cacheState(full: Boolean, filter: Boolean, packageName: String? = null): ApiResponse {
        return cacheJsonResponse(
            source = if (full) "state_full" else "state",
            response = if (full) getStateFull(filter, packageName) else getState(packageName),
        )
    }

    fun getWindows(): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val windows = stateRepo.getVisibleWindows()
        return ApiResponse.RawObject(JSONObject().apply {
            put("count", windows.length())
            put("windows", windows)
        })
    }

    fun getVisibleTexts(params: JSONObject): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val packageName = packageFilter(params)
        val limit = params.optInt("limit", 120).coerceIn(1, 1000)
        return ApiResponse.RawObject(JSONObject().apply {
            put("packageFilter", packageName ?: JSONObject.NULL)
            put("limit", limit)
            put("texts", visibleTextsJson(packageName, limit))
        })
    }

    fun cacheVisibleTexts(params: JSONObject): ApiResponse {
        return cacheJsonResponse("ui-texts", getVisibleTexts(params))
    }

    fun getDiagnostics(params: JSONObject): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val packageName = packageFilter(params)
        val textLimit = params.optInt("textLimit", params.optInt("limit", 80)).coerceIn(1, 500)
        val hideOverlay = params.optBoolean("hideOverlay", true)
        val screenshot = cacheScreenshot(hideOverlay)
        val screenBounds = stateRepo.getScreenBounds()

        return ApiResponse.RawObject(JSONObject().apply {
            put("phone_state", JsonBuilders.phoneStateToJson(stateRepo.getPhoneState()))
            put("windows", stateRepo.getVisibleWindows())
            put("overlay", JSONObject().apply {
                put("visible", stateRepo.isOverlayVisible())
            })
            put("screen_bounds", JSONObject().apply {
                put("left", screenBounds.left)
                put("top", screenBounds.top)
                put("right", screenBounds.right)
                put("bottom", screenBounds.bottom)
                put("width", screenBounds.width())
                put("height", screenBounds.height())
            })
            put("packageFilter", packageName ?: JSONObject.NULL)
            put("texts", visibleTextsJson(packageName, textLimit))
            when (screenshot) {
                is ApiResponse.RawObject -> put("screenshot", screenshot.json)
                is ApiResponse.Error -> put("screenshot_error", screenshot.message)
                else -> put("screenshot_error", "Unexpected screenshot response")
            }
        })
    }

    private fun cacheJsonResponse(source: String, response: ApiResponse): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        val payload = when (response) {
            is ApiResponse.Error -> return response
            is ApiResponse.RawObject -> response.json.toString()
            is ApiResponse.RawArray -> response.json.toString()
            is ApiResponse.Success -> when (val data = response.data) {
                is String -> data
                is JSONObject -> data.toString()
                is JSONArray -> data.toString()
                else -> JSONObject.wrap(data)?.toString() ?: JSONObject.NULL.toString()
            }
            is ApiResponse.Text -> response.data
            is ApiResponse.Binary -> return ApiResponse.Error("Cannot cache binary response as JSON")
        }
        return fileOperations.writeTransferCache(payload.toByteArray(Charsets.UTF_8), "json").fold(
            onSuccess = { cached ->
                ApiResponse.RawObject(JSONObject().apply {
                    put("path", cached.path)
                    put("bytes", cached.bytes)
                    put("content_type", "application/json")
                    put("source", source)
                })
            },
            onFailure = { error -> fileFailureResponse("cache $source", error) },
        )
    }

    private fun visibleTextsJson(packageName: String?, limit: Int): JSONArray {
        val nodes = flattenElements(stateRepo.getVisibleElements(packageName))
        val arr = JSONArray()
        for (node in nodes) {
            val text = node.nodeInfo.text?.toString()?.trim().orEmpty()
            val desc = node.nodeInfo.contentDescription?.toString()?.trim().orEmpty()
            val hint = node.nodeInfo.hintText?.toString()?.trim().orEmpty()
            if (text.isEmpty() && desc.isEmpty() && hint.isEmpty()) continue
            arr.put(JSONObject().apply {
                put("index", node.overlayIndex)
                put("packageName", node.nodeInfo.packageName?.toString() ?: JSONObject.NULL)
                put("className", node.className)
                put("resourceId", node.nodeInfo.viewIdResourceName ?: JSONObject.NULL)
                put("text", text)
                put("contentDescription", desc)
                put("hint", hint)
                put("windowLayer", node.windowLayer)
                put("bounds", JSONObject().apply {
                    put("left", node.rect.left)
                    put("top", node.rect.top)
                    put("right", node.rect.right)
                    put("bottom", node.rect.bottom)
                    put("width", node.rect.width())
                    put("height", node.rect.height())
                })
                put("center", JSONObject().apply {
                    put("x", node.rect.centerX())
                    put("y", node.rect.centerY())
                })
                put("isClickable", node.nodeInfo.isClickable)
                put("isScrollable", node.nodeInfo.isScrollable)
                put("isEditable", node.nodeInfo.isEditable)
            })
            if (arr.length() >= limit) break
        }
        return arr
    }

    private fun packageFilter(params: JSONObject): String? {
        return params.optString("packageName")
            .ifBlank { params.optString("package_name") }
            .ifBlank { params.optString("package") }
            .trim()
            .takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun fileFailureResponse(operation: String, error: Throwable): ApiResponse {
        return when (error) {
            is SecurityException -> ApiResponse.Error("Security error: ${error.message}")
            is java.nio.file.NoSuchFileException -> ApiResponse.Error("File not found")
            is NoSuchFileException -> ApiResponse.Error("File not found")
            is IllegalArgumentException -> ApiResponse.Error(error.message ?: "Invalid argument")
            else -> ApiResponse.Error("Failed to $operation: ${error.message}")
        }
    }

    fun performNodeAction(params: JSONObject): ApiResponse {
        requireAccessibilityService()?.let { return it }

        val actionName = params.optString("action", "click")
            .trim()
            .lowercase(Locale.US)
            .ifBlank { "click" }
        val packageName = packageFilter(params)
        val elements = flattenElements(stateRepo.getVisibleElements(packageName))
        val matchedNode = findNodeActionTarget(elements, params)
            ?: return ApiResponse.Error("No matching accessibility node")
        val actionableNode = when (actionName) {
            "click" -> if (matchedNode.nodeInfo.isClickable) matchedNode
                else findActionableAncestor(matchedNode) { it.isClickable } ?: matchedNode
            "long-click", "long_click" -> if (matchedNode.nodeInfo.isLongClickable) matchedNode
                else findActionableAncestor(matchedNode) { it.isLongClickable } ?: matchedNode
            "scroll-forward", "scroll_forward", "scroll-backward", "scroll_backward" ->
                if (matchedNode.nodeInfo.isScrollable) matchedNode
                else findActionableAncestor(matchedNode) { it.isScrollable } ?: matchedNode
            else -> matchedNode
        }

        val arguments = Bundle()
        val actionId = when (actionName) {
            "click" -> AccessibilityNodeInfo.ACTION_CLICK
            "long-click", "long_click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            "focus" -> AccessibilityNodeInfo.ACTION_FOCUS
            "clear-focus", "clear_focus" -> AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
            "select" -> AccessibilityNodeInfo.ACTION_SELECT
            "clear-selection", "clear_selection" -> AccessibilityNodeInfo.ACTION_CLEAR_SELECTION
            "scroll-forward", "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "scroll-backward", "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "copy" -> AccessibilityNodeInfo.ACTION_COPY
            "paste" -> AccessibilityNodeInfo.ACTION_PASTE
            "cut" -> AccessibilityNodeInfo.ACTION_CUT
            "set-text", "set_text" -> {
                val text = readNodeActionText(params)
                    ?: return ApiResponse.Error("Missing required param for set-text: 'value', 'value_base64', 'text', or 'text_base64'")
                arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
                AccessibilityNodeInfo.ACTION_SET_TEXT
            }
            "set-selection", "set_selection" -> {
                if (!params.has("start") || !params.has("end")) {
                    return ApiResponse.Error("Missing required params for set-selection: 'start' and 'end'")
                }
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                    params.optInt("start"),
                )
                arguments.putInt(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                    params.optInt("end"),
                )
                AccessibilityNodeInfo.ACTION_SET_SELECTION
            }
            else -> return ApiResponse.Error("Unsupported node action: $actionName")
        }

        val ok = try {
            if (arguments.isEmpty) actionableNode.nodeInfo.performAction(actionId)
            else actionableNode.nodeInfo.performAction(actionId, arguments)
        } catch (e: Exception) {
            Log.e(TAG, "Node action failed", e)
            return ApiResponse.Error("Node action failed: ${e.message}")
        }

        return if (ok) {
            ApiResponse.RawObject(JSONObject().apply {
                put("action", actionName)
                put("index", actionableNode.overlayIndex)
                put("matchedIndex", matchedNode.overlayIndex)
                put("text", actionableNode.text)
                put("resourceId", actionableNode.nodeInfo.viewIdResourceName ?: JSONObject.NULL)
                put("className", actionableNode.className)
                put("packageName", actionableNode.nodeInfo.packageName?.toString() ?: JSONObject.NULL)
                put("windowLayer", actionableNode.windowLayer)
                put("bounds", JSONObject().apply {
                    put("left", actionableNode.rect.left)
                    put("top", actionableNode.rect.top)
                    put("right", actionableNode.rect.right)
                    put("bottom", actionableNode.rect.bottom)
                })
            })
        } else {
            ApiResponse.Error("Accessibility node action returned false: $actionName")
        }
    }

    fun findNodes(params: JSONObject): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val includeAll = params.optBoolean("all", false)
        if (!includeAll && !hasNodeSelector(params)) {
            return ApiResponse.Error("At least one node selector is required unless 'all' is true")
        }

        val limit = params.optInt("limit", 20).coerceIn(1, 500)
        val packageName = packageFilter(params)
        val elements = flattenElements(stateRepo.getVisibleElements(packageName))
        val matches = elements.filter { node -> includeAll || nodeMatchesSelector(node, params) }
        val nodes = JSONArray()
        matches.take(limit).forEach { node -> nodes.put(elementNodeDetailJson(node)) }

        return ApiResponse.RawObject(JSONObject().apply {
            put("count", matches.size)
            put("returned", nodes.length())
            put("limit", limit)
            packageName?.let { put("packageFilter", it) }
            put("nodes", nodes)
        })
    }

    fun cacheFindNodes(params: JSONObject): ApiResponse {
        return cacheJsonResponse("node-find", findNodes(params))
    }

    fun getFocusedNode(): ApiResponse {
        requireAccessibilityService()?.let { return it }
        val state = stateRepo.getPhoneState()
        val focused = state.focusedElement
        return ApiResponse.RawObject(JSONObject().apply {
            put("focused", focused != null)
            put("keyboardVisible", state.keyboardVisible)
            put("isEditable", state.isEditable)
            put("packageName", state.packageName ?: JSONObject.NULL)
            put("activityName", state.activityName ?: JSONObject.NULL)
            if (focused != null) {
                put("node", accessibilityNodeInfoJson(focused))
            }
        })
    }

    fun cacheFocusedNode(): ApiResponse {
        return cacheJsonResponse("node-focused", getFocusedNode())
    }

    private fun readNodeActionText(params: JSONObject): String? {
        if (params.has("value")) return params.optString("value")
        if (params.has("setText")) return params.optString("setText")
        if (params.has("value_base64")) {
            return try {
                String(Base64.decode(params.optString("value_base64"), Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
        if (params.has("text")) return params.optString("text")
        if (!params.has("text_base64")) return null
        return try {
            String(Base64.decode(params.optString("text_base64"), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun findNodeActionTarget(
        elements: List<ElementNode>,
        params: JSONObject,
    ): ElementNode? {
        if (!hasNodeSelector(params)) return null
        return elements.firstOrNull { node -> nodeMatchesSelector(node, params) }
    }

    private fun hasNodeSelector(params: JSONObject): Boolean {
        return params.has("index") ||
                params.has("text") ||
                params.has("textContains") ||
                params.has("text_contains") ||
                params.has("desc") ||
                params.has("contentDescription") ||
                params.has("description") ||
                params.has("descContains") ||
                params.has("desc_contains") ||
                params.has("resourceId") ||
                params.has("resource_id") ||
                params.has("viewId") ||
                params.has("className") ||
                params.has("class_name") ||
                params.has("scrollable")
    }

    private fun nodeMatchesSelector(node: ElementNode, params: JSONObject): Boolean {
        return matchesOptionalIndex(node, params) &&
                matchesOptionalBoolean(node.nodeInfo.isScrollable, params, "scrollable") &&
                matchesOptionalString(node.text, params, "text", exact = true) &&
                matchesOptionalString(node.text, params, "textContains", "text_contains", exact = false) &&
                matchesOptionalString(
                    node.nodeInfo.contentDescription?.toString().orEmpty(),
                    params,
                    "desc",
                    "contentDescription",
                    "description",
                    exact = true,
                ) &&
                matchesOptionalString(
                    node.nodeInfo.contentDescription?.toString().orEmpty(),
                    params,
                    "descContains",
                    "desc_contains",
                    exact = false,
                ) &&
                matchesOptionalString(
                    node.nodeInfo.viewIdResourceName.orEmpty(),
                    params,
                    "resourceId",
                    "resource_id",
                    "viewId",
                    exact = true,
                ) &&
                matchesOptionalString(
                    node.className,
                    params,
                    "className",
                    "class_name",
                    exact = false,
                )
    }

    private fun elementNodeDetailJson(node: ElementNode): JSONObject {
        return accessibilityNodeInfoJson(node.nodeInfo, node.rect, node.overlayIndex).apply {
            put("text", node.text)
            put("className", node.className)
            put("windowLayer", node.windowLayer)
            put("clickableIndex", node.clickableIndex)
            put("semanticParentId", node.semanticParentId ?: JSONObject.NULL)
        }
    }

    private fun accessibilityNodeInfoJson(
        nodeInfo: AccessibilityNodeInfo,
        boundsOverride: android.graphics.Rect? = null,
        index: Int? = null,
    ): JSONObject {
        val bounds = boundsOverride ?: android.graphics.Rect().also { nodeInfo.getBoundsInScreen(it) }
        @Suppress("DEPRECATION")
        val checked = nodeInfo.isChecked
        return JSONObject().apply {
            if (index != null) put("index", index)
            put("text", nodeInfo.text?.toString() ?: "")
            put("contentDescription", nodeInfo.contentDescription?.toString() ?: "")
            put("resourceId", nodeInfo.viewIdResourceName ?: "")
            put("className", nodeInfo.className?.toString() ?: "")
            put("packageName", nodeInfo.packageName?.toString() ?: "")
            put("bounds", JSONObject().apply {
                put("left", bounds.left)
                put("top", bounds.top)
                put("right", bounds.right)
                put("bottom", bounds.bottom)
                put("width", bounds.width())
                put("height", bounds.height())
            })
            put("center", JSONObject().apply {
                put("x", bounds.centerX())
                put("y", bounds.centerY())
            })
            put("isClickable", nodeInfo.isClickable)
            put("isLongClickable", nodeInfo.isLongClickable)
            put("isScrollable", nodeInfo.isScrollable)
            put("isEditable", nodeInfo.isEditable)
            put("isEnabled", nodeInfo.isEnabled)
            put("isVisibleToUser", nodeInfo.isVisibleToUser)
            put("isFocusable", nodeInfo.isFocusable)
            put("isFocused", nodeInfo.isFocused)
            put("isSelected", nodeInfo.isSelected)
            put("isChecked", checked)
            put("isPassword", nodeInfo.isPassword)
            put("actions", JSONArray().apply {
                nodeInfo.actionList.forEach { action ->
                    put(JSONObject().apply {
                        put("id", action.id)
                        put("name", accessibilityActionName(action.id))
                        put("label", action.label?.toString() ?: JSONObject.NULL)
                    })
                }
            })
        }
    }

    private fun accessibilityActionName(id: Int): String {
        return when (id) {
            AccessibilityNodeInfo.ACTION_CLICK -> "click"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long-click"
            AccessibilityNodeInfo.ACTION_FOCUS -> "focus"
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> "clear-focus"
            AccessibilityNodeInfo.ACTION_SELECT -> "select"
            AccessibilityNodeInfo.ACTION_CLEAR_SELECTION -> "clear-selection"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll-forward"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll-backward"
            AccessibilityNodeInfo.ACTION_COPY -> "copy"
            AccessibilityNodeInfo.ACTION_PASTE -> "paste"
            AccessibilityNodeInfo.ACTION_CUT -> "cut"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "set-text"
            AccessibilityNodeInfo.ACTION_SET_SELECTION -> "set-selection"
            else -> "action-$id"
        }
    }

    private fun matchesOptionalIndex(node: ElementNode, params: JSONObject): Boolean {
        if (!params.has("index")) return true
        return node.overlayIndex == params.optInt("index")
    }

    private fun matchesOptionalBoolean(
        candidate: Boolean,
        params: JSONObject,
        key: String,
    ): Boolean {
        if (!params.has(key)) return true
        return candidate == params.optBoolean(key)
    }

    private fun matchesOptionalString(
        candidate: String,
        params: JSONObject,
        vararg keys: String,
        exact: Boolean,
    ): Boolean {
        val expected = keys.firstNotNullOfOrNull { key ->
            if (params.has(key)) params.optString(key) else null
        } ?: return true
        return if (exact) {
            candidate == expected
        } else {
            candidate.contains(expected, ignoreCase = true)
        }
    }

    // New Gesture Actions
    fun performTap(x: Int, y: Int): ApiResponse {
        return if (GestureController.tap(x, y)) {
            ApiResponse.Success("Tap performed at ($x, $y)")
        } else {
            ApiResponse.Error("Failed to perform tap at ($x, $y)")
        }
    }

    fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Int): ApiResponse {
        return if (GestureController.swipe(startX, startY, endX, endY, duration)) {
            ApiResponse.Success("Swipe performed")
        } else {
            ApiResponse.Error("Failed to perform swipe")
        }
    }

    fun performGlobalAction(action: Int): ApiResponse {
        return if (GestureController.performGlobalAction(action)) {
            ApiResponse.Success("Global action $action performed")
        } else {
            ApiResponse.Error("Failed to perform global action $action")
        }
    }

    fun startApp(packageName: String, activityName: String? = null): ApiResponse {
        return try {
            val pm = getPackageManager()
            val intent = if (!activityName.isNullOrEmpty() && activityName != "null") {
                Intent().apply {
                    setClassName(
                        packageName,
                        if (activityName.startsWith(".")) packageName + activityName else activityName
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                pm.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (intent != null) {
                context.startActivity(intent)
                verifyStartedApp(packageName)
            } else {
                Log.e(
                    "ApiHandler",
                    "Could not create intent for $packageName - getLaunchIntentForPackage returned null. Trying fallback.",
                )

                try {
                    val fallbackIntent = Intent(Intent.ACTION_MAIN)
                    fallbackIntent.addCategory(Intent.CATEGORY_LAUNCHER)
                    fallbackIntent.setPackage(packageName)
                    fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    if (fallbackIntent.resolveActivity(pm) != null) {
                        context.startActivity(fallbackIntent)
                        verifyStartedApp(packageName, fallback = true)
                    } else {
                        ApiResponse.Error("Could not create intent for $packageName")
                    }
                } catch (e2: Exception) {
                    Log.e("ApiHandler", "Fallback start failed", e2)
                    ApiResponse.Error("Could not create intent for $packageName")
                }
            }
        } catch (e: Exception) {
            Log.e("ApiHandler", "Error starting app", e)
            ApiResponse.Error("Error starting app: ${e.message}")
        }
    }

    fun startIntent(params: JSONObject): ApiResponse {
        return try {
            val intent = buildActivityIntent(params)
            if (intent.resolveActivity(getPackageManager()) == null && intent.component == null && intent.`package` == null) {
                return ApiResponse.Error("No activity can handle intent")
            }
            context.startActivity(intent)
            ApiResponse.RawObject(JSONObject().apply {
                put("started", true)
                put("action", intent.action ?: JSONObject.NULL)
                put("data", intent.data?.toString() ?: JSONObject.NULL)
                put("packageName", intent.`package` ?: JSONObject.NULL)
                put("component", intent.component?.flattenToString() ?: JSONObject.NULL)
            })
        } catch (e: Exception) {
            ApiResponse.Error("Failed to start intent: ${e.message}")
        }
    }

    fun openUrl(url: String, packageName: String? = null): ApiResponse {
        if (url.isBlank()) return ApiResponse.Error("Missing required param: 'url'")
        return startIntent(JSONObject().apply {
            put("action", Intent.ACTION_VIEW)
            put("data", url)
            packageName?.takeIf { it.isNotBlank() }?.let { put("package", it) }
        })
    }

    fun openAppSettings(packageName: String, screen: String = "details"): ApiResponse {
        if (packageName.isBlank()) return ApiResponse.Error("Missing required param: 'package'")
        return try {
            val action = when (screen.lowercase(Locale.US).replace("_", "-")) {
                "notification", "notifications" -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
                "permissions", "permission" -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                else -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            }
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                } else {
                    data = Uri.parse("package:$packageName")
                }
            }
            context.startActivity(intent)
            ApiResponse.RawObject(JSONObject().apply {
                put("opened", true)
                put("packageName", packageName)
                put("screen", screen)
                put("action", action)
            })
        } catch (e: Exception) {
            ApiResponse.Error("Failed to open app settings for $packageName: ${e.message}")
        }
    }

    fun requestUninstallApp(packageName: String): ApiResponse {
        if (packageName.isBlank()) return ApiResponse.Error("Missing required param: 'package'")
        if (packageName == context.packageName) return ApiResponse.Error("Refusing to uninstall AutoTermux")
        return try {
            context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ApiResponse.RawObject(JSONObject().apply {
                put("requested", true)
                put("packageName", packageName)
            })
        } catch (e: Exception) {
            ApiResponse.Error("Failed to request uninstall for $packageName: ${e.message}")
        }
    }

    private fun verifyStartedApp(packageName: String, fallback: Boolean = false): ApiResponse {
        val deadline = SystemClock.uptimeMillis() + APP_LAUNCH_VERIFY_TIMEOUT_MS
        var foregroundPackage = stateRepo.getPhoneState().packageName

        while (SystemClock.uptimeMillis() < deadline) {
            foregroundPackage = stateRepo.getPhoneState().packageName
            if (foregroundPackage == packageName) {
                return ApiResponse.Success(JSONObject().apply {
                    put("message", "Started app $packageName${if (fallback) " (fallback)" else ""}")
                    put("packageName", packageName)
                    put("foregroundPackageName", foregroundPackage)
                    put("verified", true)
                })
            }
            SystemClock.sleep(APP_LAUNCH_VERIFY_INTERVAL_MS)
        }

        return ApiResponse.Error(
            "Launch request for $packageName was accepted but foreground package is " +
                "${foregroundPackage ?: "unknown"} after ${APP_LAUNCH_VERIFY_TIMEOUT_MS}ms; " +
                "Android may have blocked background activity launch"
        )
    }

    fun stopApp(packageName: String): ApiResponse {
        if (packageName.isBlank()) {
            return ApiResponse.Error("Missing required param: 'package'")
        }
        if (packageName == context.packageName) {
            return ApiResponse.Error("Refusing to stop AutoTermux")
        }

        val pm = getPackageManager()
        val appLabel = getAppLabel(packageName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return ApiResponse.Error("Package not installed: $packageName")
        }

        val granted =
            context.checkSelfPermission(Manifest.permission.KILL_BACKGROUND_PROCESSES) ==
                    PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return ApiResponse.Error("Missing permission: KILL_BACKGROUND_PROCESSES")
        }

        val phoneState = stateRepo.getPhoneState()
        if (phoneState.packageName == packageName) {
            GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }

        var killError: String? = null
        val killSuccess = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping app", e)
            killError = e.message
            false
        }

        val uiResult = if (ENABLE_UI_STOP_FALLBACK) {
            tryForceStopViaSettings(packageName, appLabel)
        } else {
            ForceStopUiResult(attempted = false, success = false, reason = "ui_disabled")
        }

        // UI fallback can be delayed on some devices; don't override a successful
        // background-process kill with a transient UI readiness failure.
        val overallSuccess = killSuccess || uiResult.success
        val resultJson = JSONObject().apply {
            put("message", "Stop requested for $packageName")
            put("killBackgroundProcesses", killSuccess)
            put("killError", killError ?: JSONObject.NULL)
            put("uiAttempted", uiResult.attempted)
            put("uiSuccess", uiResult.success)
            put("uiReason", uiResult.reason ?: JSONObject.NULL)
            put("overallSuccess", overallSuccess)
        }

        return if (overallSuccess) {
            ApiResponse.RawObject(resultJson)
        } else {
            ApiResponse.Error(resultJson.toString())
        }
    }

    fun getTime(): ApiResponse {
        return ApiResponse.Success(System.currentTimeMillis())
    }

    fun getDeviceIdentity(): ApiResponse {
        val config = ConfigManager.getInstance(applicationContext)
        return ApiResponse.RawObject(JSONObject().apply {
            put("device_id", config.deviceID)
            put("package_name", applicationContext.packageName)
            put("version", appVersionProvider())
        })
    }

    fun getRemoteConfiguration(showToken: Boolean): ApiResponse {
        val config = ConfigManager.getInstance(applicationContext)
        val current = config.getCurrentConfiguration()
        return ApiResponse.RawObject(JSONObject().apply {
            put("overlay_visible", current.overlayVisible)
            put("overlay_offset", current.overlayOffset)
            put("auto_offset_enabled", current.autoOffsetEnabled)
            put("auto_offset_calculated", current.autoOffsetCalculated)
            put("socket_server_enabled", current.socketServerEnabled)
            put("socket_server_port", current.socketServerPort)
            put("websocket_enabled", current.websocketEnabled)
            put("websocket_port", current.websocketPort)
            put("auth_token", if (showToken) current.authToken else "redacted")
            put("no_a11y_mode", config.noA11yMode)
            put("media_projection_auto_accept", config.mediaProjectionAutoAcceptEnabled)
            put("install_auto_accept", config.installAutoAcceptEnabled)
            put("keep_screen_awake_enabled", config.keepScreenAwakeEnabled)
            put("device_id", config.deviceID)
        })
    }

    fun callTermuxApiCompat(command: String, params: JSONObject): ApiResponse =
        termuxApiCompat.dispatch(command, params)

    private fun getAppLabel(packageName: String): String? {
        return try {
            val pm = getPackageManager()
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get app label for $packageName: ${e.message}")
            null
        }
    }

    private fun packageInfoJson(packageName: String): JSONObject {
        val pm = getPackageManager()
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES
        val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
        val appInfo = pkgInfo.applicationInfo
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        val activities = JSONArray()
        pkgInfo.activities.orEmpty().forEach { activity ->
            activities.put(JSONObject().apply {
                put("name", activity.name)
                put("exported", activity.exported)
                put("permission", activity.permission ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("packageName", pkgInfo.packageName)
            put("label", appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkgInfo.packageName)
            put("versionName", pkgInfo.versionName ?: JSONObject.NULL)
            put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            })
            put("firstInstallTime", pkgInfo.firstInstallTime)
            put("lastUpdateTime", pkgInfo.lastUpdateTime)
            put("enabled", appInfo?.enabled ?: false)
            put("isSystemApp", appInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false)
            put("isUpdatedSystemApp", appInfo?.let { (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 } ?: false)
            put("sourceDir", appInfo?.sourceDir ?: JSONObject.NULL)
            put("dataDir", appInfo?.dataDir ?: JSONObject.NULL)
            put("launchIntent", launchIntent?.toUri(Intent.URI_INTENT_SCHEME) ?: JSONObject.NULL)
            put("activities", activities)
            put("requestedPermissions", JSONArray().apply {
                pkgInfo.requestedPermissions.orEmpty().forEach { put(it) }
            })
        }
    }

    private fun buildActivityIntent(params: JSONObject): Intent {
        val action = params.optString("action", Intent.ACTION_VIEW).ifBlank { Intent.ACTION_VIEW }
        val data = params.optString("data", params.optString("uri", ""))
        val mime = params.optString("mime", params.optString("type", ""))
        val intentUri = params.optString("intentUri", params.optString("intent_uri", ""))
        val intent = if (intentUri.isNotBlank()) {
            Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(action)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (data.isNotBlank() && mime.isNotBlank()) {
            intent.setDataAndType(Uri.parse(data), mime)
        } else if (data.isNotBlank()) {
            intent.data = Uri.parse(data)
        } else if (mime.isNotBlank()) {
            intent.type = mime
        }
        params.optString("package", params.optString("packageName", "")).takeIf(String::isNotBlank)?.let {
            intent.`package` = it
        }
        params.optString("component", "").takeIf(String::isNotBlank)?.let {
            intent.component = ComponentName.unflattenFromString(it)
                ?: throw IllegalArgumentException("Invalid component: $it")
        }
        val categories = params.optJSONArray("categories")
        if (categories != null) {
            for (i in 0 until categories.length()) {
                categories.optString(i).takeIf(String::isNotBlank)?.let { intent.addCategory(it) }
            }
        }
        val extras = params.optJSONObject("extras")
        if (extras != null) {
            extras.keys().forEach { key ->
                when (val value = extras.opt(key)) {
                    is Boolean -> intent.putExtra(key, value)
                    is Int -> intent.putExtra(key, value)
                    is Long -> intent.putExtra(key, value)
                    is Double -> intent.putExtra(key, value)
                    is String -> intent.putExtra(key, value)
                    else -> if (value != null && value !== JSONObject.NULL) intent.putExtra(key, value.toString())
                }
            }
        }
        return intent
    }

    private data class ForceStopUiResult(
        val attempted: Boolean,
        val success: Boolean,
        val reason: String?,
    )

    private enum class ForceStopButtonState {
        CLICKED,
        DISABLED,
        NOT_FOUND,
        NOT_READY,
        CLICK_FAILED,
    }

    private fun tryForceStopViaSettings(packageName: String, appLabel: String?): ForceStopUiResult {
        val service = AutoTermuxAccessibilityService.getInstance()
            ?: return ForceStopUiResult(false, false, "service_unavailable")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open app settings for $packageName: ${e.message}")
            return ForceStopUiResult(
                attempted = true,
                success = false,
                reason = "open_settings_failed",
            )
        }

        val screenReady = waitForUiAction(
            timeoutMs = FORCE_STOP_SCREEN_READY_TIMEOUT_MS,
            intervalMs = 250L,
        ) {
            val elements = flattenElements(stateRepo.getVisibleElements())
            isForceStopConfirmDialogVisible(elements) ||
                    isAppInfoScreenVisible(elements, appLabel, packageName)
        }
        if (!screenReady) {
            Log.d(TAG, "App info screen not ready for $packageName")
            logUiSnapshot("force_stop_screen_not_ready")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return ForceStopUiResult(attempted = true, success = false, reason = "screen_not_ready")
        }

        val dialogAlreadyVisible = waitForUiAction(
            timeoutMs = 1500L,
            intervalMs = 200L,
        ) { isForceStopConfirmDialogVisible() }
        if (dialogAlreadyVisible) {
            val confirmed = waitForUiAction(
                timeoutMs = 4000L,
                intervalMs = 250L,
            ) { tryClickForceStopConfirm() }
            if (!confirmed) {
                Log.d(TAG, "Force stop confirm dialog not detected for $packageName")
                logUiSnapshot("force_stop_confirm_not_found")
            }
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return ForceStopUiResult(
                true,
                confirmed,
                if (confirmed) "confirm_clicked" else "confirm_not_found"
            )
        }

        var buttonState = ForceStopButtonState.NOT_FOUND
        val buttonDeadline = SystemClock.elapsedRealtime() + 2500L
        while (SystemClock.elapsedRealtime() < buttonDeadline) {
            buttonState = evaluateForceStopButtonState(appLabel, packageName)
            if (buttonState == ForceStopButtonState.CLICKED ||
                buttonState == ForceStopButtonState.DISABLED
            ) {
                break
            }
            SystemClock.sleep(300L)
        }

        if (buttonState == ForceStopButtonState.DISABLED) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return ForceStopUiResult(
                attempted = true,
                success = true,
                reason = "force_stop_disabled",
            )
        }

        if (buttonState != ForceStopButtonState.CLICKED) {
            val confirmed = waitForUiAction(
                timeoutMs = 1000L,
                intervalMs = 200L,
            ) { tryClickForceStopConfirm() }
            if (confirmed) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return ForceStopUiResult(
                    attempted = true,
                    success = true,
                    reason = "confirm_clicked",
                )
            }
            val openVisible = isOpenButtonVisible(flattenElements(stateRepo.getVisibleElements()))
            if (openVisible) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return ForceStopUiResult(
                    attempted = true,
                    success = true,
                    reason = "force_stop_unavailable",
                )
            }
            Log.d(TAG, "Force stop button not found for $packageName")
            logUiSnapshot("force_stop_button_not_found")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            return ForceStopUiResult(
                attempted = true,
                success = false,
                reason = "force_stop_button_not_found",
            )
        }

        val confirmed = waitForUiAction(
            timeoutMs = 4000L,
            intervalMs = 250L,
        ) { tryClickForceStopConfirm() }
        if (!confirmed) {
            Log.d(TAG, "Force stop confirm dialog not detected for $packageName")
            logUiSnapshot("force_stop_confirm_not_found")
        }
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        return ForceStopUiResult(
            true,
            confirmed,
            if (confirmed) "confirm_clicked" else "confirm_not_found"
        )
    }

    private fun evaluateForceStopButtonState(
        appLabel: String?,
        packageName: String,
    ): ForceStopButtonState {
        val elements = flattenElements(stateRepo.getVisibleElements())
        if (isForceStopConfirmDialogVisible(elements)) return ForceStopButtonState.NOT_READY
        if (!isAppInfoScreenVisible(
                elements,
                appLabel,
                packageName
            )
        ) return ForceStopButtonState.NOT_READY
        val button = findForceStopButton(elements) ?: return ForceStopButtonState.NOT_FOUND
        val info = button.nodeInfo
        if (!info.isEnabled) return ForceStopButtonState.DISABLED
        return if (info.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            ForceStopButtonState.CLICKED
        } else {
            ForceStopButtonState.CLICK_FAILED
        }
    }

    private fun tryClickForceStopConfirm(): Boolean {
        val elements = flattenElements(stateRepo.getVisibleElements())
        if (!isForceStopConfirmDialogVisible(elements)) return false
        val dialogButton = findDialogPositiveButton(elements)
        val info = dialogButton?.nodeInfo
        if (info != null && info.isEnabled) {
            return info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        if (!isEnglishLocale()) return false
        val button = findBestClickableMatch(
            elements,
            listOf("force stop", "force-stop", "ok", "yes", "confirm"),
        )
        val fallbackInfo = button?.nodeInfo ?: return false
        if (!fallbackInfo.isEnabled) return false
        return fallbackInfo.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun isForceStopConfirmDialogVisible(): Boolean {
        val elements = flattenElements(stateRepo.getVisibleElements())
        return isForceStopConfirmDialogVisible(elements)
    }

    private fun isForceStopConfirmDialogVisible(
        elements: List<com.termux.autotermux.model.ElementNode>,
    ): Boolean {
        var hasDialogText = false
        var hasButtons = false
        var hasButtonPanel = false
        for (element in elements) {
            val viewId = element.nodeInfo.viewIdResourceName.orEmpty()
            if (viewId == "com.android.settings:id/alertTitle" ||
                viewId == "android:id/alertTitle" ||
                viewId == "android:id/message"
            ) {
                hasDialogText = true
            }
            if (viewId == "android:id/button1" || viewId == "android:id/button2") {
                hasButtons = true
            }
            if (viewId == "com.android.settings:id/buttonPanel" ||
                viewId == "android:id/buttonPanel"
            ) {
                hasButtonPanel = true
            }
        }
        if (hasButtons && (hasDialogText || hasButtonPanel)) {
            return true
        }

        val dialogButtons = findDialogButtonRow(elements)
        if (dialogButtons.size < 2) return false

        val screenWidth =
            elements.maxOfOrNull { it.rect.right }?.toFloat()?.coerceAtLeast(1f) ?: return false
        val screenHeight =
            elements.maxOfOrNull { it.rect.bottom }?.toFloat()?.coerceAtLeast(1f) ?: return false
        var left = dialogButtons.minOf { it.rect.left }
        var top = dialogButtons.minOf { it.rect.top }
        var right = dialogButtons.maxOf { it.rect.right }
        var bottom = dialogButtons.maxOf { it.rect.bottom }

        val buttonsTop = top
        val horizontalMargin = (screenWidth * 0.08f).toInt()
        val titleCandidates = elements.filter { element ->
            if (!element.className.contains("TextView", ignoreCase = true)) return@filter false
            if (element.text.isBlank()) return@filter false
            if (element.rect.bottom > buttonsTop) return@filter false
            val overlaps =
                element.rect.right >= left - horizontalMargin &&
                        element.rect.left <= right + horizontalMargin
            overlaps
        }
        if (titleCandidates.isEmpty()) return false
        left = minOf(left, titleCandidates.minOf { it.rect.left })
        top = minOf(top, titleCandidates.minOf { it.rect.top })
        right = maxOf(right, titleCandidates.maxOf { it.rect.right })
        bottom = maxOf(bottom, titleCandidates.maxOf { it.rect.bottom })

        val heightRatio = (bottom - top).toFloat() / screenHeight
        val widthRatio = (right - left).toFloat() / screenWidth
        val leftMargin = left.toFloat() / screenWidth
        val rightMargin = (screenWidth - right).toFloat() / screenWidth
        if (heightRatio !in 0.12f..0.6f) return false
        if (widthRatio !in 0.3f..0.95f) return false
        if (leftMargin < 0.05f || rightMargin < 0.05f) return false

        return true
    }

    private fun findDialogButtonRow(
        elements: List<com.termux.autotermux.model.ElementNode>,
    ): List<com.termux.autotermux.model.ElementNode> {
        val screenWidth = elements.maxOfOrNull { it.rect.right }?.toFloat()?.coerceAtLeast(1f)
            ?: return emptyList()
        val screenHeight = elements.maxOfOrNull { it.rect.bottom }?.toFloat()?.coerceAtLeast(1f)
            ?: return emptyList()
        val minButtonWidth = screenWidth * 0.12f
        val minButtonHeight = screenHeight * 0.03f
        val candidates = elements.filter { element ->
            val info = element.nodeInfo
            val width = element.rect.width().toFloat()
            val height = element.rect.height().toFloat()
            val isButtonClass = element.className.contains("Button", ignoreCase = true)
            (info.isClickable || isButtonClass) && width >= minButtonWidth && height >= minButtonHeight
        }
        if (candidates.isEmpty()) return emptyList()
        val tolerance = screenHeight * 0.04f
        val rows = mutableListOf<MutableList<com.termux.autotermux.model.ElementNode>>()
        for (candidate in candidates) {
            val centerY = candidate.rect.centerY().toFloat()
            val row = rows.firstOrNull { group ->
                val groupCenter = group.first().rect.centerY().toFloat()
                kotlin.math.abs(groupCenter - centerY) <= tolerance
            }
            if (row != null) {
                row.add(candidate)
            } else {
                rows.add(mutableListOf(candidate))
            }
        }
        val bestRow = rows
            .filter { it.size >= 2 }
            .maxByOrNull { row ->
                val minX = row.minOf { it.rect.left }
                val maxX = row.maxOf { it.rect.right }
                val span = (maxX - minX).toFloat()
                val centerY = row.first().rect.centerY().toFloat()
                span + centerY
            }
        return bestRow ?: emptyList()
    }

    private fun isAppInfoScreenVisible(
        elements: List<com.termux.autotermux.model.ElementNode>,
        appLabel: String?,
        packageName: String,
    ): Boolean {
        if (isForceStopConfirmDialogVisible(elements)) return true
        val hasForceStopText = if (isEnglishLocale()) {
            elements.any { element ->
                val text = element.text.lowercase()
                val desc = element.nodeInfo.contentDescription?.toString()?.lowercase().orEmpty()
                text.contains("force stop") || desc.contains("force stop")
            }
        } else {
            false
        }
        val labelVisible = isAppLabelVisible(elements, appLabel, packageName)
        val hasForceStopButton = findForceStopButton(elements) != null
        if (!labelVisible) return false
        return if (isEnglishLocale()) {
            hasForceStopText && hasForceStopButton
        } else {
            hasForceStopButton
        }
    }

    private fun isAppLabelVisible(
        elements: List<com.termux.autotermux.model.ElementNode>,
        appLabel: String?,
        packageName: String,
    ): Boolean {
        val label = appLabel?.trim().orEmpty().lowercase()
        val minLength = 3
        return elements.any { element ->
            val text = element.text.lowercase()
            val desc = element.nodeInfo.contentDescription?.toString()?.lowercase().orEmpty()
            val labelMatch =
                label.length >= minLength &&
                        (text.contains(label) || desc.contains(label))
            val packageMatch = text.contains(packageName) || desc.contains(packageName)
            labelMatch || packageMatch
        }
    }

    private fun findForceStopButton(
        elements: List<com.termux.autotermux.model.ElementNode>,
    ): com.termux.autotermux.model.ElementNode? {
        val idMatches = listOf(
            "force_stop",
            "force_stop_button",
            "button_force_stop",
            "forceStop",
        )
        for (element in elements) {
            val viewId = element.nodeInfo.viewIdResourceName.orEmpty()
            if (idMatches.any { token -> viewId.contains(token, ignoreCase = true) }) {
                return element
            }
        }
        if (isEnglishLocale()) {
            val textMatch = findClickableForText(
                elements,
                listOf("force stop", "force-stop"),
            )
            if (textMatch != null) return textMatch
        }
        val settingsButton = findSettingsActionButton(elements)
        if (settingsButton != null) return settingsButton
        val actionRowButton = findActionRowForceStopFallback(elements)
        if (actionRowButton != null) return actionRowButton
        if (!isEnglishLocale()) return null
        return findBestClickableMatch(elements, listOf("force stop", "force-stop"))
    }

    private fun findClickableForText(
        elements: List<com.termux.autotermux.model.ElementNode>,
        needles: List<String>,
    ): com.termux.autotermux.model.ElementNode? {
        val matches = elements.filter { element ->
            val text = element.text.lowercase()
            val desc = element.nodeInfo.contentDescription?.toString()?.lowercase().orEmpty()
            needles.any { needle -> text.contains(needle) || desc.contains(needle) }
        }
        for (match in matches) {
            val ancestor = findClickableAncestor(match)
            if (ancestor != null) return ancestor
            val containing = elements.filter { element ->
                element.nodeInfo.isClickable && element.rect.contains(match.rect)
            }
            if (containing.isNotEmpty()) {
                return containing.minBy { it.rect.width() * it.rect.height() }
            }
        }
        return null
    }

    private fun findClickableAncestor(
        node: com.termux.autotermux.model.ElementNode,
        maxDepth: Int = 6,
    ): com.termux.autotermux.model.ElementNode? {
        return findActionableAncestor(node, maxDepth) { it.isClickable }
    }

    private fun findActionableAncestor(
        node: com.termux.autotermux.model.ElementNode,
        maxDepth: Int = 6,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): com.termux.autotermux.model.ElementNode? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (predicate(current.nodeInfo)) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun findSettingsActionButton(
        elements: List<com.termux.autotermux.model.ElementNode>,
    ): com.termux.autotermux.model.ElementNode? {
        val candidates = mutableMapOf<Int, com.termux.autotermux.model.ElementNode>()
        for (element in elements) {
            val viewId = element.nodeInfo.viewIdResourceName.orEmpty()
            if (viewId.startsWith("android:id/button")) continue
            val index = settingsButtonIndex(viewId) ?: continue
            candidates[index] = element
        }
        if (candidates.isEmpty()) return null
        val maxIndex = candidates.keys.maxOrNull() ?: return null
        return if (maxIndex >= 3) candidates[maxIndex] else null
    }

    private fun findActionRowForceStopFallback(
        elements: List<com.termux.autotermux.model.ElementNode>,
    ): com.termux.autotermux.model.ElementNode? {
        if (elements.isEmpty()) return null
        val screenWidth = elements.maxOf { it.rect.right }.toFloat().coerceAtLeast(1f)
        val screenHeight = elements.maxOf { it.rect.bottom }.toFloat().coerceAtLeast(1f)
        val minWidth = screenWidth * 0.2f
        val minHeight = screenHeight * 0.05f
        val candidates = elements.filter { element ->
            val info = element.nodeInfo
            if (!info.isClickable) return@filter false
            val width = element.rect.width().toFloat()
            val height = element.rect.height().toFloat()
            width >= minWidth && height >= minHeight
        }
        if (candidates.isEmpty()) return null
        val tolerance = screenHeight * 0.08f
        val groups = mutableListOf<MutableList<com.termux.autotermux.model.ElementNode>>()
        for (candidate in candidates) {
            val centerY = candidate.rect.centerY().toFloat()
            val group = groups.firstOrNull { group ->
                val groupCenter = group.first().rect.centerY().toFloat()
                kotlin.math.abs(groupCenter - centerY) <= tolerance
            }
            if (group != null) {
                group.add(candidate)
            } else {
                groups.add(mutableListOf(candidate))
            }
        }
        val bestGroup = groups
            .filter { it.size >= 3 }
            .maxByOrNull { group ->
                val minX = group.minOf { it.rect.left }
                val maxX = group.maxOf { it.rect.right }
                val span = (maxX - minX).toFloat()
                span
            } ?: return null
        val minX = bestGroup.minOf { it.rect.left }
        val maxX = bestGroup.maxOf { it.rect.right }
        val span = (maxX - minX).toFloat()
        if (span < screenWidth * 0.6f) return null
        return bestGroup.maxByOrNull { it.rect.right }
    }

    private fun settingsButtonIndex(viewId: String): Int? {
        val prefix = ":id/button"
        val idx = viewId.lastIndexOf(prefix)
        if (idx == -1) return null
        val suffix = viewId.substring(idx + prefix.length)
        if (suffix.isEmpty()) return null
        val digit = suffix.trim().toIntOrNull() ?: return null
        return if (digit in 1..4) digit else null
    }

    private fun isOpenButtonVisible(
        elements: List<com.termux.autotermux.model.ElementNode>,
    ): Boolean {
        val idMatches = listOf("launch", "open")
        for (element in elements) {
            val info = element.nodeInfo
            val viewId = info.viewIdResourceName.orEmpty()
            if (idMatches.any { token -> viewId.contains(token, ignoreCase = true) }) {
                return true
            }
            if (isEnglishLocale()) {
                val text = element.text.lowercase()
                val desc = info.contentDescription?.toString()?.lowercase().orEmpty()
                if (text == "open" || desc == "open") return true
            }
        }
        return false
    }

    private fun isEnglishLocale(): Boolean {
        return Locale.getDefault().language.equals("en", ignoreCase = true)
    }

    private fun findDialogPositiveButton(
        elements: List<com.termux.autotermux.model.ElementNode>,
    ): com.termux.autotermux.model.ElementNode? {
        val byId = findBestClickableById(
            elements,
            listOf("android:id/button1", "com.android.settings:id/button1"),
        )
        if (byId != null) return byId

        val row = findDialogButtonRow(elements)
        val rightmost = row.maxByOrNull { it.rect.right } ?: return null
        val ancestor =
            if (rightmost.nodeInfo.isClickable) null else findClickableAncestor(rightmost)
        return ancestor ?: rightmost
    }

    private fun waitForUiAction(
        timeoutMs: Long,
        intervalMs: Long,
        action: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (action()) return true
            SystemClock.sleep(intervalMs)
        }
        return false
    }

    private fun findBestClickableMatch(
        elements: List<com.termux.autotermux.model.ElementNode>,
        needles: List<String>,
    ): com.termux.autotermux.model.ElementNode? {
        var best: com.termux.autotermux.model.ElementNode? = null
        var bestScore = Int.MAX_VALUE
        for (element in elements) {
            val info = element.nodeInfo
            if (!info.isClickable) continue
            val text = element.text.lowercase()
            val desc = info.contentDescription?.toString()?.lowercase().orEmpty()
            for (needle in needles) {
                val score = scoreStringMatch(text, needle) ?: scoreStringMatch(desc, needle)
                if (score != null && score < bestScore) {
                    best = element
                    bestScore = score
                }
            }
        }
        return best
    }

    private fun findBestClickableById(
        elements: List<com.termux.autotermux.model.ElementNode>,
        viewIdMatches: List<String>,
    ): com.termux.autotermux.model.ElementNode? {
        for (element in elements) {
            val info = element.nodeInfo
            if (!info.isClickable) continue
            val viewId = info.viewIdResourceName ?: continue
            if (viewIdMatches.any { match ->
                    viewId.equals(match, ignoreCase = true) || viewId.endsWith(match)
                }
            ) {
                return element
            }
        }
        return null
    }

    private fun flattenElements(
        elements: List<com.termux.autotermux.model.ElementNode>
    ): List<com.termux.autotermux.model.ElementNode> {
        val all = mutableListOf<com.termux.autotermux.model.ElementNode>()
        val visited = Collections.newSetFromMap(IdentityHashMap<com.termux.autotermux.model.ElementNode, Boolean>())
        fun collect(node: com.termux.autotermux.model.ElementNode) {
            if (!visited.add(node)) {
                Log.w(TAG, "Skipping cyclic flattened element: ${node.redactedLogIdentifier()}")
                return
            }
            all.add(node)
            try {
                node.children.forEach { child -> collect(child) }
            } finally {
                visited.remove(node)
            }
        }
        elements.forEach { root -> collect(root) }
        return all
    }

    private fun logUiSnapshot(reason: String) {
        val elements = flattenElements(stateRepo.getVisibleElements())
        val total = elements.size
        val maxLines = 80
        val sb = StringBuilder()
        var lines = 0
        for (element in elements) {
            val text = element.text.trim()
            val desc = element.nodeInfo.contentDescription?.toString()?.trim().orEmpty()
            val viewId = element.nodeInfo.viewIdResourceName?.trim().orEmpty()
            if (text.isEmpty() && desc.isEmpty() && viewId.isEmpty()) continue
            sb.append("[").append(element.className).append("] ")
            if (text.isNotEmpty()) sb.append("text='").append(text).append("' ")
            if (desc.isNotEmpty()) sb.append("desc='").append(desc).append("' ")
            if (viewId.isNotEmpty()) sb.append("id='").append(viewId).append("' ")
            sb.append("rect=").append(element.rect.toShortString())
            sb.append('\n')
            lines++
            if (lines >= maxLines) break
        }
        Log.d(TAG, "UI snapshot reason=$reason total=$total listed=$lines\n$sb")
    }

    private fun scoreStringMatch(haystack: String, needle: String): Int? {
        if (haystack.isEmpty()) return null
        if (haystack == needle) return 0
        if (haystack.contains(needle)) return 10 + (haystack.length - needle.length)
        return null
    }

    fun installApp(
        apkStream: InputStream,
        hideOverlay: Boolean = false,
        expectedSizeBytes: Long = -1L,
    ): ApiResponse {
        return try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Log.e(
                    TAG,
                    "Install permission not granted (canRequestPackageInstalls = false)"
                )
                // Show permission dialog to guide the user
                showInstallPermissionDialog()
                return ApiResponse.Error("Install permission denied. Please enable 'Install unknown apps' for AutoTermux in Settings.")
            }

            if (expectedSizeBytes > MAX_APK_BYTES) {
                return ApiResponse.Error("APK too large: $expectedSizeBytes bytes (max $MAX_APK_BYTES)")
            }

            if (expectedSizeBytes > 0) {
                val availableBytes = getAvailableInternalBytes()
                if (availableBytes != null) {
                    val requiredBytes = expectedSizeBytes + INSTALL_FREE_SPACE_MARGIN_BYTES
                    if (availableBytes < requiredBytes) {
                        return ApiResponse.Error(
                            "Insufficient storage: need ~$requiredBytes bytes, have $availableBytes bytes",
                        )
                    }
                }
            }

            val packageInstaller = getPackageManager().packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use {
                val totalBytes = writeApkToSession(it, "base_apk", apkStream, expectedSizeBytes)
                Log.i("ApiHandler", "Written $totalBytes decoded bytes to install session")
                commitInstallSession(sessionId, it, hideOverlay)
            }
        } catch (e: Exception) {
            Log.e("ApiHandler", "Install failed", e)
            ApiResponse.Error("Install exception: ${e.message}")
        }
    }

    private fun writeApkToSession(
        session: PackageInstaller.Session,
        entryName: String,
        apkStream: InputStream,
        expectedSizeBytes: Long,
    ): Long {
        val writeSize = if (expectedSizeBytes > 0) expectedSizeBytes else -1L
        val out = session.openWrite(entryName, 0, writeSize)
        var totalBytes = 0L
        apkStream.use { rawInput ->
            val input = SizeLimitedInputStream(rawInput, MAX_APK_BYTES)
            val buffer = ByteArray(65536)
            var c: Int
            while (input.read(buffer).also { c = it } != -1) {
                out.write(buffer, 0, c)
                totalBytes += c
            }
        }
        session.fsync(out)
        out.close()
        return totalBytes
    }

    private fun commitInstallSession(
        sessionId: Int,
        session: PackageInstaller.Session,
        hideOverlay: Boolean,
    ): ApiResponse {
        val latch = CountDownLatch(1)
        var success = false
        var errorMsg = ""
        var confirmationLaunched = false
        var installedPackageName: String? = null
        val wasOverlayVisible = stateRepo.isOverlayVisible()
        val shouldHideOverlay = hideOverlay && wasOverlayVisible
        var receiverRegistered = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val status =
                    intent?.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    )
                val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val packageName = intent?.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                if (!packageName.isNullOrBlank()) installedPackageName = packageName

                Log.d("ApiHandler", "Install Status Received: $status, Message: $message")

                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirmationIntent =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent?.getParcelableExtra(
                                Intent.EXTRA_INTENT,
                                Intent::class.java,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            (intent?.getParcelableExtra(Intent.EXTRA_INTENT))
                        }

                    if (confirmationIntent == null) {
                        errorMsg = "Install confirmation intent missing"
                        latch.countDown()
                        return
                    }

                    if (!confirmationLaunched) {
                        confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            AutoAcceptGate.armInstall()
                            context.startActivity(confirmationIntent)
                        } catch (e: Exception) {
                            errorMsg = "Failed to launch install confirmation: ${e.message}"
                            latch.countDown()
                        }
                    }
                    return
                }

                if (status == PackageInstaller.STATUS_SUCCESS) {
                    success = true
                    latch.countDown()
                    return
                }

                errorMsg = message ?: "Unknown error (Status Code: $status)"
                if (status == PackageInstaller.STATUS_FAILURE_INVALID) errorMsg += " [INVALID]"
                if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) errorMsg += " [INCOMPATIBLE]"
                if (status == PackageInstaller.STATUS_FAILURE_STORAGE) errorMsg += " [STORAGE]"
                latch.countDown()
            }
        }

        val action = "com.termux.autotermux.INSTALL_COMPLETE_${sessionId}"
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(action),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, IntentFilter(action))
            }
            receiverRegistered = true

            if (shouldHideOverlay) {
                Log.i(TAG, "Hiding overlay to prevent Tapjacking protection...")
                stateRepo.setOverlayVisible(false)
            }

            // bring the app to the foreground
            Log.i(TAG, "Bringing app to foreground for install prompt...")
            val foregroundIntent =
                Intent(context, com.termux.autotermux.ui.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            context.startActivity(foregroundIntent)

            try {
                Thread.sleep(INSTALL_UI_DELAY_MS)
            } catch (ignored: InterruptedException) {
            }

            Log.i(TAG, "Committing install session...")
            session.commit(pendingIntent.intentSender)

            val completed =
                latch.await(3, TimeUnit.MINUTES) // timeout for user interaction
            if (!completed && errorMsg.isBlank()) {
                errorMsg = "Timed out waiting for install result"
            }
        } finally {
            AutoAcceptGate.disarmInstall()
            if (receiverRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unregister install receiver", e)
                }
            }
            if (shouldHideOverlay) {
                stateRepo.setOverlayVisible(wasOverlayVisible)
            }
        }

        val packageSuffix = installedPackageName?.let { " ($it)" } ?: ""
        val response = if (success) {
            ApiResponse.Success("App installed successfully")
        } else {
            ApiResponse.Error("Install failed: $errorMsg")
        }

        val message = if (success) {
            "App installed successfully$packageSuffix"
        } else {
            "Install failed$packageSuffix: $errorMsg"
        }

        notifyInstallResult(success, message, installedPackageName)
        return response
    }

    private fun notifyInstallResult(success: Boolean, message: String, packageName: String?) {
        try {
            val intent = Intent(ACTION_INSTALL_RESULT)
                .setPackage(context.packageName)
                .putExtra(EXTRA_INSTALL_SUCCESS, success)
                .putExtra(EXTRA_INSTALL_MESSAGE, message)
                .putExtra(EXTRA_INSTALL_PACKAGE, packageName ?: "")
            context.sendBroadcast(intent)

            if (!AppVisibilityTracker.isInForeground()) {
                showInstallNotification(success, message)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast install result", e)
        }
    }

    private fun showInstallNotification(success: Boolean, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            INSTALL_NOTIFICATION_CHANNEL_ID,
            "Install Results",
            NotificationManager.IMPORTANCE_HIGH,
        )
        nm.createNotificationChannel(channel)

        val icon = if (success) {
            android.R.drawable.stat_sys_download_done
        } else {
            android.R.drawable.stat_notify_error
        }

        val notification = NotificationCompat.Builder(context, INSTALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("App install")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(INSTALL_NOTIFICATION_ID, notification)
    }

    private fun installSplitApksFromUrls(urls: List<String>, hideOverlay: Boolean): ApiResponse {
        val invalidUrl = urls.firstOrNull { url ->
            val scheme = url.toUri().scheme?.lowercase()
            scheme != "https" && scheme != "http"
        }
        if (invalidUrl != null) {
            val scheme = invalidUrl.toUri().scheme?.lowercase()
            return ApiResponse.Error("Unsupported URL scheme: ${scheme ?: "null"}")
        }

        val packageInstaller = getPackageManager().packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.use {
            var totalBytes = 0L
            urls.forEachIndexed { index, urlString ->
                val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    requestMethod = "GET"
                    setRequestProperty(
                        "Accept",
                        "application/vnd.android.package-archive,application/octet-stream,*/*",
                    )
                }

                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        val errorBody =
                            connection.errorStream?.bufferedReader()?.use { reader ->
                                val text = reader.readText()
                                if (text.length > MAX_ERROR_BODY_SIZE) text.take(
                                    MAX_ERROR_BODY_SIZE
                                ) else text
                            }
                        session.abandon()
                        return ApiResponse.Error(
                            buildString {
                                append("Download failed: HTTP $code")
                                connection.responseMessage?.let { msg ->
                                    if (msg.isNotBlank()) append(" $msg")
                                }
                                if (!errorBody.isNullOrBlank()) append(": $errorBody")
                            },
                        )
                    }

                    val contentLength = connection.contentLengthLong
                    if (contentLength > MAX_APK_BYTES) {
                        session.abandon()
                        return ApiResponse.Error(
                            "APK too large: $contentLength bytes (max $MAX_APK_BYTES)",
                        )
                    }

                    val availableBytes = getAvailableInternalBytes()
                    if (availableBytes != null) {
                        val requiredBytes = when {
                            contentLength > 0 -> contentLength + INSTALL_FREE_SPACE_MARGIN_BYTES
                            else -> INSTALL_FREE_SPACE_MARGIN_BYTES
                        }
                        if (availableBytes < requiredBytes) {
                            session.abandon()
                            return ApiResponse.Error(
                                "Insufficient storage: need ~$requiredBytes bytes, have $availableBytes bytes",
                            )
                        }
                    }

                    val entryName = "apk_${index}.apk"
                    val writtenBytes = connection.inputStream.use { stream ->
                        writeApkToSession(session, entryName, stream, contentLength)
                    }
                    totalBytes += writtenBytes
                } finally {
                    try {
                        connection.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }

            Log.i("ApiHandler", "Written $totalBytes decoded bytes to install session")
            return commitInstallSession(sessionId, it, hideOverlay)
        }
    }

    fun installFromUrls(urls: List<String>, hideOverlay: Boolean = false): ApiResponse {
        if (urls.isEmpty()) return ApiResponse.Error("No APK URLs provided")

        if (!context.packageManager.canRequestPackageInstalls()) {
            Log.e(TAG, "Install permission not granted (canRequestPackageInstalls = false)")
            // Show permission dialog to guide the user
            showInstallPermissionDialog()
            return ApiResponse.Error(
                "Install permission denied. Please enable 'Install unknown apps' for AutoTermux in Settings.",
            )
        }

        val results = JSONArray()
        var successCount = 0
        val uniqueUrls = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        synchronized(installLock) {
            if (uniqueUrls.size > 1) {
                val installResponse = installSplitApksFromUrls(uniqueUrls, hideOverlay)
                val success = installResponse is ApiResponse.Success
                val message = when (installResponse) {
                    is ApiResponse.Success -> installResponse.data.toString()
                    is ApiResponse.Error -> installResponse.message
                    else -> "Unexpected install response: ${installResponse.javaClass.simpleName}"
                }

                for (urlString in uniqueUrls) {
                    val result = JSONObject().apply { put("url", urlString) }
                    if (success) {
                        successCount += 1
                        result.put("success", true)
                        result.put("message", message)
                    } else {
                        result.put("success", false)
                        result.put("error", message)
                    }
                    results.put(result)
                }
            } else {
                for (urlString in uniqueUrls) {
                    val result = JSONObject().apply { put("url", urlString) }

                    try {
                        val uri = urlString.toUri()
                        val scheme = uri.scheme?.lowercase()
                        if (scheme != "https" && scheme != "http") {
                            result.put("success", false)
                            result.put("error", "Unsupported URL scheme: ${scheme ?: "null"}")
                            results.put(result)
                            continue
                        }

                        val connection =
                            (URL(urlString).openConnection() as HttpURLConnection).apply {
                                instanceFollowRedirects = true
                                connectTimeout = 15_000
                                readTimeout = 60_000
                                requestMethod = "GET"
                                setRequestProperty(
                                    "Accept",
                                    "application/vnd.android.package-archive,application/octet-stream,*/*",
                                )
                            }

                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) {
                                val errorBody =
                                    connection.errorStream?.bufferedReader()?.use { reader ->
                                        val text = reader.readText()
                                        if (text.length > MAX_ERROR_BODY_SIZE) text.take(
                                            MAX_ERROR_BODY_SIZE
                                        ) else text
                                    }
                                result.put("success", false)
                                result.put(
                                    "error",
                                    buildString {
                                        append("Download failed: HTTP $code")
                                        connection.responseMessage?.let { msg ->
                                            if (msg.isNotBlank()) append(" $msg")
                                        }
                                        if (!errorBody.isNullOrBlank()) append(": $errorBody")
                                    },
                                )
                                results.put(result)
                                continue
                            }

                            val contentLength = connection.contentLengthLong

                            if (contentLength > MAX_APK_BYTES) {
                                result.put("success", false)
                                result.put(
                                    "error",
                                    "APK too large: $contentLength bytes (max $MAX_APK_BYTES)",
                                )
                                results.put(result)
                                continue
                            }

                            val availableBytes = getAvailableInternalBytes()
                            if (availableBytes != null) {
                                val requiredBytes = when {
                                    contentLength > 0 -> contentLength + INSTALL_FREE_SPACE_MARGIN_BYTES
                                    else -> INSTALL_FREE_SPACE_MARGIN_BYTES
                                }
                                if (availableBytes < requiredBytes) {
                                    result.put("success", false)
                                    result.put(
                                        "error",
                                        "Insufficient storage: need ~$requiredBytes bytes, have $availableBytes bytes",
                                    )
                                    results.put(result)
                                    continue
                                }
                            }

                            val installResponse =
                                connection.inputStream.use { stream ->
                                    installApp(
                                        stream,
                                        hideOverlay,
                                        expectedSizeBytes = contentLength
                                    )
                                }

                            when (installResponse) {
                                is ApiResponse.Success -> {
                                    successCount += 1
                                    result.put("success", true)
                                    result.put("message", installResponse.data.toString())
                                }

                                is ApiResponse.Error -> {
                                    result.put("success", false)
                                    result.put("error", installResponse.message)
                                }

                                else -> {
                                    result.put("success", false)
                                    result.put(
                                        "error",
                                        "Unexpected install response: ${installResponse.javaClass.simpleName}",
                                    )
                                }
                            }
                        } finally {
                            try {
                                connection.disconnect()
                            } catch (_: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Install from URL failed: $urlString", e)
                        result.put("success", false)
                        result.put("error", e.message ?: "Install from URL failed")
                    }

                    results.put(result)
                }
            }
        }

        val summary = JSONObject().apply {
            put("overallSuccess", successCount == uniqueUrls.size)
            put("successCount", successCount)
            put("failureCount", uniqueUrls.size - successCount)
            put("results", results)
        }

        return ApiResponse.RawObject(summary)
    }

    fun setScreenKeepAwakeEnabled(enabled: Boolean): ApiResponse {
        return try {
            KeepAliveController.setEnabled(context, enabled)
            ApiResponse.RawObject(KeepAliveController.getMutationResultStatusJson(context, enabled))
        } catch (e: KeepAliveStartupException) {
            ApiResponse.Error(e.reason)
        }
    }

    fun getScreenKeepAwakeStatus(): ApiResponse =
        ApiResponse.RawObject(KeepAliveController.getStatusJson(context))

    fun getScreenStatus(): ApiResponse {
        return ApiResponse.RawObject(screenStatusJson())
    }

    fun wakeScreen(durationMs: Int): ApiResponse {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wasInteractive = powerManager.isInteractive
        val timeoutMs = durationMs.coerceIn(100, 60_000)

        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "$TAG:WakeScreen",
        )
        wakeLock.acquire(timeoutMs.toLong())

        return ApiResponse.RawObject(JSONObject().apply {
            put("requested", true)
            put("duration_ms", timeoutMs)
            put("was_interactive", wasInteractive)
            put("is_interactive", powerManager.isInteractive)
        })
    }

    fun lockScreen(): ApiResponse {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return ApiResponse.Error("Lock screen global action requires Android 9+")
        }
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    fun getScreenOrientationStatus(): ApiResponse {
        return ApiResponse.RawObject(screenOrientationJson())
    }

    fun setScreenOrientation(mode: String): ApiResponse {
        val normalized = mode.lowercase(Locale.US).replace("_", "-")
        val resolver = context.contentResolver
        if (!Settings.System.canWrite(context)) {
            return ApiResponse.Error(
                "WRITE_SETTINGS required; run: tp-android permissions open write-settings",
            )
        }

        val rotation = when (normalized) {
            "auto", "sensor", "unlocked" -> null
            "portrait", "natural", "0", "rotation-0" -> Surface.ROTATION_0
            "landscape", "right", "90", "rotation-90" -> Surface.ROTATION_90
            "reverse-portrait", "inverted", "180", "rotation-180" -> Surface.ROTATION_180
            "reverse-landscape", "left", "270", "rotation-270" -> Surface.ROTATION_270
            else -> return ApiResponse.Error(
                "Unknown orientation '$mode'. Use auto, portrait, landscape, reverse-portrait, or reverse-landscape",
            )
        }

        return try {
            if (rotation == null) {
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1)
            } else {
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
                Settings.System.putInt(resolver, Settings.System.USER_ROTATION, rotation)
            }
            ApiResponse.RawObject(screenOrientationJson().apply {
                put("requested_mode", mode)
                put("updated", true)
            })
        } catch (e: Exception) {
            ApiResponse.Error("Failed to set screen orientation: ${e.message}")
        }
    }

    fun startScreenRecording(params: JSONObject): ApiResponse {
        val maxDuration = if (params.has("maxDurationMs")) {
            params.optLong("maxDurationMs")
        } else {
            ScreenRecorderService.DEFAULT_MAX_DURATION_MS
        }
        val bitRate = params.optInt("bitRate", ScreenRecorderService.DEFAULT_BIT_RATE)
        val frameRate = params.optInt("frameRate", ScreenRecorderService.DEFAULT_FRAME_RATE)
        val includeMic = params.optBoolean("includeMic", false)

        val startParams = ScreenRecorderService.StartParams(
            maxDurationMs = if (maxDuration > 0) maxDuration else null,
            bitRate = bitRate,
            frameRate = frameRate,
            includeMic = includeMic,
        )
        // Prime params before launching consent activity so the eventual service
        // construction picks them up.
        ScreenRecorderService.primeParams(startParams)

        val result = ScreenRecorderService.start(applicationContext, startParams)
        return if (!result.ok) {
            ApiResponse.Error(result.error ?: "Failed to start screen recording")
        } else {
            ApiResponse.RawObject(JsonBuilders.screenRecorderStartResult(result))
        }
    }

    fun stopScreenRecording(): ApiResponse {
        val result = ScreenRecorderService.stop()
        return if (!result.ok) {
            ApiResponse.Error(result.error ?: "Failed to stop screen recording")
        } else {
            ApiResponse.RawObject(JsonBuilders.screenRecorderStopResult(result))
        }
    }

    fun getScreenRecordingStatus(): ApiResponse {
        val snapshot = ScreenRecorderService.status()
        return ApiResponse.RawObject(JsonBuilders.screenRecorderStatus(snapshot))
    }

    fun getConnectionState(): ApiResponse {
        // Reconcile ConnectionStateManager with the actual running server instances
        // before returning the snapshot. This guards against missed hook events
        // (e.g., when the service was started in a previous process or before
        // the hooks were installed).
        reconcileConnectionState()
        return ApiResponse.RawObject(ConnectionStateManager.snapshot())
    }

    private fun reconcileConnectionState() {
        val a11y = com.termux.autotermux.service.AutoTermuxAccessibilityService.getInstance()
        val local = com.termux.autotermux.service.LocalAutomationService.getInstance()
        val socketServer = a11y?.activeSocketServer() ?: local?.activeSocketServer()
        val actuallyUp = socketServer?.isRunning() == true
        val actualPort = if (actuallyUp) socketServer?.getPort() else null
        if (actuallyUp && actualPort != null && (!ConnectionStateManager.isHttpServerUp() || ConnectionStateManager.httpServerPort() != actualPort)) {
            ConnectionStateManager.markHttpServerUp(actualPort)
        } else if (!actuallyUp && ConnectionStateManager.isHttpServerUp()) {
            ConnectionStateManager.markHttpServerDown()
        }
        val wsActive = a11y?.isWebSocketServerActive() == true || local?.isWebSocketServerActive() == true
        val wsPort = a11y?.activeWebSocketPort() ?: local?.activeWebSocketPort()
        if (wsActive && wsPort != null && (!ConnectionStateManager.isWsServerUp() || ConnectionStateManager.wsServerPort() != wsPort)) {
            ConnectionStateManager.markWsServerUp(wsPort)
        } else if (!wsActive && ConnectionStateManager.isWsServerUp()) {
            ConnectionStateManager.markWsServerDown()
        }
    }

    private fun screenStatusJson(): JSONObject {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = currentWindowBounds(windowManager)
        val metrics = context.resources.displayMetrics
        @Suppress("DEPRECATION")
        val scaledDensity = metrics.scaledDensity

        return JSONObject().apply {
            put("interactive", powerManager.isInteractive)
            put("keyguard_locked", keyguardManager.isKeyguardLocked)
            put("device_locked", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                keyguardManager.isDeviceLocked
            } else {
                JSONObject.NULL
            })
            put("width", bounds.width())
            put("height", bounds.height())
            put("density", metrics.density)
            put("density_dpi", metrics.densityDpi)
            put("scaled_density", scaledDensity)
            put("orientation", configurationOrientationName())
            put("rotation", currentRotationJson(windowManager))
            put("orientation_settings", screenOrientationJson())
            put("keep_awake", KeepAliveController.getStatusJson(context))
        }
    }

    private fun screenOrientationJson(): JSONObject {
        val resolver = context.contentResolver
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val accelerometerRotation = Settings.System.getInt(
            resolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0,
        )
        val userRotation = Settings.System.getInt(
            resolver,
            Settings.System.USER_ROTATION,
            Surface.ROTATION_0,
        )

        return JSONObject().apply {
            put("auto_rotate_enabled", accelerometerRotation == 1)
            put("user_rotation", userRotation)
            put("user_rotation_name", rotationName(userRotation))
            put("current_rotation", currentDisplayRotation(windowManager))
            put("current_rotation_name", rotationName(currentDisplayRotation(windowManager)))
            put("configuration_orientation", configurationOrientationName())
            put("can_write_settings", Settings.System.canWrite(context))
        }
    }

    private fun currentWindowBounds(windowManager: WindowManager): android.graphics.Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun currentRotationJson(windowManager: WindowManager): JSONObject {
        val rotation = currentDisplayRotation(windowManager)
        return JSONObject().apply {
            put("value", rotation)
            put("name", rotationName(rotation))
        }
    }

    private fun currentDisplayRotation(windowManager: WindowManager): Int {
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay.rotation
    }

    private fun rotationName(rotation: Int): String {
        return when (rotation) {
            Surface.ROTATION_0 -> "rotation-0"
            Surface.ROTATION_90 -> "rotation-90"
            Surface.ROTATION_180 -> "rotation-180"
            Surface.ROTATION_270 -> "rotation-270"
            else -> "unknown"
        }
    }

    private fun configurationOrientationName(): String {
        return when (context.resources.configuration.orientation) {
            android.content.res.Configuration.ORIENTATION_PORTRAIT -> "portrait"
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            else -> "undefined"
        }
    }

    private fun fileOperationsUnavailableResponse(): ApiResponse? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return null
        return ApiResponse.Error(
            "File operations are only supported on Android 11+ in this compatibility tier"
        )
    }

    fun listFiles(path: String): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        return fileOperations.listFiles(path).fold(
            onSuccess = { response ->
                ApiResponse.RawObject(response.toJson())
            },
            onFailure = { error ->
                when (error) {
                    is SecurityException -> ApiResponse.Error("Security error: ${error.message}")
                    is java.nio.file.NoSuchFileException -> ApiResponse.Error("Path not found: $path")
                    is NoSuchFileException -> ApiResponse.Error("Path not found: $path")
                    is IllegalArgumentException -> ApiResponse.Error(error.message ?: "Invalid argument")
                    else -> ApiResponse.Error("Failed to list files: ${error.message}")
                }
            }
        )
    }

    fun downloadFile(path: String): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        if (path.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'path'")
        }

        return fileOperations.readFile(path).fold(
            onSuccess = { data ->
                ApiResponse.Binary(data)
            },
            onFailure = { error ->
                when (error) {
                    is SecurityException -> ApiResponse.Error("Security error: ${error.message}")
                    is java.nio.file.NoSuchFileException -> ApiResponse.Error("File not found: $path")
                    is NoSuchFileException -> ApiResponse.Error("File not found: $path")
                    is IllegalArgumentException -> ApiResponse.Error(error.message ?: "Invalid argument")
                    else -> ApiResponse.Error("Failed to read file: ${error.message}")
                }
            }
        )
    }

    fun stageFileDownload(path: String): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        if (path.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'path'")
        }

        return fileOperations.stageFileForDownload(path).fold(
            onSuccess = { cached ->
                ApiResponse.RawObject(JSONObject().apply {
                    put("path", cached.path)
                    put("bytes", cached.bytes)
                    put("remote_path", path)
                })
            },
            onFailure = { error ->
                when (error) {
                    is SecurityException -> ApiResponse.Error("Security error: ${error.message}")
                    is java.nio.file.NoSuchFileException -> ApiResponse.Error("File not found: $path")
                    is NoSuchFileException -> ApiResponse.Error("File not found: $path")
                    is IllegalArgumentException -> ApiResponse.Error(error.message ?: "Invalid argument")
                    else -> ApiResponse.Error("Failed to stage file: ${error.message}")
                }
            }
        )
    }

    fun uploadFile(path: String, data: ByteArray): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        if (path.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'path'")
        }

        return fileOperations.writeFile(path, data).fold(
            onSuccess = {
                ApiResponse.Success("File written successfully")
            },
            onFailure = { error ->
                when (error) {
                    is SecurityException -> ApiResponse.Error("Security error: ${error.message}")
                    is IllegalArgumentException -> ApiResponse.Error(error.message ?: "Invalid argument")
                    else -> ApiResponse.Error("Failed to write file: ${error.message}")
                }
            }
        )
    }

    fun importStagedFile(path: String, cachePath: String): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        if (path.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'path'")
        }
        if (cachePath.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'cachePath'")
        }

        return fileOperations.importTransferCacheFile(cachePath, path).fold(
            onSuccess = { bytes ->
                ApiResponse.RawObject(JSONObject().apply {
                    put("path", path)
                    put("bytes", bytes)
                    put("cache_path", cachePath)
                })
            },
            onFailure = { error ->
                when (error) {
                    is SecurityException -> ApiResponse.Error("Security error: ${error.message}")
                    is java.nio.file.NoSuchFileException -> ApiResponse.Error("Cache file not found: $cachePath")
                    is NoSuchFileException -> ApiResponse.Error("Cache file not found: $cachePath")
                    is IllegalArgumentException -> ApiResponse.Error(error.message ?: "Invalid argument")
                    else -> ApiResponse.Error("Failed to import staged file: ${error.message}")
                }
            }
        )
    }

    fun deleteFile(path: String): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        if (path.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'path'")
        }

        return fileOperations.deleteFile(path).fold(
            onSuccess = {
                ApiResponse.Success("File deleted successfully")
            },
            onFailure = { error ->
                when (error) {
                    is SecurityException -> ApiResponse.Error("Security error: ${error.message}")
                    is java.nio.file.NoSuchFileException -> ApiResponse.Error("File not found: $path")
                    is NoSuchFileException -> ApiResponse.Error("File not found: $path")
                    else -> ApiResponse.Error("Failed to delete file: ${error.message}")
                }
            }
        )
    }

    fun fetchFile(url: String, path: String): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        if (url.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'url'")
        }
        if (path.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'path'")
        }

        return fileOperations.fetchFile(url, path).fold(
            onSuccess = {
                ApiResponse.Success("ok")
            },
            onFailure = { error ->
                when (error) {
                    is SecurityException -> ApiResponse.Error("Security error: ${error.message}")
                    is IllegalArgumentException -> ApiResponse.Error(error.message ?: "Invalid argument")
                    else -> ApiResponse.Error("Failed to fetch file: ${error.message}")
                }
            }
        )
    }

    fun pushFile(url: String, path: String): ApiResponse {
        fileOperationsUnavailableResponse()?.let { return it }
        if (url.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'url'")
        }
        if (path.isEmpty()) {
            return ApiResponse.Error("Missing required param: 'path'")
        }

        return fileOperations.pushFile(url, path).fold(
            onSuccess = {
                ApiResponse.Success("ok")
            },
            onFailure = { error ->
                when (error) {
                    is SecurityException -> ApiResponse.Error("Security error: ${error.message}")
                    is java.nio.file.NoSuchFileException -> ApiResponse.Error("File not found: $path")
                    is NoSuchFileException -> ApiResponse.Error("File not found: $path")
                    is IllegalArgumentException -> ApiResponse.Error(error.message ?: "Invalid argument")
                    else -> ApiResponse.Error("Failed to push file: ${error.message}")
                }
            }
        )
    }

    /**
     * Shows a dialog prompting the user to enable "Install unknown apps" permission.
     */
    private fun showInstallPermissionDialog() {
        try {
            val intent = PermissionDialogActivity.createInstallPermissionIntent(context)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show install permission dialog", e)
        }
    }
}
