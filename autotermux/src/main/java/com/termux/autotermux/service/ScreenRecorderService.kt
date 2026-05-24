package com.termux.autotermux.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.termux.autotermux.ui.ScreenCaptureActivity
import java.io.File

/**
 * Lightweight foreground service that records the device screen to an MP4 file in
 * the app's cacheDir using `MediaProjection` + `MediaRecorder`.
 *
 * Bridge-mode contract: the agent receives the absolute file path; large content is
 * never streamed over RPC, the caller is expected to `adb pull` the file.
 */
class ScreenRecorderService : Service() {

    enum class State { IDLE, STARTING, RECORDING, FINISHED, ERROR }

    data class StartParams(
        val maxDurationMs: Long? = null,
        val bitRate: Int = DEFAULT_BIT_RATE,
        val frameRate: Int = DEFAULT_FRAME_RATE,
        val includeMic: Boolean = false,
    )

    data class StatusSnapshot(
        val state: State,
        val path: String?,
        val durationMs: Long,
        val error: String?,
        val bitRate: Int,
        val frameRate: Int,
        val width: Int,
        val height: Int,
    )

    data class Result(
        val ok: Boolean,
        val state: State,
        val path: String?,
        val durationMs: Long,
        val error: String?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recorderThread: HandlerThread? = null
    private var recorderHandler: Handler? = null

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null

    @Volatile private var stateInternal: State = State.IDLE
    @Volatile private var outputPath: String? = null
    @Volatile private var startedAtMs: Long = 0L
    @Volatile private var finishedAtMs: Long = 0L
    @Volatile private var errorMsg: String? = null
    @Volatile private var paramsCache: StartParams = StartParams()
    @Volatile private var widthPx: Int = 0
    @Volatile private var heightPx: Int = 0

    private val autoStopRunnable = Runnable {
        Log.i(TAG, "Auto-stop fired after max duration")
        stopInternal(reason = null)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        INSTANCE = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START_RECORDING) {
            // We were started via stopService or with a stale intent.
            stopSelf()
            return START_NOT_STICKY
        }

        // Latch the params that were primed before consent was launched.
        paramsCache = pendingParams
        stateInternal = State.STARTING
        startForegroundCompat()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            transitionToError("permission_denied")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        beginRecording(resultCode, resultData)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (stateInternal == State.RECORDING || stateInternal == State.STARTING) {
            stopInternal(reason = "service_destroyed")
        }
        mainHandler.removeCallbacks(autoStopRunnable)
        // Preserve the last-finished snapshot so post-destroy `status()` calls
        // can still report the resulting MP4 path/duration/dims.
        lastFinishedSnapshot = snapshotInternal()
        if (INSTANCE === this) INSTANCE = null
    }

