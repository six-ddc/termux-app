package com.termux.autotermux.triggers

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.termux.autotermux.api.ApiResponse
import com.termux.autotermux.events.model.EventType
import com.termux.autotermux.service.AutoTermuxAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

data class TriggerEnvironmentStatus(
    val accessibilityServiceConnected: Boolean,
    val notificationAccessEnabled: Boolean,
    val receiveSmsGranted: Boolean,
    val readContactsGranted: Boolean,
    val runCommandGranted: Boolean,
    val exactAlarmAvailable: Boolean,
)

sealed class TriggerApiResult<out T> {
    data class Success<T>(val value: T, val message: String? = null) : TriggerApiResult<T>()
    data class Error(val message: String) : TriggerApiResult<Nothing>()
}

class TriggerApi(
    context: Context,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext

    init {
        TriggerRuntime.initialize(appContext)
    }

    fun dispatch(method: String, params: JSONObject): ApiResponse {
        val normalized = method.trim().removePrefix("/").replace(".", "/")
        return when (normalized) {
            "triggers/catalog" -> ApiResponse.RawObject(catalog())
            "triggers/status" -> ApiResponse.RawObject(status())
            "triggers/rules/list", "triggers/rules" -> ApiResponse.RawArray(listRules())
            "triggers/rules/get" -> resultToResponse(getRule(params.optString("ruleId", params.optString("rule_id", ""))))
            "triggers/rules/save" -> {
                val rawRule = params.optJSONObject("rule")?.toString()
                    ?: params.optString("rule_json", params.optString("ruleJson", ""))
                resultToResponse(saveRule(rawRule))
            }
            "triggers/rules/delete" -> resultToResponse(deleteRule(params.optString("ruleId", params.optString("rule_id", ""))))
            "triggers/rules/setEnabled", "triggers/rules/set_enabled" -> resultToResponse(
                setRuleEnabled(
                    params.optString("ruleId", params.optString("rule_id", "")),
                    params.optBoolean("enabled", true),
                ),
            )
            "triggers/rules/test" -> resultToResponse(testRule(params.optString("ruleId", params.optString("rule_id", ""))))
            "triggers/runs/list", "triggers/runs" -> ApiResponse.RawArray(listRuns(params.optInt("limit", 50)))
            "triggers/runs/delete" -> resultToResponse(deleteRun(params.optString("runId", params.optString("run_id", ""))))
            "triggers/runs/clear" -> resultToResponse(clearRuns())
            else -> ApiResponse.Error("Unknown trigger method: $method")
        }
    }

    fun catalog(): JSONObject =
        JSONObject().apply {
            put("schemaVersion", TriggerJson.CURRENT_SCHEMA_VERSION)
            put("eventTypes", JSONArray(EventType.entries.map { it.name }))
            put("triggerSources", JSONArray(TriggerSource.entries.map { it.name }))
            put("runDispositions", JSONArray(TriggerRunDisposition.entries.map { it.name }))
            put("stringMatchModes", JSONArray(TriggerStringMatchMode.entries.map { it.name }))
            put("networkTypes", JSONArray(TriggerNetworkType.entries.map { it.name }))
            put("thresholdComparisons", JSONArray(TriggerThresholdComparison.entries.map { it.name }))
            put("sourceMetadata", sourceMetadata())
        }

    fun status(): JSONObject {
        val environment = resolveEnvironmentStatus(appContext)
        return JSONObject().apply {
            put("accessibilityServiceConnected", environment.accessibilityServiceConnected)
            put("notificationAccessEnabled", environment.notificationAccessEnabled)
            put("receiveSmsGranted", environment.receiveSmsGranted)
            put("readContactsGranted", environment.readContactsGranted)
            put("runCommandGranted", environment.runCommandGranted)
            put("termuxAllowExternalAppsRequired", true)
            put("exactAlarmAvailable", environment.exactAlarmAvailable)
            put("ruleCount", TriggerRuntime.listRules().size)
            put("runCount", TriggerRuntime.listRuns(Int.MAX_VALUE).size)
            put("schemaVersion", TriggerJson.CURRENT_SCHEMA_VERSION)
        }
    }

    fun listRules(): JSONArray = TriggerJson.rulesToJsonArray(TriggerRuntime.listRules())

    fun getRule(ruleId: String): TriggerApiResult<JSONObject> {
        if (ruleId.isBlank()) return TriggerApiResult.Error("Missing rule id")
        val rule = TriggerRuntime.getRule(ruleId)
            ?: return TriggerApiResult.Error("Trigger rule not found: $ruleId")
        return TriggerApiResult.Success(TriggerJson.ruleToJson(rule))
    }

    fun listRuns(limit: Int = 50): JSONArray =
        TriggerJson.runsToJsonArray(TriggerRuntime.listRuns(limit.coerceAtLeast(1)))

    fun saveRule(rawRuleJson: String): TriggerApiResult<JSONObject> {
        if (rawRuleJson.isBlank()) return TriggerApiResult.Error("Missing rule JSON")
        val parsed = try {
            JSONObject(rawRuleJson)
        } catch (e: Exception) {
            return TriggerApiResult.Error("Invalid rule_json: ${e.message}")
        }
        val rule = try {
            TriggerJson.ruleFromJson(parsed)
        } catch (e: Exception) {
            return TriggerApiResult.Error("Invalid trigger rule: ${e.message}")
        }

        val validation = TriggerRuleValidator.validateForSave(rule, nowProvider())
        if (!validation.isValid) {
            val joined = validation.issues.joinToString("; ") { "${it.field.name.lowercase()}: ${it.message}" }
            return TriggerApiResult.Error(joined)
        }
        val sanitized = validation.rule!!

        val environment = resolveEnvironmentStatus(appContext)
        val ruleToSave = if (sanitized.enabled && isNotificationSource(sanitized.source) && !environment.notificationAccessEnabled) {
            sanitized.copy(enabled = false)
        } else {
            sanitized
        }

        TriggerRuntime.saveRule(ruleToSave)
        val saved = TriggerRuntime.getRule(ruleToSave.id) ?: ruleToSave
        val message = if (saved.enabled != sanitized.enabled) {
            "Saved trigger rule ${saved.id} (disabled: notification listener access not granted)"
        } else {
            "Saved trigger rule ${saved.id}"
        }
        return TriggerApiResult.Success(TriggerJson.ruleToJson(saved), message)
    }

    fun deleteRule(ruleId: String): TriggerApiResult<String> {
        if (ruleId.isBlank()) return TriggerApiResult.Error("Missing rule id")
        TriggerRuntime.getRule(ruleId)
            ?: return TriggerApiResult.Error("Trigger rule not found: $ruleId")
        TriggerRuntime.deleteRule(ruleId)
        return TriggerApiResult.Success("Deleted trigger rule $ruleId")
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean): TriggerApiResult<JSONObject> {
        if (ruleId.isBlank()) return TriggerApiResult.Error("Missing rule id")
        val existing = TriggerRuntime.getRule(ruleId)
            ?: return TriggerApiResult.Error("Trigger rule not found: $ruleId")
        if (enabled && isNotificationSource(existing.source) && !resolveEnvironmentStatus(appContext).notificationAccessEnabled) {
            return TriggerApiResult.Error("Cannot enable notification trigger: notification listener access is not granted")
        }
        TriggerRuntime.setRuleEnabled(ruleId, enabled)
        val updated = TriggerRuntime.getRule(ruleId) ?: existing.copy(enabled = enabled)
        return TriggerApiResult.Success(TriggerJson.ruleToJson(updated), "Updated trigger rule ${updated.id}")
    }

    fun testRule(ruleId: String): TriggerApiResult<String> {
        if (ruleId.isBlank()) return TriggerApiResult.Error("Missing rule id")
        TriggerRuntime.getRule(ruleId)
            ?: return TriggerApiResult.Error("Trigger rule not found: $ruleId")
        TriggerRuntime.launchTest(ruleId)
        return TriggerApiResult.Success("Test run requested for $ruleId")
    }

    fun deleteRun(runId: String): TriggerApiResult<String> {
        if (runId.isBlank()) return TriggerApiResult.Error("Missing run id")
        val exists = TriggerRuntime.listRuns(Int.MAX_VALUE).any { it.id == runId }
        if (!exists) return TriggerApiResult.Error("Trigger run not found: $runId")
        TriggerRuntime.deleteRun(runId)
        return TriggerApiResult.Success("Deleted trigger run $runId")
    }

    fun clearRuns(): TriggerApiResult<String> {
        TriggerRuntime.clearRuns()
        return TriggerApiResult.Success("Cleared trigger runs")
    }

    private fun resultToResponse(result: TriggerApiResult<*>): ApiResponse =
        when (result) {
            is TriggerApiResult.Error -> ApiResponse.Error(result.message)
            is TriggerApiResult.Success<*> -> when (val value = result.value) {
                is JSONObject -> ApiResponse.RawObject(withMessage(value, result.message))
                is JSONArray -> ApiResponse.RawArray(value)
                else -> ApiResponse.Success(result.message ?: value.toString())
            }
        }

    private fun withMessage(json: JSONObject, message: String?): JSONObject {
        if (message.isNullOrBlank()) return json
        return JSONObject(json.toString()).apply { put("message", message) }
    }

    private fun sourceMetadata(): JSONObject =
        JSONObject().apply {
            TriggerSource.entries.forEach { source ->
                put(
                    source.name,
                    JSONObject().apply {
                        put("label", source.name.lowercase().replace('_', ' '))
                        put("defaultCooldownSeconds", TriggerEditorSupport.defaultCooldownSecondsFor(source))
                        put("requiresNotificationAccess", isNotificationSource(source))
                        put("isTimeRule", source == TriggerSource.TIME_DELAY ||
                            source == TriggerSource.TIME_ABSOLUTE ||
                            source == TriggerSource.TIME_DAILY ||
                            source == TriggerSource.TIME_WEEKLY)
                    },
                )
            }
        }

    private fun isNotificationSource(source: TriggerSource): Boolean =
        TriggerEditorSupport.isNotificationSource(source)

    companion object {
        fun resolveEnvironmentStatus(context: Context): TriggerEnvironmentStatus =
            TriggerEnvironmentStatus(
                accessibilityServiceConnected = AutoTermuxAccessibilityService.getInstance() != null,
                notificationAccessEnabled = isNotificationAccessEnabled(context),
                receiveSmsGranted = hasPermission(context, Manifest.permission.RECEIVE_SMS),
                readContactsGranted = hasPermission(context, Manifest.permission.READ_CONTACTS),
                runCommandGranted = hasPermission(context, "com.termux.permission.RUN_COMMAND"),
                exactAlarmAvailable = hasExactAlarmAccess(context),
            )

        private fun hasPermission(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        private fun hasExactAlarmAccess(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        }

        private fun isNotificationAccessEnabled(context: Context): Boolean {
            val expected = ComponentName(context, com.termux.autotermux.service.NotificationAccessService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
