package com.termux.autotermux.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.os.SystemClock
import android.widget.Toast
import com.termux.autotermux.R
import com.termux.autotermux.api.ApiHandler
import com.termux.autotermux.audit.AuditEntry
import com.termux.autotermux.audit.AuditLog
import com.termux.autotermux.core.AccessibilityTraversalGuard
import com.termux.autotermux.core.StateRepository
import com.termux.autotermux.config.ConfigManager
import com.termux.autotermux.input.AutoTermuxKeyboardIME
import com.termux.autotermux.ui.overlay.HudOverlay
import com.termux.autotermux.ui.overlay.OverlayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import com.termux.autotermux.events.AutoTermuxWebSocketServer
import com.termux.autotermux.events.EventHub
import com.termux.autotermux.events.model.DeviceEvent
import com.termux.autotermux.events.model.EventType
import com.termux.autotermux.keepalive.KeepAliveController
import com.termux.autotermux.keepalive.KeepAliveRecoveryActivity
import com.termux.autotermux.model.ElementNode
import com.termux.autotermux.model.PhoneState
import com.termux.autotermux.triggers.TriggerRuntime
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

@SuppressLint("AccessibilityPolicy")
class AutoTermuxAccessibilityService : AccessibilityService(), ConfigManager.ConfigChangeListener {

    companion object {
        const val TAG = "AutoTermuxAccessibility"
        const val ACTION_DISABLE_LOCAL_WS_SERVER =
            "com.termux.autotermux.action.DISABLE_LOCAL_WS_SERVER"
        private var instance: AutoTermuxAccessibilityService? = null
        private const val MIN_ELEMENT_SIZE = 5
        private const val TOAST_DEBOUNCE_MS = 60_000L
        private const val AUTO_ACCEPT_FAILURE_TOAST_DEBOUNCE_MS = 10_000L
        private const val LOCAL_WS_NOTIFICATION_CHANNEL_ID = "local_ws_connection_channel"
        private const val LOCAL_WS_NOTIFICATION_ID = 2003
        internal const val VISIBLE_ELEMENTS_STALE_GRACE_MS = 750L
        private const val PACKAGE_ROOT_LOOKUP_ATTEMPTS = 4
        private const val PACKAGE_ROOT_LOOKUP_RETRY_DELAY_MS = 80L

        // Periodic update constants
        private const val REFRESH_INTERVAL_MS = 250L // Update every 250ms
        private const val MIN_FRAME_TIME_MS = 16L // Minimum time between frames (roughly 60 FPS)

        internal fun shouldReuseVisibleElementsSnapshot(
            cachedElementCount: Int,
            snapshotTimeMs: Long,
            nowMs: Long,
            snapshotPackageName: String,
            currentPackageName: String,
            snapshotActivityName: String,
            currentActivityName: String,
            snapshotScreenWidth: Int,
            currentScreenWidth: Int,
            snapshotScreenHeight: Int,
            currentScreenHeight: Int,
        ): Boolean {
            val snapshotAgeMs = nowMs - snapshotTimeMs
            return cachedElementCount > 0 &&
                    snapshotTimeMs > 0L &&
                    snapshotAgeMs in 0L..VISIBLE_ELEMENTS_STALE_GRACE_MS &&
                    snapshotPackageName == currentPackageName &&
                    snapshotActivityName == currentActivityName &&
                    snapshotScreenWidth == currentScreenWidth &&
                    snapshotScreenHeight == currentScreenHeight
        }

        internal fun updateScreenBounds(bounds: Rect, width: Int, height: Int): Boolean {
            val safeWidth = width.coerceAtLeast(0)
            val safeHeight = height.coerceAtLeast(0)
            val changed = bounds.left != 0 ||
                    bounds.top != 0 ||
                    bounds.right != safeWidth ||
                    bounds.bottom != safeHeight
            bounds.left = 0
            bounds.top = 0
            bounds.right = safeWidth
            bounds.bottom = safeHeight
            return changed
        }

        fun getInstance(): AutoTermuxAccessibilityService? = instance

        fun calculateInputText(
            currentText: String?,
            hintText: String?,
            newText: String,
            clear: Boolean
        ): String {
            return calculateInputText(
                currentText = currentText,
                hintText = hintText,
                newText = newText,
                clear = clear,
                selectionStart = null,
                selectionEnd = null,
            )
        }

        fun calculateInputText(
            currentText: String?,
            hintText: String?,
            newText: String,
            clear: Boolean,
            selectionStart: Int?,
            selectionEnd: Int?,
        ): String {
            if (clear) return newText

            val safeCurrentText = currentText.orEmpty()

            // If the current text matches the hint text, treat it as empty.
            if (hintText != null && safeCurrentText == hintText) return newText

            val length = safeCurrentText.length
            val rawStart = selectionStart ?: length
            val rawEnd = selectionEnd ?: rawStart
            val start = rawStart.coerceIn(0, length)
            val end = rawEnd.coerceIn(0, length)
            val replaceStart = minOf(start, end)
            val replaceEnd = maxOf(start, end)

            val before = safeCurrentText.take(replaceStart)
            val after = safeCurrentText.substring(replaceEnd)
            return before + newText + after
        }

        fun calculateDeleteText(
            currentText: String?,
            hintText: String?,
            count: Int,
            forward: Boolean,
            selectionStart: Int?,
            selectionEnd: Int?,
        ): String? {
            if (count <= 0) return currentText

            val safeCurrentText = currentText.orEmpty()

            if (hintText != null && safeCurrentText == hintText) return null
            if (safeCurrentText.isEmpty()) return null

            val length = safeCurrentText.length
            val rawStart = selectionStart ?: length
            val rawEnd = selectionEnd ?: rawStart
            val start = rawStart.coerceIn(0, length)
            val end = rawEnd.coerceIn(0, length)
            val replaceStart = minOf(start, end)
            val replaceEnd = maxOf(start, end)

            if (replaceStart != replaceEnd) {
                return safeCurrentText.take(replaceStart) + safeCurrentText.substring(replaceEnd)
            }

            return if (forward) {
                val deleteEnd = minOf(length, replaceEnd + count)
                if (deleteEnd == replaceEnd) safeCurrentText
                else safeCurrentText.take(replaceEnd) + safeCurrentText.substring(deleteEnd)
            } else {
                val deleteStart = maxOf(0, replaceStart - count)
                if (deleteStart == replaceStart) safeCurrentText
                else safeCurrentText.substring(0, deleteStart) + safeCurrentText.substring(
                    replaceStart
                )
            }
        }
    }

    private lateinit var overlayManager: OverlayManager
    private lateinit var hudOverlay: HudOverlay
    private val screenBounds = Rect()
    private lateinit var configManager: ConfigManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastWebSocketServerToastAtMs = 0L
    private var lastAutoAcceptFailureToastAtMs = 0L
    private var serviceDisconnectedAuditRecorded = false