    private fun beginRecording(resultCode: Int, resultData: Intent) {
        val params = paramsCache
        val (width, height, density) = resolveScreenSize(this)
        widthPx = width
        heightPx = height

        val outputDir = File(cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val outFile = File(outputDir, "recording-${System.currentTimeMillis()}.mp4")
        val outFilePath = outFile.absolutePath
        outputPath = outFilePath

        val thread = HandlerThread("ScreenRecorder").also { it.start() }
        recorderThread = thread
        val handler = Handler(thread.looper)
        recorderHandler = handler

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        handler.post {
            try {
                val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                    ?: throw IllegalStateException("projection_null")
                projection = mediaProjection

                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection stopped externally")
                        if (stateInternal == State.RECORDING || stateInternal == State.STARTING) {
                            stopInternal(reason = "projection_stopped")
                        }
                    }
                }, handler)

                val mr = newMediaRecorder()
                if (params.includeMic) {
                    mr.setAudioSource(MediaRecorder.AudioSource.MIC)
                }
                mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setOutputFile(outFilePath)
                mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                mr.setVideoSize(width, height)
                mr.setVideoEncodingBitRate(params.bitRate)
                mr.setVideoFrameRate(params.frameRate)
                if (params.includeMic) {
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                mr.prepare()
                recorder = mr

                virtualDisplay = mediaProjection.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mr.surface,
                    null,
                    handler,
                )

                mr.start()
                startedAtMs = SystemClock.elapsedRealtime()
                stateInternal = State.RECORDING

                params.maxDurationMs?.let { maxDuration ->
                    val clamped = maxDuration.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
                    mainHandler.postDelayed(autoStopRunnable, clamped)
                }

                Log.i(TAG, "Recording started -> $outFilePath (${width}x$height @ ${params.frameRate}fps, ${params.bitRate}bps)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                transitionToError(e.message ?: "start_failed")
                cleanupRecorder()
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private fun newMediaRecorder(): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
    }

    private fun stopInternal(reason: String?) {
        val handler = recorderHandler
        if (handler == null) {
            // No thread to schedule on; do cleanup inline.
            cleanupRecorderAndFinalize(reason)
            return
        }
        handler.post { cleanupRecorderAndFinalize(reason) }
    }

    private fun cleanupRecorderAndFinalize(reason: String?) {
        val wasRecording = stateInternal == State.RECORDING
        mainHandler.removeCallbacks(autoStopRunnable)
        cleanupRecorder()
        if (wasRecording && reason == null) {
            finishedAtMs = SystemClock.elapsedRealtime()
            stateInternal = State.FINISHED
            Log.i(TAG, "Recording finished -> $outputPath")
        } else if (reason != null) {
            transitionToError(reason)
        } else {
            // Not recording but asked to stop; keep state but ensure foreground is dropped.
            finishedAtMs = SystemClock.elapsedRealtime()
            if (stateInternal == State.RECORDING) {
                stateInternal = State.FINISHED
            }
        }
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanupRecorder() {
        try {
            recorder?.let { mr ->
                try {
                    mr.stop()
                } catch (_: Exception) {
                    // stop() throws if no frames produced; swallow.
                }
                try {
                    mr.reset()
                } catch (_: Exception) {
                }
                try {
                    mr.release()
                } catch (_: Exception) {
                }
            }
        } finally {
            recorder = null
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        recorderThread?.quitSafely()
        recorderThread = null
        recorderHandler = null
    }

    private fun transitionToError(reason: String) {
        stateInternal = State.ERROR
        errorMsg = reason
        finishedAtMs = SystemClock.elapsedRealtime()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        stopForeground(true)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Recording",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen recording active")
            .setContentText("AutoTermux is recording the screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun snapshotInternal(): StatusSnapshot {
        val dur = when (stateInternal) {
            State.RECORDING -> if (startedAtMs == 0L) 0L else SystemClock.elapsedRealtime() - startedAtMs
            State.FINISHED, State.ERROR ->
                if (startedAtMs == 0L) 0L else (finishedAtMs - startedAtMs).coerceAtLeast(0L)
            else -> 0L
        }
        return StatusSnapshot(
            state = stateInternal,
            path = outputPath,
            durationMs = dur,
            error = errorMsg,
            bitRate = paramsCache.bitRate,
            frameRate = paramsCache.frameRate,
            width = widthPx,
            height = heightPx,
        )
    }

    companion object {
        private const val TAG = "ScreenRecorderService"
        private const val CHANNEL_ID = "screen_recorder_channel"
        private const val NOTIFICATION_ID = 2002
        private const val CACHE_SUBDIR = "screenrec"
        private const val VIRTUAL_DISPLAY_NAME = "autotermux-screenrec"

        const val ACTION_START_RECORDING = "com.termux.autotermux.action.START_RECORDING"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val DEFAULT_BIT_RATE = 6_000_000
        const val DEFAULT_FRAME_RATE = 30
        const val DEFAULT_MAX_DURATION_MS = 60_000L
        const val MIN_DURATION_MS = 500L
        const val MAX_DURATION_MS = 10L * 60L * 1000L // 10 minutes safety cap

        @Volatile
        var INSTANCE: ScreenRecorderService? = null
            private set

        // Pending params for the next ACTION_START_RECORDING invocation. The consent
        // Activity round-trips through ScreenCaptureActivity, so we keep the start
        // arguments here until the service is actually constructed.
        @Volatile
        private var pendingParams: StartParams = StartParams()

        // Snapshot of the most recently completed (or errored) recording, retained
        // across service destruction so `status()` can still return path/duration
        // after the service stops itself.
        @Volatile
        private var lastFinishedSnapshot: StatusSnapshot? = null

        /**
         * Initiate the consent + start flow. The actual recording begins once
         * [ScreenCaptureActivity] delivers the user's consent to
         * [ScreenRecorderService.onStartCommand].
         */
        fun start(context: Context, params: StartParams): Result {
            val existing = INSTANCE
            if (existing != null) {
                val current = existing.stateInternal
                if (current == State.STARTING || current == State.RECORDING) {
                    return Result(
                        ok = false,
                        state = current,
                        path = existing.outputPath,
                        durationMs = existing.snapshotInternal().durationMs,
                        error = "already_recording",
                    )
                }
            }

            val clamped = clampParams(params)
            pendingParams = clamped
            existing?.paramsCache = clamped

            try {
                val intent = Intent(context.applicationContext, ScreenCaptureActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(ScreenCaptureActivity.EXTRA_MODE, ScreenCaptureActivity.MODE_RECORD)
                }
                context.applicationContext.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch consent activity", e)
                return Result(
                    ok = false,
                    state = State.ERROR,
                    path = null,
                    durationMs = 0L,
                    error = "consent_launch_failed: ${e.message}",
                )
            }

            existing?.stateInternal = State.STARTING
            return Result(
                ok = true,
                state = State.STARTING,
                path = null,
                durationMs = 0L,
                error = null,
            )
        }

        /**
         * Stop a currently running recording. Returns the recorded file path on
         * success.
         */
        fun stop(): Result {
            val service = INSTANCE
                ?: return Result(
                    ok = false,
                    state = State.IDLE,
                    path = null,
                    durationMs = 0L,
                    error = "not_recording",
                )
            val current = service.stateInternal
            if (current != State.RECORDING) {
                return Result(
                    ok = false,
                    state = current,
                    path = service.outputPath,
                    durationMs = service.snapshotInternal().durationMs,
                    error = if (current == State.STARTING) "still_starting" else "not_recording",
                )
            }
            service.stopInternal(reason = null)
            return Result(
                ok = true,
                state = State.FINISHED,
                path = service.outputPath,
                durationMs = service.snapshotInternal().durationMs,
                error = null,
            )
        }

        fun status(): StatusSnapshot {
            val service = INSTANCE
            if (service != null) {
                return service.snapshotInternal()
            }
            lastFinishedSnapshot?.let { return it }
            return StatusSnapshot(
                state = State.IDLE,
                path = null,
                durationMs = 0L,
                error = null,
                bitRate = pendingParams.bitRate,
                frameRate = pendingParams.frameRate,
                width = 0,
                height = 0,
            )
        }

        /**
         * Called by [ScreenCaptureActivity] after the system consent dialog returns,
         * to launch the foreground service with the projection grant.
         */
        fun deliverConsentToService(context: Context, resultCode: Int, data: Intent?) {
            val serviceIntent = Intent(context.applicationContext, ScreenRecorderService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_RESULT_CODE, resultCode)
                if (data != null) putExtra(EXTRA_RESULT_DATA, data)
            }
            context.applicationContext.startForegroundService(serviceIntent)
        }

        /**
         * Notify the service of pending params before consent is delivered. Public so
         * the consent Activity can call it; safe to call repeatedly.
         */
        fun primeParams(params: StartParams) {
            pendingParams = clampParams(params)
        }

        fun currentPendingParams(): StartParams = pendingParams

        private fun clampParams(params: StartParams): StartParams {
            val clampedDuration = params.maxDurationMs?.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            val clampedBitRate = params.bitRate.coerceIn(200_000, 50_000_000)
            val clampedFrameRate = params.frameRate.coerceIn(5, 60)
            return params.copy(
                maxDurationMs = clampedDuration,
                bitRate = clampedBitRate,
                frameRate = clampedFrameRate,
            )
        }

        /**
         * Resolve real screen size for use by both screenshot and recorder paths.
         * Returns Triple(width, height, densityDpi).
         */
        fun resolveScreenSize(context: Context): Triple<Int, Int, Int> {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            return Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }
}
