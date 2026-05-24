package com.termux.autotermux.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import com.termux.autotermux.service.AutoAcceptGate
import com.termux.autotermux.service.MediaProjectionScreenshotter
import com.termux.autotermux.service.ScreenCaptureService
import com.termux.autotermux.service.ScreenRecorderService

class ScreenCaptureActivity : Activity() {

    companion object {
        private const val REQUEST_CODE_CAPTURE_PERM = 1001
        private const val TAG = "ScreenCaptureActivity"

        const val EXTRA_MODE = "mode"
        const val MODE_SCREENSHOT = "screenshot"
        const val MODE_RECORD = "record"
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        AutoAcceptGate.armMediaProjection()
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_CODE_CAPTURE_PERM,
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CODE_CAPTURE_PERM) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }

        AutoAcceptGate.disarmMediaProjection()
        val mode = intent.getStringExtra(EXTRA_MODE)
        when (mode) {
            MODE_SCREENSHOT -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_PERMISSION_RESULT
                        putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                    }
                    startForegroundService(serviceIntent)
                } else {
                    MediaProjectionScreenshotter.getInstance(this)
                        .onPermissionDenied("permission_denied")
                }
            }
            MODE_RECORD -> {
                ScreenRecorderService.deliverConsentToService(this, resultCode, data)
            }
            else -> {
                Log.w(TAG, "Ignoring unsupported capture mode: $mode")
            }
        }
        finish()
    }
}