    // Servers
    // TODO Make nullable
    private lateinit var actionDispatcher: ActionDispatcher
    private var socketServer: SocketServer? = null
    private var websocketServer: AutoTermuxWebSocketServer? = null

    fun activeSocketServer(): SocketServer? = socketServer
    fun isWebSocketServerActive(): Boolean = websocketServer != null
    fun activeWebSocketPort(): Int? = if (websocketServer != null) configManager.websocketPort else null

    // Periodic update state
    private var isInitialized = false
    private val isProcessing = AtomicBoolean(false)
    private var lastUpdateTime = 0L
    private var currentPackageName: String = ""
    private var currentActivityName: String = ""
    private val visibleElements = mutableListOf<ElementNode>()
    private var visibleElementsSnapshotTimeMs = 0L
    private var visibleElementsSnapshotPackageName = ""
    private var visibleElementsSnapshotActivityName = ""
    private var visibleElementsSnapshotScreenWidth = 0
    private var visibleElementsSnapshotScreenHeight = 0

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)
        hudOverlay = HudOverlay(this).also { hud ->
            // Click handlers — see UX plan L4 §cancel. For Phase 1 we just record
            // the intent in AutoTermux memory + a shared-storage marker so the
            // running tp-android process (in Termux) picks it up at its next
            // status checkpoint. The marker file is the same transfer cache dir
            // both processes already use, so no extra perms are required.
            hud.onPauseClick = {
                Log.i(TAG, "HUD pause clicked (run=${hud.snapshot().runId})")
                HudControlSignal.writePause(this, hud.snapshot().runId)
            }
            hud.onCancelClick = {
                Log.i(TAG, "HUD cancel clicked (run=${hud.snapshot().runId})")
                HudControlSignal.writeCancel(this, hud.snapshot().runId)
            }
        }
        refreshScreenBounds()

        // Initialize ConfigManager
        configManager = ConfigManager.getInstance(this)
        configManager.addListener(this)

        // Initialize Event System
        EventHub.init(configManager)
        TriggerRuntime.initialize(this)

        // Initialize SocketServer with ApiHandler
        val stateRepo = StateRepository(this)
        val apiHandler = ApiHandler(
            stateRepo,
            { AutoTermuxKeyboardIME.getInstance() },
            { packageManager },
            {
                try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                } catch (e: Exception) {
                    "unknown"
                }
            },
            this
        )

        actionDispatcher = ActionDispatcher(apiHandler)
        socketServer = SocketServer(apiHandler, configManager, actionDispatcher)

        isInitialized = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceDisconnectedAuditRecorded = false

        serviceInfo = buildAccessibilityServiceInfo(configManager.armed)

        applyConfiguration()

        startPeriodicUpdates()

        startSocketServerIfEnabled()
        startWebSocketServerIfEnabled()
        val keepAliveReconcileResult = KeepAliveController.reconcileBestEffort(this)
        keepAliveReconcileResult.deferredReason?.let { reason ->
            Log.w(
                TAG,
                "Deferred keep-awake reconcile during accessibility startup: $reason",
            )
        }

        Log.d(TAG, "Accessibility service connected and configured")
        emitDeviceEvent(
            EventType.ACCESSIBILITY_SERVICE_CONNECTED,
            JSONObject().apply {
                put("http_enabled", configManager.socketServerEnabled)
                put("http_port", configManager.socketServerPort)
                put("websocket_enabled", configManager.websocketEnabled)
                put("websocket_port", configManager.websocketPort)
            },
        )
        AuditLog.getInstance(this).record(
            AuditEntry.Kind.SERVICE_CONNECTED,
            "Accessibility service connected",
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!configManager.armed) return

        // User-touch detection: any click that doesn't correlate to a recent
        // Agent-injected gesture means the human took over. We drop a marker
        // in shared storage which tp-android consumes at its next checkpoint.
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            try {
                AgentGestureTagger.onAccessibilityClick(event)
            } catch (t: Throwable) {
                Log.w(TAG, "AgentGestureTagger error: ${t.message}")
            }
        }

        val eventPackage = event?.packageName?.toString() ?: ""
        val eventClassName = event?.className?.toString() ?: ""
        val previousPackage = currentPackageName
        val previousActivity = currentActivityName

        // Detect package changes
        if (eventPackage.isNotEmpty() && eventPackage != currentPackageName && currentPackageName.isNotEmpty()) {
            resetOverlayState()
        }

        if (eventPackage.isNotEmpty()) {
            currentPackageName = eventPackage
            if (previousPackage != eventPackage) {
                emitDeviceEvent(
                    EventType.FOREGROUND_APP_CHANGED,
                    JSONObject().apply {
                        put("package", eventPackage)
                        if (previousPackage.isNotEmpty()) put("previous_package", previousPackage)
                        put("event_type", accessibilityEventName(event?.eventType))
                    },
                )
            }
        }

        // Capture activity name from TYPE_WINDOW_STATE_CHANGED events
        // These events typically indicate navigation to a new activity/screen
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (eventClassName.isNotEmpty() && !eventClassName.startsWith("android.")) {
                // Filter out Android system dialogs and only keep app activities
                currentActivityName = eventClassName
                Log.d(TAG, "Activity changed: $currentActivityName")
                if (previousActivity != currentActivityName) {
                    emitDeviceEvent(
                        EventType.ACTIVITY_CHANGED,
                        JSONObject().apply {
                            put("package", currentPackageName)
                            put("activity", currentActivityName)
                            if (previousActivity.isNotEmpty()) put("previous_activity", previousActivity)
                        },
                    )
                }
            }
        }

        // Auto-accept MediaProjection dialog only while AutoTermux is actively requesting it.
        if (MediaProjectionAutoAccept.isMediaProjectionDialog(event, eventClassName) &&
            configManager.screenShareAutoAcceptEnabled &&
            AutoAcceptGate.isMediaProjectionArmed()
        ) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                try {
                    val result = MediaProjectionAutoAccept.tryAutoAccept(rootNode, eventClassName) {
                        AuditLog.getInstance(this).record(
                            AuditEntry.Kind.AUTO_ACCEPT_FIRED,
                            "MediaProjection auto-accepted",
                        )
                    }
                    if (result is MediaProjectionAutoAccept.AutoAcceptResult.Failed) {
                        showAutoAcceptFailedToastIfEnoughTimeIsPassed()
                    }
                } finally {
                    rootNode.recycle()
                }
            }
        }

        if (PackageInstallerAutoAccept.isInstallDialog(event, eventClassName) &&
            configManager.installAutoAcceptEnabled &&
            AutoAcceptGate.isInstallArmed()
        ) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                try {
                    if (PackageInstallerAutoAccept.tryAutoAccept(rootNode, eventClassName) is
                        PackageInstallerAutoAccept.AutoAcceptResult.ActionPerformed
                    ) {
                        AuditLog.getInstance(this).record(
                            AuditEntry.Kind.AUTO_ACCEPT_FIRED,
                            "APK install auto-accepted",
                        )
                    }
                } finally {
                    rootNode.recycle()
                }
            }
        }

        // Trigger update on relevant events
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                emitDeviceEvent(
                    EventType.WINDOW_STATE_CHANGED,
                    accessibilityPayload(event, eventPackage, eventClassName),
                )
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                emitDeviceEvent(
                    EventType.WINDOW_CONTENT_CHANGED,
                    accessibilityPayload(event, eventPackage, eventClassName),
                )
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                emitDeviceEvent(
                    EventType.VIEW_SCROLLED,
                    accessibilityPayload(event, eventPackage, eventClassName),
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        emitDeviceEvent(
            EventType.CONFIGURATION_CHANGED,
            JSONObject().apply {
                put("orientation", newConfig.orientation)
                put("screen_layout", newConfig.screenLayout)
            },
        )
        refreshScreenBounds()
        clearVisibleElementSnapshot()
        refreshVisibleElements()
    }

    private fun emitDeviceEvent(type: EventType, payload: JSONObject = JSONObject()) {
        EventHub.emit(DeviceEvent(type = type, payload = payload))
    }

    private fun buildAccessibilityServiceInfo(armed: Boolean): AccessibilityServiceInfo =
        AccessibilityServiceInfo().apply {
            eventTypes = if (armed) AccessibilityEvent.TYPES_ALL_MASK else 0
            packageNames = if (armed) null else emptyArray<String>()

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
            }
        }

    private fun accessibilityPayload(
        event: AccessibilityEvent?,
        eventPackage: String,
        eventClassName: String,
    ): JSONObject {
        return JSONObject().apply {
            put("event_type", accessibilityEventName(event?.eventType))
            if (eventPackage.isNotEmpty()) put("package", eventPackage)
            if (eventClassName.isNotEmpty()) put("class_name", eventClassName)
            event?.let {
                put("event_time", it.eventTime)
                put("window_id", it.windowId)
                put("content_change_types", it.contentChangeTypes)
            }
        }
    }

    private fun accessibilityEventName(eventType: Int?): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
            null -> "UNKNOWN"
            else -> eventType.toString()
        }
    }

    // Periodic update runnable
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (isInitialized && configManager.overlayVisible) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastUpdate = currentTime - lastUpdateTime

                if (timeSinceLastUpdate >= MIN_FRAME_TIME_MS) {
                    refreshVisibleElements()
                    lastUpdateTime = currentTime
                }
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private fun startPeriodicUpdates() {
        lastUpdateTime = System.currentTimeMillis()
        mainHandler.postDelayed(updateRunnable, REFRESH_INTERVAL_MS)
        Log.d(TAG, "Started periodic updates")
    }

    private fun stopPeriodicUpdates() {
        mainHandler.removeCallbacks(updateRunnable)
        Log.d(TAG, "Stopped periodic updates")
    }

    private fun refreshVisibleElements() {
        if (!isProcessing.compareAndSet(false, true)) {
            return // Already processing
        }

        try {
            if (currentPackageName.isEmpty()) {
                overlayManager.clearElements()
                overlayManager.refreshOverlay()
                clearVisibleElementSnapshot()
                return
            }

            // Get fresh elements
            val elements = getVisibleElementsInternal()

            // Update overlay if visible
            if (configManager.overlayVisible) {
                overlayManager.clearElements()

                elements.forEach { rootElement ->
                    addElementAndChildrenToOverlay(rootElement, 0)
                }

                overlayManager.refreshOverlay()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing visible elements: ${e.message}", e)
        } finally {
            isProcessing.set(false)
        }
    }

    private fun applyAutoOffset() {
        val autoOffset = overlayManager.calculateAutoOffset()
        configManager.overlayOffset = autoOffset
        overlayManager.setPositionOffsetY(autoOffset)
    }

    private fun resetOverlayState() {
        try {
            overlayManager.clearElements()
            overlayManager.refreshOverlay()
            clearVisibleElementSnapshot()
            Log.d(TAG, "Reset overlay state for package change")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting overlay state: ${e.message}", e)
        }
    }

    private fun clearElementList() {
        for (element in visibleElements) {
            try {
                element.nodeInfo.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling node: ${e.message}")
            }
        }
        visibleElements.clear()
    }

    private fun clearVisibleElementSnapshot() {
        clearElementList()
        visibleElementsSnapshotTimeMs = 0L
        visibleElementsSnapshotPackageName = ""
        visibleElementsSnapshotActivityName = ""
        visibleElementsSnapshotScreenWidth = 0
        visibleElementsSnapshotScreenHeight = 0
    }

    private fun applyConfiguration() {
        mainHandler.post {
            try {
                val config = configManager.getCurrentConfiguration()
                if (config.overlayVisible) {
                    overlayManager.showOverlay()
                } else {
                    overlayManager.hideOverlay()
                }

                // Apply offset: auto or manual
                val offsetToApply = if (config.autoOffsetEnabled) {
                    // Only calculate auto offset if it hasn't been calculated before
                    if (!config.autoOffsetCalculated) {
                        val autoOffset = overlayManager.calculateAutoOffset()
                        // Save the calculated auto offset back to ConfigManager
                        // so MainActivity can read the correct value
                        configManager.overlayOffset = autoOffset
                        // Mark that auto offset has been calculated
                        configManager.autoOffsetCalculated = true
                        Log.d(TAG, "Auto offset calculated for the first time: $autoOffset")
                        autoOffset
                    } else {
                        // Use the previously calculated/saved offset
                        val savedOffset = config.overlayOffset
                        Log.d(TAG, "Using previously calculated auto offset: $savedOffset")
                        savedOffset
                    }
                } else {
                    config.overlayOffset
                }

                overlayManager.setPositionOffsetY(offsetToApply)
            } catch (e: Exception) {
                Log.e(TAG, "Error applying configuration: ${e.message}", e)
            }
        }
    }

    // Public methods for MainActivity to call directly
    fun setOverlayVisible(visible: Boolean): Boolean {
        return try {
            configManager.overlayVisible = visible

            mainHandler.post {
                if (visible) {
                    overlayManager.showOverlay()
                    // Trigger immediate refresh when showing overlay
                    refreshVisibleElements()
                } else {
                    overlayManager.hideOverlay()
                }
            }

            Log.d(TAG, "Overlay visibility set to: $visible")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay visibility: ${e.message}", e)
            false
        }
    }

    fun isOverlayVisible(): Boolean = configManager.overlayVisible

    fun setOverlayOffset(offset: Int): Boolean {
        return try {
            configManager.overlayOffset = offset

            mainHandler.post {
                overlayManager.setPositionOffsetY(offset)
            }

            Log.d(TAG, "Overlay offset set to: $offset")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting overlay offset: ${e.message}", e)
            false
        }
    }

    fun getOverlayOffset(): Int = configManager.overlayOffset

    fun getCurrentAppliedOffset(): Int = overlayManager.getPositionOffsetY()

    fun getHudOverlay(): HudOverlay = hudOverlay

    fun getScreenBounds(): Rect = refreshScreenBounds()

    private fun refreshScreenBounds(): Rect {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val boundsChanged = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            updateScreenBounds(screenBounds, bounds.width(), bounds.height())
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            updateScreenBounds(screenBounds, metrics.widthPixels, metrics.heightPixels)
        }
        if (boundsChanged) {
            emitDeviceEvent(
                EventType.SCREEN_BOUNDS_CHANGED,
                JSONObject().apply {
                    put("width", screenBounds.width())
                    put("height", screenBounds.height())
                },
            )
        }
        return Rect(screenBounds)
    }

    fun getActionDispatcher(): ActionDispatcher = actionDispatcher

    fun launchKeepAliveRecoveryActivity(
        reason: String,
        recoveryToken: Long,
    ): Boolean {
        return try {
            val intent =
                Intent(this, KeepAliveRecoveryActivity::class.java).apply {
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(KeepAliveRecoveryActivity.EXTRA_REASON, reason)
                    putExtra(KeepAliveRecoveryActivity.EXTRA_RECOVERY_TOKEN, recoveryToken)
                }
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch keep-alive recovery activity", e)
            false
        }
    }

    fun setAutoOffsetEnabled(enabled: Boolean): Boolean {
        return try {
            if (!enabled) {
                // When disabling auto-offset, save the current applied offset
                // as the manual offset so it persists across restarts
                configManager.overlayOffset = overlayManager.getPositionOffsetY()
                // Reset the calculated flag so it will recalculate if re-enabled
                configManager.autoOffsetCalculated = false
            } else {
                // When enabling, reset the calculated flag to trigger recalculation
                configManager.autoOffsetCalculated = false
            }

            configManager.autoOffsetEnabled = enabled

            // Only recalculate when enabling auto-offset
            if (enabled) {
                mainHandler.post {
                    val autoOffset = overlayManager.calculateAutoOffset()
                    // Save the calculated auto offset back to ConfigManager
                    // so MainActivity can read the correct value
                    configManager.overlayOffset = autoOffset
                    // Mark that auto offset has been calculated
                    configManager.autoOffsetCalculated = true
                    overlayManager.setPositionOffsetY(autoOffset)
                    Log.d(TAG, "Auto offset recalculated: $autoOffset")
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting auto offset: ${e.message}", e)
            false
        }
    }

    fun isAutoOffsetEnabled(): Boolean = configManager.autoOffsetEnabled

    fun getVisibleElements(packageName: String? = null): MutableList<ElementNode> {
        return getVisibleElementsInternal(packageName?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun getVisibleElementsInternal(packageNameFilter: String? = null): MutableList<ElementNode> {
        val elements = mutableListOf<ElementNode>()
        val indexCounter = IndexCounter(1)
        val screenBoundsSnapshot = refreshScreenBounds()

        val rootCandidates = collectRootCandidatesWithRetry(packageNameFilter)
        if (rootCandidates.isEmpty()) {
            synchronized(visibleElements) {
                if (packageNameFilter == null && shouldReuseVisibleElementsSnapshot(
                        cachedElementCount = visibleElements.size,
                        snapshotTimeMs = visibleElementsSnapshotTimeMs,
                        nowMs = SystemClock.elapsedRealtime(),
                        snapshotPackageName = visibleElementsSnapshotPackageName,
                        currentPackageName = currentPackageName,
                        snapshotActivityName = visibleElementsSnapshotActivityName,
                        currentActivityName = currentActivityName,
                        snapshotScreenWidth = visibleElementsSnapshotScreenWidth,
                        currentScreenWidth = screenBoundsSnapshot.width(),
                        snapshotScreenHeight = visibleElementsSnapshotScreenHeight,
                        currentScreenHeight = screenBoundsSnapshot.height(),
                    )
                ) {
                    return visibleElements.toMutableList()
                }

                clearVisibleElementSnapshot()
                return mutableListOf()
            }
        }

        try {
            for ((rootNode, layer) in rootCandidates) {
                collectVisibleElements(rootNode, layer, null, elements, indexCounter, screenBoundsSnapshot)
            }
        } finally {
            rootCandidates.forEach { (node, _) -> node.recycle() }
        }

        synchronized(visibleElements) {
            if (packageNameFilter == null) {
                clearVisibleElementSnapshot()
                visibleElements.addAll(elements)
                visibleElementsSnapshotTimeMs = SystemClock.elapsedRealtime()
                visibleElementsSnapshotPackageName = currentPackageName
                visibleElementsSnapshotActivityName = currentActivityName
                visibleElementsSnapshotScreenWidth = screenBoundsSnapshot.width()
                visibleElementsSnapshotScreenHeight = screenBoundsSnapshot.height()
            }
        }

        return elements
    }

    private fun collectRootCandidatesWithRetry(packageNameFilter: String? = null): List<Pair<AccessibilityNodeInfo, Int>> {
        if (packageNameFilter == null) return collectRootCandidates(null)

        repeat(PACKAGE_ROOT_LOOKUP_ATTEMPTS) { attempt ->
            val candidates = collectRootCandidates(packageNameFilter)
            if (candidates.isNotEmpty()) return candidates
            if (attempt < PACKAGE_ROOT_LOOKUP_ATTEMPTS - 1) {
                SystemClock.sleep(PACKAGE_ROOT_LOOKUP_RETRY_DELAY_MS)
            }
        }
        return emptyList()
    }

    private fun collectRootCandidates(packageNameFilter: String? = null): List<Pair<AccessibilityNodeInfo, Int>> {
        val windows = try {
            windows
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to read accessibility windows: ${e.message}", e)
            null
        }
        val out = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        if (windows == null) {
            val activeRoot = try {
                rootInActiveWindow
            } catch (e: RuntimeException) {
                Log.e(TAG, "Unable to read active accessibility root: ${e.message}", e)
                null
            }
            if (activeRoot != null && rootMatchesPackage(activeRoot, packageNameFilter)) {
                out.add(activeRoot to 0)
            } else {
                activeRoot?.recycle()
            }
            return out
        }

        try {
            windows.sortedWith(
                compareBy<AccessibilityWindowInfo> { fallbackWindowTypePriority(it) }
                    .thenByDescending { it.layer }
            )
                .filter { isUserFacingWindow(it) }
                .forEach { window ->
                    val root = try {
                        window.root
                    } catch (e: RuntimeException) {
                        Log.e(
                            TAG,
                            "Unable to read accessibility window root layer=${window.layer}: ${e.message}",
                            e,
                        )
                        null
                    }
                    if (root != null) {
                        if (rootMatchesPackage(root, packageNameFilter)) {
                            out.add(root to window.layer)
                        } else {
                            root.recycle()
                        }
                    }
                }
        } finally {
            windows.forEach { it.recycle() }
        }
        return out
    }

    private fun rootMatchesPackage(
        root: AccessibilityNodeInfo,
        packageNameFilter: String?,
    ): Boolean {
        return packageNameFilter == null || root.packageName?.toString() == packageNameFilter
    }

    fun getVisibleWindowsJson(): JSONArray {
        val windows = try {
            windows
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to read accessibility windows: ${e.message}", e)
            null
        } ?: return JSONArray()

        val arr = JSONArray()
        try {
            windows.sortedByDescending { it.layer }.forEachIndexed { index, window ->
                val bounds = Rect()
                try {
                    window.getBoundsInScreen(bounds)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Unable to read accessibility window bounds: ${e.message}", e)
                }

                val root = try {
                    window.root
                } catch (e: RuntimeException) {
                    Log.e(
                        TAG,
                        "Unable to read accessibility window root layer=${window.layer}: ${e.message}",
                        e,
                    )
                    null
                }

                try {
                    arr.put(JSONObject().apply {
                        put("zIndex", index)
                        put("id", window.id)
                        put("type", window.type)
                        put("typeName", accessibilityWindowTypeName(window.type))
                        put("layer", window.layer)
                        put("isActive", window.isActive)
                        put("isFocused", window.isFocused)
                        put("title", window.title?.toString() ?: JSONObject.NULL)
                        put("packageName", root?.packageName?.toString() ?: JSONObject.NULL)
                        put("className", root?.className?.toString() ?: JSONObject.NULL)
                        put("bounds", JSONObject().apply {
                            put("left", bounds.left)
                            put("top", bounds.top)
                            put("right", bounds.right)
                            put("bottom", bounds.bottom)
                            put("width", bounds.width())
                            put("height", bounds.height())
                        })
                    })
                } finally {
                    root?.recycle()
                }
            }
        } finally {
            windows.forEach { it.recycle() }
        }
        return arr
    }

    private fun isUserFacingWindow(window: AccessibilityWindowInfo): Boolean {
        return window.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                window.type == AccessibilityWindowInfo.TYPE_SYSTEM
    }

    private fun fallbackWindowTypePriority(window: AccessibilityWindowInfo): Int {
        return when (window.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> 0
            AccessibilityWindowInfo.TYPE_SYSTEM -> 1
            else -> 2
        }
    }

    private fun accessibilityWindowTypeName(type: Int): String {
        return when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
            else -> "UNKNOWN_$type"
        }
    }

    private fun collectVisibleElements(
        node: AccessibilityNodeInfo,
        windowLayer: Int,
        parent: ElementNode?,
        rootElements: MutableList<ElementNode>,
        indexCounter: IndexCounter,
        screenBoundsSnapshot: Rect,
        depth: Int = 0,
        activeNodePath: MutableSet<AccessibilityNodeInfo> = mutableSetOf()
    ) {
        try {

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val nodeKey = AccessibilityTraversalGuard.createTraversalKey(node, rect)

            if (AccessibilityTraversalGuard.isTooDeep(depth)) {
                Log.w(
                    TAG,
                    "Skipping accessibility subtree deeper than " +
                        "${AccessibilityTraversalGuard.MAX_ACCESSIBILITY_TREE_DEPTH} levels: $nodeKey",
                )
                return
            }

            if (!AccessibilityTraversalGuard.enterActivePath(node, activeNodePath)) {
                Log.w(TAG, "Skipping cyclic accessibility node: $nodeKey")
                return
            }

            try {
                val isInScreen = Rect.intersects(rect, screenBoundsSnapshot)
                val hasSize = rect.width() > MIN_ELEMENT_SIZE && rect.height() > MIN_ELEMENT_SIZE

                var currentElement: ElementNode? = null

                if (isInScreen && hasSize) {
                    val text = node.text?.toString() ?: ""
                    val contentDesc = node.contentDescription?.toString() ?: ""
                    val className = node.className?.toString() ?: ""
                    val viewId = node.viewIdResourceName ?: ""

                    val displayText = when {
                        text.isNotEmpty() -> text
                        contentDesc.isNotEmpty() -> contentDesc
                        viewId.isNotEmpty() -> viewId.substringAfterLast('/')
                        else -> className.substringAfterLast('.')
                    }

                    val id = ElementNode.createId(rect, className.substringAfterLast('.'), displayText)

                    val nodeCopy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        AccessibilityNodeInfo(node)
                    } else {
                        @Suppress("DEPRECATION")
                        AccessibilityNodeInfo.obtain(node)
                    }
                    currentElement = ElementNode(
                        nodeCopy,
                        Rect(rect),
                        displayText,
                        className.substringAfterLast('.'),
                        windowLayer,
                        System.currentTimeMillis(),
                        id
                    )

                    // Assign unique index
                    currentElement.overlayIndex = indexCounter.getNext()

                    if (parent != null) {
                        parent.addChild(currentElement)
                    } else {
                        rootElements.add(currentElement)
                    }
                }

                // Recursively process children
                val childParent = currentElement ?: parent
                val childCount = try {
                    node.childCount
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Unable to read child count for accessibility node $nodeKey: ${e.message}", e)
                    0
                }
                for (i in 0 until childCount) {
                    val childNode = try {
                        node.getChild(i)
                    } catch (e: RuntimeException) {
                        Log.e(
                            TAG,
                            "Unable to read child accessibility node index=$i parent=$nodeKey: ${e.message}",
                            e,
                        )
                        null
                    } ?: continue

                    if (childNode === node) {
                        Log.w(TAG, "Skipping child accessibility node that references its parent: $nodeKey")
                        continue
                    }

                    if (AccessibilityTraversalGuard.isActiveNodeReference(childNode, activeNodePath)) {
                        Log.w(TAG, "Skipping child accessibility node that references an active ancestor: $nodeKey")
                        continue
                    }

                    try {
                        collectVisibleElements(
                            childNode,
                            windowLayer,
                            childParent,
                            rootElements,
                            indexCounter,
                            screenBoundsSnapshot,
                            depth + 1,
                            activeNodePath
                        )
                    } finally {
                        childNode.recycle()
                    }
                }
            } finally {
                AccessibilityTraversalGuard.leaveActivePath(node, activeNodePath)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in collectVisibleElements: ${e.message}", e)
        }
    }

    fun getPhoneState(): PhoneState {
        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(
            AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
        )
        val isEditable = focusedNode?.isEditable ?: false
        val keyboardVisible = detectKeyboardVisibility()
        val currentPackage = rootInActiveWindow?.packageName?.toString()
        val appName = getAppName(currentPackage)

        return PhoneState(
            focusedNode,
            keyboardVisible,
            currentPackage,
            appName,
            isEditable,
            currentActivityName,
        )
    }

    private fun detectKeyboardVisibility(): Boolean {
        try {
            val windows = windows
            if (windows != null) {
                val hasInputMethodWindow =
                    windows.any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                windows.forEach { it.recycle() }
                return hasInputMethodWindow
            } else {
                return false
            }
        } catch (e: Exception) {
            return false
        }
    }

    private fun getAppName(packageName: String?): String? {
        return try {
            if (packageName == null) return null

            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for package $packageName: ${e.message}")
            null
        }
    }

    // Helper class to maintain global index counter
    private class IndexCounter(private var current: Int = 1) {
        fun getNext(): Int = current++
    }

    fun getDeviceContext(): org.json.JSONObject {
        val bounds = refreshScreenBounds()
        return org.json.JSONObject().apply {
            // Screen dimensions
            put("screen_bounds", org.json.JSONObject().apply {
                put("width", bounds.width())
                put("height", bounds.height())
            })

            // Filtering parameters
            put("filtering_params", org.json.JSONObject().apply {
                put("min_element_size", MIN_ELEMENT_SIZE)
                put("overlay_offset", getOverlayOffset())
            })

            // Display metrics
            val metrics = resources.displayMetrics
            put("display_metrics", org.json.JSONObject().apply {
                put("density", metrics.density)
                put("densityDpi", metrics.densityDpi)
                put("scaledDensity", metrics.scaledDensity)
                put("widthPixels", metrics.widthPixels)
                put("heightPixels", metrics.heightPixels)
            })
        }
    }

    // Socket server management methods
    private fun startSocketServerIfEnabled() {
        if (configManager.socketServerEnabled) {
            startSocketServer()
        }
    }

    private fun startSocketServer() {
        socketServer?.let { server ->
            if (!server.isRunning()) {
                val port = configManager.socketServerPort
                val success = server.start(port)
                if (success) {
                    com.termux.autotermux.state.ConnectionStateManager.markHttpServerUp(port)
                    emitDeviceEvent(
                        EventType.LOCAL_SERVER_STARTED,
                        JSONObject().apply {
                            put("transport", "http")
                            put("port", port)
                        },
                    )
                    Log.i(TAG, "Socket server started on port $port")
                } else {
                    Log.e(TAG, "Failed to start socket server on port $port")
                }
            }
        }
    }

    private fun stopSocketServer() {
        socketServer?.let { server ->
            if (server.isRunning()) {
                server.stop()
                com.termux.autotermux.state.ConnectionStateManager.markHttpServerDown()
                emitDeviceEvent(
                    EventType.LOCAL_SERVER_STOPPED,
                    JSONObject().apply { put("transport", "http") },
                )
                Log.i(TAG, "Socket server stopped")
            }
        }
    }

    fun inputText(text: String, clear: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false

        // Strategy 1: Find focused input directly
        var targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        // Strategy 2: If no focus, try to find editable node in the tree
        if (targetNode == null) {
            // Simple DFS search for first editable node
            // Use a helper function
            targetNode = findEditableNode(root)
        }

        if (targetNode == null) return false

        try {
            // Logic to support both Replace (clear=true) and Append (clear=false).

            // Delegate logic to pure function for testability
            val currentText = targetNode.text?.toString()
            val hintText = targetNode.hintText?.toString()
            val effectiveCurrent =
                if (!hintText.isNullOrEmpty() && currentText == hintText) ""
                else currentText.orEmpty()
            val currentLength = effectiveCurrent.length
            val rawStart = targetNode.textSelectionStart
            val rawEnd = targetNode.textSelectionEnd
            val selectionStart =
                if (rawStart >= 0) rawStart.coerceIn(0, currentLength) else currentLength
            val selectionEnd =
                if (rawEnd >= 0) rawEnd.coerceIn(0, currentLength) else selectionStart
            val replaceStart = minOf(selectionStart, selectionEnd)

            val finalText = calculateInputText(
                currentText = currentText,
                hintText = hintText,
                newText = text,
                clear = clear,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
            )

            val desiredSelection =
                if (clear) {
                    finalText.length
                } else {
                    (replaceStart + text.length).coerceIn(0, finalText.length)
                }

            // Note: ACTION_SET_TEXT always replaces existing content with the argument.
            // So for clear=true, we just set 'text'.
            // For clear=false, we set 'oldText + text'.

            val arguments = android.os.Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                finalText
            )
            val setTextSuccess =
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!setTextSuccess) return false

            setSelectionOnFocusedInput(targetNode, desiredSelection)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting text via accessibility: ${e.message}")
            return false
        } finally {
            // Don't recycle targetNode if it's root (unlikely for focus) but standard practice
            // findFocus returns a node that MUST be recycled.
            // rootInActiveWindow returns a node that MUST be recycled. 
            // We should handle recycling carefully.
            try {
                if (targetNode != root) targetNode.recycle()
                root.recycle()
            } catch (e: Exception) {
            }
        }
    }

    fun deleteText(count: Int, forward: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false

        var targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (targetNode == null) {
            targetNode = findEditableNode(root)
        }

        if (targetNode == null) return false

        try {
            val currentText = targetNode.text?.toString() ?: return false
            val hintText = targetNode.hintText?.toString()
            val currentLength = currentText.length
            val rawStart = targetNode.textSelectionStart
            val rawEnd = targetNode.textSelectionEnd
            val selectionStart =
                if (rawStart >= 0) rawStart.coerceIn(0, currentLength) else currentLength
            val selectionEnd =
                if (rawEnd >= 0) rawEnd.coerceIn(0, currentLength) else selectionStart
            val replaceStart = minOf(selectionStart, selectionEnd)
            val replaceEnd = maxOf(selectionStart, selectionEnd)

            val newText = calculateDeleteText(
                currentText = currentText,
                hintText = hintText,
                count = count,
                forward = forward,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
            ) ?: return false

            val desiredSelection =
                if (replaceStart != replaceEnd) {
                    replaceStart
                } else if (forward) {
                    replaceStart
                } else {
                    maxOf(0, replaceStart - count)
                }.coerceIn(0, newText.length)

            // Avoid unnecessary churn if the delete request is effectively a noop.
            if (newText == currentText) {
                setSelectionOnFocusedInput(targetNode, desiredSelection)
                return true
            }

            val arguments = android.os.Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
            val setTextSuccess =
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!setTextSuccess) return false

            setSelectionOnFocusedInput(targetNode, desiredSelection)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting text via accessibility: ${e.message}")
            return false
        } finally {
            try {
                if (targetNode != root) targetNode.recycle()
                root.recycle()
            } catch (e: Exception) {
            }
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) {
                if (found != child) child.recycle() // Recycle intermediate if not the one
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun setSelectionOnFocusedInput(
        targetNode: AccessibilityNodeInfo,
        selection: Int
    ): Boolean {
        val args = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selection)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selection)
        }

        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)) {
            return true
        }

        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } finally {
            try {
                if (focusedNode != targetNode) focusedNode.recycle()
            } catch (_: Exception) {
            }
        }
    }

    fun getSocketServerStatus(): String {
        return socketServer?.let { server ->
            if (server.isRunning()) {
                "Running on port ${server.getPort()}"
            } else {
                "Stopped"
            }
        } ?: "Not initialized"
    }

    fun getAdbForwardCommand(): String {
        val port = configManager.socketServerPort
        return "adb forward tcp:$port tcp:$port"
    }

    // WebSocket Management
    private fun startWebSocketServerIfEnabled() {
        if (configManager.websocketEnabled) {
            startWebSocketServer()
        }
    }

    private fun startWebSocketServer() {
        try {
            if (websocketServer == null) {
                val port = configManager.websocketPort
                websocketServer = AutoTermuxWebSocketServer(
                    port,
                    actionDispatcher,
                    configManager,
                ) {
                    com.termux.autotermux.state.ConnectionStateManager.markWsServerUp(port)
                    emitDeviceEvent(
                        EventType.LOCAL_SERVER_STARTED,
                        JSONObject().apply {
                            put("transport", "websocket")
                            put("port", port)
                        },
                    )
                    showWebSocketServerStartedToastIfEnoughTimeIsPassed(port)
                    showLocalWebSocketConnectionNotificationIfEligible()
                }
                websocketServer?.start()
                Log.i(TAG, "WebSocket server started on port $port")
            }
        } catch (e: Exception) {
            hideLocalWebSocketConnectionNotification()
            Log.e(TAG, "Failed to start WebSocket server", e)
        }
    }

    private fun showWebSocketServerStartedToastIfEnoughTimeIsPassed(port: Int) {
        val now = SystemClock.elapsedRealtime()
        if (lastWebSocketServerToastAtMs == 0L ||
            now - lastWebSocketServerToastAtMs >= TOAST_DEBOUNCE_MS
        ) {
            lastWebSocketServerToastAtMs = now
            mainHandler.post {
                Toast.makeText(
                    this@AutoTermuxAccessibilityService,
                    getString(R.string.websocket_server_started, port),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun showAutoAcceptFailedToastIfEnoughTimeIsPassed() {
        val now = SystemClock.elapsedRealtime()
        if (lastAutoAcceptFailureToastAtMs == 0L ||
            now - lastAutoAcceptFailureToastAtMs >= AUTO_ACCEPT_FAILURE_TOAST_DEBOUNCE_MS
        ) {
            lastAutoAcceptFailureToastAtMs = now
            mainHandler.post {
                Toast.makeText(
                    this@AutoTermuxAccessibilityService,
                    getString(R.string.media_projection_auto_accept_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun stopWebSocketServer() {
        try {
            val wasRunning = websocketServer != null
            websocketServer?.stopSafely()
            websocketServer = null
            if (wasRunning) {
                com.termux.autotermux.state.ConnectionStateManager.markWsServerDown()
                emitDeviceEvent(
                    EventType.LOCAL_SERVER_STOPPED,
                    JSONObject().apply { put("transport", "websocket") },
                )
                Log.i(TAG, "WebSocket server stopped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping WebSocket server", e)
        } finally {
            hideLocalWebSocketConnectionNotification()
        }
    }

    fun showLocalWebSocketConnectionNotificationIfEligible() {
        if (!shouldShowLocalWebSocketConnectionNotification()) {
            hideLocalWebSocketConnectionNotification()
            return
        }

        try {
            createLocalWebSocketNotificationChannel()
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.notify(LOCAL_WS_NOTIFICATION_ID, createLocalWebSocketNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show local WS connection notification", e)
        }
    }

    fun hideLocalWebSocketConnectionNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(LOCAL_WS_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide local WS connection notification", e)
        }
    }

    private fun shouldShowLocalWebSocketConnectionNotification(): Boolean {
        if (!configManager.websocketEnabled) return false
        if (websocketServer == null) return false
        return true
    }

    private fun createLocalWebSocketNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            LOCAL_WS_NOTIFICATION_CHANNEL_ID,
            "Local Connection",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun createLocalWebSocketNotification(): Notification {
        val intent = Intent(this, com.termux.autotermux.ui.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disableIntent = Intent(this, LocalWsNotificationActionReceiver::class.java).apply {
            action = ACTION_DISABLE_LOCAL_WS_SERVER
        }
        val disablePendingIntent = PendingIntent.getBroadcast(
            this,
            3,
            disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, LOCAL_WS_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.local_connection_service_title))
            .setContentText(getString(R.string.local_connection_service_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.disable_ws_server_action),
                disablePendingIntent,
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    // ConfigManager.ConfigChangeListener implementation
    override fun onOverlayVisibilityChanged(visible: Boolean) {
        try {
            mainHandler.post {
                if (visible) {
                    overlayManager.showOverlay()
                    refreshVisibleElements()
                } else {
                    overlayManager.hideOverlay()
                }
            }
            Log.d(TAG, "Overlay visibility changed to: $visible")
        } catch (e: Exception) {
            Log.e(TAG, "Error applying overlay visibility change: ${e.message}", e)
        }
    }

    override fun onOverlayOffsetChanged(offset: Int) {
        // Already handled in setOverlayOffset method
    }

    override fun onSocketServerEnabledChanged(enabled: Boolean) {
        if (enabled) {
            startSocketServer()
        } else {
            stopSocketServer()
        }
    }

    override fun onSocketServerPortChanged(port: Int) {
        // Restart server with new port if enabled
        socketServer?.let { server ->
            val wasRunning = server.isRunning()
            if (wasRunning) {
                server.stop()
            }

            // Start server on new port if it was running or if socket server is enabled
            if (wasRunning || configManager.socketServerEnabled) {
                val success = server.start(port)
                if (success) {
                    emitDeviceEvent(
                        EventType.LOCAL_SERVER_STARTED,
                        JSONObject().apply {
                            put("transport", "http")
                            put("port", port)
                            put("restarted", true)
                        },
                    )
                    Log.i(TAG, "Socket server started on new port $port")
                } else {
                    Log.e(TAG, "Failed to start socket server on new port $port")
                }
            }
        }
    }

    override fun onWebSocketEnabledChanged(enabled: Boolean) {
        if (enabled) {
            startWebSocketServer()
        } else {
            stopWebSocketServer()
        }
    }

    override fun onWebSocketPortChanged(port: Int) {
        stopWebSocketServer()
        if (configManager.websocketEnabled) {
            startWebSocketServer()
        }
    }

    override fun onArmedChanged(armed: Boolean) {
        mainHandler.post {
            try {
                serviceInfo = buildAccessibilityServiceInfo(armed)
                Log.i(TAG, "Accessibility service armed state changed: $armed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply armed state", e)
            }
        }
    }

    fun updateSocketServerPort(port: Int): Boolean {
        if (port !in 1..65535) {
            Log.e(TAG, "Invalid port: $port")
            return false
        }

        val server = socketServer ?: return false

        // If port hasn't changed, just return true
        if (server.getPort() == port && server.isRunning()) {
            return true
        }

        val oldPort = server.getPort()
        val wasRunning = server.isRunning()

        // Stop current server
        if (wasRunning) server.stop()


        // Try to start on new port
        val success = server.start(port)

        if (success) {
            // Update config without triggering listener notification loop
            // We use the property setter which persists but doesn't notify listeners
            configManager.socketServerPort = port
            Log.i(TAG, "Successfully updated socket server port to $port")
            return true
        } else {
            Log.e(TAG, "Failed to bind to new port $port, reverting to $oldPort")
            // Revert to old port if it was running
            if (wasRunning) {
                server.start(oldPort)
            }
            return false
        }
    }

    // Screenshot functionality
    //
    // Dispatch by API level:
    //   API 30+: AccessibilityService.takeScreenshot() (fast path)
    //   API 26-29: MediaProjectionScreenshotter one-shot MediaProjection capture.
    fun takeScreenshotBase64(hideOverlay: Boolean = true): CompletableFuture<String> {
        val future = CompletableFuture<String>()

        // Temporarily hide overlay if requested
        val wasOverlayDrawingEnabled = if (hideOverlay) {
            val enabled = overlayManager.isDrawingEnabled()
            overlayManager.setDrawingEnabled(false)
            enabled
        } else {
            true
        }

        try {
            if (hideOverlay) {
                // Small delay to ensure overlay is hidden before screenshot
                mainHandler.postDelayed({
                    performScreenshotCapture(future, wasOverlayDrawingEnabled, hideOverlay)
                }, 100)
            } else {
                performScreenshotCapture(future, wasOverlayDrawingEnabled, hideOverlay)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")

            // Restore overlay drawing state in case of exception
            if (hideOverlay) {
                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
            }
        }

        return future
    }

    private fun performScreenshotCapture(
        future: CompletableFuture<String>,
        wasOverlayDrawingEnabled: Boolean,
        hideOverlay: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            performAccessibilityScreenshot(future, wasOverlayDrawingEnabled, hideOverlay)
        } else {
            performMediaProjectionScreenshot(future, wasOverlayDrawingEnabled, hideOverlay)
        }
    }

    private fun performMediaProjectionScreenshot(
        future: CompletableFuture<String>,
        wasOverlayDrawingEnabled: Boolean,
        hideOverlay: Boolean,
    ) {
        try {
            val fallback = MediaProjectionScreenshotter.getInstance(this).capture()
            fallback.whenComplete { result, error ->
                try {
                    val value = when {
                        error != null -> "error: ${error.message}"
                        result != null -> result
                        else -> "error: empty_result"
                    }
                    future.complete(value)
                } finally {
                    if (hideOverlay) {
                        overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting MediaProjection screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")
            if (hideOverlay) {
                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun performAccessibilityScreenshot(
        future: CompletableFuture<String>,
        wasOverlayDrawingEnabled: Boolean,
        hideOverlay: Boolean,
    ) {
        try {
            AccessibilityScreenshotApi30.takeScreenshot(
                service = this,
                tag = TAG,
                onSuccess = { base64 ->
                    try {
                        future.complete(base64)
                    } finally {
                        if (hideOverlay) {
                            overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                        }
                    }
                },
                onFailure = { message ->
                    try {
                        future.complete("error: $message")
                    } finally {
                        if (hideOverlay) {
                            overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
                        }
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            future.complete("error: Failed to take screenshot: ${e.message}")

            // Restore overlay drawing state in case of exception
            if (hideOverlay) {
                overlayManager.setDrawingEnabled(wasOverlayDrawingEnabled)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        emitDeviceEvent(EventType.ACCESSIBILITY_SERVICE_INTERRUPTED)
        stopPeriodicUpdates()
        stopSocketServer()
        stopWebSocketServer()
        hideLocalWebSocketConnectionNotification()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        recordServiceDisconnectedAudit()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPeriodicUpdates()
        stopSocketServer()
        stopWebSocketServer()
        hideLocalWebSocketConnectionNotification()

        clearVisibleElementSnapshot()
        configManager.removeListener(this)
        instance = null
        emitDeviceEvent(EventType.ACCESSIBILITY_SERVICE_DISCONNECTED)
        recordServiceDisconnectedAudit()
        Log.d(TAG, "Accessibility service destroyed")
    }

    private fun recordServiceDisconnectedAudit() {
        if (serviceDisconnectedAuditRecorded) return
        serviceDisconnectedAuditRecorded = true
        AuditLog.getInstance(this).record(
            AuditEntry.Kind.SERVICE_DISCONNECTED,
            "Accessibility service disconnected",
        )
    }

    private fun addElementAndChildrenToOverlay(element: ElementNode, depth: Int) {
        addElementAndChildrenToOverlay(element, depth, identityElementSet())
    }

    private fun addElementAndChildrenToOverlay(
        element: ElementNode,
        depth: Int,
        visited: MutableSet<ElementNode>
    ) {
        if (!visited.add(element)) {
            Log.w(TAG, "Skipping cyclic overlay element: ${element.redactedLogIdentifier()}")
            return
        }

        try {
            overlayManager.addElement(
                text = element.text,
                rect = element.rect,
                type = element.className,
                index = element.overlayIndex
            )

            for (child in element.children) {
                addElementAndChildrenToOverlay(child, depth + 1, visited)
            }
        } finally {
            visited.remove(element)
        }
    }

    private fun identityElementSet(): MutableSet<ElementNode> {
        return Collections.newSetFromMap(IdentityHashMap())
    }
}
