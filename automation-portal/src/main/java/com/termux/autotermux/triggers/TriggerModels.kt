package com.termux.autotermux.triggers

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TriggerSource {
    TIME_DELAY,
    TIME_ABSOLUTE,
    TIME_DAILY,
    TIME_WEEKLY,
    NOTIFICATION_POSTED,
    NOTIFICATION_REMOVED,
    APP_ENTERED,
    APP_EXITED,
    FOREGROUND_APP_CHANGED,
    ACTIVITY_CHANGED,
    BATTERY_LOW,
    BATTERY_OKAY,
    BATTERY_LEVEL_CHANGED,
    POWER_CONNECTED,
    POWER_DISCONNECTED,
    USER_PRESENT,
    NETWORK_CONNECTED,
    NETWORK_DISCONNECTED,
    NETWORK_TYPE_CHANGED,
    SMS_RECEIVED,
}

enum class TriggerBusyPolicy {
    SKIP,
    QUEUE,
}

enum class TriggerStringMatchMode {
    CONTAINS,
    REGEX,
}

enum class TriggerNetworkType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
}

enum class TriggerThresholdComparison {
    AT_OR_BELOW,
    AT_OR_ABOVE,
}

enum class TriggerRunDisposition {
    MATCHED,
    LAUNCHED,
    SKIPPED_BUSY,
    BUFFERED,
    DEBOUNCED,
    PERMISSION_MISSING,
    LAUNCH_FAILED,
    RULE_DISABLED,
    TEST_LAUNCHED,
}

data class TriggerRule(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val name: String,
    val source: TriggerSource,
    val promptTemplate: String,
    val cooldownSeconds: Int = 0,
    val busyPolicy: TriggerBusyPolicy = TriggerBusyPolicy.SKIP,
    val stringMatchMode: TriggerStringMatchMode = TriggerStringMatchMode.CONTAINS,
    val packageName: String? = null,
    val titleFilter: String? = null,
    val textFilter: String? = null,
    val thresholdValue: Int? = null,
    val thresholdComparison: TriggerThresholdComparison = TriggerThresholdComparison.AT_OR_BELOW,
    val networkType: TriggerNetworkType? = null,
    val phoneNumberFilter: String? = null,
    val messageFilter: String? = null,
    val absoluteTimeMillis: Long? = null,
    val delayMinutes: Int? = null,
    val dailyHour: Int? = null,
    val dailyMinute: Int? = null,
    val weeklyDaysOfWeek: List<Int>? = null,
    val weeklyDayOfWeek: Int? = null,
    val maxLaunchCount: Int? = null,
    val successfulLaunchCount: Int = 0,
    val commandPath: String? = null,
    val arguments: List<String> = emptyList(),
    val stdinTemplate: String? = null,
    val workingDirectory: String? = null,
    val background: Boolean = true,
    val runner: String = "app-shell",
    val commandLabel: String? = null,
    val notificationDebounceMs: Long = 0L,
    val lastMatchedAtMs: Long = 0L,
    val lastLaunchedAtMs: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    fun isTimeRule(): Boolean =
        source == TriggerSource.TIME_DELAY ||
            source == TriggerSource.TIME_ABSOLUTE ||
            source == TriggerSource.TIME_DAILY ||
            source == TriggerSource.TIME_WEEKLY

    fun resolvedWeeklyDaysOfWeek(): List<Int> {
        val resolved = when {
            !weeklyDaysOfWeek.isNullOrEmpty() -> weeklyDaysOfWeek
            weeklyDayOfWeek != null -> listOf(weeklyDayOfWeek)
            else -> emptyList()
        }
        return resolved.map { it.coerceIn(1, 7) }.distinct().sorted()
    }

    fun hasLaunchLimitRemaining(): Boolean {
        val maxCount = maxLaunchCount ?: return true
        return successfulLaunchCount < maxCount
    }
}

data class TriggerRunRecord(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val ruleName: String,
    val source: TriggerSource,
    val disposition: TriggerRunDisposition,
    val timestampMs: Long = System.currentTimeMillis(),
    val summary: String,
    val payloadSnapshot: String? = null,
    val renderedPrompt: String? = null,
)

