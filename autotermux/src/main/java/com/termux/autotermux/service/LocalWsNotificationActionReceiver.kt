package com.termux.autotermux.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.termux.autotermux.config.ConfigManager

class LocalWsNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AutoTermuxAccessibilityService.ACTION_DISABLE_LOCAL_WS_SERVER) {
            return
        }

        val appContext = context.applicationContext
        val configManager = ConfigManager.getInstance(appContext)
        configManager.setWebSocketEnabledWithNotification(false)
        AutoTermuxAccessibilityService.getInstance()?.hideLocalWebSocketConnectionNotification()
        Log.i(
            AutoTermuxAccessibilityService.TAG,
            "Disabled local WebSocket server from notification action",
        )
    }
}
