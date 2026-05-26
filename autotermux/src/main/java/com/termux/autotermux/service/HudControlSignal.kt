package com.termux.autotermux.service

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Cross-process signalling for HUD button taps.
 *
 * The HUD lives in AutoTermux but the agent-run lifecycle lives in Termux's
 * `tp-android status` state under `$HOME/.termuxplus/runs/`. SELinux denies
 * direct file access between the two app contexts, so we drop a tiny marker
 * file in the shared transfer-cache directory (which both packages already
 * use). `tp-android` polls the marker at every status checkpoint.
 *
 * The marker is intentionally simple — `pause` or `cancel` plus the runId.
 * tp-android removes the file once it has acted on the signal.
 */
object HudControlSignal {

    private const val TAG = "TP_HUD_SIGNAL"
    private const val SIGNAL_DIR_NAME = ".termuxplus/tp-android-cache/hud-signals"

    private fun signalDir(): File {
        val root = Environment.getExternalStorageDirectory()
        return File(root, "Download/$SIGNAL_DIR_NAME").also {
            if (!it.exists()) {
                try {
                    it.mkdirs()
                } catch (t: Throwable) {
                    Log.w(TAG, "mkdirs failed: ${t.message}")
                }
            }
        }
    }

    private fun write(kind: String, runId: String?) {
        val target = File(signalDir(), "${runId ?: "current"}.$kind")
        try {
            target.writeText(System.currentTimeMillis().toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "write $kind signal failed: ${t.message}")
        }
    }

    fun writePause(context: Context, runId: String?) {
        write("pause", runId)
    }

    fun writeCancel(context: Context, runId: String?) {
        write("cancel", runId)
    }
}