data class TriggerSignal(
    val source: TriggerSource,
    val timestampMs: Long = System.currentTimeMillis(),
    val payload: Map<String, String> = emptyMap(),
)

object TriggerJson {
    const val CURRENT_SCHEMA_VERSION = 1

    fun rulesToJsonArray(rules: List<TriggerRule>): JSONArray =
        JSONArray().apply { rules.forEach { put(ruleToJson(it)) } }

    fun parseRules(raw: String?): List<TriggerRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { ruleFromJsonOrNull(it)?.let(::add) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun runsToJsonArray(runs: List<TriggerRunRecord>): JSONArray =
        JSONArray().apply { runs.forEach { put(runToJson(it)) } }

    fun parseRuns(raw: String?): List<TriggerRunRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let { runFromJsonOrNull(it)?.let(::add) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun ruleToJson(rule: TriggerRule): JSONObject =
        JSONObject().apply {
            put("id", rule.id)
            put("enabled", rule.enabled)
            put("name", rule.name)
            put("source", rule.source.name)
            put("promptTemplate", rule.promptTemplate)
            put("cooldownSeconds", rule.cooldownSeconds)
            put("busyPolicy", rule.busyPolicy.name)
            put("stringMatchMode", rule.stringMatchMode.name)
            putOpt("packageName", rule.packageName)
            putOpt("titleFilter", rule.titleFilter)
            putOpt("textFilter", rule.textFilter)
            putOpt("thresholdValue", rule.thresholdValue)
            put("thresholdComparison", rule.thresholdComparison.name)
            putOpt("networkType", rule.networkType?.name)
            putOpt("phoneNumberFilter", rule.phoneNumberFilter)
            putOpt("messageFilter", rule.messageFilter)
            putOpt("absoluteTimeMillis", rule.absoluteTimeMillis)
            putOpt("delayMinutes", rule.delayMinutes)
            putOpt("dailyHour", rule.dailyHour)
            putOpt("dailyMinute", rule.dailyMinute)
            putOpt(
                "weeklyDaysOfWeek",
                rule.resolvedWeeklyDaysOfWeek().takeIf { it.isNotEmpty() }?.let { JSONArray(it) },
            )
            putOpt("maxLaunchCount", rule.maxLaunchCount)
            put("successfulLaunchCount", rule.successfulLaunchCount)
            putOpt("commandPath", rule.commandPath)
            put("arguments", JSONArray(rule.arguments))
            putOpt("stdinTemplate", rule.stdinTemplate)
            putOpt("workingDirectory", rule.workingDirectory)
            put("background", rule.background)
            put("runner", rule.runner)
            putOpt("commandLabel", rule.commandLabel)
            put("notificationDebounceMs", rule.notificationDebounceMs)
            put("lastMatchedAtMs", rule.lastMatchedAtMs)
            put("lastLaunchedAtMs", rule.lastLaunchedAtMs)
            put("createdAtMs", rule.createdAtMs)
        }

    fun ruleFromJson(json: JSONObject): TriggerRule =
        ruleFromJsonOrNull(json) ?: error("Unsupported trigger source")

    fun ruleFromJsonOrNull(json: JSONObject): TriggerRule? {
        val source = parseSource(json.optString("source")) ?: return null
        return TriggerRule(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            enabled = json.optBoolean("enabled", true),
            name = json.optString("name", ""),
            source = source,
            promptTemplate = json.optString("promptTemplate", ""),
            cooldownSeconds = json.optInt("cooldownSeconds", 0),
            busyPolicy = parseEnum(json.optString("busyPolicy"), TriggerBusyPolicy.SKIP),
            stringMatchMode = parseEnum(json.optString("stringMatchMode"), TriggerStringMatchMode.CONTAINS),
            packageName = json.optNullableString("packageName"),
            titleFilter = json.optNullableString("titleFilter"),
            textFilter = json.optNullableString("textFilter"),
            thresholdValue = json.optNullableInt("thresholdValue"),
            thresholdComparison = parseEnum(
                json.optString("thresholdComparison"),
                TriggerThresholdComparison.AT_OR_BELOW,
            ),
            networkType = json.optString("networkType")
                .takeIf { it.isNotBlank() }
                ?.let { parseEnum(it, TriggerNetworkType.OTHER) },
            phoneNumberFilter = json.optNullableString("phoneNumberFilter"),
            messageFilter = json.optNullableString("messageFilter"),
            absoluteTimeMillis = json.optNullableLong("absoluteTimeMillis"),
            delayMinutes = json.optNullableInt("delayMinutes"),
            dailyHour = json.optNullableInt("dailyHour"),
            dailyMinute = json.optNullableInt("dailyMinute"),
            weeklyDaysOfWeek = json.optNullableIntList("weeklyDaysOfWeek"),
            weeklyDayOfWeek = json.optNullableInt("weeklyDayOfWeek"),
            maxLaunchCount = json.optNullableInt("maxLaunchCount"),
            successfulLaunchCount = json.optInt("successfulLaunchCount", 0).coerceAtLeast(0),
            commandPath = json.optNullableString("commandPath")
                ?: json.optNullableString("command")
                ?: json.optNullableString("scriptPath"),
            arguments = json.optNullableStringList("arguments")
                ?: json.optNullableStringList("commandArguments")
                ?: emptyList(),
            stdinTemplate = json.optNullableString("stdinTemplate"),
            workingDirectory = json.optNullableString("workingDirectory")
                ?: json.optNullableString("workdir"),
            background = json.optBoolean("background", true),
            runner = json.optString("runner", "app-shell").ifBlank { "app-shell" },
            commandLabel = json.optNullableString("commandLabel"),
            notificationDebounceMs = json.optLong("notificationDebounceMs", 0L).coerceAtLeast(0L),
            lastMatchedAtMs = json.optLong("lastMatchedAtMs", 0L),
            lastLaunchedAtMs = json.optLong("lastLaunchedAtMs", 0L),
            createdAtMs = json.optLong("createdAtMs", System.currentTimeMillis()),
        )
    }

    fun runToJson(record: TriggerRunRecord): JSONObject =
        JSONObject().apply {
            put("id", record.id)
            put("ruleId", record.ruleId)
            put("ruleName", record.ruleName)
            put("source", record.source.name)
            put("disposition", record.disposition.name)
            put("timestampMs", record.timestampMs)
            put("summary", record.summary)
            putOpt("payloadSnapshot", record.payloadSnapshot)
            putOpt("renderedPrompt", record.renderedPrompt)
        }

    fun runFromJsonOrNull(json: JSONObject): TriggerRunRecord? {
        val source = parseSource(json.optString("source")) ?: return null
        return TriggerRunRecord(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            ruleId = json.optString("ruleId", ""),
            ruleName = json.optString("ruleName", ""),
            source = source,
            disposition = parseEnum(json.optString("disposition"), TriggerRunDisposition.MATCHED),
            timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
            summary = json.optString("summary", ""),
            payloadSnapshot = json.optNullableString("payloadSnapshot"),
            renderedPrompt = json.optNullableString("renderedPrompt"),
        )
    }

    private fun parseSource(raw: String?): TriggerSource? {
        if (raw.isNullOrBlank()) return null
        return try {
            enumValueOf<TriggerSource>(raw.trim().uppercase().replace("-", "_"))
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return try {
            enumValueOf<T>(raw.trim().uppercase().replace("-", "_"))
        } catch (_: Exception) {
            fallback
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key)
    }

    private fun JSONObject.optNullableIntList(key: String): List<Int>? {
        if (!has(key) || isNull(key)) return null
        val array = optJSONArray(key) ?: return null
        return buildList {
            for (index in 0 until array.length()) add(array.optInt(index))
        }
    }

    private fun JSONObject.optNullableStringList(key: String): List<String>? {
        if (!has(key) || isNull(key)) return null
        val array = optJSONArray(key) ?: return null
        return buildList {
            for (index in 0 until array.length()) add(array.optString(index, ""))
        }
    }
}
