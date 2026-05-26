package com.termux.autotermux.service

import android.os.Environment
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Tracks coordinates of recent Agent-injected gestures so the AccessibilityService
 * can tell apart "Agent tapped this" vs "user tapped this".
 *
 * When [onAccessibilityClick] is called for a TYPE_VIEW_CLICKED event whose
 * source bounds don't overlap any recent Agent gesture, we consider the click
 * to be USER-originated and drop a marker file in the shared signal directory.
 *
 * The tp-android client (in Termux) polls that directory at every status
 * checkpoint and routes the signal back into the run state.
 */
object AgentGestureTagger {

    private const val TAG = "TP_AGENT_GESTURE"
    private const val WINDOW_MS = 400L            // gestures count as "recent" within this window
    private const val RADIUS_PX = 80              // a click near (±80px) an agent gesture counts as Agent
    private const val MAX_RECENT = 32

    private data class Stamp(val x: Int, val y: Int, val at: Long)

    private val recent = ConcurrentLinkedQueue<Stamp>()

    /** Call from GestureController right before dispatchGesture. */
    fun recordAgentTap(x: Int, y: Int) {
        recent.offer(Stamp(x, y, System.currentTimeMillis()))
        // bound the queue
        while (recent.size > MAX_RECENT) {
            recent.poll()
        }
    }

    /** Call once per accessibility click event. Returns true if it was a user touch. */
    fun onAccessibilityClick(event: AccessibilityEvent?): Boolean {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return false
        val source: AccessibilityNodeInfo = event.source ?: return false
        // Skip clicks that originate from the IME / soft keyboard. Otherwise
        // every keystroke a user types is counted as "user interrupted the
        // agent", which makes --on-user-touch pause/abort unusable. The
        // canonical signal is AccessibilityWindowInfo.TYPE_INPUT_METHOD on
        // the source node's window.
        try {
            val window = source.window
            if (window != null && window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return false
            }
        } catch (_: Throwable) {
            // ignore — fall through to bounds check
        }
        // Belt-and-braces: many IMEs land here even when window type isn't
        // reported. Filter by package-name hints too.
        val pkg = event.packageName?.toString().orEmpty().lowercase()
        if (pkg.contains("inputmethod") || pkg.endsWith(".ime") || pkg.contains("keyboard") || pkg.contains("latin")) {
            return false
        }
        val rect = android.graphics.Rect()
        source.getBoundsInScreen(rect)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val now = System.currentTimeMillis()

        var matched = false
        val it = recent.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (now - s.at > WINDOW_MS) {
                it.remove()
                continue
            }
            if (Math.abs(s.x - cx) <= RADIUS_PX && Math.abs(s.y - cy) <= RADIUS_PX) {
                matched = true
                // consume so subsequent clicks don't get falsely attributed
                it.remove()
                break
            }
        }
        if (matched) return false
        recordUserTouch(cx, cy)
        return true
    }

    private fun recordUserTouch(cx: Int, cy: Int) {
        val root = Environment.getExternalStorageDirectory()
        val dir = File(root, "Download/.termuxplus/tp-android-cache/hud-signals")
        try {
            if (!dir.exists()) dir.mkdirs()
            val marker = File(dir, "current.user-touch")
            marker.writeText("${System.currentTimeMillis()},$cx,$cy", Charsets.UTF_8)
            Log.d(TAG, "user-touch marker at ($cx,$cy)")
        } catch (t: Throwable) {
            Log.w(TAG, "user-touch marker write failed: ${t.message}")
        }
    }
}
