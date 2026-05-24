package com.termux.autotermux.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.termux.autotermux.events.EventHub
import com.termux.autotermux.events.model.DeviceEvent
import com.termux.autotermux.events.model.EventType
import org.json.JSONObject

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_NOTIFICATION_EVENT = "com.termux.autotermux.action.NOTIFICATION_EVENT"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_NOTIFICATION_TAG = "notification_tag"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_ACTION_TITLE = "action_title"
        const val EXTRA_ACTION_INDEX = "action_index"
        const val EXTRA_AUTO_CANCEL = "auto_cancel"
        const val KEY_TEXT_REPLY = "reply"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_NOTIFICATION_EVENT) return

        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)
            ?.toString()
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1000)
        val notificationTag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG).orEmpty()
        val actionId = intent.getStringExtra(EXTRA_ACTION_ID).orEmpty()
        val actionTitle = intent.getStringExtra(EXTRA_ACTION_TITLE).orEmpty()
        val actionIndex = intent.getIntExtra(EXTRA_ACTION_INDEX, -1)
        val payload = JSONObject().apply {
            put("notificationId", notificationId)
            put("tag", notificationTag)
            put("actionId", actionId)
            put("actionTitle", actionTitle)
            put("actionIndex", actionIndex)
            if (replyText != null) put("reply", replyText)
        }

        EventHub.emit(
            DeviceEvent(
                if (replyText == null) EventType.NOTIFICATION_ACTION else EventType.NOTIFICATION_REPLY,
                payload = payload,
            ),
        )

        if (intent.getBooleanExtra(EXTRA_AUTO_CANCEL, false)) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationTag.ifBlank { null }, notificationId)
        }
    }
}
