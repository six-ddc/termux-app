package com.termux.autotermux.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Status HUD shown at the top of the screen while a tp-android agent run is
 * active. Renders a thin (36dp) bar with task title + step indicator on the
 * left and Pause / Cancel buttons on the right. State is pushed by the
 * `hud/update` bridge action from tp-android.
 *
 * Lives independently of [OverlayManager] (which draws element rects) — we
 * intentionally don't share state to keep these two visual concerns
 * independent. Both can be attached at the same time.
 */
class HudOverlay(private val context: Context) {

    data class State(
        val runId: String? = null,
        val task: String? = null,
        val step: Int = 0,
        val totalSteps: Int = 0,
        val stepLabel: String? = null,
        val percent: Float? = null,
        val state: String = "idle", // running / waiting / done / cancelled / error / idle
        val pendingPrompt: String? = null,
    )

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var view: FrameLayout? = null
    @Volatile private var currentState: State = State()

    /** Invoked when the user taps the Pause/Cancel buttons. */
    var onPauseClick: (() -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null

    fun isShowing(): Boolean = view != null

    fun show() {
        handler.post {
            if (view != null) return@post
            try {
                val container = buildView()
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    dp(36),
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP
                }
                wm.addView(container, lp)
                view = container
                renderState()
            } catch (t: Throwable) {
                Log.e(TAG, "HudOverlay.show failed: ${t.message}", t)
            }
        }
    }

    fun hide() {
        handler.post {
            val v = view ?: return@post
            try {
                wm.removeView(v)
            } catch (t: Throwable) {
                Log.w(TAG, "HudOverlay.hide removeView: ${t.message}")
            }
            view = null
        }
    }

    fun update(newState: State) {
        currentState = newState
        handler.post { renderState() }
    }

    fun snapshot(): State = currentState

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    private fun renderState() {
        val v = view ?: return
        val title = v.findViewWithTag<TextView>("title") ?: return
        val pause = v.findViewWithTag<TextView>("pause") ?: return
        val cancel = v.findViewWithTag<TextView>("cancel") ?: return
        val s = currentState

        val prefix = when (s.state) {
            "waiting" -> "WAIT"
            "error" -> "ERR"
            "done" -> "DONE"
            "cancelled" -> "STOP"
            "idle" -> "IDLE"
            else -> "RUN"
        }
        val taskText = s.task ?: "(no task)"
        val stepText = when {
            s.totalSteps > 0 -> " · ${s.step}/${s.totalSteps}"
            s.step > 0 -> " · step ${s.step}"
            else -> ""
        }
        val percentText = s.percent?.let { " · ${it.toInt()}%" } ?: ""
        val labelText = s.stepLabel?.let { " · $it" } ?: ""
        val promptSuffix = s.pendingPrompt?.let { " — ${it}" } ?: ""
        title.text = "$prefix  $taskText$stepText$percentText$labelText$promptSuffix"
        title.setTextColor(stateColor(s.state))

        v.background = GradientDrawable().apply {
            setColor(HUD_BG)
            setStroke(dp(1), stateColor(s.state))
        }

        pause.text = if (s.state == "waiting") ">" else "II"
        // Disable Pause when nothing is running.
        pause.alpha = if (s.state in setOf("idle", "done", "cancelled", "error")) 0.35f else 1f
    }

    private fun buildView(): FrameLayout {
        val container = FrameLayout(context).apply {
            setPadding(dp(8), 0, dp(8), 0)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val title = TextView(context).apply {
            tag = "title"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val pause = TextView(context).apply {
            tag = "pause"
            text = "II"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setPadding(dp(10), dp(2), dp(10), dp(2))
            isClickable = true
            setOnClickListener { onPauseClick?.invoke() }
        }
        val cancel = TextView(context).apply {
            tag = "cancel"
            text = "X"
            setTextColor(ERROR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setPadding(dp(10), dp(2), dp(10), dp(2))
            isClickable = true
            setOnClickListener { onCancelClick?.invoke() }
        }
        row.addView(title)
        row.addView(pause)
        row.addView(cancel)
        container.addView(row)
        return container
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private fun stateColor(state: String): Int =
        when (state) {
            "waiting" -> WARNING
            "error" -> ERROR
            "done" -> ACCENT
            "cancelled" -> TEXT_DIM
            "idle" -> TEXT_DIM
            else -> ACCENT
        }

    companion object {
        private const val TAG = "TP_HUD_OVERLAY"
        private const val ACCENT = -16004727
        private const val WARNING = -11930
        private const val ERROR = -1546148
        private const val TEXT = -322576683
        private const val TEXT_DIM = -2139515251
        private const val HUD_BG = -435614450
    }
}
