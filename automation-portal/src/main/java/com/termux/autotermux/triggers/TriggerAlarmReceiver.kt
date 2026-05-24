package com.termux.autotermux.triggers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TriggerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TRIGGER_ALARM) return
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID).orEmpty()
        if (ruleId.isBlank()) return
        TriggerRuntime.initialize(context.applicationContext)
        TriggerRuntime.handleScheduledRule(ruleId)
    }

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.termux.autotermux.triggers.TRIGGER_ALARM"
        const val EXTRA_RULE_ID = "com.termux.autotermux.triggers.RULE_ID"
    }
}
