package com.termux.autotermux.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.termux.autotermux.service.FileOperations
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StorageGetActivity : Activity() {
    companion object {
        private const val REQUEST_CODE = 1002
        private const val EXTRA_MIME = "mime"

        private val lock = Any()
        private var pendingRequest: PendingRequest? = null

        fun request(context: Context, mime: String, timeoutMs: Long): StorageResult {
            val request = PendingRequest()
            synchronized(lock) {
                if (pendingRequest != null) {
                    return StorageResult(success = false, error = "storage-get request already in progress")
                }
                pendingRequest = request
            }

            val intent = Intent(context, StorageGetActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_MIME, mime.ifBlank { "*/*" })
            }
            return try {
                context.startActivity(intent)
                if (!request.latch.await(timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)) {
                    synchronized(lock) {
                        if (pendingRequest === request) pendingRequest = null
                    }
                    StorageResult(success = false, error = "storage-get timed out")
                } else {
                    request.result ?: StorageResult(success = false, error = "storage-get returned no result")
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (pendingRequest === request) pendingRequest = null
                }
                StorageResult(success = false, error = "failed to start file picker: ${e.message}")
            }
        }

        private fun complete(result: StorageResult) {
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
        openFilePicker()
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = this@StorageGetActivity.intent.getStringExtra(EXTRA_MIME).orEmpty().ifBlank { "*/*" }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            complete(StorageResult(success = false, error = "no file picker activity available"))
            finish()
        }
    }

    @Deprecated("Deprecated Android callback is sufficient for this tiny bridge activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return

        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            complete(StorageResult(success = false, error = "file picker cancelled or failed"))
            finish()
            return
        }

        complete(copyUriToTransferCache(uri))
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            complete(StorageResult(success = false, error = "file picker closed"))
        }
        super.onDestroy()
    }

    private fun copyUriToTransferCache(uri: Uri): StorageResult {
        val displayName = displayName(uri)
        val mimeType = contentResolver.getType(uri).orEmpty()
        val extension = File(displayName).extension.ifBlank {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType).orEmpty().ifBlank { "bin" }
        }
        val input = contentResolver.openInputStream(uri)
            ?: return StorageResult(success = false, error = "failed to open selected file")
        return input.use {
            FileOperations().writeTransferCache(it, extension).fold(
                onSuccess = { cached ->
                    StorageResult(
                        success = true,
                        path = cached.path,
                        bytes = cached.bytes,
                        uri = uri.toString(),
                        displayName = displayName,
                        mimeType = mimeType,
                    )
                },
                onFailure = { error -> StorageResult(success = false, error = error.message ?: "failed to cache selected file") },
            )
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty().ifBlank { "selected-file" }
    }

    private class PendingRequest {
        val latch = CountDownLatch(1)
        var result: StorageResult? = null
    }

    data class StorageResult(
        val success: Boolean,
        val path: String = "",
        val bytes: Long = 0L,
        val uri: String = "",
        val displayName: String = "",
        val mimeType: String = "",
        val error: String = "",
    )
}
