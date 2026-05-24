package com.termux.autotermux.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SafManageActivity : Activity() {
    companion object {
        private const val REQUEST_CODE = 1003

        private val lock = Any()
        private var pendingRequest: PendingRequest? = null

        fun request(context: Context, timeoutMs: Long): SafManageResult {
            val request = PendingRequest()
            synchronized(lock) {
                if (pendingRequest != null) {
                    return SafManageResult(success = false, error = "saf-managedir request already in progress")
                }
                pendingRequest = request
            }

            return try {
                context.startActivity(Intent(context, SafManageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (!request.latch.await(timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)) {
                    synchronized(lock) {
                        if (pendingRequest === request) pendingRequest = null
                    }
                    SafManageResult(success = false, error = "saf-managedir timed out")
                } else {
                    request.result ?: SafManageResult(success = false, error = "saf-managedir returned no result")
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (pendingRequest === request) pendingRequest = null
                }
                SafManageResult(success = false, error = "failed to start SAF directory picker: ${e.message}")
            }
        }

        private fun complete(result: SafManageResult) {
            synchronized(lock) {
                pendingRequest?.let {
                    it.result = result
                    it.latch.countDown()
                }
                pendingRequest = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            complete(SafManageResult(success = false, error = "no SAF directory picker activity available"))
            finish()
        }
    }

    @Deprecated("Deprecated Android callback is sufficient for this tiny bridge activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return

        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            complete(SafManageResult(success = false, error = "SAF directory picker cancelled or failed"))
            finish()
            return
        }

        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        contentResolver.takePersistableUriPermission(uri, flags)
        complete(SafManageResult(success = true, uri = uri.toString()))
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            complete(SafManageResult(success = false, error = "SAF directory picker closed"))
        }
        super.onDestroy()
    }

    private class PendingRequest {
        val latch = CountDownLatch(1)
        var result: SafManageResult? = null
    }

    data class SafManageResult(
        val success: Boolean,
        val uri: String = "",
        val error: String = "",
    )
}
