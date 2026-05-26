package com.termux.autotermux.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles Pause / Cancel button taps on the active-run sticky notification
 * issued by {@link AutoTermuxBackgroundService}.
 *
 * Each tap writes a hud-signal marker file in the shared transfer-cache
 * directory; the corresponding tp-android Python process reads that marker at
 * its next status checkpoint and acts on it.
 */
class BackgroundActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        when (intent.action) {
            ACTION_PAUSE -> {
                Log.i(TAG, "Pause requested via notification (run=$runId)")
                HudControlSignal.writePause(context, runId)
            }
            ACTION_CANCEL -> {
                Log.i(TAG, "Cancel requested via notification (run=$runId)")
                HudControlSignal.writeCancel(context, runId)
            }
        }
    }

    companion object {
        private const val TAG = "TP_BG_ACTION"
        const val ACTION_PAUSE = "com.termux.autotermux.background.action.PAUSE"
        const val ACTION_CANCEL = "com.termux.autotermux.background.action.CANCEL"
        const val EXTRA_RUN_ID = "run_id"
    }
}
