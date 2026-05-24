package com.termux.autotermux.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FingerprintBridgeActivity : FragmentActivity() {
    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SUBTITLE = "subtitle"
        private const val EXTRA_DESCRIPTION = "description"
        private const val EXTRA_CANCEL = "cancel"
        private const val SENSOR_TIMEOUT_MS = 10_000L
        private const val MAX_ATTEMPTS = 5

        private val lock = Any()
        private var pendingRequest: PendingRequest? = null

        fun request(
            context: Context,
            title: String,
            subtitle: String,
            description: String,
            cancel: String,
            timeoutMs: Long,
        ): FingerprintResult {
            val request = PendingRequest()
            synchronized(lock) {
                if (pendingRequest != null) {
                    return FingerprintResult(
                        success = false,
                        authResult = "AUTH_RESULT_UNKNOWN",
                        errors = listOf("ERROR_ALREADY_IN_PROGRESS"),
                    )
                }
                pendingRequest = request
            }

            val intent = Intent(context, FingerprintBridgeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SUBTITLE, subtitle)
                putExtra(EXTRA_DESCRIPTION, description)
                putExtra(EXTRA_CANCEL, cancel)
            }
            return try {
                context.startActivity(intent)
                if (!request.latch.await(timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)) {
                    synchronized(lock) {
                        if (pendingRequest === request) pendingRequest = null
                    }
                    FingerprintResult(
                        success = false,
                        authResult = "AUTH_RESULT_UNKNOWN",
                        errors = listOf("ERROR_TIMEOUT"),
                    )
                } else {
                    request.result ?: FingerprintResult(
                        success = false,
                        authResult = "AUTH_RESULT_UNKNOWN",
                        errors = listOf("ERROR_NO_RESULT"),
                    )
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (pendingRequest === request) pendingRequest = null
                }
                FingerprintResult(
                    success = false,
                    authResult = "AUTH_RESULT_UNKNOWN",
                    errors = listOf("ERROR_START_ACTIVITY", e.message.orEmpty()).filter(String::isNotBlank),
                )
            }
        }

        private fun complete(result: FingerprintResult) {
            synchronized(lock) {
                pendingRequest?.let {
                    it.result = result
                    it.latch.countDown()
                }
                pendingRequest = null
            }
        }
    }

    private var failedAttempts = 0
    private var completed = false
    private var biometricPrompt: BiometricPrompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authenticate()
    }

    private fun authenticate() {
        val availability = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            completeAndFinish(
                FingerprintResult(
                    success = false,
                    authResult = "AUTH_RESULT_UNKNOWN",
                    errors = listOf(fingerprintAvailabilityError(availability)),
                ),
            )
            return
        }

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val errors = mutableListOf<String>()
                    if (errorCode == BiometricPrompt.ERROR_LOCKOUT) {
                        errors.add("ERROR_LOCKOUT")
                    }
                    if (failedAttempts >= MAX_ATTEMPTS) {
                        errors.add("ERROR_TOO_MANY_FAILED_ATTEMPTS")
                    }
                    if (errors.isEmpty() && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        errors.add("ERROR_AUTHENTICATION")
                    }
                    completeAndFinish(
                        FingerprintResult(
                            success = false,
                            authResult = "AUTH_RESULT_FAILURE",
                            failedAttempts = failedAttempts,
                            errors = errors,
                        ),
                    )
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    completeAndFinish(
                        FingerprintResult(
                            success = true,
                            authResult = "AUTH_RESULT_SUCCESS",
                            failedAttempts = failedAttempts,
                        ),
                    )
                }

                override fun onAuthenticationFailed() {
                    failedAttempts += 1
                }
            },
        )

        val prompt = BiometricPrompt.PromptInfo.Builder()
            .setTitle(intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Authenticate" })
            .setNegativeButtonText(intent.getStringExtra(EXTRA_CANCEL).orEmpty().ifBlank { "Cancel" })
            .apply {
                intent.getStringExtra(EXTRA_SUBTITLE)?.takeIf(String::isNotBlank)?.let { setSubtitle(it) }
                intent.getStringExtra(EXTRA_DESCRIPTION)?.takeIf(String::isNotBlank)?.let { setDescription(it) }
            }
            .build()

        biometricPrompt?.authenticate(prompt)
        Handler(Looper.getMainLooper()).postDelayed({
            if (!completed) {
                biometricPrompt?.cancelAuthentication()
                completeAndFinish(
                    FingerprintResult(
                        success = false,
                        authResult = "AUTH_RESULT_UNKNOWN",
                        failedAttempts = failedAttempts,
                        errors = listOf("ERROR_TIMEOUT"),
                    ),
                )
            }
        }, SENSOR_TIMEOUT_MS)
    }

    private fun completeAndFinish(result: FingerprintResult) {
        if (completed) return
        completed = true
        complete(result)
        finish()
    }

    override fun onDestroy() {
        if (!completed && isFinishing) {
            complete(
                FingerprintResult(
                    success = false,
                    authResult = "AUTH_RESULT_UNKNOWN",
                    failedAttempts = failedAttempts,
                    errors = listOf("ERROR_CLOSED"),
                ),
            )
        }
        super.onDestroy()
    }

    private fun fingerprintAvailabilityError(code: Int): String =
        when (code) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "ERROR_NO_HARDWARE"
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "ERROR_NO_ENROLLED_FINGERPRINTS"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "ERROR_HW_UNAVAILABLE"
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "ERROR_SECURITY_UPDATE_REQUIRED"
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "ERROR_UNSUPPORTED_OS_VERSION"
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN -> "ERROR_UNKNOWN"
            else -> "ERROR_UNKNOWN"
        }

    private class PendingRequest {
        val latch = CountDownLatch(1)
        var result: FingerprintResult? = null
    }

    data class FingerprintResult(
        val success: Boolean,
        val authResult: String,
        val failedAttempts: Int = 0,
        val errors: List<String> = emptyList(),
    )
}
