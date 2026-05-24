package com.termux.autotermux.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechToTextActivity : Activity() {
    companion object {
        private const val REQUEST_CODE = 1001
        private const val EXTRA_LANGUAGE = "language"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_PARTIAL = "partial"

        private val lock = Any()
        private var pendingRequest: PendingRequest? = null

        fun request(
            context: Context,
            language: String,
            prompt: String,
            partial: Boolean,
            timeoutMs: Long,
        ): SpeechResult {
            val request = PendingRequest()
            synchronized(lock) {
                if (pendingRequest != null) {
                    return SpeechResult(success = false, error = "speech-to-text request already in progress")
                }
                pendingRequest = request
            }

            val intent = Intent(context, SpeechToTextActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_PARTIAL, partial)
            }
            return try {
                context.startActivity(intent)
                if (!request.latch.await(timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)) {
                    synchronized(lock) {
                        if (pendingRequest === request) pendingRequest = null
                    }
                    SpeechResult(success = false, error = "speech-to-text timed out")
                } else {
                    request.result ?: SpeechResult(success = false, error = "speech-to-text returned no result")
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (pendingRequest === request) pendingRequest = null
                }
                SpeechResult(success = false, error = "failed to start speech recognizer: ${e.message}")
            }
        }

        private fun complete(result: SpeechResult) {
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
        startRecognizer()
    }

    private fun startRecognizer() {
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, intent.getBooleanExtra(EXTRA_PARTIAL, false))
            intent.getStringExtra(EXTRA_LANGUAGE)
                ?.takeIf(String::isNotBlank)
                ?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            intent.getStringExtra(EXTRA_PROMPT)
                ?.takeIf(String::isNotBlank)
                ?.let { putExtra(RecognizerIntent.EXTRA_PROMPT, it) }
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(recognizerIntent, REQUEST_CODE)
        } catch (e: ActivityNotFoundException) {
            complete(SpeechResult(success = false, error = "no speech recognizer activity available"))
            finish()
        }
    }

    @Deprecated("Deprecated Android callback is sufficient for this tiny bridge activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return

        if (resultCode != RESULT_OK) {
            complete(SpeechResult(success = false, error = "speech recognizer cancelled or failed"))
            finish()
            return
        }

        val matches = data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.toList()
            .orEmpty()
        val partials = data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS)
            ?.toList()
            .orEmpty()
        complete(
            SpeechResult(
                success = true,
                text = matches.firstOrNull().orEmpty(),
                matches = matches,
                partials = partials,
                language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty().ifBlank { Locale.getDefault().toLanguageTag() },
            ),
        )
        finish()
    }

    override fun onDestroy() {
        if (isFinishing) {
            complete(SpeechResult(success = false, error = "speech recognizer closed"))
        }
        super.onDestroy()
    }

    private class PendingRequest {
        val latch = CountDownLatch(1)
        var result: SpeechResult? = null
    }

    data class SpeechResult(
        val success: Boolean,
        val text: String = "",
        val matches: List<String> = emptyList(),
        val partials: List<String> = emptyList(),
        val language: String = "",
        val error: String = "",
    )
}
