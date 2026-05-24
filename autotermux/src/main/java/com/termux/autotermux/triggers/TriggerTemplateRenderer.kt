package com.termux.autotermux.triggers

object TriggerTemplateRenderer {
    fun render(template: String, rule: TriggerRule, signal: TriggerSignal): String {
        var rendered = template
        val replacements = mutableMapOf(
            "rule.id" to rule.id,
            "rule.name" to rule.name,
            "rule.source" to rule.source.name,
            "trigger.source" to signal.source.name,
            "trigger.timestamp_ms" to signal.timestampMs.toString(),
            "timestamp_ms" to signal.timestampMs.toString(),
        )
        signal.payload.forEach { (key, value) ->
            replacements["trigger.$key"] = value
            replacements["event.$key"] = value
        }
        replacements.forEach { (key, value) ->
            rendered = rendered.replace("{{$key}}", value)
        }
        return rendered
    }
}
