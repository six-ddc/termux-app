package com.termux.autotermux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.termux.autotermux.R
import com.termux.autotermux.ui.MainActivity

/**
 * Lifecycle-bound foreground service that runs **only while a tp-android run
 * is active**. The single sticky notification it holds raises our process
 * importance to "perceptible", which OEM battery managers (vivo / MIUI / Honor
 * / OPPO) honor and won't kill mid-run.
 *
 * Idle behaviour: not started, no notification. The OS may freeze or kill
 * AutoTermux freely when no run is in flight — `tp-android status start` is
 * what triggers re-launch + keepalive.
 *
 * Notification carries Pause and Cancel action buttons. Tapping either fires
 * a broadcast to {@link BackgroundActionReceiver} which writes the
 * corresponding hud-signal marker; the running tp-android process picks it up
 * at its next checkpoint. See {@link HudControlSignal} for the marker shape.
 */
class AutoTermuxBackgroundService : Service() {

    private var currentRunId: String? = null
    private var currentSnapshot: Snapshot = Snapshot()

    data class Snapshot(
        val runId: String? = null,
        val task: String? = null,
        val step: Int = 0,
        val totalSteps: Int = 0,
        val stepLabel: String? = null,
        val state: String = "running",
        val pendingPrompt: String? = null,
        val percent: Float? = null,
    )

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                currentSnapshot = snapshotFromIntent(intent)
                currentRunId = currentSnapshot.runId
                startForeground(NOTIF_ID, buildNotification(currentSnapshot))
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun snapshotFromIntent(intent: Intent?): Snapshot {
        if (intent == null) return currentSnapshot
        return Snapshot(
            runId = intent.getStringExtra(EXTRA_RUN_ID) ?: currentSnapshot.runId,
            task = intent.getStringExtra(EXTRA_TASK) ?: currentSnapshot.task,
            step = intent.getIntExtra(EXTRA_STEP, currentSnapshot.step),
            totalSteps = intent.getIntExtra(EXTRA_TOTAL_STEPS, currentSnapshot.totalSteps),
            stepLabel = intent.getStringExtra(EXTRA_STEP_LABEL) ?: currentSnapshot.stepLabel,
            state = intent.getStringExtra(EXTRA_STATE) ?: currentSnapshot.state,
            pendingPrompt = intent.getStringExtra(EXTRA_PENDING_PROMPT) ?: currentSnapshot.pendingPrompt,
            percent = if (intent.hasExtra(EXTRA_PERCENT)) intent.getFloatExtra(EXTRA_PERCENT, 0f) else currentSnapshot.percent,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoTermux active run",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a tp-android run is in progress; keeps the bridge alive."
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(s: Snapshot): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val titleParts = mutableListOf<String>()
        val prefix = when (s.state) {
            "waiting" -> "⏸"
            "error" -> "✗"
            "done" -> "✓"
            "cancelled" -> "◌"
            else -> "●"
        }
        titleParts += prefix
        if (s.totalSteps > 0) titleParts += "[${s.step}/${s.totalSteps}]"
        else if (s.step > 0) titleParts += "step ${s.step}"
        s.task?.let { titleParts += it }
        val title = titleParts.joinToString(" ")

        val contentBits = mutableListOf<String>()
        s.stepLabel?.let { contentBits += it }
        s.percent?.let { contentBits += "${it.toInt()}%" }
        s.pendingPrompt?.let { contentBits += "needs you: $it" }
        if (contentBits.isEmpty()) contentBits += s.state
        val content = contentBits.joinToString(" · ")

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_autotermux_bridge_24)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(false)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)

        // Pause / Cancel action buttons. Both go to BackgroundActionReceiver
        // which writes a hud-signal marker for tp-android to consume.
        val runId = s.runId
        if (runId != null && s.state !in setOf("done", "cancelled", "error")) {
            builder.addAction(
                NotificationCompat.Action(
                    0,
                    if (s.state == "waiting") "Resume" else "Pause",
                    PendingIntent.getBroadcast(
                        this,
                        runId.hashCode(),
                        Intent(this, BackgroundActionReceiver::class.java)
                            .setAction(BackgroundActionReceiver.ACTION_PAUSE)
                            .putExtra(BackgroundActionReceiver.EXTRA_RUN_ID, runId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ),
            )
            builder.addAction(
                NotificationCompat.Action(
                    0,
                    "Cancel",
                    PendingIntent.getBroadcast(
                        this,
                        runId.hashCode() + 1,
                        Intent(this, BackgroundActionReceiver::class.java)
                            .setAction(BackgroundActionReceiver.ACTION_CANCEL)
                            .putExtra(BackgroundActionReceiver.EXTRA_RUN_ID, runId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ),
            )
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "autotermux_active_run"
        const val NOTIF_ID = 7301

        const val ACTION_START = "com.termux.autotermux.background.START"
        const val ACTION_UPDATE = "com.termux.autotermux.background.UPDATE"
        const val ACTION_STOP = "com.termux.autotermux.background.STOP"

        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_TASK = "task"
        const val EXTRA_STEP = "step"
        const val EXTRA_TOTAL_STEPS = "total_steps"
        const val EXTRA_STEP_LABEL = "step_label"
        const val EXTRA_STATE = "state"
        const val EXTRA_PENDING_PROMPT = "pending_prompt"
        const val EXTRA_PERCENT = "percent"

        fun startOrUpdate(
            context: Context,
            runId: String?,
            task: String?,
            step: Int,
            totalSteps: Int,
            stepLabel: String?,
            state: String,
            pendingPrompt: String?,
            percent: Float?,
        ) {
            val intent = Intent(context, AutoTermuxBackgroundService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_RUN_ID, runId)
                .putExtra(EXTRA_TASK, task)
                .putExtra(EXTRA_STEP, step)
                .putExtra(EXTRA_TOTAL_STEPS, totalSteps)
                .putExtra(EXTRA_STEP_LABEL, stepLabel)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_PENDING_PROMPT, pendingPrompt)
            if (percent != null) intent.putExtra(EXTRA_PERCENT, percent)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Throwable) {
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AutoTermuxBackgroundService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
