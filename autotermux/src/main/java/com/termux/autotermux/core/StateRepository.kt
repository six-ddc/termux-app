package com.termux.autotermux.core

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.termux.autotermux.service.AutoTermuxAccessibilityService
import com.termux.autotermux.model.ElementNode
import com.termux.autotermux.model.PhoneState
import org.json.JSONArray
import org.json.JSONObject

class StateRepository(private val service: AutoTermuxAccessibilityService?) {
    companion object {
        private const val TAG = "StateRepository"
        private const val PACKAGE_ROOT_LOOKUP_ATTEMPTS = 4
        private const val PACKAGE_ROOT_LOOKUP_RETRY_DELAY_MS = 80L
    }

    val hasAccessibilityService: Boolean
        get() = service != null

    fun getVisibleElements(packageName: String? = null): List<ElementNode> =
        service?.getVisibleElements(packageName) ?: emptyList()

    fun getFullTree(filter: Boolean, packageName: String? = null): JSONObject? {
        val svc = service ?: return null
        val normalizedPackage = packageName?.trim()?.takeIf { it.isNotEmpty() }
        val root = if (normalizedPackage != null) {
            pickRootForPackage(svc, normalizedPackage)
        } else {
            getActiveRoot(svc) ?: pickFallbackRoot(svc)
        } ?: return null
        val bounds = if (filter) svc.getScreenBounds() else null
        return AccessibilityTreeBuilder.buildFullAccessibilityTreeJson(root, bounds)
    }

    private fun getActiveRoot(svc: AutoTermuxAccessibilityService): AccessibilityNodeInfo? {
        return try {
            svc.rootInActiveWindow
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to read active accessibility root: ${e.message}", e)
            null
        }
    }

    private fun pickFallbackRoot(svc: AutoTermuxAccessibilityService): AccessibilityNodeInfo? {
        val windows = try {
            svc.windows
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to read accessibility windows: ${e.message}", e)
            null
        } ?: return null

        return try {
            windows.sortedWith(
                compareBy<AccessibilityWindowInfo> { fallbackWindowTypePriority(it) }
                    .thenByDescending { it.layer }
            )
                .asSequence()
                .filter { isUserFacingWindow(it) }
                .mapNotNull { window ->
                    try {
                        window.root
                    } catch (e: RuntimeException) {
                        Log.e(
                            TAG,
                            "Unable to read accessibility window root layer=${window.layer}: ${e.message}",
                            e,
                        )
                        null
                    }
                }
                .firstOrNull()
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun pickRootForPackage(
        svc: AutoTermuxAccessibilityService,
        packageName: String,
    ): AccessibilityNodeInfo? {
        repeat(PACKAGE_ROOT_LOOKUP_ATTEMPTS) { attempt ->
            val root = pickRootForPackageOnce(svc, packageName)
            if (root != null) return root
            if (attempt < PACKAGE_ROOT_LOOKUP_ATTEMPTS - 1) {
                SystemClock.sleep(PACKAGE_ROOT_LOOKUP_RETRY_DELAY_MS)
            }
        }
        return null
    }

    private fun pickRootForPackageOnce(
        svc: AutoTermuxAccessibilityService,
        packageName: String,
    ): AccessibilityNodeInfo? {
        val windows = try {
            svc.windows
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to read accessibility windows: ${e.message}", e)
            null
        }

        if (windows == null) {
            val activeRoot = getActiveRoot(svc)
            return if (activeRoot?.packageName?.toString() == packageName) {
                activeRoot
            } else {
                activeRoot?.let { recycleNodeQuietly(it) }
                null
            }
        }

        val unmatchedRoots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows.sortedWith(
                compareBy<AccessibilityWindowInfo> { fallbackWindowTypePriority(it) }
                    .thenByDescending { it.layer }
            )
                .asSequence()
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
                    } ?: return@forEach

                    if (root.packageName?.toString() == packageName) {
                        unmatchedRoots.forEach { recycleNodeQuietly(it) }
                        return root
                    }
                    unmatchedRoots.add(root)
                }
        } finally {
            windows.forEach { it.recycle() }
        }
        unmatchedRoots.forEach { recycleNodeQuietly(it) }
        return null
    }

    private fun recycleNodeQuietly(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: RuntimeException) {
        }
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

    fun getPhoneState(): PhoneState =
        service?.getPhoneState() ?: PhoneState(
            focusedElement = null,
            keyboardVisible = false,
            packageName = null,
            appName = null,
            isEditable = false,
            activityName = null,
        )

    fun getVisibleWindows(): JSONArray = service?.getVisibleWindowsJson() ?: JSONArray()

    fun getDeviceContext(): JSONObject = service?.getDeviceContext() ?: JSONObject()

    fun getScreenBounds(): Rect = service?.getScreenBounds() ?: Rect()

    fun setOverlayOffset(offset: Int): Boolean = service?.setOverlayOffset(offset) ?: false

    fun setOverlayVisible(visible: Boolean): Boolean = service?.setOverlayVisible(visible) ?: false

    fun isOverlayVisible(): Boolean = service?.isOverlayVisible() ?: false

    fun setAutoOffsetEnabled(enabled: Boolean): Boolean = service?.setAutoOffsetEnabled(enabled) ?: false

    fun isAutoOffsetEnabled(): Boolean = service?.isAutoOffsetEnabled() ?: false

    fun takeScreenshot(hideOverlay: Boolean): java.util.concurrent.CompletableFuture<String> {
        val liveService = service
        if (liveService != null) {
            return liveService.takeScreenshotBase64(hideOverlay)
        }
        return java.util.concurrent.CompletableFuture<String>().apply {
            completeExceptionally(IllegalStateException("Accessibility service not available"))
        }
    }

    fun updateSocketServerPort(port: Int): Boolean = service?.updateSocketServerPort(port) ?: false

    fun inputText(text: String, clear: Boolean): Boolean = service?.inputText(text, clear) ?: false
}
