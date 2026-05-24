package com.termux.autotermux.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Intent
import android.os.Bundle
import com.termux.autotermux.events.EventHub
import com.termux.autotermux.events.model.DeviceEvent
import com.termux.autotermux.events.model.EventType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationAccessService : NotificationListenerService() {
    companion object {
        @Volatile
        private var instance: NotificationAccessService? = null

        fun getInstance(): NotificationAccessService? = instance

        fun activeNotificationsJson(): JSONArray? {
            val service = instance ?: return null
            return service.activeNotificationsJsonInternal()
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        EventHub.emit(DeviceEvent(EventType.NOTIFICATION_POSTED, payload = statusBarNotificationToJson(sbn)))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        EventHub.emit(DeviceEvent(EventType.NOTIFICATION_REMOVED, payload = statusBarNotificationToJson(sbn)))
    }

    private fun activeNotificationsJsonInternal(): JSONArray {
        val arr = JSONArray()
        activeNotifications.orEmpty().forEach { arr.put(statusBarNotificationToJson(it)) }
        return arr
    }

    fun tapNotification(params: JSONObject): JSONObject {
        val sbn = findNotification(params)
        val intent = sbn.notification.contentIntent
            ?: throw IllegalArgumentException("Notification has no content intent")
        intent.send()
        val result = notificationActionResult(sbn, actionType = "tap", actionIndex = -1)
        EventHub.emit(DeviceEvent(EventType.NOTIFICATION_ACTION, payload = result))
        return result
    }

    fun performNotificationAction(params: JSONObject, replyText: String? = null): JSONObject {
        val sbn = findNotification(params)
        val actions = sbn.notification.actions.orEmpty()
        val actionIndex = params.optInt("actionIndex", params.optInt("index", -1))
        if (actionIndex !in actions.indices) {
            throw IllegalArgumentException("Notification action index $actionIndex is not available")
        }
        val action = actions[actionIndex]
        val actionIntent = action.actionIntent
            ?: throw IllegalArgumentException("Notification action $actionIndex has no pending intent")
        val remoteInputs = action.remoteInputs.orEmpty()
        val sendIntent = Intent()
        if (replyText != null) {
            if (remoteInputs.isEmpty()) {
                throw IllegalArgumentException("Notification action $actionIndex does not accept inline reply")
            }
            val replyBundle = Bundle()
            remoteInputs.forEachIndexed { index, remoteInput ->
                val value = if (index == 0) {
                    replyText
                } else {
                    params.optString(remoteInput.resultKey, replyText)
                }
                replyBundle.putCharSequence(remoteInput.resultKey, value)
            }
            android.app.RemoteInput.addResultsToIntent(remoteInputs, sendIntent, replyBundle)
        }
        actionIntent.send(this, 0, sendIntent)
        if (params.optBoolean("cancel", false)) {
            cancelNotification(sbn.key)
        }
        val result = notificationActionResult(
            sbn = sbn,
            actionType = if (replyText == null) "action" else "reply",
            actionIndex = actionIndex,
            actionTitle = action.title?.toString().orEmpty(),
            replyText = replyText,
        )
        EventHub.emit(
            DeviceEvent(
                if (replyText == null) EventType.NOTIFICATION_ACTION else EventType.NOTIFICATION_REPLY,
                payload = result,
            ),
        )
        return result
    }

    private fun findNotification(params: JSONObject): StatusBarNotification {
        val key = params.optString("key", "")
        val packageName = params.optString("packageName", params.optString("package", ""))
        val tag = params.optString("tag", "")
        val id = if (params.has("id")) params.optInt("id") else null
        return activeNotifications.orEmpty().firstOrNull { sbn ->
            (key.isBlank() || sbn.key == key) &&
                (packageName.isBlank() || sbn.packageName == packageName) &&
                (tag.isBlank() || (sbn.tag ?: "") == tag) &&
                (id == null || sbn.id == id)
        } ?: throw IllegalArgumentException("Notification not found")
    }
}

internal fun statusBarNotificationToJson(sbn: StatusBarNotification): JSONObject {
    val notification = sbn.notification
    val extras = notification.extras
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
    return notificationBaseJson(sbn).apply {
        put("id", sbn.id)
        put("tag", sbn.tag ?: "")
        put("key", sbn.key ?: "")
        put("group", notification.group ?: "")
        put("packageName", sbn.packageName ?: "")
        put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
        put(
            "content",
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: "",
        )
        put("when", dateFormat.format(Date(notification.`when`)))
        put("postTime", sbn.postTime)
        put("isClearable", sbn.isClearable)
        put("isOngoing", sbn.isOngoing)
        put("hasContentIntent", notification.contentIntent != null)
        put("actions", notificationActionsJson(notification))
        if (lines != null) {
            put("lines", JSONArray().apply {
                lines.forEach { put(it?.toString() ?: "") }
            })
        }
    }
}

private fun notificationBaseJson(sbn: StatusBarNotification): JSONObject =
    JSONObject().apply {
        put("id", sbn.id)
        put("tag", sbn.tag ?: "")
        put("key", sbn.key ?: "")
        put("packageName", sbn.packageName ?: "")
    }

private fun notificationActionsJson(notification: Notification): JSONArray =
    JSONArray().apply {
        notification.actions.orEmpty().forEachIndexed { index, action ->
            put(JSONObject().apply {
                put("index", index)
                put("title", action.title?.toString().orEmpty())
                put("hasRemoteInput", !action.remoteInputs.isNullOrEmpty())
                put("remoteInputs", JSONArray().apply {
                    action.remoteInputs.orEmpty().forEach { input ->
                        put(JSONObject().apply {
                            put("resultKey", input.resultKey)
                            put("label", input.label?.toString().orEmpty())
                            if (input.choices != null) {
                                put("choices", JSONArray().apply {
                                    input.choices.orEmpty().forEach { put(it?.toString().orEmpty()) }
                                })
                            }
                        })
                    }
                })
            })
        }
    }

private fun notificationActionResult(
    sbn: StatusBarNotification,
    actionType: String,
    actionIndex: Int,
    actionTitle: String = "",
    replyText: String? = null,
): JSONObject =
    notificationBaseJson(sbn).apply {
        put("actionType", actionType)
        put("actionIndex", actionIndex)
        put("actionTitle", actionTitle)
        if (replyText != null) put("reply", replyText)
    }
