package com.termux.autotermux.api

import android.Manifest
import android.app.DownloadManager
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.nfc.NfcAdapter
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.DocumentsContract
import android.provider.Settings
import android.provider.Telephony
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.speech.tts.TextToSpeech
import android.telephony.CellInfo
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.termux.autotermux.keepalive.KeepAliveController
import com.termux.autotermux.keepalive.KeepAliveStartupException
import com.termux.autotermux.service.AutoTermuxJobService
import com.termux.autotermux.service.FileOperations
import com.termux.autotermux.service.NotificationActionReceiver
import com.termux.autotermux.service.NotificationAccessService
import com.termux.autotermux.ui.DialogBridgeActivity
import com.termux.autotermux.ui.FingerprintBridgeActivity
import com.termux.autotermux.ui.NfcBridgeActivity
import com.termux.autotermux.ui.SafManageActivity
import com.termux.autotermux.ui.SpeechToTextActivity
import com.termux.autotermux.ui.StorageGetActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TermuxApiCompat(
    private val context: Context,
    private val fileOperations: FileOperations,
) {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "termux_api_compat"
        private const val NOTIFICATION_CHANNEL_NAME = "Termux API compatibility"
        private const val DEFAULT_TIMEOUT_MS = 10000L
        private const val USB_PERMISSION_ACTION = "com.termux.autotermux.USB_PERMISSION"
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"

        @Volatile
        private var mediaPlayer: MediaPlayer? = null

        @Volatile
        private var mediaPlayerSource: String? = null

        @Volatile
        private var recorder: MediaRecorder? = null

        @Volatile
        private var recorderPath: String? = null

        @Volatile
        private var recorderStartedAtMs: Long = 0L

        val COMMANDS: List<String> = listOf(
            "api-start",
            "api-stop",
            "audio-info",
            "battery-status",
            "brightness",
            "call-log",
            "camera-info",
            "camera-photo",
            "clipboard-get",
            "clipboard-set",
            "contact-list",
            "dialog",
            "download",
            "fingerprint",
            "infrared-frequencies",
            "infrared-transmit",
            "job-scheduler",
            "keystore",
            "location",
            "media-player",
            "media-scan",
            "microphone-record",
            "nfc",
            "notification",
            "notification-channel",
            "notification-list",
            "notification-remove",
            "saf-create",
            "saf-dirs",
            "saf-ls",
            "saf-managedir",
            "saf-mkdir",
            "saf-read",
            "saf-rm",
            "saf-stat",
            "saf-write",
            "sensor",
            "share",
            "sms-inbox",
            "sms-list",
            "sms-send",
            "speech-to-text",
            "storage-get",
            "telephony-call",
            "telephony-cellinfo",
            "telephony-deviceinfo",
            "toast",
            "torch",
            "tts-engines",
            "tts-speak",
            "usb",
            "vibrate",
            "volume",
            "wallpaper",
            "wifi-connectioninfo",
            "wifi-enable",
            "wifi-scaninfo",
        )

        private val UNSUPPORTED_INTERACTIVE = emptySet<String>()

        private val UNSUPPORTED_STATEFUL_PLATFORM = emptySet<String>()
    }

    fun dispatch(command: String, params: JSONObject): ApiResponse {
        val normalized = normalizeCommand(command)
        val response = when (normalized) {
            "support", "support-matrix", "list" -> supportMatrix()
            "api-start" -> apiService(enabled = true)
            "api-stop" -> apiService(enabled = false)
            "audio-info" -> audioInfo()
            "battery-status" -> batteryStatus()
            "brightness" -> brightness(params)
            "call-log" -> callLog(params)
            "camera-info" -> cameraInfo()
            "camera-photo" -> cameraPhoto(params)
            "clipboard-get" -> ApiResponse.Error("Use tp-android clipboard get; clipboard reads require the AutoTermux keyboard path.")
            "clipboard-set" -> ApiResponse.Error("Use tp-android clipboard set.")
            "contact-list" -> contactList(params)
            "dialog" -> dialog(params)
            "download" -> download(params)
            "fingerprint" -> fingerprint(params)
            "infrared-frequencies" -> infraredFrequencies()
            "infrared-transmit" -> infraredTransmit(params)
            "job-scheduler" -> jobScheduler(params)
            "keystore" -> keystore(params)
            "location" -> location(params)
            "media-player" -> mediaPlayer(params)
            "media-scan" -> mediaScan(params)
            "microphone-record" -> microphoneRecord(params)
            "nfc" -> nfc(params)
            "notification" -> notificationPost(params)
            "notification-channel" -> notificationChannel(params)
            "notification-list" -> notificationList()
            "notification-remove" -> notificationRemove(params)
            "notification-tap", "notification-click", "notification-open" -> notificationTap(params)
            "notification-action" -> notificationAction(params)
            "notification-reply" -> notificationReply(params)
            "saf-managedir", "saf-manage-dir" -> safManageDir(params)
            "saf-dirs" -> safDirs()
            "saf-ls" -> safLs(params)
            "saf-stat" -> safStat(params)
            "saf-create" -> safCreate(params, directory = false)
            "saf-mkdir" -> safCreate(params, directory = true)
            "saf-read" -> safRead(params)
            "saf-write" -> safWrite(params)
            "saf-rm" -> safRemove(params)
            "sensor" -> sensor(params)
            "share" -> share(params)
            "sms-inbox", "sms-list" -> smsList(params)
            "sms-send" -> smsSend(params)
            "speech-to-text" -> speechToText(params)
            "storage-get" -> storageGet(params)
            "telephony-call" -> telephonyCall(params)
            "telephony-cellinfo" -> telephonyCellInfo()
            "telephony-deviceinfo" -> telephonyDeviceInfo()
            "toast" -> toast(params)
            "torch" -> torch(params)
            "tts-engines" -> ttsEngines()
            "tts-speak" -> ttsSpeak(params)
            "usb" -> usb(params)
            "vibrate" -> vibrate(params)
            "volume" -> volume(params)
            "wallpaper" -> wallpaper(params)
            "wifi-connectioninfo" -> wifiConnectionInfo()
            "wifi-enable" -> wifiEnable(params)
            "wifi-scaninfo" -> wifiScanInfo()
            "permissions-status", "permission-status" -> permissionsStatus()
            "permissions-open", "permission-open" -> permissionsOpen(params)
            in UNSUPPORTED_INTERACTIVE -> unsupported(normalized, "This termux-api command requires an interactive Activity result flow; bridge-safe support needs a dedicated AutoTermux activity.")
            in UNSUPPORTED_STATEFUL_PLATFORM -> unsupported(normalized, "This termux-api command is app-specific or platform-gated and is not bridge-safe in AutoTermux yet.")
            else -> ApiResponse.Error("Unknown termux-api command: $command")
        }
        return if (params.optBoolean("cache", false)) cacheJsonResponse(normalized, response) else response
    }

    private fun normalizeCommand(command: String): String =
        command.trim()
            .removePrefix("termux-")
            .replace('_', '-')
            .lowercase(Locale.US)

    private fun supportMatrix(): ApiResponse {
        val arr = JSONArray()
        COMMANDS.forEach { command ->
            val status = when (command) {
                in UNSUPPORTED_INTERACTIVE -> "requires_activity"
                in UNSUPPORTED_STATEFUL_PLATFORM -> "not_bridge_safe_yet"
                "clipboard-get", "clipboard-set" -> "covered_by_native_tp_android_command"
                else -> "supported"
            }
            arr.put(JSONObject().apply {
                put("command", "termux-$command")
                put("tp_command", "tp-android termux-api $command")
                put("status", status)
            })
        }
        return ApiResponse.RawArray(arr)
    }

    private fun unsupported(command: String, reason: String): ApiResponse =
        ApiResponse.RawObject(JSONObject().apply {
            put("command", "termux-$command")
            put("supported", false)
            put("reason", reason)
        })

    private fun cacheJsonResponse(source: String, response: ApiResponse): ApiResponse {
        val payload = when (response) {
            is ApiResponse.Error -> return response
            is ApiResponse.RawObject -> response.json.toString()
            is ApiResponse.RawArray -> response.json.toString()
            is ApiResponse.Success -> when (val data = response.data) {
                is JSONObject -> data.toString()
                is JSONArray -> data.toString()
                is String -> data
                else -> JSONObject.wrap(data)?.toString() ?: JSONObject.NULL.toString()
            }
            is ApiResponse.Text -> response.data
            is ApiResponse.Binary -> return ApiResponse.Error("Cannot cache binary response as JSON")
        }
        return fileOperations.writeTransferCache(payload.toByteArray(Charsets.UTF_8), "json").fold(
            onSuccess = { cached ->
                ApiResponse.RawObject(JSONObject().apply {
                    put("path", cached.path)
                    put("bytes", cached.bytes)
                    put("content_type", "application/json")
                    put("source", "termux-api/$source")
                })
            },
            onFailure = { error -> ApiResponse.Error("Failed to cache termux-api/$source: ${error.message}") },
        )
    }

    private fun apiService(enabled: Boolean): ApiResponse {
        return try {
            KeepAliveController.setEnabled(context, enabled)
            ApiResponse.RawObject(
                KeepAliveController.getMutationResultStatusJson(context, enabled).apply {
                    put("command", if (enabled) "termux-api-start" else "termux-api-stop")
                },
            )
        } catch (e: KeepAliveStartupException) {
            ApiResponse.Error("Failed to ${if (enabled) "start" else "stop"} AutoTermux API keepalive: ${e.reason}")
        } catch (e: Exception) {
            ApiResponse.Error("Failed to ${if (enabled) "start" else "stop"} AutoTermux API keepalive: ${e.message}")
        }
    }

    private fun fingerprint(params: JSONObject): ApiResponse {
        val result = FingerprintBridgeActivity.request(
            context = context,
            title = params.optString("title", "Authenticate"),
            subtitle = params.optString("subtitle", ""),
            description = params.optString("description", ""),
            cancel = params.optString("cancel", "Cancel"),
            timeoutMs = params.optLong("timeoutMs", params.optLong("timeout_ms", 30000L)),
        )
        return ApiResponse.RawObject(JSONObject().apply {
            put("errors", JSONArray(result.errors))
            put("failed_attempts", result.failedAttempts)
            put("auth_result", result.authResult)
        })
    }

    private fun nfc(params: JSONObject): ApiResponse {
        val args = apiArgs(params)
        val mode = params.optString("mode", params.optString("action", args.firstOrNull().orEmpty())).ifBlank { "noData" }
        if (mode == "noData" || mode == "status") {
            return ApiResponse.RawObject(NfcBridgeActivity.nfcStatusJson(context))
        }
        val param = params.optString("param", params.optString("read", args.drop(1).firstOrNull().orEmpty())).ifBlank {
            if (mode == "read") "short" else "text"
        }
        val result = NfcBridgeActivity.request(
            context = context,
            mode = mode,
            param = param,
            value = params.optString("value", params.optString("text", "")),
            timeoutMs = params.optLong("timeoutMs", params.optLong("timeout_ms", 300000L)),
        )
        result.json?.let { return ApiResponse.RawObject(it) }
        return if (result.success) {
            ApiResponse.RawObject(JSONObject())
        } else {
            ApiResponse.Error(result.error.ifBlank { "nfc failed" })
        }
    }

    private fun usb(params: JSONObject): ApiResponse {
        val args = apiArgs(params)
        val action = params.optString("action", params.optString("command", args.firstOrNull().orEmpty())).ifBlank {
            if (params.optBoolean("list", false)) "list" else "permission"
        }
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return ApiResponse.Error("USB service unavailable")
        return when (action) {
            "list" -> usbList(manager, detailed = params.optBoolean("detailed", params.optBoolean("detail", false)))
            "permission" -> usbPermission(manager, params)
            "open" -> usbOpen(manager, params)
            else -> ApiResponse.Error("Unknown USB action: $action")
        }
    }

    private fun usbList(manager: UsbManager, detailed: Boolean): ApiResponse {
        return if (detailed) {
            ApiResponse.RawArray(JSONArray().apply {
                manager.deviceList.values.forEach { put(usbDeviceJson(manager, it)) }
            })
        } else {
            ApiResponse.RawArray(JSONArray().apply {
                manager.deviceList.keys.sorted().forEach { put(it) }
            })
        }
    }

    private fun usbPermission(manager: UsbManager, params: JSONObject): ApiResponse {
        val device = findUsbDevice(manager, params) ?: return ApiResponse.Error("No such device.")
        if (manager.hasPermission(device)) {
            return ApiResponse.RawObject(usbDeviceJson(manager, device).put("permission", true))
        }
        if (!params.optBoolean("request", false)) {
            return ApiResponse.RawObject(usbDeviceJson(manager, device).put("permission", false))
        }
        val granted = requestUsbPermission(manager, device, params.optLong("timeoutMs", 30000L))
        return ApiResponse.RawObject(usbDeviceJson(manager, device).put("permission", granted))
    }

    private fun usbOpen(manager: UsbManager, params: JSONObject): ApiResponse {
        val device = findUsbDevice(manager, params) ?: return ApiResponse.Error("No such device.")
        if (!manager.hasPermission(device)) {
            if (params.optBoolean("request", false) && !requestUsbPermission(manager, device, params.optLong("timeoutMs", 30000L))) {
                return ApiResponse.Error("Permission denied.")
            }
            if (!manager.hasPermission(device)) return ApiResponse.Error("Permission denied.")
        }
        val connection = manager.openDevice(device) ?: return ApiResponse.Error("Open device failed.")
        val fd = connection.fileDescriptor
        connection.close()
        return ApiResponse.RawObject(usbDeviceJson(manager, device).apply {
            put("opened", true)
            put("fd", fd)
            put("fd_bridge_safe", false)
            put("reason", "USB file descriptors cannot be passed back through the JSON bridge; use list/permission from tp-android and a dedicated in-process bridge for raw USB I/O.")
        })
    }

    private fun findUsbDevice(manager: UsbManager, params: JSONObject): UsbDevice? {
        val args = apiArgs(params)
        val requestedName = params.optString("device", params.optString("name", args.firstOrNull().orEmpty()))
        if (requestedName.isNotBlank()) {
            manager.deviceList[requestedName]?.let { return it }
        }
        val vendorId = params.optString("vendorId", params.optString("vendor_id", args.getOrNull(0).orEmpty())).toIntOrNull()
        val productId = params.optString("productId", params.optString("product_id", args.getOrNull(1).orEmpty())).toIntOrNull()
        if (vendorId != null && productId != null) {
            return manager.deviceList.values.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        }
        return null
    }

    private fun requestUsbPermission(manager: UsbManager, device: UsbDevice, timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (USB_PERMISSION_ACTION == intent.action) {
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    latch.countDown()
                }
            }
        }
        val filter = IntentFilter(USB_PERMISSION_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        return try {
            val intent = Intent(USB_PERMISSION_ACTION).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
            manager.requestPermission(device, pendingIntent)
            latch.await(timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS) && granted
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun usbDeviceJson(manager: UsbManager, device: UsbDevice): JSONObject =
        JSONObject().apply {
            put("device", device.deviceName)
            put("vendor_id", device.vendorId)
            put("product_id", device.productId)
            put("device_class", device.deviceClass)
            put("device_subclass", device.deviceSubclass)
            put("device_protocol", device.deviceProtocol)
            put("permission", manager.hasPermission(device))
            runCatching { device.manufacturerName }.getOrNull()?.let { put("manufacturer", it) }
            runCatching { device.productName }.getOrNull()?.let { put("product", it) }
            runCatching { device.serialNumber }.getOrNull()?.let { put("serial", it) }
        }

    private fun jobScheduler(params: JSONObject): ApiResponse {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return ApiResponse.Error("JobScheduler service unavailable")
        return when {
            params.optBoolean("pending", false) -> ApiResponse.RawArray(pendingJobsJson(scheduler))
            params.optBoolean("cancel_all", params.optBoolean("cancelAll", false)) -> {
                val before = pendingJobsJson(scheduler)
                scheduler.cancelAll()
                ApiResponse.RawObject(JSONObject().apply {
                    put("cancelled_count", before.length())
                    put("jobs", before)
                })
            }
            params.optBoolean("cancel", false) -> {
                val jobId = jobId(params) ?: return ApiResponse.Error("Job id not passed")
                val existing = scheduler.getPendingJob(jobId)
                scheduler.cancel(jobId)
                ApiResponse.RawObject(JSONObject().apply {
                    put("job_id", jobId)
                    put("cancelled", existing != null)
                    existing?.let { put("job", jobInfoJson(it)) }
                })
            }
            else -> scheduleJob(scheduler, params)
        }
    }

    private fun scheduleJob(scheduler: JobScheduler, params: JSONObject): ApiResponse {
        val script = params.optString("script", apiArgs(params).firstOrNull().orEmpty())
        if (script.isBlank()) return ApiResponse.Error("No script path given")
        val jobId = jobId(params) ?: 0
        val extras = PersistableBundle().apply {
            putString(AutoTermuxJobService.EXTRA_SCRIPT_PATH, script)
        }
        val builder = JobInfo.Builder(jobId, ComponentName(context, AutoTermuxJobService::class.java))
            .setExtras(extras)
            .setRequiredNetworkType(networkType(params.optString("network", "any")))
            .setRequiresCharging(params.optBoolean("charging", false))
            .setPersisted(params.optBoolean("persisted", false))
            .setRequiresDeviceIdle(params.optBoolean("idle", false))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setRequiresBatteryNotLow(params.optBoolean("battery_not_low", params.optBoolean("batteryNotLow", true)))
            builder.setRequiresStorageNotLow(params.optBoolean("storage_not_low", params.optBoolean("storageNotLow", false)))
        }
        val periodMs = params.optLong("period_ms", params.optLong("periodMs", 0L))
        if (periodMs > 0L) builder.setPeriodic(periodMs)
        params.optString("trigger_content_uri", params.optString("triggerContentUri", "")).takeIf(String::isNotBlank)?.let { uri ->
            val flag = params.optInt("trigger_content_flag", params.optInt("triggerContentFlag", JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            builder.addTriggerContentUri(JobInfo.TriggerContentUri(Uri.parse(uri), flag))
        }

        val jobInfo = builder.build()
        val response = scheduler.schedule(jobInfo)
        return ApiResponse.RawObject(JSONObject().apply {
            put("schedule_response", response)
            put("job", jobInfoJson(jobInfo))
            scheduler.getPendingJob(jobId)?.let { put("pending", jobInfoJson(it)) }
        })
    }

    private fun pendingJobsJson(scheduler: JobScheduler): JSONArray =
        JSONArray().apply {
            scheduler.allPendingJobs.sortedBy { it.id }.forEach { put(jobInfoJson(it)) }
        }

    private fun jobInfoJson(jobInfo: JobInfo): JSONObject =
        JSONObject().apply {
            put("id", jobInfo.id)
            put("script", jobInfo.extras.getString(AutoTermuxJobService.EXTRA_SCRIPT_PATH))
            put("periodic", jobInfo.isPeriodic)
            if (jobInfo.isPeriodic) put("interval_ms", jobInfo.intervalMillis)
            put("charging", jobInfo.isRequireCharging)
            put("idle", jobInfo.isRequireDeviceIdle)
            put("persisted", jobInfo.isPersisted)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                put("battery_not_low", jobInfo.isRequireBatteryNotLow)
                put("storage_not_low", jobInfo.isRequireStorageNotLow)
            }
            put("network_type", jobInfo.networkType)
        }

    private fun jobId(params: JSONObject): Int? {
        if (params.has("job_id")) return params.optInt("job_id")
        if (params.has("jobId")) return params.optInt("jobId")
        return null
    }

    private fun networkType(network: String): Int =
        when (network.lowercase(Locale.US)) {
            "any" -> JobInfo.NETWORK_TYPE_ANY
            "unmetered" -> JobInfo.NETWORK_TYPE_UNMETERED
            "cellular" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) JobInfo.NETWORK_TYPE_CELLULAR else JobInfo.NETWORK_TYPE_UNMETERED
            "not_roaming", "not-roaming" -> JobInfo.NETWORK_TYPE_NOT_ROAMING
            "none" -> JobInfo.NETWORK_TYPE_NONE
            else -> JobInfo.NETWORK_TYPE_NONE
        }

    private fun keystore(params: JSONObject): ApiResponse {
        val args = apiArgs(params)
        val command = params.optString("command", params.optString("action", args.firstOrNull().orEmpty())).ifBlank { "list" }
        return try {
            when (command) {
                "list" -> keystoreList(params.optBoolean("detailed", params.optBoolean("detail", args.contains("-d"))))
                "delete" -> keystoreDelete(params.optString("alias", args.getOrNull(1).orEmpty()))
                "generate" -> keystoreGenerate(params, args)
                "sign" -> keystoreSign(params, args)
                "verify" -> keystoreVerify(params, args)
                else -> ApiResponse.Error("Unknown keystore command: $command")
            }
        } catch (e: Exception) {
            ApiResponse.Error("Keystore $command failed: ${e.message}")
        }
    }

    private fun keystoreList(detailed: Boolean): ApiResponse {
        val keyStore = loadAndroidKeyStore()
        val arr = JSONArray()
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val obj = JSONObject().put("alias", alias)
            val entry = keyStore.getEntry(alias, null)
            if (entry is KeyStore.PrivateKeyEntry) {
                obj.putPrivateKeyInfo(entry, detailed)
            }
            arr.put(obj)
        }
        return ApiResponse.RawArray(arr)
    }

    private fun JSONObject.putPrivateKeyInfo(entry: KeyStore.PrivateKeyEntry, detailed: Boolean) {
        val privateKey = entry.privateKey
        val algorithm = privateKey.algorithm
        put("algorithm", algorithm)
        runCatching {
            val keyInfo = KeyFactory.getInstance(algorithm).getKeySpec(privateKey, KeyInfo::class.java)
            put("size", keyInfo.keySize)
            put("inside_secure_hardware", keyInfo.isInsideSecureHardware)
            put("user_authentication", JSONObject().apply {
                put("required", keyInfo.isUserAuthenticationRequired)
                put("enforced_by_secure_hardware", keyInfo.isUserAuthenticationRequirementEnforcedBySecureHardware)
                @Suppress("DEPRECATION")
                val validity = keyInfo.userAuthenticationValidityDurationSeconds
                if (validity >= 0) put("validity_duration_seconds", validity)
            })
        }
        val publicKey = entry.certificate.publicKey
        if (detailed && publicKey is RSAPublicKey) {
            put("modulus", publicKey.modulus.toString(16))
            put("exponent", publicKey.publicExponent.toString(16))
        }
        if (detailed && publicKey is ECPublicKey) {
            put("x", publicKey.w.affineX.toString(16))
            put("y", publicKey.w.affineY.toString(16))
        }
    }

    private fun keystoreDelete(alias: String): ApiResponse {
        if (alias.isBlank()) return ApiResponse.Error("Missing alias")
        loadAndroidKeyStore().deleteEntry(alias)
        return ApiResponse.RawObject(JSONObject().put("deleted", true).put("alias", alias))
    }

    private fun keystoreGenerate(params: JSONObject, args: List<String>): ApiResponse {
        val alias = params.optString("alias", args.getOrNull(1).orEmpty())
        if (alias.isBlank()) return ApiResponse.Error("Missing alias")
        val algorithm = params.optString("algorithm", params.optString("alg", "RSA")).uppercase(Locale.US)
        val size = params.optInt("size", if (algorithm == KeyProperties.KEY_ALGORITHM_EC) 256 else 2048)
        val curve = params.optString("curve", when (size) {
            384 -> "secp384r1"
            521 -> "secp521r1"
            else -> "secp256r1"
        })
        val validity = params.optInt("validity", params.optInt("userValidity", 0))
        val purposes = params.optInt("purposes", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
        val digests = jsonStringList(params.opt("digests")).ifEmpty {
            listOf(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA1,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
        }
        val builder = KeyGenParameterSpec.Builder(alias, purposes).setDigests(*digests.toTypedArray())
        when (algorithm) {
            KeyProperties.KEY_ALGORITHM_RSA -> builder
                .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(size, RSAKeyGenParameterSpec.F4))
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            KeyProperties.KEY_ALGORITHM_EC -> builder.setAlgorithmParameterSpec(ECGenParameterSpec(curve))
            else -> return ApiResponse.Error("Invalid algorithm: $algorithm")
        }
        if (validity > 0) {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(validity)
        }
        KeyPairGenerator.getInstance(algorithm, ANDROID_KEYSTORE_PROVIDER).apply {
            initialize(builder.build())
            generateKeyPair()
        }
        return ApiResponse.RawObject(JSONObject().apply {
            put("generated", true)
            put("alias", alias)
            put("algorithm", algorithm)
            put("size", size)
            if (algorithm == KeyProperties.KEY_ALGORITHM_EC) put("curve", curve)
        })
    }

    private fun keystoreSign(params: JSONObject, args: List<String>): ApiResponse {
        val alias = params.optString("alias", args.getOrNull(1).orEmpty())
        val algorithm = params.optString("algorithm", args.getOrNull(2).orEmpty())
        if (alias.isBlank()) return ApiResponse.Error("Missing alias")
        if (algorithm.isBlank()) return ApiResponse.Error("Missing signature algorithm")
        val input = keystoreInputBytes(params)
        val key = loadAndroidKeyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: return ApiResponse.Error("No private key entry for alias: $alias")
        val output = Signature.getInstance(algorithm).run {
            initSign(key.privateKey as PrivateKey)
            update(input)
            sign()
        }
        return fileOperations.writeTransferCache(output, "sig").fold(
            onSuccess = { cached ->
                ApiResponse.RawObject(JSONObject().apply {
                    put("alias", alias)
                    put("algorithm", algorithm)
                    put("path", cached.path)
                    put("bytes", cached.bytes)
                    put("base64", Base64.encodeToString(output, Base64.NO_WRAP))
                    put("content_type", "application/octet-stream")
                    put("source", "termux-api/keystore/sign")
                })
            },
            onFailure = { error -> ApiResponse.Error("Failed to cache signature: ${error.message}") },
        )
    }

    private fun keystoreVerify(params: JSONObject, args: List<String>): ApiResponse {
        val alias = params.optString("alias", args.getOrNull(1).orEmpty())
        val algorithm = params.optString("algorithm", args.getOrNull(2).orEmpty())
        if (alias.isBlank()) return ApiResponse.Error("Missing alias")
        if (algorithm.isBlank()) return ApiResponse.Error("Missing signature algorithm")
        val input = keystoreInputBytes(params)
        val signatureBytes = keystoreSignatureBytes(params, args)
        val certificate = loadAndroidKeyStore().getCertificate(alias)
            ?: return ApiResponse.Error("No certificate for alias: $alias")
        val verified = Signature.getInstance(algorithm).run {
            initVerify(certificate.publicKey)
            update(input)
            verify(signatureBytes)
        }
        return ApiResponse.RawObject(JSONObject().apply {
            put("alias", alias)
            put("algorithm", algorithm)
            put("verified", verified)
        })
    }

    private fun keystoreInputBytes(params: JSONObject): ByteArray {
        val cachePath = params.optString("cachePath", params.optString("cache_path", ""))
        if (cachePath.isNotBlank()) {
            return fileOperations.resolveTransferCacheFile(cachePath).getOrThrow().readBytes()
        }
        val base64 = params.optString("dataBase64", params.optString("data_base64", ""))
        if (base64.isNotBlank()) return Base64.decode(base64, Base64.DEFAULT)
        val text = params.optString("text", params.optString("data", ""))
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun keystoreSignatureBytes(params: JSONObject, args: List<String>): ByteArray {
        val cachePath = params.optString(
            "signatureCachePath",
            params.optString("signature_cache_path", params.optString("signature", args.getOrNull(3).orEmpty())),
        )
        if (cachePath.isNotBlank()) {
            return fileOperations.resolveTransferCacheFile(cachePath).getOrThrow().readBytes()
        }
        val base64 = params.optString("signatureBase64", params.optString("signature_base64", ""))
        if (base64.isNotBlank()) return Base64.decode(base64, Base64.DEFAULT)
        throw IllegalArgumentException("Missing signature cache path or base64")
    }

    private fun loadAndroidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun requirePermission(permission: String): ApiResponse.Error? =
        if (hasPermission(permission)) {
            null
        } else {
            ApiResponse.Error("Missing permission: $permission. Grant it with: adb shell pm grant ${context.packageName} $permission")
        }

    private fun requireAnyPermission(vararg permissions: String): ApiResponse.Error? =
        if (permissions.any { hasPermission(it) }) {
            null
        } else {
            ApiResponse.Error(
                "Missing one of permissions: ${permissions.joinToString()}. Grant with adb shell pm grant ${context.packageName} <permission>",
            )
        }

    private fun permissionsStatus(): ApiResponse {
        val dangerous = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            "com.termux.permission.RUN_COMMAND",
        )
        val permissions = JSONArray()
        dangerous.forEach { permission ->
            permissions.put(JSONObject().apply {
                put("name", permission)
                put("granted", hasPermission(permission))
                put("grant_command", "adb shell pm grant ${context.packageName} $permission")
            })
        }
        val notificationListener = ComponentName(context, NotificationAccessService::class.java)
        val enabledNotificationListeners =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val accessibilityService = ComponentName(
            context,
            "com.termux.autotermux.service.AutoTermuxAccessibilityService",
        )
        val enabledAccessibilityServices =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()

        return ApiResponse.RawObject(JSONObject().apply {
            put("package", context.packageName)
            put("permissions", permissions)
            put("special_access", JSONObject().apply {
                put("notification_listener", JSONObject().apply {
                    put("granted", enabledNotificationListeners.contains(notificationListener.flattenToString()))
                    put("settings_action", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                })
                put("write_settings", JSONObject().apply {
                    put("granted", Settings.System.canWrite(context))
                    put("settings_action", Settings.ACTION_MANAGE_WRITE_SETTINGS)
                })
                put("manage_external_storage", JSONObject().apply {
                    put("granted", Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
                    put("settings_action", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    } else {
                        JSONObject.NULL
                    })
                })
                put("accessibility_service", JSONObject().apply {
                    put("granted", enabledAccessibilityServices.contains(accessibilityService.flattenToString()))
                    put("settings_action", Settings.ACTION_ACCESSIBILITY_SETTINGS)
                })
            })
        })
    }

    private fun permissionsOpen(params: JSONObject): ApiResponse {
        val target = params.optString("target", "app").lowercase(Locale.US).replace("_", "-")
        val intent = when (target) {
            "notification", "notifications", "notification-listener" ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "write-settings", "brightness" ->
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            "manage-external-storage", "all-files", "files" ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                }
            "accessibility", "a11y" ->
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            else ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return ApiResponse.RawObject(JSONObject().apply {
            put("target", target)
            put("opened", true)
            put("action", intent.action ?: "")
        })
    }

    private fun batteryStatus(): ApiResponse {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: Intent()
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return ApiResponse.RawObject(JSONObject().apply {
            put("present", batteryStatus.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false))
            put("technology", batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: JSONObject.NULL)
            put("health", batteryHealthName(batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)))
            put("plugged", batteryPluggedName(batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)))
            put("status", batteryStatusName(batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)))
            put("temperature", batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) / 10.0)
            put("voltage", batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
            put("level", level)
            put("scale", scale)
            if (scale > 0 && level >= 0) put("percentage", (level * 100.0 / scale).toInt())
            bm?.let {
                putBatteryIntIfSet(this, "current", it, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                putBatteryIntIfSet(this, "current_average", it, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                putBatteryIntIfSet(this, "charge_counter", it, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                putBatteryLongIfSet(this, "energy", it, BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                putBatteryIntIfSet(this, "capacity", it, BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val cycle = batteryStatus.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
                if (cycle >= 0) put("cycle", cycle)
            }
        })
    }

    private fun putBatteryIntIfSet(obj: JSONObject, name: String, manager: BatteryManager, property: Int) {
        val value = manager.getIntProperty(property)
        if (value != Int.MIN_VALUE) obj.put(name, value)
    }

    private fun putBatteryLongIfSet(obj: JSONObject, name: String, manager: BatteryManager, property: Int) {
        val value = manager.getLongProperty(property)
        if (value != Long.MIN_VALUE) obj.put(name, value)
    }

    private fun batteryHealthName(value: Int): String = when (value) {
        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
        BatteryManager.BATTERY_HEALTH_UNKNOWN -> "UNKNOWN"
        else -> value.toString()
    }

    private fun batteryPluggedName(value: Int): String = when (value) {
        0 -> "UNPLUGGED"
        BatteryManager.BATTERY_PLUGGED_AC -> "PLUGGED_AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "PLUGGED_USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "PLUGGED_WIRELESS"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "PLUGGED_DOCK"
        else -> "PLUGGED_$value"
    }

    private fun batteryStatusName(value: Int): String = when (value) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
        else -> "UNKNOWN"
    }

    private fun audioInfo(): ApiResponse {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = JSONArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.getDevices(AudioManager.GET_DEVICES_ALL).forEach { device ->
                devices.put(JSONObject().apply {
                    put("id", device.id)
                    put("type", audioDeviceTypeName(device.type))
                    put("isSink", device.isSink)
                    put("isSource", device.isSource)
                    put("productName", device.productName?.toString() ?: "")
                })
            }
        }
        return ApiResponse.RawObject(JSONObject().apply {
            put("mode", manager.mode)
            put("ringer_mode", manager.ringerMode)
            put("is_music_active", manager.isMusicActive)
            put("is_speakerphone_on", manager.isSpeakerphoneOn)
            put("is_bluetooth_sco_on", manager.isBluetoothScoOn)
            put("devices", devices)
        })
    }

    private fun audioDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        else -> type.toString()
    }

    private fun volume(params: JSONObject): ApiResponse {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamName = params.optString("stream", "")
        val value = if (params.has("volume")) params.optInt("volume") else null
        if (streamName.isNotBlank()) {
            val stream = audioStream(streamName) ?: return ApiResponse.Error("Unknown audio stream: $streamName")
            if (value != null) manager.setStreamVolume(stream, value.coerceIn(0, manager.getStreamMaxVolume(stream)), 0)
            return ApiResponse.RawObject(volumeForStream(manager, streamName, stream))
        }
        val arr = JSONArray()
        mapOf(
            "music" to AudioManager.STREAM_MUSIC,
            "alarm" to AudioManager.STREAM_ALARM,
            "notification" to AudioManager.STREAM_NOTIFICATION,
            "ring" to AudioManager.STREAM_RING,
            "system" to AudioManager.STREAM_SYSTEM,
            "voice_call" to AudioManager.STREAM_VOICE_CALL,
        ).forEach { (name, stream) -> arr.put(volumeForStream(manager, name, stream)) }
        return ApiResponse.RawArray(arr)
    }

    private fun audioStream(name: String): Int? = when (name.lowercase(Locale.US).replace("-", "_")) {
        "music" -> AudioManager.STREAM_MUSIC
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "ring" -> AudioManager.STREAM_RING
        "system" -> AudioManager.STREAM_SYSTEM
        "voice_call", "call" -> AudioManager.STREAM_VOICE_CALL
        else -> null
    }

    private fun volumeForStream(manager: AudioManager, name: String, stream: Int): JSONObject =
        JSONObject().apply {
            put("stream", name)
            put("volume", manager.getStreamVolume(stream))
            put("max_volume", manager.getStreamMaxVolume(stream))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                put("min_volume", manager.getStreamMinVolume(stream))
            }
        }

    private fun brightness(params: JSONObject): ApiResponse {
        if (!Settings.System.canWrite(context)) {
            return ApiResponse.Error("WRITE_SETTINGS not granted. Open: adb shell am start -a android.settings.action.MANAGE_WRITE_SETTINGS -d package:${context.packageName}")
        }
        val resolver = context.contentResolver
        if (params.has("brightness")) {
            val value = params.optInt("brightness").coerceIn(0, 255)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }
        if (params.optBoolean("auto", false)) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        }
        return ApiResponse.RawObject(JSONObject().apply {
            put("brightness", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1))
            put("mode", Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1))
        })
    }

    private fun callLog(params: JSONObject): ApiResponse {
        requirePermission(Manifest.permission.READ_CALL_LOG)?.let { return it }
        val limit = params.optInt("limit", 10).coerceIn(1, 1000)
        val offset = params.optInt("offset", 0).coerceAtLeast(0)
        // Android 11+ rejects "LIMIT" in the sortOrder string ("Invalid token
        // LIMIT"), so we pass the Bundle-based query args. BUT vendors (vivo,
        // observed) sometimes ignore QUERY_ARG_LIMIT entirely and return every
        // call-log row, which then blows past the Binder ~1MB transaction
        // limit on the response. Defensively cap server-side too: break out
        // of the cursor once we have collected `limit` rows after `offset`.
        // Only request the columns we map, never `null` projection.
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NEW,
        )
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${CallLog.Calls.DATE} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }
        return try {
            val arr = JSONArray()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                queryArgs,
                null,
            )?.use { cursor ->
                var skipped = 0
                while (cursor.moveToNext()) {
                    if (skipped < offset) {
                        skipped++
                        continue
                    }
                    if (arr.length() >= limit) break
                    arr.put(
                        JSONObject().apply {
                            put("id", cursor.getLongOrNull(CallLog.Calls._ID))
                            put("number", cursor.getStringOrNull(CallLog.Calls.NUMBER) ?: "")
                            put("name", cursor.getStringOrNull(CallLog.Calls.CACHED_NAME) ?: JSONObject.NULL)
                            put("type", callTypeName(cursor.getIntOrNull(CallLog.Calls.TYPE) ?: -1))
                            put("date", cursor.getLongOrNull(CallLog.Calls.DATE))
                            put("duration", cursor.getLongOrNull(CallLog.Calls.DURATION))
                            put("new", cursor.getIntOrNull(CallLog.Calls.NEW) == 1)
                        },
                    )
                }
                ApiResponse.RawArray(arr)
            } ?: ApiResponse.Error("Content provider unavailable: ${CallLog.Calls.CONTENT_URI}")
        } catch (e: SecurityException) {
            ApiResponse.Error("Permission denied: ${e.message}")
        } catch (e: Exception) {
            ApiResponse.Error("Query failed: ${e.message}")
        }
    }

    private fun callTypeName(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
        CallLog.Calls.MISSED_TYPE -> "MISSED"
        CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
        CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
        CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED_EXTERNALLY"
        else -> type.toString()
    }

    private fun contactList(params: JSONObject): ApiResponse {
        requirePermission(Manifest.permission.READ_CONTACTS)?.let { return it }
        val limit = params.optInt("limit", 1000).coerceIn(1, 10000)
        val arr = JSONArray()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && arr.length() < limit) {
                val type = cursor.getIntOrNull(ContactsContract.CommonDataKinds.Phone.TYPE) ?: 0
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                    context.resources,
                    type,
                    cursor.getStringOrNull(ContactsContract.CommonDataKinds.Phone.LABEL),
                )
                arr.put(JSONObject().apply {
                    put("contact_id", cursor.getLongOrNull(ContactsContract.CommonDataKinds.Phone.CONTACT_ID))
                    put("name", cursor.getStringOrNull(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME) ?: "")
                    put("number", cursor.getStringOrNull(ContactsContract.CommonDataKinds.Phone.NUMBER) ?: "")
                    put("type", label?.toString() ?: "")
                })
            }
        } ?: return ApiResponse.Error("Contacts provider unavailable")
        return ApiResponse.RawArray(arr)
    }

    private fun smsList(params: JSONObject): ApiResponse {
        requirePermission(Manifest.permission.READ_SMS)?.let { return it }
        val limit = params.optInt("limit", 10).coerceIn(1, 5000)
        val offset = params.optInt("offset", 0).coerceAtLeast(0)
        val typeName = smsTypeParamName(params.opt("type") ?: params.opt("box") ?: "inbox")
        val from = params.optString("from", "").takeIf { it.isNotBlank() }
        val uri = smsUri(typeName)
        val selectionParts = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()
        val messageSelection = params.optString("message-selection", params.optString("messageSelection", ""))
        if (messageSelection.isNotBlank()) {
            selectionParts += "($messageSelection)"
        } else if (from != null) {
            selectionParts += "${Telephony.TextBasedSmsColumns.ADDRESS} LIKE ?"
            selectionArgs += "%$from%"
        }
        val requestedSort = params.optString("message-sort-order", params.optString("messageSortOrder", ""))
        val sortOrder = "${requestedSort.ifBlank { "${Telephony.TextBasedSmsColumns.DATE} DESC" }} LIMIT $limit OFFSET $offset"
        if (params.optBoolean("conversation-list", params.optBoolean("conversationList", false))) {
            val conversationSelection = params.optString("conversation-selection", params.optString("conversationSelection", ""))
            val conversationSort = params.optString("conversation-sort-order", params.optString("conversationSortOrder", ""))
            return smsConversationList(
                params,
                uri,
                if (conversationSelection.isNotBlank()) listOf("($conversationSelection)") else selectionParts,
                if (conversationSelection.isNotBlank()) emptyList() else selectionArgs,
                "${conversationSort.ifBlank { requestedSort.ifBlank { "${Telephony.TextBasedSmsColumns.DATE} DESC" } }} LIMIT 5000",
            )
        }
        return cursorArray(
            uri,
            null,
            selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
            sortOrder,
        ) { cursor ->
            smsCursorJson(cursor)
        }
    }

    private fun smsConversationList(
        params: JSONObject,
        uri: Uri,
        selectionParts: List<String>,
        selectionArgs: List<String>,
        sortOrder: String,
    ): ApiResponse {
        val conversationLimit = params.optInt("conversation-limit", params.optInt("conversationLimit", 10)).coerceIn(1, 1000)
        val conversationOffset = params.optInt("conversation-offset", params.optInt("conversationOffset", 0)).coerceAtLeast(0)
        val returnMultiple = params.optBoolean(
            "conversation-return-multiple-messages",
            params.optBoolean("conversationReturnMultipleMessages", false),
        )
        val nested = params.optBoolean(
            "conversation-return-nested-view",
            params.optBoolean("conversationReturnNestedView", false),
        )
        val groups = linkedMapOf<Long, MutableList<JSONObject>>()
        val queryResponse = cursorArray(
            uri,
            null,
            selectionParts.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
            selectionArgs.takeIf { it.isNotEmpty() }?.toTypedArray(),
            sortOrder,
        ) { cursor ->
            val message = smsCursorJson(cursor)
            val threadId = message.optLong("thread_id")
            val list = groups.getOrPut(threadId) { mutableListOf() }
            if (returnMultiple || list.isEmpty()) list.add(message)
            message
        }
        if (queryResponse is ApiResponse.Error) return queryResponse
        val selected = groups.entries.drop(conversationOffset).take(conversationLimit)
        return if (nested) {
            ApiResponse.RawObject(JSONObject().apply {
                selected.forEach { (threadId, messages) ->
                    put(threadId.toString(), JSONArray().apply { messages.forEach(::put) })
                }
            })
        } else {
            ApiResponse.RawArray(JSONArray().apply {
                selected.forEach { (threadId, messages) ->
                    put(JSONObject().apply {
                        put("thread_id", threadId)
                        put("count", messages.size)
                        put("messages", JSONArray().apply { messages.forEach(::put) })
                    })
                }
            })
        }
    }

    private fun smsCursorJson(cursor: Cursor): JSONObject =
        JSONObject().apply {
            put("id", cursor.getLongOrNull("_id"))
            put("thread_id", cursor.getLongOrNull(Telephony.TextBasedSmsColumns.THREAD_ID))
            put("address", cursor.getStringOrNull(Telephony.TextBasedSmsColumns.ADDRESS) ?: "")
            put("person", cursor.getStringOrNull(Telephony.TextBasedSmsColumns.PERSON) ?: JSONObject.NULL)
            put("date", cursor.getLongOrNull(Telephony.TextBasedSmsColumns.DATE))
            put("date_sent", cursor.getLongOrNull(Telephony.TextBasedSmsColumns.DATE_SENT))
            put("read", cursor.getIntOrNull(Telephony.TextBasedSmsColumns.READ) == 1)
            put("seen", cursor.getIntOrNull(Telephony.TextBasedSmsColumns.SEEN) == 1)
            put("status", cursor.getIntOrNull(Telephony.TextBasedSmsColumns.STATUS))
            put("type", smsTypeName(cursor.getIntOrNull(Telephony.TextBasedSmsColumns.TYPE) ?: 0))
            put("body", cursor.getStringOrNull(Telephony.TextBasedSmsColumns.BODY) ?: "")
        }

    private fun smsUri(typeName: String): Uri = when (typeName.lowercase(Locale.US)) {
        "all" -> Telephony.Sms.CONTENT_URI
        "sent" -> Telephony.Sms.Sent.CONTENT_URI
        "draft" -> Telephony.Sms.Draft.CONTENT_URI
        "outbox" -> Telephony.Sms.Outbox.CONTENT_URI
        "failed" -> Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "failed")
        "queued" -> Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, "queued")
        else -> Telephony.Sms.Inbox.CONTENT_URI
    }

    private fun smsTypeParamName(value: Any?): String {
        if (value is Number) {
            return when (value.toInt()) {
                Telephony.TextBasedSmsColumns.MESSAGE_TYPE_ALL -> "all"
                Telephony.TextBasedSmsColumns.MESSAGE_TYPE_INBOX -> "inbox"
                Telephony.TextBasedSmsColumns.MESSAGE_TYPE_SENT -> "sent"
                Telephony.TextBasedSmsColumns.MESSAGE_TYPE_DRAFT -> "draft"
                Telephony.TextBasedSmsColumns.MESSAGE_TYPE_OUTBOX -> "outbox"
                Telephony.TextBasedSmsColumns.MESSAGE_TYPE_FAILED -> "failed"
                Telephony.TextBasedSmsColumns.MESSAGE_TYPE_QUEUED -> "queued"
                else -> "inbox"
            }
        }
        return value?.toString().orEmpty().ifBlank { "inbox" }
    }

    private fun smsTypeName(type: Int): String = when (type) {
        Telephony.TextBasedSmsColumns.MESSAGE_TYPE_ALL -> "ALL"
        Telephony.TextBasedSmsColumns.MESSAGE_TYPE_INBOX -> "INBOX"
        Telephony.TextBasedSmsColumns.MESSAGE_TYPE_SENT -> "SENT"
        Telephony.TextBasedSmsColumns.MESSAGE_TYPE_DRAFT -> "DRAFT"
        Telephony.TextBasedSmsColumns.MESSAGE_TYPE_OUTBOX -> "OUTBOX"
        Telephony.TextBasedSmsColumns.MESSAGE_TYPE_FAILED -> "FAILED"
        Telephony.TextBasedSmsColumns.MESSAGE_TYPE_QUEUED -> "QUEUED"
        else -> type.toString()
    }

    private fun smsSend(params: JSONObject): ApiResponse {
        requirePermission(Manifest.permission.SEND_SMS)?.let { return it }
        val recipients = jsonStringList(params.opt("to"))
            .ifEmpty { jsonStringList(params.opt("numbers")) }
        val message = params.optString("message", params.optString("text", ""))
        if (recipients.isEmpty()) return ApiResponse.Error("Missing recipient: to")
        if (message.isBlank()) return ApiResponse.Error("Missing message")
        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        recipients.forEach { number ->
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
        }
        return ApiResponse.RawObject(JSONObject().apply {
            put("sent", recipients.size)
            put("recipients", JSONArray(recipients))
        })
    }

    private fun dialog(params: JSONObject): ApiResponse {
        val widget = params.optString("widget", params.optString("input_method", "text")).ifBlank { "text" }
        if (widget == "speech") {
            requirePermission(Manifest.permission.RECORD_AUDIO)?.let { return it }
            val result = SpeechToTextActivity.request(
                context = context,
                language = params.optString("language", ""),
                prompt = params.optString("hint", params.optString("input_hint", "")),
                partial = false,
                timeoutMs = params.optLong("timeoutMs", params.optLong("timeout_ms", 60000L)),
            )
            if (!result.success) return ApiResponse.Error(result.error.ifBlank { "dialog speech failed" })
            return ApiResponse.RawObject(JSONObject().apply {
                put("code", -1)
                put("text", result.text)
                put("matches", JSONArray(result.matches))
            })
        }
        val result = DialogBridgeActivity.request(
            context = context,
            params = JSONObject(params.toString()).put("widget", widget),
            timeoutMs = params.optLong("timeoutMs", params.optLong("timeout_ms", 300000L)),
        )
        if (!result.success) return ApiResponse.Error(result.error.ifBlank { "dialog failed" })
        return ApiResponse.RawObject(JSONObject().apply {
            put("code", result.code)
            put("text", result.text)
            if (result.index >= 0) put("index", result.index)
            if (result.values.isNotEmpty()) {
                put("values", JSONArray().apply {
                    result.values.forEach { value ->
                        put(JSONObject().apply {
                            put("index", value.index)
                            put("text", value.text)
                        })
                    }
                })
            }
            if (result.error.isNotBlank()) put("error", result.error)
        })
    }

    private fun speechToText(params: JSONObject): ApiResponse {
        requirePermission(Manifest.permission.RECORD_AUDIO)?.let { return it }
        val timeoutMs = params.optLong("timeoutMs", params.optLong("timeout_ms", 60000L))
        val result = SpeechToTextActivity.request(
            context = context,
            language = params.optString("language", params.optString("locale", "")),
            prompt = params.optString("prompt", ""),
            partial = params.optBoolean("partial", params.optBoolean("showProgress", false)),
            timeoutMs = timeoutMs,
        )
        if (!result.success) return ApiResponse.Error(result.error.ifBlank { "speech-to-text failed" })
        return ApiResponse.RawObject(JSONObject().apply {
            put("text", result.text)
            put("matches", JSONArray(result.matches))
            put("partials", JSONArray(result.partials))
            put("language", result.language)
        })
    }

    private fun storageGet(params: JSONObject): ApiResponse {
        val timeoutMs = params.optLong("timeoutMs", params.optLong("timeout_ms", 300000L))
        val result = StorageGetActivity.request(
            context = context,
            mime = params.optString("mime", params.optString("type", "*/*")),
            timeoutMs = timeoutMs,
        )
        if (!result.success) return ApiResponse.Error(result.error.ifBlank { "storage-get failed" })
        return ApiResponse.RawObject(JSONObject().apply {
            put("path", result.path)
            put("bytes", result.bytes)
            put("uri", result.uri)
            put("displayName", result.displayName)
            put("mimeType", result.mimeType)
            put("content_type", result.mimeType.ifBlank { "application/octet-stream" })
            put("source", "storage-get")
        })
    }

    private fun safManageDir(params: JSONObject): ApiResponse {
        val timeoutMs = params.optLong("timeoutMs", params.optLong("timeout_ms", 300000L))
        val result = SafManageActivity.request(context, timeoutMs)
        if (!result.success) return ApiResponse.Error(result.error.ifBlank { "saf-managedir failed" })
        return ApiResponse.RawObject(JSONObject().apply {
            put("uri", result.uri)
            safStatJson(result.uri)?.let { put("document", it) }
        })
    }

    private fun safDirs(): ApiResponse {
        val arr = JSONArray()
        context.contentResolver.persistedUriPermissions.forEach { permission ->
            arr.put(JSONObject().apply {
                put("uri", permission.uri.toString())
                put("read", permission.isReadPermission)
                put("write", permission.isWritePermission)
                put("persistedTime", permission.persistedTime)
                safStatJson(permission.uri.toString())?.let { put("document", it) }
            })
        }
        return ApiResponse.RawArray(arr)
    }

    private fun safLs(params: JSONObject): ApiResponse {
        val uriString = params.optString("treeuri", params.optString("uri", params.optString("folderUri", "")))
        if (uriString.isBlank()) return ApiResponse.Error("Missing treeuri/uri")
        val treeUri = Uri.parse(uriString)
        val documentId = safDocumentId(treeUri)
            ?: return ApiResponse.Error("Invalid SAF directory uri")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val arr = JSONArray()
        return try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    safStatJson(childUri.toString())?.let { arr.put(it) }
                }
            }
            ApiResponse.RawArray(arr)
        } catch (e: Exception) {
            ApiResponse.Error("Failed to list SAF directory: ${e.message}")
        }
    }

    private fun safStat(params: JSONObject): ApiResponse {
        val uri = params.optString("uri", params.optString("treeuri", ""))
        if (uri.isBlank()) return ApiResponse.Error("Missing uri")
        return safStatJson(uri)?.let { ApiResponse.RawObject(it) }
            ?: ApiResponse.Error("SAF uri not found or not accessible")
    }

    private fun safCreate(params: JSONObject, directory: Boolean): ApiResponse {
        val treeUriString = params.optString("treeuri", params.optString("parentUri", params.optString("uri", "")))
        val filename = params.optString("filename", params.optString("name", ""))
        if (treeUriString.isBlank()) return ApiResponse.Error("Missing treeuri/parentUri")
        if (filename.isBlank()) return ApiResponse.Error("Missing filename/name")
        val treeUri = Uri.parse(treeUriString)
        val parentId = safDocumentId(treeUri) ?: return ApiResponse.Error("Invalid SAF parent uri")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val mimeType = if (directory) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else {
            params.optString("mimetype", params.optString("mime", "application/octet-stream"))
                .ifBlank { "application/octet-stream" }
        }
        return try {
            val created = DocumentsContract.createDocument(context.contentResolver, parentUri, mimeType, filename)
                ?: return ApiResponse.Error("SAF provider did not create document")
            ApiResponse.RawObject(JSONObject().apply {
                put("uri", created.toString())
                safStatJson(created.toString())?.let { put("document", it) }
            })
        } catch (e: Exception) {
            ApiResponse.Error("Failed to create SAF document: ${e.message}")
        }
    }

    private fun safRead(params: JSONObject): ApiResponse {
        val uri = params.optString("uri", "")
        if (uri.isBlank()) return ApiResponse.Error("Missing uri")
        val docUri = safDocumentUri(Uri.parse(uri))
        val stat = safStatJson(docUri.toString())
        val extension = stat?.optString("name", "")?.substringAfterLast('.', "")?.ifBlank { "bin" } ?: "bin"
        return try {
            val input = context.contentResolver.openInputStream(docUri)
                ?: return ApiResponse.Error("Failed to open SAF document for read")
            input.use {
                fileOperations.writeTransferCache(it, extension).fold(
                    onSuccess = { cached ->
                        ApiResponse.RawObject(JSONObject().apply {
                            put("path", cached.path)
                            put("bytes", cached.bytes)
                            put("uri", docUri.toString())
                            put("content_type", stat?.optString("type", "application/octet-stream") ?: "application/octet-stream")
                            put("source", "saf-read")
                            stat?.let { put("document", it) }
                        })
                    },
                    onFailure = { error -> ApiResponse.Error("Failed to cache SAF document: ${error.message}") },
                )
            }
        } catch (e: Exception) {
            ApiResponse.Error("Failed to read SAF document: ${e.message}")
        }
    }

    private fun safWrite(params: JSONObject): ApiResponse {
        val uri = params.optString("uri", "")
        if (uri.isBlank()) return ApiResponse.Error("Missing uri")
        val cachePath = params.optString("cachePath", params.optString("cache_path", ""))
        val text = params.optString("text", params.optString("content", ""))
        if (cachePath.isBlank() && text.isEmpty()) return ApiResponse.Error("Missing cachePath/cache_path or text/content")
        val docUri = safDocumentUri(Uri.parse(uri))
        return try {
            val bytes = context.contentResolver.openOutputStream(docUri, "rwt")?.use { output ->
                if (cachePath.isNotBlank()) {
                    val source = fileOperations.resolveTransferCacheFile(cachePath).getOrElse { error ->
                        return ApiResponse.Error("Invalid cachePath: ${error.message}")
                    }
                    source.inputStream().use { input -> copyStream(input, output) }
                } else {
                    val data = text.toByteArray(Charsets.UTF_8)
                    output.write(data)
                    data.size.toLong()
                }
            } ?: return ApiResponse.Error("Failed to open SAF document for write")
            ApiResponse.RawObject(JSONObject().apply {
                put("uri", docUri.toString())
                put("bytes", bytes)
                safStatJson(docUri.toString())?.let { put("document", it) }
            })
        } catch (e: Exception) {
            ApiResponse.Error("Failed to write SAF document: ${e.message}")
        }
    }

    private fun safRemove(params: JSONObject): ApiResponse {
        val uri = params.optString("uri", "")
        if (uri.isBlank()) return ApiResponse.Error("Missing uri")
        return try {
            ApiResponse.RawObject(JSONObject().apply {
                put("uri", uri)
                put("deleted", DocumentsContract.deleteDocument(context.contentResolver, safDocumentUri(Uri.parse(uri))))
            })
        } catch (e: Exception) {
            ApiResponse.Error("Failed to remove SAF document: ${e.message}")
        }
    }

    private fun safDocumentId(uri: Uri): String? {
        var id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            null
        }
        try {
            id = DocumentsContract.getDocumentId(uri)
        } catch (_: IllegalArgumentException) {
        }
        return id
    }

    private fun safDocumentUri(uri: Uri): Uri {
        val id = safDocumentId(uri) ?: return uri
        return try {
            DocumentsContract.buildDocumentUriUsingTree(uri, id)
        } catch (_: IllegalArgumentException) {
            uri
        }
    }

    private fun safStatJson(uriString: String): JSONObject? {
        val uri = safDocumentUri(Uri.parse(uriString))
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val mimeType = cursor.getStringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE).orEmpty()
                JSONObject().apply {
                    put("name", cursor.getStringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty())
                    put("type", mimeType)
                    put("uri", uri.toString())
                    cursor.getLongOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.let { put("last_modified", it) }
                    if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
                        cursor.getLongOrNull(DocumentsContract.Document.COLUMN_SIZE)?.let { put("length", it) }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyStream(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            output.write(buffer, 0, read)
            total += read
        }
    }

    private fun notificationList(): ApiResponse {
        val arr = NotificationAccessService.activeNotificationsJson()
            ?: return ApiResponse.Error("Notification access not enabled. Open notification listener settings and enable AutoTermux.")
        return ApiResponse.RawArray(arr)
    }

    private fun notificationTap(params: JSONObject): ApiResponse {
        val service = NotificationAccessService.getInstance()
            ?: return ApiResponse.Error("Notification access not enabled. Open notification listener settings and enable AutoTermux.")
        return try {
            ApiResponse.RawObject(service.tapNotification(params))
        } catch (e: Exception) {
            ApiResponse.Error("Failed to tap notification: ${e.message}")
        }
    }

    private fun notificationAction(params: JSONObject): ApiResponse {
        val service = NotificationAccessService.getInstance()
            ?: return ApiResponse.Error("Notification access not enabled. Open notification listener settings and enable AutoTermux.")
        return try {
            ApiResponse.RawObject(service.performNotificationAction(params))
        } catch (e: Exception) {
            ApiResponse.Error("Failed to perform notification action: ${e.message}")
        }
    }

    private fun notificationReply(params: JSONObject): ApiResponse {
        val service = NotificationAccessService.getInstance()
            ?: return ApiResponse.Error("Notification access not enabled. Open notification listener settings and enable AutoTermux.")
        val replyText = params.optString("reply", params.optString("text", params.optString("message", "")))
        if (replyText.isBlank()) return ApiResponse.Error("Missing reply text")
        return try {
            ApiResponse.RawObject(service.performNotificationAction(params, replyText))
        } catch (e: Exception) {
            ApiResponse.Error("Failed to reply to notification: ${e.message}")
        }
    }

    private data class NotificationActionSpec(
        val title: String,
        val actionId: String,
        val reply: Boolean,
        val autoCancel: Boolean,
    )

    private fun notificationPost(params: JSONObject): ApiResponse {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = params.optString("channel", NOTIFICATION_CHANNEL_ID).ifBlank { NOTIFICATION_CHANNEL_ID }
        val priority = params.optString("priority", "default")
        ensureNotificationChannel(
            manager,
            channelId,
            params.optString("channelName", params.optString("channel_name", NOTIFICATION_CHANNEL_NAME)),
            notificationImportance(priority),
        )
        val id = params.optInt("id", 1000)
        val tag = params.optString("tag", "")
        val title = params.optString("title", "AutoTermux")
        val content = params.optString("content", params.optString("text", ""))
        val autoCancel = params.optBoolean("autoCancel", true)
        val tapAction = params.optString("tapAction", params.optString("action", ""))
        val pendingIntent = if (tapAction.isNotBlank()) {
            createNotificationActionPendingIntent(
                id = id,
                tag = tag,
                actionIndex = -1,
                title = "content",
                actionId = tapAction,
                reply = false,
                autoCancel = autoCancel,
            )
        } else {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            PendingIntent.getActivity(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setPriority(notificationCompatPriority(priority))
            .setOnlyAlertOnce(params.optBoolean("alertOnce", params.optBoolean("alert-once", false)))
            .setAutoCancel(autoCancel)
            .setOngoing(params.optBoolean("ongoing", false))
        params.optString("group", "").takeIf(String::isNotBlank)?.let { builder.setGroup(it) }
        params.optString("deleteAction", params.optString("on_delete_action", "")).takeIf(String::isNotBlank)?.let { action ->
            builder.setDeleteIntent(
                createNotificationActionPendingIntent(
                    id = id,
                    tag = tag,
                    actionIndex = -2,
                    title = "delete",
                    actionId = action,
                    reply = false,
                    autoCancel = false,
                ),
            )
        }
        val imagePath = params.optString("imagePath", params.optString("image-path", ""))
        if (imagePath.isNotBlank()) {
            BitmapFactory.decodeFile(imagePath)?.let { bitmap ->
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).setSummaryText(content))
            }
        }
        val ledColor = params.optString("ledColor", params.optString("led-color", ""))
        if (ledColor.isNotBlank()) {
            parseColorOrNull(ledColor.ensureColorPrefix())?.let { color ->
                builder.setLights(
                    color,
                    params.optInt("ledOn", params.optInt("led-on", 800)),
                    params.optInt("ledOff", params.optInt("led-off", 800)),
                )
            }
        }
        if (params.optBoolean("sound", false)) {
            builder.setSound(Settings.System.DEFAULT_NOTIFICATION_URI)
        }
        jsonLongArray(params.opt("vibrate")).takeIf { it.isNotEmpty() }?.let { pattern ->
            builder.setVibrate(longArrayOf(0L) + pattern.toLongArray())
        }
        val actions = notificationActionSpecs(params, autoCancel)
        actions.forEachIndexed { index, action ->
            val actionPendingIntent = createNotificationActionPendingIntent(
                id = id,
                tag = tag,
                actionIndex = index,
                title = action.title,
                actionId = action.actionId,
                reply = action.reply,
                autoCancel = action.autoCancel,
            )
            val actionBuilder = NotificationCompat.Action.Builder(
                if (action.reply) android.R.drawable.ic_menu_send else android.R.drawable.ic_menu_view,
                action.title,
                actionPendingIntent,
            )
            if (action.reply) {
                actionBuilder.addRemoteInput(
                    RemoteInput.Builder(NotificationActionReceiver.KEY_TEXT_REPLY)
                        .setLabel(action.title)
                        .build(),
                )
            }
            builder.addAction(actionBuilder.build())
        }
        manager.notify(tag.ifBlank { null }, id, builder.build())
        return ApiResponse.RawObject(JSONObject().apply {
            put("id", id)
            put("tag", tag)
            put("posted", true)
            put("actions", actions.size)
            if (tapAction.isNotBlank()) put("tapAction", tapAction)
        })
    }

    private fun notificationActionSpecs(params: JSONObject, defaultAutoCancel: Boolean): List<NotificationActionSpec> {
        val actions = mutableListOf<NotificationActionSpec>()
        fun addFromObject(obj: JSONObject) {
            val title = obj.optString(
                "title",
                obj.optString("text", obj.optString("label", obj.optString("id", "Action"))),
            ).ifBlank { "Action" }
            val actionId = obj.optString("id", obj.optString("action", title)).ifBlank { title }
            val legacyAction = obj.optString("action", "")
            actions += NotificationActionSpec(
                title = title,
                actionId = actionId,
                reply = obj.optBoolean("reply", legacyAction.contains("\$REPLY")),
                autoCancel = if (obj.has("autoCancel")) obj.optBoolean("autoCancel") else defaultAutoCancel,
            )
        }
        fun addFromValue(value: Any?) {
            when (value) {
                is JSONObject -> addFromObject(value)
                is String -> actions += NotificationActionSpec(
                    title = value,
                    actionId = value,
                    reply = false,
                    autoCancel = defaultAutoCancel,
                )
            }
        }
        val explicitActions = params.opt("actions")
        if (explicitActions is JSONArray) {
            for (i in 0 until explicitActions.length()) addFromValue(explicitActions.opt(i))
        }
        val buttons = params.opt("buttons")
        if (buttons is JSONArray) {
            for (i in 0 until buttons.length()) addFromValue(buttons.opt(i))
        }
        for (button in 1..3) {
            val text = params.optString("button_text_$button", params.optString("button$button", ""))
            val legacyAction = params.optString("button_action_$button", "")
            if (text.isNotBlank() || legacyAction.isNotBlank()) {
                addFromObject(JSONObject().apply {
                    put("title", text.ifBlank { "Button $button" })
                    put("id", legacyAction.ifBlank { text })
                    put("action", legacyAction)
                })
            }
        }
        return actions.take(3)
    }

    private fun createNotificationActionPendingIntent(
        id: Int,
        tag: String,
        actionIndex: Int,
        title: String,
        actionId: String,
        reply: Boolean,
        autoCancel: Boolean,
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_NOTIFICATION_EVENT
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_ID, id)
            putExtra(NotificationActionReceiver.EXTRA_NOTIFICATION_TAG, tag)
            putExtra(NotificationActionReceiver.EXTRA_ACTION_ID, actionId)
            putExtra(NotificationActionReceiver.EXTRA_ACTION_TITLE, title)
            putExtra(NotificationActionReceiver.EXTRA_ACTION_INDEX, actionIndex)
            putExtra(NotificationActionReceiver.EXTRA_AUTO_CANCEL, autoCancel)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (reply && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else if (!reply) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            context,
            notificationActionRequestCode(id, tag, actionIndex, actionId),
            intent,
            flags,
        )
    }

    private fun notificationActionRequestCode(id: Int, tag: String, actionIndex: Int, actionId: String): Int =
        "$id:$tag:$actionIndex:$actionId".hashCode() and Int.MAX_VALUE

    private fun notificationCompatPriority(priority: String): Int = when (priority.lowercase(Locale.US)) {
        "high" -> NotificationCompat.PRIORITY_HIGH
        "low" -> NotificationCompat.PRIORITY_LOW
        "max" -> NotificationCompat.PRIORITY_MAX
        "min" -> NotificationCompat.PRIORITY_MIN
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    private fun notificationImportance(priority: String): Int = when (priority.lowercase(Locale.US)) {
        "high", "max" -> NotificationManager.IMPORTANCE_HIGH
        "low" -> NotificationManager.IMPORTANCE_LOW
        "min" -> NotificationManager.IMPORTANCE_MIN
        else -> NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun notificationChannel(params: JSONObject): ApiResponse {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = params.optString("id", NOTIFICATION_CHANNEL_ID)
        if (params.optBoolean("delete", false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.deleteNotificationChannel(channelId)
            }
            return ApiResponse.RawObject(JSONObject().apply {
                put("id", channelId)
                put("deleted", true)
            })
        }
        ensureNotificationChannel(
            manager,
            channelId,
            params.optString("name", channelId),
            notificationImportance(params.optString("priority", "default")),
        )
        return ApiResponse.RawObject(JSONObject().apply {
            put("id", channelId)
            put("created", true)
        })
    }

    private fun notificationRemove(params: JSONObject): ApiResponse {
        val key = params.optString("key", "")
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (key.isNotBlank()) {
            val service = NotificationAccessService.getInstance()
                ?: return ApiResponse.Error("Notification access not enabled")
            service.cancelNotification(key)
            return ApiResponse.Success("notification removed by key")
        }
        val id = params.optInt("id", 1000)
        val tag = params.optString("tag", "").ifBlank { null }
        manager.cancel(tag, id)
        return ApiResponse.Success("notification removed")
    }

    private fun ensureNotificationChannel(
        manager: NotificationManager,
        id: String,
        name: String = NOTIFICATION_CHANNEL_NAME,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(id, name, importance),
            )
        }
    }

    private fun telephonyDeviceInfo(): ApiResponse {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return ApiResponse.RawObject(JSONObject().apply {
            put("phone_type", phoneTypeName(tm.phoneType))
            put("network_operator", tm.networkOperator ?: "")
            put("network_operator_name", tm.networkOperatorName ?: "")
            put("sim_operator", tm.simOperator ?: "")
            put("sim_operator_name", tm.simOperatorName ?: "")
            put("sim_country_iso", tm.simCountryIso ?: "")
            put("network_country_iso", tm.networkCountryIso ?: "")
            put("data_state", tm.dataState)
            put("call_state", tm.callState)
            if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                put("sim_state", tm.simState)
                put("line1_number", runCatching { tm.line1Number }.getOrNull() ?: JSONObject.NULL)
            } else {
                put("read_phone_state_granted", false)
            }
        })
    }

    private fun phoneTypeName(type: Int): String = when (type) {
        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
        TelephonyManager.PHONE_TYPE_NONE -> "NONE"
        else -> type.toString()
    }

    private fun telephonyCellInfo(): ApiResponse {
        requireAnyPermission(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)?.let { return it }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val arr = JSONArray()
        val cells: List<CellInfo> = try {
            tm.allCellInfo ?: emptyList()
        } catch (e: SecurityException) {
            return ApiResponse.Error("Cell info permission denied: ${e.message}")
        }
        cells.forEach { cell ->
            arr.put(JSONObject().apply {
                put("type", cell.javaClass.simpleName)
                put("registered", cell.isRegistered)
                put("timestamp_nanos", cell.timeStamp)
                put("raw", cell.toString())
            })
        }
        return ApiResponse.RawArray(arr)
    }

    private fun telephonyCall(params: JSONObject): ApiResponse {
        val number = params.optString("number", "")
        if (number.isBlank()) return ApiResponse.Error("Missing number")
        val direct = params.optBoolean("direct", true)
        if (direct) requirePermission(Manifest.permission.CALL_PHONE)?.let { return it }
        val intent = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ApiResponse.Success(if (direct) "call started" else "dialer opened")
    }

    private fun location(params: JSONObject): ApiResponse {
        requireAnyPermission(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)?.let { return it }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val requestedProvider = params.optString("provider", "").lowercase(Locale.US)
        val providers = when (requestedProvider) {
            "gps" -> listOf(LocationManager.GPS_PROVIDER)
            "network" -> listOf(LocationManager.NETWORK_PROVIDER)
            "passive" -> listOf(LocationManager.PASSIVE_PROVIDER)
            else -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }.filter { manager.allProviders.contains(it) }
        if (providers.isEmpty()) return ApiResponse.Error("No requested location provider available")
        val last = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        if (params.optString("request", "once") == "last" || last != null && !params.optBoolean("fresh", false)) {
            return ApiResponse.RawObject(last?.toJson() ?: JSONObject().put("location", JSONObject.NULL))
        }

        val timeoutMs = params.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(1000L, 60000L)
        val latch = CountDownLatch(1)
        var result: Location? = null
        val thread = HandlerThread("autotermux-location").apply { start() }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                result = location
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            providers.forEach { provider ->
                manager.requestSingleUpdate(provider, listener, thread.looper)
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: SecurityException) {
            return ApiResponse.Error("Location permission denied: ${e.message}")
        } finally {
            runCatching { manager.removeUpdates(listener) }
            thread.quitSafely()
        }
        return ApiResponse.RawObject((result ?: last)?.toJson() ?: JSONObject().put("location", JSONObject.NULL))
    }

    private fun Location.toJson(): JSONObject = JSONObject().apply {
        put("provider", provider)
        put("latitude", latitude)
        put("longitude", longitude)
        put("altitude", altitude)
        put("accuracy", accuracy)
        put("bearing", bearing)
        put("speed", speed)
        put("time", time)
        put("elapsedRealtimeNanos", elapsedRealtimeNanos)
    }

    private fun sensor(params: JSONObject): ApiResponse {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (params.optBoolean("cleanup", false)) {
            return ApiResponse.RawObject(JSONObject().put("cleanup", true))
        }
        if (
            params.optBoolean("list", false) ||
            (!params.optBoolean("all", false) && !params.has("sensor") && !params.has("sensors"))
        ) {
            val arr = JSONArray()
            manager.getSensorList(Sensor.TYPE_ALL).forEach { sensor ->
                arr.put(sensor.toJson())
            }
            return ApiResponse.RawArray(arr)
        }
        val targets = resolveRequestedSensors(manager, params)
            ?: return ApiResponse.Error("Unknown sensor: ${params.opt("sensors") ?: params.opt("sensor")}")
        if (targets.isEmpty()) return ApiResponse.Error("No matching sensors available")
        val limit = params.optInt("limit", 1).coerceIn(1, 1000)
        val timeoutMs = params.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(500L, 60000L)
        val delayUs = when {
            params.has("delayUs") -> params.optInt("delayUs")
            params.has("delayMs") -> (params.optInt("delayMs").coerceAtLeast(0) * 1000)
            params.has("delay") -> (params.optInt("delay").coerceAtLeast(0) * 1000)
            else -> SensorManager.SENSOR_DELAY_NORMAL
        }
        val arr = JSONArray()
        val latch = CountDownLatch((limit * targets.size).coerceAtLeast(1))
        val thread = HandlerThread("autotermux-sensor").apply { start() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                arr.put(JSONObject().apply {
                    put("sensor", event.sensor.name)
                    put("type", event.sensor.type)
                    put("timestamp", event.timestamp)
                    put("accuracy", event.accuracy)
                    put("values", JSONArray().apply { event.values.forEach { put(it.toDouble()) } })
                })
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        return try {
            val handler = Handler(thread.looper)
            val registered = targets.filter { target ->
                manager.registerListener(listener, target, delayUs, handler)
            }
            if (registered.isEmpty()) {
                ApiResponse.Error("Failed to register sensor listener")
            } else {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                ApiResponse.RawArray(arr)
            }
        } finally {
            manager.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    private fun resolveRequestedSensors(manager: SensorManager, params: JSONObject): List<Sensor>? {
        if (params.optBoolean("all", false)) {
            return manager.getSensorList(Sensor.TYPE_ALL)
        }
        val requested = jsonStringList(params.opt("sensors")).ifEmpty {
            params.optString("sensor", "").takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList()
        }
        if (requested.isEmpty()) return emptyList()
        val targets = mutableListOf<Sensor>()
        requested.forEach { query ->
            val direct = resolveSensorType(query)?.let { manager.getDefaultSensor(it) }
            if (direct != null && targets.none { sameSensor(it, direct) }) {
                targets.add(direct)
                return@forEach
            }
            val normalizedQuery = query.lowercase(Locale.US)
            val matches = manager.getSensorList(Sensor.TYPE_ALL).filter { sensor ->
                sensor.name.lowercase(Locale.US).contains(normalizedQuery) ||
                    (sensor.stringType ?: "").lowercase(Locale.US).contains(normalizedQuery)
            }
            if (matches.isEmpty()) return null
            matches.forEach { match ->
                if (targets.none { sameSensor(it, match) }) targets.add(match)
            }
        }
        return targets
    }

    private fun sameSensor(left: Sensor, right: Sensor): Boolean =
        left.type == right.type && left.name == right.name && left.vendor == right.vendor

    private fun Sensor.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("vendor", vendor)
        put("version", version)
        put("type", type)
        put("type_string", stringType ?: "")
        put("max_range", maximumRange.toDouble())
        put("resolution", resolution.toDouble())
        put("power", power.toDouble())
        put("min_delay", minDelay)
    }

    private fun resolveSensorType(value: Any?): Int? {
        if (value is Number) return value.toInt()
        val name = value?.toString()?.lowercase(Locale.US)?.replace("_", "-") ?: return null
        return when (name) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "ambient-temperature" -> Sensor.TYPE_AMBIENT_TEMPERATURE
            "game-rotation-vector" -> Sensor.TYPE_GAME_ROTATION_VECTOR
            "gravity" -> Sensor.TYPE_GRAVITY
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "light" -> Sensor.TYPE_LIGHT
            "linear-acceleration" -> Sensor.TYPE_LINEAR_ACCELERATION
            "magnetic-field", "magnetometer" -> Sensor.TYPE_MAGNETIC_FIELD
            "pressure" -> Sensor.TYPE_PRESSURE
            "proximity" -> Sensor.TYPE_PROXIMITY
            "relative-humidity" -> Sensor.TYPE_RELATIVE_HUMIDITY
            "rotation-vector" -> Sensor.TYPE_ROTATION_VECTOR
            "step-counter" -> Sensor.TYPE_STEP_COUNTER
            "step-detector" -> Sensor.TYPE_STEP_DETECTOR
            else -> name.toIntOrNull()
        }
    }

    private fun wifiConnectionInfo(): ApiResponse {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = manager.connectionInfo
        return ApiResponse.RawObject(JSONObject().apply {
            put("ssid", info?.ssid ?: "")
            put("bssid", info?.bssid ?: "")
            put("hidden_ssid", info?.hiddenSSID ?: false)
            put("ip", info?.ipAddress ?: 0)
            put("link_speed_mbps", info?.linkSpeed ?: 0)
            put("rssi", info?.rssi ?: 0)
            put("network_id", info?.networkId ?: -1)
            put("supplicant_state", info?.supplicantState?.toString() ?: "")
            put("wifi_enabled", manager.isWifiEnabled)
        })
    }

    private fun wifiScanInfo(): ApiResponse {
        requireAnyPermission(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)?.let { return it }
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val arr = JSONArray()
        @Suppress("DEPRECATION")
        manager.scanResults.orEmpty().forEach { result ->
            arr.put(JSONObject().apply {
                put("ssid", result.SSID ?: "")
                put("bssid", result.BSSID ?: "")
                put("capabilities", result.capabilities ?: "")
                put("frequency_mhz", result.frequency)
                put("level", result.level)
                put("timestamp", result.timestamp)
            })
        }
        return ApiResponse.RawArray(arr)
    }

    private fun wifiEnable(params: JSONObject): ApiResponse {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!params.has("enabled")) {
            return ApiResponse.RawObject(JSONObject().put("enabled", manager.isWifiEnabled))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ApiResponse.Error("Changing Wi-Fi enabled state is blocked for apps on Android 10+; open system settings instead.")
        }
        @Suppress("DEPRECATION")
        val ok = manager.setWifiEnabled(params.optBoolean("enabled"))
        return ApiResponse.RawObject(JSONObject().apply {
            put("requested", params.optBoolean("enabled"))
            put("ok", ok)
            put("enabled", manager.isWifiEnabled)
        })
    }

    private fun torch(params: JSONObject): ApiResponse {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = params.optString("camera", "").ifBlank {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ApiResponse.Error("No camera with flash available")
        }
        manager.setTorchMode(cameraId, params.optBoolean("enabled", true))
        return ApiResponse.RawObject(JSONObject().apply {
            put("camera", cameraId)
            put("enabled", params.optBoolean("enabled", true))
        })
    }

    private fun vibrate(params: JSONObject): ApiResponse {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val durationMs = params.optLong("durationMs", params.optLong("duration", 1000L)).coerceIn(1L, 60000L)
        val force = params.optBoolean("force", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, if (force) VibrationEffect.DEFAULT_AMPLITUDE else 128))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
        return ApiResponse.RawObject(JSONObject().apply {
            put("durationMs", durationMs)
            put("force", force)
        })
    }

    private fun toast(params: JSONObject): ApiResponse {
        val text = params.optString("text", "")
        if (text.isBlank()) return ApiResponse.Error("Missing text")
        val short = params.optBoolean("short", false)
        Handler(Looper.getMainLooper()).post {
            val toast = Toast.makeText(context, text, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
            params.optString("gravity", "").takeIf { it.isNotBlank() }?.let { gravity ->
                toast.setGravity(toastGravity(gravity), 0, 0)
            }
            val view = toast.view
            if (view != null) {
                parseColorOrNull(params.optString("background", ""))?.let { view.setBackgroundColor(it) }
                parseColorOrNull(params.optString("color", params.optString("text_color", "")))?.let { color ->
                    view.findViewById<TextView>(android.R.id.message)?.setTextColor(color)
                }
            }
            toast.show()
        }
        return ApiResponse.Success("toast shown")
    }

    private fun toastGravity(value: String): Int = when (value.lowercase(Locale.US)) {
        "top" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        "bottom" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        "middle", "center", "centre" -> Gravity.CENTER
        else -> Gravity.CENTER
    }

    private fun parseColorOrNull(value: String): Int? {
        if (value.isBlank()) return null
        return runCatching {
            when (value.lowercase(Locale.US)) {
                "black" -> Color.BLACK
                "blue" -> Color.BLUE
                "cyan" -> Color.CYAN
                "dkgray", "darkgray", "darkgrey" -> Color.DKGRAY
                "gray", "grey" -> Color.GRAY
                "green" -> Color.GREEN
                "ltgray", "lightgray", "lightgrey" -> Color.LTGRAY
                "magenta" -> Color.MAGENTA
                "red" -> Color.RED
                "transparent" -> Color.TRANSPARENT
                "white" -> Color.WHITE
                "yellow" -> Color.YELLOW
                else -> Color.parseColor(value)
            }
        }.getOrNull()
    }

    private fun String.ensureColorPrefix(): String =
        if (matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) "#$this" else this

    private fun ttsEngines(): ApiResponse {
        val latch = CountDownLatch(1)
        var tts: TextToSpeech? = null
        Handler(Looper.getMainLooper()).post {
            tts = TextToSpeech(context) { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        val arr = JSONArray()
        tts?.engines.orEmpty().forEach { engine ->
            arr.put(JSONObject().apply {
                put("name", engine.name)
                put("label", engine.label)
                put("icon", engine.icon)
            })
        }
        tts?.shutdown()
        return ApiResponse.RawArray(arr)
    }

    private fun ttsSpeak(params: JSONObject): ApiResponse {
        val text = params.optString("text", "")
        if (text.isBlank()) return ApiResponse.Error("Missing text")
        val initLatch = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        var tts: TextToSpeech? = null
        val engine = params.optString("engine", "")
        Handler(Looper.getMainLooper()).post {
            val listener = TextToSpeech.OnInitListener { status ->
                initStatus = status
                initLatch.countDown()
            }
            tts = if (engine.isNotBlank()) {
                TextToSpeech(context, listener, engine)
            } else {
                TextToSpeech(context, listener)
            }
        }
        if (!initLatch.await(5, TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
            tts?.shutdown()
            return ApiResponse.Error("TextToSpeech init failed")
        }
        ttsLanguageTag(params)?.let {
            tts?.language = Locale.forLanguageTag(it)
        }
        if (params.has("pitch")) tts?.setPitch(params.optDouble("pitch").toFloat())
        if (params.has("rate")) tts?.setSpeechRate(params.optDouble("rate").toFloat())
        val queueMode = if (params.optBoolean("queue", false)) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val speakParams = Bundle()
        params.optString("stream", "").takeIf { it.isNotBlank() }?.let { stream ->
            speakParams.putString(TextToSpeech.Engine.KEY_PARAM_STREAM, audioStreamType(stream).toString())
        }
        val utteranceId = "autotermux-${SystemClock.uptimeMillis()}"
        val result = tts?.speak(text, queueMode, speakParams, utteranceId) ?: TextToSpeech.ERROR
        return ApiResponse.RawObject(JSONObject().apply {
            put("queued", result == TextToSpeech.SUCCESS)
            put("chars", text.length)
            put("engine", engine.ifBlank { JSONObject.NULL })
            put("utteranceId", utteranceId)
        })
    }

    private fun ttsLanguageTag(params: JSONObject): String? {
        val language = params.optString("language", "")
        if (language.isBlank()) return null
        val region = params.optString("region", "")
        val variant = params.optString("variant", "")
        return listOf(language, region, variant).filter { it.isNotBlank() }.joinToString("-")
    }

    private fun audioStreamType(value: String): Int = when (value.uppercase(Locale.US)) {
        "ALARM" -> AudioManager.STREAM_ALARM
        "MUSIC" -> AudioManager.STREAM_MUSIC
        "RING" -> AudioManager.STREAM_RING
        "SYSTEM" -> AudioManager.STREAM_SYSTEM
        "VOICE_CALL" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_NOTIFICATION
    }

    private fun mediaScan(params: JSONObject): ApiResponse {
        val requestedPaths = jsonStringList(params.opt("paths")).ifEmpty {
            params.optString("path", "").takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
        }
        val paths = if (params.optBoolean("recursive", false)) {
            requestedPaths.flatMap { path ->
                val file = File(path)
                if (file.isDirectory) {
                    file.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
                } else {
                    listOf(path)
                }
            }
        } else {
            requestedPaths
        }
        if (paths.isEmpty()) return ApiResponse.Error("Missing path or paths")
        val latch = CountDownLatch(paths.size)
        val arr = JSONArray()
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { path, uri ->
            arr.put(JSONObject().apply {
                put("path", path)
                put("uri", uri?.toString() ?: JSONObject.NULL)
            })
            latch.countDown()
        }
        latch.await(params.optLong("timeoutMs", DEFAULT_TIMEOUT_MS), TimeUnit.MILLISECONDS)
        return ApiResponse.RawArray(arr)
    }

    private fun download(params: JSONObject): ApiResponse {
        val url = params.optString("url", "")
        if (url.isBlank()) return ApiResponse.Error("Missing url")
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(params.optString("title", URL(url).path.substringAfterLast('/').ifBlank { "download" }))
            .setDescription(params.optString("description", ""))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        params.optString("path", "").takeIf { it.isNotBlank() }?.let { path ->
            request.setDestinationUri(Uri.fromFile(File(path)))
        }
        val id = manager.enqueue(request)
        return ApiResponse.RawObject(JSONObject().apply {
            put("download_id", id)
            put("url", url)
        })
    }

    private fun share(params: JSONObject): ApiResponse {
        val text = params.optString("text", "")
        val file = params.optString("file", "")
        val mime = params.optString(
            "mime",
            params.optString("content-type", if (file.isNotBlank()) "application/octet-stream" else "text/plain"),
        )
        val action = params.optString("action", if (file.isNotBlank()) "view" else "send").lowercase(Locale.US)
        val intentAction = when (action) {
            "edit" -> if (file.isNotBlank()) Intent.ACTION_EDIT else Intent.ACTION_SEND
            "send" -> Intent.ACTION_SEND
            else -> if (file.isNotBlank()) Intent.ACTION_VIEW else Intent.ACTION_SEND
        }
        val intent = Intent(intentAction).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            type = mime
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
            if (file.isNotBlank()) {
                val uri = Uri.fromFile(File(file))
                if (intentAction == Intent.ACTION_SEND) {
                    putExtra(Intent.EXTRA_STREAM, uri)
                } else {
                    setDataAndType(uri, mime)
                }
            }
        }
        val launchIntent = if (params.optBoolean("defaultReceiver", params.optBoolean("default-receiver", false))) {
            intent
        } else {
            Intent.createChooser(intent, params.optString("title", "Share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launchIntent)
        return ApiResponse.RawObject(JSONObject().apply {
            put("started", true)
            put("action", action)
            put("mime", mime)
            put("chooser", launchIntent !== intent)
        })
    }

    private fun mediaPlayer(params: JSONObject): ApiResponse {
        return when (params.optString("action", "info").lowercase(Locale.US)) {
            "play", "resume" -> {
                val source = params.optString("source", params.optString("file", params.optString("url", "")))
                if (source.isBlank()) {
                    mediaPlayer?.start() ?: return ApiResponse.Error("No media player is active")
                    return mediaPlayerInfo()
                }
                mediaPlayer?.release()
                val player = MediaPlayer().apply {
                    setDataSource(source)
                    setOnCompletionListener { it.release(); if (mediaPlayer === it) mediaPlayer = null }
                    prepare()
                    start()
                }
                mediaPlayer = player
                mediaPlayerSource = source
                mediaPlayerInfo()
            }
            "pause" -> {
                mediaPlayer?.pause()
                mediaPlayerInfo()
            }
            "stop" -> {
                mediaPlayer?.release()
                mediaPlayer = null
                mediaPlayerSource = null
                mediaPlayerInfo()
            }
            "info" -> mediaPlayerInfo()
            else -> ApiResponse.Error("Unsupported media-player action")
        }
    }

    private fun mediaPlayerInfo(): ApiResponse {
        val player = mediaPlayer
        return ApiResponse.RawObject(JSONObject().apply {
            put("active", player != null)
            put("source", mediaPlayerSource ?: JSONObject.NULL)
            if (player != null) {
                put("is_playing", runCatching { player.isPlaying }.getOrDefault(false))
                put("position", runCatching { player.currentPosition }.getOrNull() ?: JSONObject.NULL)
                put("duration", runCatching { player.duration }.getOrNull() ?: JSONObject.NULL)
            }
        })
    }

    private fun microphoneRecord(params: JSONObject): ApiResponse {
        return when (params.optString("action", "info").lowercase(Locale.US)) {
            "start", "record" -> {
                requirePermission(Manifest.permission.RECORD_AUDIO)?.let { return it }
                val encoder = params.optString("encoder", "aac")
                val path = params.optString("file", params.optString("path", "")).ifBlank {
                    fileOperations.writeTransferCache(ByteArray(0), microphoneExtension(encoder))
                        .fold(
                            onSuccess = { it.path },
                            onFailure = { return ApiResponse.Error("Failed to allocate transfer-cache recording path: ${it.message}") },
                        )
                }
                recorder?.release()
                val rec = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    val format = microphoneOutputFormat(encoder)
                        ?: return ApiResponse.Error("Unsupported microphone encoder on this Android version: $encoder")
                    val audioEncoder = microphoneAudioEncoder(encoder)
                        ?: return ApiResponse.Error("Unsupported microphone encoder: $encoder")
                    setOutputFormat(format)
                    setAudioEncoder(audioEncoder)
                    if (params.has("bitRate")) setAudioEncodingBitRate(params.optInt("bitRate"))
                    if (params.has("bitrate")) setAudioEncodingBitRate(params.optInt("bitrate"))
                    if (params.has("sampleRate")) setAudioSamplingRate(params.optInt("sampleRate"))
                    if (params.has("srate")) setAudioSamplingRate(params.optInt("srate"))
                    if (params.has("channels")) setAudioChannels(params.optInt("channels").coerceAtLeast(1))
                    val limitMs = params.optLong("limitMs", params.optLong("limit", 0L)).coerceAtLeast(0L)
                    if (limitMs > 0L) {
                        setMaxDuration(limitMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        setOnInfoListener { mediaRecorder, what, _ ->
                            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED && recorder === mediaRecorder) {
                                runCatching { mediaRecorder.stop() }
                                mediaRecorder.release()
                                recorder = null
                            }
                        }
                    }
                    setOutputFile(path)
                    prepare()
                    start()
                }
                recorder = rec
                recorderPath = path
                recorderStartedAtMs = SystemClock.elapsedRealtime()
                microphoneRecordInfo()
            }
            "stop", "quit" -> {
                recorder?.let {
                    runCatching { it.stop() }
                    it.release()
                }
                recorder = null
                microphoneRecordInfo()
            }
            "info" -> microphoneRecordInfo()
            else -> ApiResponse.Error("Unsupported microphone-record action")
        }
    }

    private fun microphoneOutputFormat(encoder: String): Int? = when (encoder.lowercase(Locale.US)) {
        "amr_nb", "amr-nb", "amr" -> MediaRecorder.OutputFormat.THREE_GPP
        "amr_wb", "amr-wb" -> MediaRecorder.OutputFormat.THREE_GPP
        "opus" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaRecorder.OutputFormat.OGG else null
        else -> MediaRecorder.OutputFormat.MPEG_4
    }

    private fun microphoneAudioEncoder(encoder: String): Int? = when (encoder.lowercase(Locale.US)) {
        "aac" -> MediaRecorder.AudioEncoder.AAC
        "amr_nb", "amr-nb", "amr" -> MediaRecorder.AudioEncoder.AMR_NB
        "amr_wb", "amr-wb" -> MediaRecorder.AudioEncoder.AMR_WB
        "opus" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaRecorder.AudioEncoder.OPUS else null
        else -> null
    }

    private fun microphoneExtension(encoder: String): String = when (encoder.lowercase(Locale.US)) {
        "amr_nb", "amr-nb", "amr", "amr_wb", "amr-wb" -> "amr"
        "opus" -> "ogg"
        else -> "m4a"
    }

    private fun microphoneRecordInfo(): ApiResponse =
        ApiResponse.RawObject(JSONObject().apply {
            put("recording", recorder != null)
            put("path", recorderPath ?: JSONObject.NULL)
            put("startedAtElapsedMs", if (recorderStartedAtMs > 0) recorderStartedAtMs else JSONObject.NULL)
            put("durationMs", if (recorderStartedAtMs > 0 && recorder != null) SystemClock.elapsedRealtime() - recorderStartedAtMs else 0)
        })

    private fun cameraInfo(): ApiResponse {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val arr = JSONArray()
        manager.cameraIdList.forEach { id ->
            val c = manager.getCameraCharacteristics(id)
            arr.put(JSONObject().apply {
                put("id", id)
                put("facing", when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                })
                put("flash", c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
                put("orientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: JSONObject.NULL)
            })
        }
        return ApiResponse.RawArray(arr)
    }

    private fun cameraPhoto(params: JSONObject): ApiResponse {
        requirePermission(Manifest.permission.CAMERA)?.let { return it }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = params.optString("camera", params.optString("cameraId", "")).ifBlank {
            defaultCameraId(manager) ?: return ApiResponse.Error("No camera available")
        }
        val timeoutMs = params.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(1000L, 30000L)
        val bytes = captureCameraJpeg(manager, cameraId, timeoutMs).getOrElse { error ->
            return ApiResponse.Error("Camera capture failed: ${error.message}")
        }
        val outputPath = params.optString("output", params.optString("path", params.optString("file", "")))
        if (outputPath.isNotBlank()) {
            return try {
                val file = File(outputPath)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                ApiResponse.RawObject(JSONObject().apply {
                    put("path", file.absolutePath)
                    put("bytes", bytes.size)
                    put("content_type", "image/jpeg")
                    put("camera", cameraId)
                })
            } catch (e: Exception) {
                ApiResponse.Error("Failed to write camera photo: ${e.message}")
            }
        }
        return fileOperations.writeTransferCache(bytes, "jpg").fold(
            onSuccess = { cached ->
                ApiResponse.RawObject(JSONObject().apply {
                    put("path", cached.path)
                    put("bytes", cached.bytes)
                    put("content_type", "image/jpeg")
                    put("camera", cameraId)
                })
            },
            onFailure = { error -> ApiResponse.Error("Failed to cache camera photo: ${error.message}") },
        )
    }

    private fun defaultCameraId(manager: CameraManager): String? {
        val back = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        return back ?: manager.cameraIdList.firstOrNull()
    }

    private fun captureCameraJpeg(
        manager: CameraManager,
        cameraId: String,
        timeoutMs: Long,
    ): Result<ByteArray> {
        val thread = HandlerThread("autotermux-camera-capture").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var previewSurface: Surface? = null
        var previewTexture: SurfaceTexture? = null
        var resultBytes: ByteArray? = null
        var failure: Throwable? = null

        fun fail(error: Throwable) {
            if (failure == null && resultBytes == null) {
                failure = error
                latch.countDown()
            }
        }

        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val largest = largestJpegSize(characteristics)
                ?: return Result.failure(IllegalStateException("Camera $cameraId has no JPEG output size"))
            reader = ImageReader.newInstance(largest.width, largest.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ imageReader ->
                    try {
                        imageReader.acquireNextImage().use { image ->
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            resultBytes = bytes
                        }
                    } catch (e: Exception) {
                        failure = e
                    } finally {
                        latch.countDown()
                    }
                }, handler)
            }

            previewTexture = SurfaceTexture(1).apply {
                setDefaultBufferSize(640, 480)
            }
            previewSurface = Surface(previewTexture)
            val imageSurface = reader.surface

            @Suppress("MissingPermission")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(openedCamera: CameraDevice) {
                    camera = openedCamera
                    openedCamera.createCaptureSession(
                        listOf(imageSurface, previewSurface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configuredSession: CameraCaptureSession) {
                                session = configuredSession
                                try {
                                    val previewRequest = openedCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(previewSurface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                    }.build()
                                    configuredSession.setRepeatingRequest(previewRequest, null, handler)
                                    handler.postDelayed({
                                        try {
                                            configuredSession.stopRepeating()
                                            val request = openedCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(imageSurface)
                                                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation(characteristics))
                                            }.build()
                                            configuredSession.capture(request, null, handler)
                                        } catch (e: Exception) {
                                            fail(e)
                                        }
                                    }, 500L)
                                } catch (e: Exception) {
                                    fail(e)
                                }
                            }

                            override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                                fail(IllegalStateException("Camera capture session configuration failed"))
                            }
                        },
                        handler,
                    )
                }

                override fun onDisconnected(disconnectedCamera: CameraDevice) {
                    fail(IllegalStateException("Camera disconnected"))
                }

                override fun onError(errorCamera: CameraDevice, error: Int) {
                    fail(IllegalStateException("Camera error $error"))
                }
            }, handler)

            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                failure = IllegalStateException("Camera capture timeout")
            }
            return resultBytes?.let { Result.success(it) }
                ?: Result.failure(failure ?: IllegalStateException("Camera capture produced no image"))
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { reader?.close() }
            runCatching { previewSurface?.release() }
            runCatching { previewTexture?.release() }
            thread.quitSafely()
        }
    }

    private fun largestJpegSize(characteristics: CameraCharacteristics): Size? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        return map.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun jpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        @Suppress("DEPRECATION")
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val deviceOrientation = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceOrientation) % 360
        } else {
            (sensorOrientation - deviceOrientation + 360) % 360
        }
    }

    private fun infraredFrequencies(): ApiResponse {
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            ?: return ApiResponse.Error("Consumer IR service unavailable")
        if (!manager.hasIrEmitter()) return ApiResponse.Error("No infrared emitter available")
        val arr = JSONArray()
        manager.carrierFrequencies.forEach { range ->
            arr.put(JSONObject().apply {
                put("min", range.minFrequency)
                put("max", range.maxFrequency)
            })
        }
        return ApiResponse.RawArray(arr)
    }

    private fun infraredTransmit(params: JSONObject): ApiResponse {
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
            ?: return ApiResponse.Error("Consumer IR service unavailable")
        if (!manager.hasIrEmitter()) return ApiResponse.Error("No infrared emitter available")
        val frequency = params.optInt("frequency")
        val pattern = jsonIntArray(params.opt("pattern"))
        if (frequency <= 0) return ApiResponse.Error("Missing frequency")
        if (pattern.isEmpty()) return ApiResponse.Error("Missing pattern")
        manager.transmit(frequency, pattern.toIntArray())
        return ApiResponse.RawObject(JSONObject().apply {
            put("frequency", frequency)
            put("pattern_length", pattern.size)
        })
    }

    private fun wallpaper(params: JSONObject): ApiResponse {
        val path = params.optString("file", params.optString("path", ""))
        val url = params.optString("url", "")
        if (path.isBlank() && url.isBlank()) return ApiResponse.Error("Missing file/path or url")
        if (path.isNotBlank() && url.isNotBlank()) return ApiResponse.Error("Specify only one of file/path or url")
        val lockscreen = params.optBoolean("lockscreen", params.optBoolean("lock", false))
        val manager = WallpaperManager.getInstance(context)
        val input = if (path.isNotBlank()) File(path).inputStream() else URL(url).openStream()
        input.use {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val which = if (lockscreen) WallpaperManager.FLAG_LOCK else WallpaperManager.FLAG_SYSTEM
                manager.setStream(it, null, true, which)
            } else {
                manager.setStream(it)
            }
        }
        return ApiResponse.RawObject(JSONObject().apply {
            put("source", path.ifBlank { url })
            put("lockscreen", lockscreen)
        })
    }

    private fun cursorArray(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        mapper: (Cursor) -> JSONObject,
    ): ApiResponse {
        val arr = JSONArray()
        return try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                while (cursor.moveToNext()) {
                    arr.put(mapper(cursor))
                }
                ApiResponse.RawArray(arr)
            } ?: ApiResponse.Error("Content provider unavailable: $uri")
        } catch (e: SecurityException) {
            ApiResponse.Error("Permission denied: ${e.message}")
        } catch (e: Exception) {
            ApiResponse.Error("Query failed: ${e.message}")
        }
    }

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.getIntOrNull(column: String): Int? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else null
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun jsonStringList(value: Any?): List<String> {
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
            is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }

    private fun apiArgs(params: JSONObject): List<String> =
        when (val value = params.opt("args")) {
            is JSONArray -> (0 until value.length()).map { value.optString(it) }.filter { it.isNotBlank() }
            is String -> value.split(',').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }

    private fun jsonIntArray(value: Any?): List<Int> {
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optInt(it) }
            is String -> value.split(',').mapNotNull { it.trim().toIntOrNull() }
            else -> emptyList()
        }
    }

    private fun jsonLongArray(value: Any?): List<Long> {
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optLong(it) }
            is String -> value.split(',').mapNotNull { it.trim().toLongOrNull() }
            else -> emptyList()
        }
    }
}
