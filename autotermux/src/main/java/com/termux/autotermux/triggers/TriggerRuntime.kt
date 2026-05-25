package com.termux.autotermux.triggers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.termux.autotermux.audit.AuditEntry
import com.termux.autotermux.audit.AuditLog
import com.termux.autotermux.config.ConfigManager
import com.termux.autotermux.events.EventHub
import com.termux.autotermux.events.model.DeviceEvent
import com.termux.autotermux.events.model.EventType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object TriggerRuntime {
    private const val BATTERY_LOW_THRESHOLD = 15
    private const val DRAIN_INTERVAL_SECONDS = 1L

    private val initLock = Any()
    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var repository: TriggerRepository
    private lateinit var scheduler: TriggerScheduler
    private lateinit var commandLauncher: TriggerTermuxCommandLauncher
    private lateinit var configManager: ConfigManager

    private var batteryReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastBatteryLevel: Int? = null
    private var lastCharging: Boolean? = null
    private var lastNetworkType: TriggerNetworkType? = null

    private val busyQueue = TriggerBusyQueue(maxSize = 32)
    private var notificationDebounceBuffer: NotificationDebounceBuffer? = null
    private var drainExecutor: ScheduledExecutorService? = null

    private val eventListener: (DeviceEvent) -> Unit = { event ->
        handleDeviceEvent(event)
    }

    fun initialize(context: Context) {
        synchronized(initLock) {
            appContext = context.applicationContext
            configManager = ConfigManager.getInstance(appContext)
            repository = TriggerRepository.getInstance(appContext)
            scheduler = TriggerScheduler(appContext)
            commandLauncher = TriggerTermuxCommandLauncher(appContext)
            normalizePersistedRules()

            if (initialized) return

            notificationDebounceBuffer = NotificationDebounceBuffer(
                onFlush = { rule, _, _, _, mergedSignal ->
                    evaluateRule(rule, mergedSignal, isTestRun = false)
                },
            )
            drainExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "TriggerBusyDrain").apply { isDaemon = true }
            }
            drainExecutor?.scheduleAtFixedRate(
                { runCatching { drainBusyQueue() } },
                DRAIN_INTERVAL_SECONDS,
                DRAIN_INTERVAL_SECONDS,
                TimeUnit.SECONDS,
            )

            EventHub.subscribe(eventListener)
            registerBatteryReceiver()
            registerScreenReceiver()
            registerNetworkCallback()
            scheduler.rescheduleAll(repository.listRules())
            initialized = true
        }
    }

    fun busyQueueSize(): Int = busyQueue.size()

    fun listRules(): List<TriggerRule> {
        ensureInitialized()
        return repository.listRules().map(::normalizeRule)
    }

    fun getRule(ruleId: String): TriggerRule? {
        ensureInitialized()
        return repository.getRule(ruleId)?.let(::normalizeRule)
    }

    fun listRuns(limit: Int = 50): List<TriggerRunRecord> {
        ensureInitialized()
        return repository.listRuns(limit)
    }

    fun saveRule(rule: TriggerRule) {
        ensureInitialized()
        repository.saveRule(normalizeRule(rule))
        onRulesChanged()
    }

    fun deleteRule(ruleId: String) {
        ensureInitialized()
        repository.deleteRule(ruleId)
        scheduler.cancel(ruleId)
        onRulesChanged()
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        ensureInitialized()
        repository.getRule(ruleId)?.let { rule ->
            repository.saveRule(normalizeRule(rule.copy(enabled = enabled)))
        }
        onRulesChanged()
    }

    fun launchTest(ruleId: String) {
        ensureInitialized()
        val rule = repository.getRule(ruleId) ?: return
        evaluateRule(rule, buildTestSignal(rule), isTestRun = true)
    }

    fun deleteRun(runId: String) {
        ensureInitialized()
        repository.deleteRun(runId)
    }

    fun clearRuns() {
        ensureInitialized()
        repository.clearRuns()
    }

    fun onRulesChanged() {
        ensureInitialized()
        normalizePersistedRules()
        scheduler.rescheduleAll(repository.listRules())
    }

    fun handleScheduledRule(ruleId: String) {
        ensureInitialized()
        val rule = repository.getRule(ruleId) ?: return
        if (!rule.enabled) return
        if (!configManager.armed) {
            auditTriggerBlocked(rule, TriggerSignal(rule.source))
            return
        }
        evaluateRule(
            rule,
            TriggerSignal(
                source = rule.source,
                payload = mapOf(
                    "rule_id" to rule.id,
                    "rule_name" to rule.name,
                    "schedule_kind" to rule.source.name,
                ),
            ),
            isTestRun = false,
        )
    }

    private fun ensureInitialized() {
        if (!initialized && ::appContext.isInitialized) {
            initialize(appContext)
        }
    }

    private fun handleDeviceEvent(event: DeviceEvent) {
        val signals = eventToSignals(event)
        if (signals.isEmpty()) return
        if (!configManager.armed) {
            signals.distinctBy { it.source }.forEach { signal ->
                auditTriggerBlocked(rule = null, signal = signal)
            }
            return
        }
        signals.forEach { signal ->
            val nowMs = System.currentTimeMillis()
            repository.listRules()
                .asSequence()
                .filter { it.enabled && it.source == signal.source }
                .filter { it.hasLaunchLimitRemaining() }
                .filter { TriggerMatcher.matches(it, signal) }
                .forEach { rule -> dispatchSignal(rule, signal, nowMs) }
        }
    }

    private fun dispatchSignal(rule: TriggerRule, signal: TriggerSignal, nowMs: Long) {
        if (isCoolingDown(rule, nowMs)) {
            if (rule.busyPolicy == TriggerBusyPolicy.QUEUE) {
                val renderedPrompt = TriggerTemplateRenderer.render(rule.promptTemplate, rule, signal)
                val accepted = busyQueue.enqueue(
                    TriggerQueueEntry(
                        ruleId = rule.id,
                        ruleName = rule.name,
                        source = rule.source,
                        renderedPrompt = renderedPrompt,
                        signal = signal,
                    ),
                )
                logRun(
                    rule = rule,
                    disposition = if (accepted) TriggerRunDisposition.BUFFERED else TriggerRunDisposition.SKIPPED_BUSY,
                    summary = if (accepted) {
                        "Queued behind cooldown (size=${busyQueue.size()})"
                    } else {
                        "Busy queue full (size=${busyQueue.size()})"
                    },
                    signal = signal,
                    renderedPrompt = renderedPrompt,
                )
            } else {
                logRun(
                    rule = rule,
                    disposition = TriggerRunDisposition.SKIPPED_BUSY,
                    summary = "Cooling down; busyPolicy=SKIP",
                    signal = signal,
                )
            }
            return
        }
        if (rule.notificationDebounceMs > 0L && TriggerEditorSupport.isNotificationSource(rule.source)) {
            notificationDebounceBuffer?.let { buffer ->
                logRun(
                    rule = rule,
                    disposition = TriggerRunDisposition.DEBOUNCED,
                    summary = "Buffering notification (debounce=${rule.notificationDebounceMs}ms)",
                    signal = signal,
                )
                buffer.add(rule, signal)
                return
            }
        }
        evaluateRule(rule, signal, isTestRun = false)
    }

    private fun drainBusyQueue() {
        if (!initialized) return
        val nowMs = System.currentTimeMillis()
        val ready = mutableListOf<TriggerQueueEntry>()
        val retained = mutableListOf<TriggerQueueEntry>()
        while (true) {
            val entry = busyQueue.popNext() ?: break
            val rule = repository.getRule(entry.ruleId)
            if (rule == null || !rule.enabled || !rule.hasLaunchLimitRemaining()) continue
            if (isCoolingDown(rule, nowMs)) {
                retained.add(entry)
            } else {
                ready.add(entry)
            }
        }
        retained.asReversed().forEach(busyQueue::pushFront)
        ready.forEach { entry ->
            val rule = repository.getRule(entry.ruleId) ?: return@forEach
            evaluateRule(rule, entry.signal, isTestRun = false)
        }
    }

    private fun evaluateRule(rule: TriggerRule, signal: TriggerSignal, isTestRun: Boolean) {
        if (!isTestRun && !configManager.armed) {
            auditTriggerBlocked(rule, signal)
            return
        }
        val nowMs = System.currentTimeMillis()
        repository.updateRuleTimestamps(rule.id, matchedAtMs = nowMs, launchedAtMs = null)
        logRun(
            rule = rule,
            disposition = TriggerRunDisposition.MATCHED,
            summary = "Matched ${signal.source.name.lowercase().replace('_', ' ')}",
            signal = signal,
        )

        val renderedPrompt = TriggerTemplateRenderer.render(rule.promptTemplate, rule, signal)
        when (val result = commandLauncher.launch(rule, signal, renderedPrompt)) {
            TriggerTermuxCommandLauncher.Result.Success -> {
                val updatedRule = if (isTestRun) {
                    rule
                } else {
                    recordSuccessfulLaunch(rule)
                }
                logRun(
                    rule = updatedRule,
                    disposition = if (isTestRun) {
                        TriggerRunDisposition.TEST_LAUNCHED
                    } else {
                        TriggerRunDisposition.LAUNCHED
                    },
                    summary = "Launched Termux command ${rule.commandPath}",
                    signal = signal,
                    renderedPrompt = renderedPrompt,
                )
                auditTriggerFired(updatedRule, signal, isTestRun)
                finalizeTimeRuleIfNeeded(updatedRule, isTestRun)
            }

            is TriggerTermuxCommandLauncher.Result.Error -> {
                logRun(
                    rule = rule,
                    disposition = TriggerRunDisposition.LAUNCH_FAILED,
                    summary = result.message,
                    signal = signal,
                    renderedPrompt = renderedPrompt,
                )
                auditTriggerFailed(rule, signal, result.message, isTestRun)
                finalizeTimeRuleIfNeeded(rule, isTestRun)
            }
        }
    }

    private fun recordSuccessfulLaunch(rule: TriggerRule): TriggerRule {
        val currentRule = repository.getRule(rule.id) ?: rule
        val successfulLaunchCount = currentRule.successfulLaunchCount + 1
        val limitReached = currentRule.maxLaunchCount != null &&
            successfulLaunchCount >= currentRule.maxLaunchCount
        val updated = currentRule.copy(
            successfulLaunchCount = successfulLaunchCount,
            lastLaunchedAtMs = System.currentTimeMillis(),
            enabled = if (limitReached) false else currentRule.enabled,
        )
        repository.saveRule(updated)
        return updated
    }

    private fun finalizeTimeRuleIfNeeded(rule: TriggerRule, isTestRun: Boolean) {
        if (isTestRun || !rule.isTimeRule()) return
        val shouldDisable = rule.source == TriggerSource.TIME_DELAY ||
            rule.source == TriggerSource.TIME_ABSOLUTE
        if (shouldDisable && rule.enabled) {
            repository.saveRule(rule.copy(enabled = false))
        }
        scheduler.rescheduleAll(repository.listRules())
    }

    private fun isCoolingDown(rule: TriggerRule, nowMs: Long): Boolean {
        if (rule.cooldownSeconds <= 0) return false
        return rule.lastMatchedAtMs + rule.cooldownSeconds * 1000L > nowMs
    }

    private fun logRun(
        rule: TriggerRule,
        disposition: TriggerRunDisposition,
        summary: String,
        signal: TriggerSignal,
        renderedPrompt: String? = null,
    ) {
        repository.addRun(
            TriggerRunRecord(
                ruleId = rule.id,
                ruleName = rule.name,
                source = rule.source,
                disposition = disposition,
                summary = summary,
                payloadSnapshot = JSONObject().apply {
                    signal.payload.toSortedMap().forEach { (key, value) -> put(key, value) }
                }.toString(),
                renderedPrompt = renderedPrompt,
            ),
        )
    }

    private fun auditTriggerBlocked(rule: TriggerRule?, signal: TriggerSignal) {
        AuditLog.getInstance(appContext).record(
            AuditEntry.Kind.TRIGGER_BLOCKED_DISARMED,
            "Skipped ${signal.source.name}",
            JSONObject().apply {
                put("signal_source", signal.source.name)
                rule?.let {
                    put("rule_id", it.id)
                    put("rule_name", it.name)
                }
            },
        )
    }

    private fun auditTriggerFired(rule: TriggerRule, signal: TriggerSignal, isTestRun: Boolean) {
        AuditLog.getInstance(appContext).record(
            AuditEntry.Kind.TRIGGER_FIRED,
            "${rule.name} -> ${rule.commandPath.orEmpty()}",
            JSONObject().apply {
                put("rule_id", rule.id)
                put("rule_name", rule.name)
                put("signal_source", signal.source.name)
                put("command_path", rule.commandPath.orEmpty())
                put("test_run", isTestRun)
            },
        )
    }

    private fun auditTriggerFailed(
        rule: TriggerRule,
        signal: TriggerSignal,
        message: String,
        isTestRun: Boolean,
    ) {
        AuditLog.getInstance(appContext).record(
            AuditEntry.Kind.TRIGGER_FAILED,
            "${rule.name} failed: $message",
            JSONObject().apply {
                put("rule_id", rule.id)
                put("rule_name", rule.name)
                put("signal_source", signal.source.name)
                put("message", message)
                put("test_run", isTestRun)
            },
        )
    }

    private fun eventToSignals(event: DeviceEvent): List<TriggerSignal> {
        val payload = normalizePayload(event.payload)
        return when (event.type) {
            EventType.NOTIFICATION_POSTED ->
                listOf(TriggerSignal(TriggerSource.NOTIFICATION_POSTED, event.timestamp, payload))

            EventType.NOTIFICATION_REMOVED ->
                listOf(TriggerSignal(TriggerSource.NOTIFICATION_REMOVED, event.timestamp, payload))

            EventType.FOREGROUND_APP_CHANGED -> buildList {
                add(TriggerSignal(TriggerSource.FOREGROUND_APP_CHANGED, event.timestamp, payload))
                add(TriggerSignal(TriggerSource.APP_ENTERED, event.timestamp, payload))
                payload["previous_package"]?.takeIf { it.isNotBlank() }?.let { previous ->
                    add(
                        TriggerSignal(
                            TriggerSource.APP_EXITED,
                            event.timestamp,
                            payload + mapOf(
                                "package" to previous,
                                "next_package" to (payload["package"] ?: ""),
                            ),
                        ),
                    )
                }
            }

            EventType.ACTIVITY_CHANGED ->
                listOf(TriggerSignal(TriggerSource.ACTIVITY_CHANGED, event.timestamp, payload))

            EventType.BATTERY_LOW ->
                listOf(TriggerSignal(TriggerSource.BATTERY_LOW, event.timestamp, payload))

            EventType.BATTERY_OKAY ->
                listOf(TriggerSignal(TriggerSource.BATTERY_OKAY, event.timestamp, payload))

            EventType.BATTERY_LEVEL_CHANGED ->
                listOf(TriggerSignal(TriggerSource.BATTERY_LEVEL_CHANGED, event.timestamp, payload))

            EventType.POWER_CONNECTED ->
                listOf(TriggerSignal(TriggerSource.POWER_CONNECTED, event.timestamp, payload))

            EventType.POWER_DISCONNECTED ->
                listOf(TriggerSignal(TriggerSource.POWER_DISCONNECTED, event.timestamp, payload))

            EventType.USER_PRESENT ->
                listOf(TriggerSignal(TriggerSource.USER_PRESENT, event.timestamp, payload))

            EventType.NETWORK_CONNECTED ->
                listOf(TriggerSignal(TriggerSource.NETWORK_CONNECTED, event.timestamp, payload))

            EventType.NETWORK_DISCONNECTED ->
                listOf(TriggerSignal(TriggerSource.NETWORK_DISCONNECTED, event.timestamp, payload))

            EventType.NETWORK_TYPE_CHANGED ->
                listOf(TriggerSignal(TriggerSource.NETWORK_TYPE_CHANGED, event.timestamp, payload))

            EventType.SMS_RECEIVED ->
                listOf(TriggerSignal(TriggerSource.SMS_RECEIVED, event.timestamp, payload))

            else -> emptyList()
        }
    }

    private fun normalizePayload(payload: Any?): Map<String, String> {
        val json = payload as? JSONObject ?: return emptyMap()
        val flattened = linkedMapOf<String, String>()
        flattenObject(json, flattened)

        flattened["packageName"]?.let { flattened.putIfAbsent("package", it) }
        flattened["content"]?.let { flattened.putIfAbsent("text", it) }
        flattened["body"]?.let { flattened.putIfAbsent("message", it) }
        flattened["address"]?.let { flattened.putIfAbsent("phone_number", it) }

        json.optJSONArray("messages")?.let { messages ->
            val bodies = mutableListOf<String>()
            val addresses = mutableListOf<String>()
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                message.optString("body").takeIf { it.isNotBlank() }?.let { bodies.add(it) }
                message.optString("address").takeIf { it.isNotBlank() }?.let { addresses.add(it) }
            }
            if (bodies.isNotEmpty()) {
                flattened["message"] = bodies.joinToString("\n")
                flattened["text"] = flattened["message"].orEmpty()
            }
            if (addresses.isNotEmpty()) {
                flattened["phone_number"] = addresses.first()
            }
        }

        return flattened
    }

    private fun flattenObject(json: JSONObject, out: MutableMap<String, String>, prefix: String = "") {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            val path = if (prefix.isBlank()) key else "$prefix.$key"
            when (value) {
                is JSONObject -> flattenObject(value, out, path)
                is JSONArray -> flattenArray(path, value, out)
                null, JSONObject.NULL -> Unit
                else -> {
                    val text = value.toString()
                    out[path] = text
                    if (!out.containsKey(key)) out[key] = text
                }
            }
        }
    }

    private fun flattenArray(path: String, array: JSONArray, out: MutableMap<String, String>) {
        val values = mutableListOf<String>()
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is JSONObject -> flattenObject(item, out, "$path.$index")
                is JSONArray -> flattenArray("$path.$index", item, out)
                null, JSONObject.NULL -> Unit
                else -> values.add(item.toString())
            }
        }
        if (values.isNotEmpty()) out[path] = values.joinToString("\n")
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val percentage = ((level * 100f) / scale).toInt()
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

                val previousLevel = lastBatteryLevel
                val previousCharging = lastCharging
                if (previousLevel != null && previousLevel != percentage) {
                    emitRuntimeEvent(
                        EventType.BATTERY_LEVEL_CHANGED,
                        mapOf("battery_level" to percentage.toString(), "is_charging" to charging.toString()),
                    )
                    if (previousLevel > BATTERY_LOW_THRESHOLD && percentage <= BATTERY_LOW_THRESHOLD) {
                        emitRuntimeEvent(EventType.BATTERY_LOW, mapOf("battery_level" to percentage.toString()))
                    } else if (previousLevel <= BATTERY_LOW_THRESHOLD && percentage > BATTERY_LOW_THRESHOLD) {
                        emitRuntimeEvent(EventType.BATTERY_OKAY, mapOf("battery_level" to percentage.toString()))
                    }
                }
                if (previousCharging != null && previousCharging != charging) {
                    emitRuntimeEvent(
                        if (charging) EventType.POWER_CONNECTED else EventType.POWER_DISCONNECTED,
                        mapOf("battery_level" to percentage.toString()),
                    )
                }
                lastBatteryLevel = percentage
                lastCharging = charging
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    emitRuntimeEvent(EventType.USER_PRESENT)
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            screenReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_NETWORK_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        lastNetworkType = currentNetworkType(connectivityManager)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleNetworkStateChange(connectivityManager)
            }

            override fun onLost(network: Network) {
                handleNetworkStateChange(connectivityManager)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                handleNetworkStateChange(connectivityManager)
            }
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun handleNetworkStateChange(connectivityManager: ConnectivityManager) {
        val currentType = currentNetworkType(connectivityManager)
        val previousType = lastNetworkType
        if (previousType == null) {
            lastNetworkType = currentType
            return
        }
        if (previousType == TriggerNetworkType.NONE && currentType != TriggerNetworkType.NONE) {
            emitRuntimeEvent(EventType.NETWORK_CONNECTED, mapOf("network_type" to currentType.name))
        } else if (currentType == TriggerNetworkType.NONE && previousType != TriggerNetworkType.NONE) {
            emitRuntimeEvent(EventType.NETWORK_DISCONNECTED, mapOf("previous_network_type" to previousType.name))
        } else if (previousType != currentType) {
            emitRuntimeEvent(
                EventType.NETWORK_TYPE_CHANGED,
                mapOf("network_type" to currentType.name, "previous_network_type" to previousType.name),
            )
        }
        lastNetworkType = currentType
    }

    private fun currentNetworkType(connectivityManager: ConnectivityManager): TriggerNetworkType {
        val network = connectivityManager.activeNetwork ?: return TriggerNetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return TriggerNetworkType.NONE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TriggerNetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TriggerNetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TriggerNetworkType.ETHERNET
            else -> TriggerNetworkType.OTHER
        }
    }

    private fun emitRuntimeEvent(type: EventType, payload: Map<String, String> = emptyMap()) {
        EventHub.emit(DeviceEvent(type = type, payload = JSONObject(payload)))
    }

    private fun normalizeRule(rule: TriggerRule): TriggerRule {
        val maxLaunchCount = rule.maxLaunchCount?.takeIf { it > 0 }
        val successfulLaunchCount = rule.successfulLaunchCount.coerceAtLeast(0)
        val withSchedule = if (rule.source == TriggerSource.TIME_DELAY &&
            rule.delayMinutes != null &&
            rule.absoluteTimeMillis == null
        ) {
            rule.copy(absoluteTimeMillis = System.currentTimeMillis() + rule.delayMinutes * 60_000L)
        } else {
            rule
        }
        return withSchedule.copy(
            cooldownSeconds = withSchedule.cooldownSeconds.coerceAtLeast(0),
            maxLaunchCount = maxLaunchCount,
            successfulLaunchCount = successfulLaunchCount,
            enabled = withSchedule.enabled && (maxLaunchCount == null || successfulLaunchCount < maxLaunchCount),
        )
    }

    private fun normalizePersistedRules() {
        repository.listRules().forEach { rule ->
            val normalized = normalizeRule(rule)
            if (normalized != rule) repository.saveRule(normalized)
        }
    }

    private fun buildTestSignal(rule: TriggerRule): TriggerSignal {
        val payload = when (rule.source) {
            TriggerSource.NOTIFICATION_POSTED,
            TriggerSource.NOTIFICATION_REMOVED,
            -> mapOf(
                "package" to (rule.packageName ?: "com.example"),
                "title" to "Test Sender",
                "text" to "Test trigger notification",
            )

            TriggerSource.APP_ENTERED,
            TriggerSource.APP_EXITED,
            TriggerSource.FOREGROUND_APP_CHANGED,
            -> mapOf("package" to (rule.packageName ?: "com.example"))

            TriggerSource.ACTIVITY_CHANGED -> mapOf(
                "package" to (rule.packageName ?: "com.example"),
                "activity" to "com.example.MainActivity",
            )

            TriggerSource.BATTERY_LOW,
            TriggerSource.BATTERY_OKAY,
            TriggerSource.BATTERY_LEVEL_CHANGED,
            -> mapOf("battery_level" to (rule.thresholdValue ?: 10).toString(), "is_charging" to "false")

            TriggerSource.POWER_CONNECTED,
            TriggerSource.POWER_DISCONNECTED,
            -> mapOf("battery_level" to "52")

            TriggerSource.USER_PRESENT -> mapOf("user_present" to "true")
            TriggerSource.NETWORK_CONNECTED,
            TriggerSource.NETWORK_DISCONNECTED,
            TriggerSource.NETWORK_TYPE_CHANGED,
            -> mapOf("network_type" to (rule.networkType ?: TriggerNetworkType.WIFI).name)

            TriggerSource.SMS_RECEIVED -> mapOf(
                "phone_number" to (rule.phoneNumberFilter ?: "+15550001111"),
                "message" to (rule.messageFilter ?: "Test SMS trigger"),
            )

            TriggerSource.TIME_DELAY,
            TriggerSource.TIME_ABSOLUTE,
            TriggerSource.TIME_DAILY,
            TriggerSource.TIME_WEEKLY,
            -> mapOf("schedule_kind" to rule.source.name)
        }
        return TriggerSignal(rule.source, payload = payload)
    }
}
