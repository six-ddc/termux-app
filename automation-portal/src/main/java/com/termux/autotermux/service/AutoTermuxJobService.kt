package com.termux.autotermux.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.util.Log

class AutoTermuxJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val script = params.extras.getString(EXTRA_SCRIPT_PATH).orEmpty()
        if (script.isBlank()) {
            Log.w(TAG, "Scheduled job ${params.jobId} has no script path")
            return false
        }

        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_COMMAND_PATH, script)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_RUNNER, "app-shell")
            putExtra(EXTRA_COMMAND_LABEL, "AutoTermux scheduled job ${params.jobId}")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch scheduled job ${params.jobId} to Termux", e)
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val TAG = "AutoTermuxJobService"
        const val EXTRA_SCRIPT_PATH = "com.termux.autotermux.jobscheduler_script_path"

        private const val TERMUX_PACKAGE = "com.termux"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
        private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    }
}
