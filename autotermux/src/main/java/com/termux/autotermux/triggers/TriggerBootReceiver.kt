package com.termux.autotermux.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TriggerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        TriggerRuntime.initialize(context.applicationContext)
        TriggerRuntime.onRulesChanged()
    }
}
