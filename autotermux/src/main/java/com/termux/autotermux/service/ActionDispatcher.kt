package com.termux.autotermux.service

import android.util.Base64
import com.termux.autotermux.api.ApiHandler
import com.termux.autotermux.api.ApiResponse
import com.termux.autotermux.triggers.TriggerApi
import org.json.JSONArray
import org.json.JSONObject

class ActionDispatcher(
    private val apiHandler: ApiHandler,
    private val maxBase64UploadBytes: Long = MAX_BASE64_UPLOAD_BYTES,
) {

    companion object {
        private const val DEFAULT_SWIPE_DURATION_MS = 300
        private const val MAX_BASE64_UPLOAD_BYTES = 16L * 1024L * 1024L
    }

    enum class Origin {
        BRIDGE,
        HTTP,
        PROVIDER,
        WEBSOCKET_LOCAL,
    }

    fun dispatch(
        action: String,
        params: JSONObject,
        origin: Origin = Origin.WEBSOCKET_LOCAL,
        requestId: Any? = null,
    ): ApiResponse {
        val method = normalizeAction(action)
        val termuxApiPrefix = when {
            method.startsWith("termux-api/") -> "termux-api/"
            method.startsWith("termux_api/") -> "termux_api/"
            else -> null
        }
        if (termuxApiPrefix != null) {
            val command = method.removePrefix(termuxApiPrefix).trim()
            return if (command.isBlank()) {
                ApiResponse.Error("Missing termux-api command")
            } else {
                apiHandler.callTermuxApiCompat(command, params)
            }
        }
        if (method == "triggers" || method.startsWith("triggers/")) {
            return TriggerApi(apiHandler.applicationContext).dispatch(method, params)
        }

        return when (method) {
            "ping" -> apiHandler.ping()

            "tap" -> apiHandler.performTap(params.optInt("x", 0), params.optInt("y", 0))

            "swipe" -> apiHandler.performSwipe(
                params.optInt("startX", 0),
                params.optInt("startY", 0),
                params.optInt("endX", 0),
                params.optInt("endY", 0),
                params.optInt("duration", DEFAULT_SWIPE_DURATION_MS),
            )

            "global" -> apiHandler.performGlobalAction(params.optInt("action", 0))

            "a11y_tree", "tree", "ui/tree" -> apiHandler.getTree()

            "a11y_tree_full", "tree/full", "ui/tree/full" ->
                apiHandler.getTreeFull(params.optBoolean("filter", true))

            "phone_state", "phone-state", "ui/phone-state" -> apiHandler.getPhoneState()

            "state" -> apiHandler.getState()

            "state_full", "state/full" -> apiHandler.getStateFull(params.optBoolean("filter", true))

            "state/connection", "connection/state" -> apiHandler.getConnectionState()

            "a11y_tree/cache", "tree/cache", "ui/tree/cache" ->
                apiHandler.cacheTree(full = false, filter = params.optBoolean("filter", true))

            "a11y_tree_full/cache", "tree/full/cache", "ui/tree/full/cache" ->
                apiHandler.cacheTree(full = true, filter = params.optBoolean("filter", true))

            "phone_state/cache", "phone-state/cache", "ui/phone-state/cache" ->
                apiHandler.cachePhoneState()

            "packages/cache" -> apiHandler.cachePackages()

            "state/cache" ->
                apiHandler.cacheState(full = false, filter = params.optBoolean("filter", true))

            "state_full/cache", "state/full/cache" ->
                apiHandler.cacheState(full = true, filter = params.optBoolean("filter", true))

            "node/action", "element/action", "ui/action" -> apiHandler.performNodeAction(params)

            "node/find", "element/find", "ui/find" -> apiHandler.findNodes(params)

            "node/find/cache", "element/find/cache", "ui/find/cache" -> apiHandler.cacheFindNodes(params)

            "node/focused", "element/focused", "ui/focused" -> apiHandler.getFocusedNode()

            "node/focused/cache", "element/focused/cache", "ui/focused/cache" -> apiHandler.cacheFocusedNode()

            "app" -> {
                val pkg = params.optString("package").ifEmpty {
                    params.optString("packageName")
                }
                if (pkg.isBlank()) return ApiResponse.Error("Missing required param: 'package'")
                val activity = params.optString("activity")
                    .takeUnless { it.isBlank() || it == "null" }
                if (params.optBoolean("stopBeforeLaunch", false)) {
                    apiHandler.stopApp(pkg)
                }
                apiHandler.startApp(pkg, activity)
            }

            "app/stop" -> {
                val pkg = params.optString("package").ifEmpty {
                    params.optString("packageName")
                }
                if (pkg.isBlank()) return ApiResponse.Error("Missing required param: 'package'")
                apiHandler.stopApp(pkg)
            }

            "app/info", "package/info" -> {
                val pkg = params.optString("package").ifEmpty {
                    params.optString("packageName")
                }
                apiHandler.getAppInfo(pkg)
            }

            "app/info/cache", "package/info/cache" -> {
                val pkg = params.optString("package").ifEmpty {
                    params.optString("packageName")
                }
                apiHandler.cacheAppInfo(pkg)
            }

            "app/open-url", "app/url", "open-url" ->
                apiHandler.openUrl(
                    params.optString("url", params.optString("data", "")),
                    params.optString("package", params.optString("packageName", "")).ifBlank { null },
                )

            "app/settings", "app/open-settings", "package/settings" -> {
                val pkg = params.optString("package").ifEmpty {
                    params.optString("packageName")
                }
                apiHandler.openAppSettings(pkg, params.optString("screen", "details"))
            }

            "app/intent", "intent/start", "start-intent" -> apiHandler.startIntent(params)

            "app/uninstall", "package/uninstall" -> {
                val pkg = params.optString("package").ifEmpty {
                    params.optString("packageName")
                }
                apiHandler.requestUninstallApp(pkg)
            }

            "keyboard/input", "input" ->
                apiHandler.keyboardInput(params.optString("base64_text", ""), params.optBoolean("clear", true))

            "keyboard/clear", "clear" -> apiHandler.keyboardClear()

            "keyboard/key", "key" -> apiHandler.keyboardKey(params.optInt("key_code", 0))

            "clipboard/get" -> apiHandler.getClipboard()

            "clipboard/set" -> {
                val text = when {
                    params.has("text") -> params.optString("text")
                    params.has("text_base64") -> decodeUtf8Base64(params.optString("text_base64"))
                        ?: return ApiResponse.Error("Invalid text_base64")
                    else -> return ApiResponse.Error("Missing required param: 'text'")
                }
                apiHandler.setClipboard(text)
            }

            "overlay_offset", "overlay/offset" ->
                apiHandler.setOverlayOffset(params.optInt("offset", 0))

            "overlay/set-visible" ->
                apiHandler.setOverlayVisible(params.optBoolean("visible", false))

            "overlay/visible", "overlay/is-visible" -> apiHandler.isOverlayVisible()

            "overlay/auto-offset/status", "overlay/auto_offset/status" ->
                apiHandler.getOverlayAutoOffsetStatus()

            "overlay/auto-offset/set", "overlay/auto_offset/set" -> {
                if (!params.has("enabled")) {
                    return ApiResponse.Error("Missing required param: 'enabled'")
                }
                apiHandler.setOverlayAutoOffsetEnabled(params.optBoolean("enabled"))
            }

            "socket_port" -> apiHandler.setSocketPort(params.optInt("port", 0))

            "screenshot" -> apiHandler.getScreenshot(params.optBoolean("hideOverlay", true))

            "screenshot/cache", "screenshot/file" ->
                apiHandler.cacheScreenshot(params.optBoolean("hideOverlay", true))

            "packages" -> apiHandler.getPackages()

            "version" -> apiHandler.getVersion()

            "time" -> apiHandler.getTime()

            "device/id", "device/identity" -> apiHandler.getDeviceIdentity()

            "config/remote", "remote_config" ->
                apiHandler.getRemoteConfiguration(params.optBoolean("showToken", false))

            "termux-api", "termux_api" -> {
                val command = params.optString("command", params.optString("api", ""))
                if (command.isBlank()) return ApiResponse.Error("Missing required param: 'command'")
                apiHandler.callTermuxApiCompat(command, params)
            }

            "files/list" -> apiHandler.listFiles(params.optString("path", ""))

            "files/download" -> apiHandler.downloadFile(params.optString("path", ""))

            "files/download-cache", "files/stage-download" ->
                apiHandler.stageFileDownload(params.optString("path", ""))

            "files/upload" -> handleFileUpload(params)

            "files/import-cache", "files/commit-upload" -> apiHandler.importStagedFile(
                params.optString("path", ""),
                params.optString("cachePath", "").ifEmpty { params.optString("cache_path", "") },
            )

            "files/delete" -> apiHandler.deleteFile(params.optString("path", ""))

            "files/fetch" -> apiHandler.fetchFile(
                params.optString("url", ""),
                params.optString("path", ""),
            )

            "files/push" -> apiHandler.pushFile(
                params.optString("url", ""),
                params.optString("path", ""),
            )

            "screen/keepAwake/set" -> {
                if (!params.has("enabled")) {
                    return ApiResponse.Error("Missing required param: 'enabled'")
                }
                apiHandler.setScreenKeepAwakeEnabled(params.optBoolean("enabled"))
            }

            "screen/keepAwake/status" -> apiHandler.getScreenKeepAwakeStatus()

            "screen/status", "screen/info" -> apiHandler.getScreenStatus()

            "screen/wake" ->
                apiHandler.wakeScreen(params.optInt("durationMs", params.optInt("duration_ms", 3000)))

            "screen/lock" -> apiHandler.lockScreen()

            "screen/orientation/status", "screen/orientation/get" ->
                apiHandler.getScreenOrientationStatus()

            "screen/orientation/set" ->
                apiHandler.setScreenOrientation(params.optString("mode", ""))

            "screen/record/start" -> apiHandler.startScreenRecording(params)

            "screen/record/stop" -> apiHandler.stopScreenRecording()

            "screen/record/status" -> apiHandler.getScreenRecordingStatus()

            "install" -> {
                val urlsArray: JSONArray = params.optJSONArray("urls")
                    ?: return ApiResponse.Error("Missing required param: 'urls'")
                val urls = mutableListOf<String>()
                for (i in 0 until urlsArray.length()) {
                    val url = urlsArray.optString(i, "").trim()
                    if (url.isNotEmpty()) urls.add(url)
                }
                if (urls.isEmpty()) {
                    ApiResponse.Error("Missing required param: 'urls'")
                } else {
                    apiHandler.installFromUrls(urls, params.optBoolean("hideOverlay", false))
                }
            }

            else -> ApiResponse.Error("Unknown method: $method")
        }
    }

    private fun normalizeAction(action: String): String =
        action.removePrefix("/action/")
            .removePrefix("action.")
            .removePrefix("/")

    private fun decodeUtf8Base64(base64: String): String? {
        return try {
            String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun estimateDecodedBase64Size(base64: String): Long {
        val normalized = base64.filterNot(Char::isWhitespace)
        if (normalized.isEmpty()) return 0L
        val padding = when {
            normalized.endsWith("==") -> 2
            normalized.endsWith("=") -> 1
            else -> 0
        }
        return ((normalized.length.toLong() + 3L) / 4L) * 3L - padding
    }

    private fun handleFileUpload(params: JSONObject): ApiResponse {
        val path = params.optString("path", "")
        val dataBase64 = params.optString("data", "")
        if (path.isEmpty()) return ApiResponse.Error("Missing required param: 'path'")
        if (dataBase64.isEmpty()) return ApiResponse.Error("Missing required param: 'data'")
        if (estimateDecodedBase64Size(dataBase64) > maxBase64UploadBytes) {
            return ApiResponse.Error(
                "Data too large for files/upload (max ${maxBase64UploadBytes / 1024 / 1024}MB decoded); use files/fetch for larger files",
            )
        }
        return try {
            apiHandler.uploadFile(path, Base64.decode(dataBase64, Base64.DEFAULT))
        } catch (e: Exception) {
            ApiResponse.Error("Invalid base64 data: ${e.message}")
        }
    }
}
