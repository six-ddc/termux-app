package com.termux.autotermux.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class TriggerScheduler(
    private val context: Context,
) {
    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    fun rescheduleAll(rules: List<TriggerRule>) {
        rules.forEach { cancel(it.id) }
        rules.filter { it.enabled && it.isTimeRule() }.forEach(::schedule)
    }

    fun cancel(ruleId: String) {
        pendingIntent(ruleId, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager?.cancel(it) }
    }

    fun schedule(rule: TriggerRule) {
        val triggerAtMs = TriggerTimeSupport.nextFireAt(rule, System.currentTimeMillis()) ?: return
        if (triggerAtMs <= System.currentTimeMillis()) return
        val operation = pendingIntent(rule.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val manager = alarmManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            manager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
        } else {
            manager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, operation)
        }
    }

    private fun pendingIntent(ruleId: String, flags: Int): PendingIntent? {
        val intent = Intent(context, TriggerAlarmReceiver::class.java).apply {
            action = TriggerAlarmReceiver.ACTION_TRIGGER_ALARM
            putExtra(TriggerAlarmReceiver.EXTRA_RULE_ID, ruleId)
        }
        return PendingIntent.getBroadcast(
            context,
            ruleId.hashCode(),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
