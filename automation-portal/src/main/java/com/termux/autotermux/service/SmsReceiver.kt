package com.termux.autotermux.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.termux.autotermux.events.EventHub
import com.termux.autotermux.events.model.DeviceEvent
import com.termux.autotermux.events.model.EventType
import org.json.JSONArray
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val arr = JSONArray()
        messages.forEach { message ->
            arr.put(JSONObject().apply {
                put("address", message.displayOriginatingAddress ?: message.originatingAddress ?: "")
                put("body", message.messageBody ?: "")
                put("timestamp", message.timestampMillis)
                put("serviceCenterAddress", message.serviceCenterAddress ?: JSONObject.NULL)
                put("status", message.status)
                put("indexOnIcc", message.indexOnIcc)
                put("protocolIdentifier", message.protocolIdentifier)
            })
        }
        EventHub.emit(DeviceEvent(EventType.SMS_RECEIVED, payload = JSONObject().apply {
            put("messages", arr)
        }))
    }
}
