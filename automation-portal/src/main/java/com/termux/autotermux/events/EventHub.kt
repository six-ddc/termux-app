package com.termux.autotermux.events

import android.util.Log
import com.termux.autotermux.config.ConfigManager
import com.termux.autotermux.events.model.EventType
import com.termux.autotermux.events.model.DeviceEvent

object EventHub {
    private const val TAG = "AutoTermuxEventHub"

    private val listeners = linkedSetOf<(DeviceEvent) -> Unit>()

    private var configManager: ConfigManager? = null

    fun init(config: ConfigManager) {
        this.configManager = config
    }

    fun subscribe(callback: (DeviceEvent) -> Unit) {
        synchronized(listeners) {
            listeners.add(callback)
        }
    }

    fun unsubscribe(callback: (DeviceEvent) -> Unit) {
        synchronized(listeners) {
            listeners.remove(callback)
        }
    }

    fun emit(event: DeviceEvent) {
        if (isEventEnabled(event.type)) {
            try {
                val snapshot = synchronized(listeners) { listeners.toList() }
                snapshot.forEach { it.invoke(event) }
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting event: ${e.message}")
            }
        }
    }

    private fun isEventEnabled(type: EventType): Boolean {
        val config = configManager ?: return type.defaultEnabled

        if (type == EventType.PONG || type == EventType.UNKNOWN) return true

        return config.isEventEnabled(type)
    }
}
