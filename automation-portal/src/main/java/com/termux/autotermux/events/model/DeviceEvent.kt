package com.termux.autotermux.events.model

import org.json.JSONObject

data class DeviceEvent(
    val type: EventType,
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = DEFAULT_SOURCE,
    val payload: Any? = null,
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("schema_version", SCHEMA_VERSION)
            put("type", type.name)
            put("timestamp", timestamp)
            put("source", source)

            when (payload) {
                is Map<*, *> -> put("payload", JSONObject(payload))
                is JSONObject -> put("payload", payload)
                is String -> put("payload", payload)
                null -> Unit
                else -> put("payload", payload.toString())
            }
        }
    }

    fun toJson(): String {
        return toJsonObject().toString()
    }

    fun toRpcNotificationJson(): String {
        return JSONObject().apply {
            put("method", "events/device")
            put("params", toJsonObject())
        }.toString()
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val DEFAULT_SOURCE = "autotermux"

        fun fromJson(jsonStr: String): DeviceEvent {
            try {
                val json = JSONObject(jsonStr)
                val typeStr = json.optString("type", "UNKNOWN")
                val type = try {
                    EventType.valueOf(typeStr)
                } catch (e: IllegalArgumentException) {
                    EventType.UNKNOWN
                }
                
                val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                val source = json.optString("source", DEFAULT_SOURCE)
                val payloadOpt = json.opt("payload")

                return DeviceEvent(type, timestamp, source, payloadOpt)
            } catch (e: Exception) {
                return DeviceEvent(EventType.UNKNOWN, payload = "Parse Error: ${e.message}")
            }
        }
    }
}
