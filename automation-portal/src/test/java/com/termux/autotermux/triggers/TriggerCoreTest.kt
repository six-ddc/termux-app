package com.termux.autotermux.triggers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerCoreTest {
    @Test
    fun ruleJsonRoundTripPreservesTermuxCommandFields() {
        val rule = TriggerRule(
            id = "rule-1",
            name = "SMS agent",
            source = TriggerSource.SMS_RECEIVED,
            promptTemplate = "Handle {{trigger.message}}",
            commandPath = "/data/data/com.termux/files/usr/bin/codex",
            arguments = listOf("exec", "--ask-for-approval=never"),
            stdinTemplate = "{{trigger.phone_number}}: {{trigger.message}}",
            workingDirectory = "/data/data/com.termux/files/home",
            maxLaunchCount = 2,
        )

        val parsed = TriggerJson.ruleFromJson(TriggerJson.ruleToJson(rule))

        assertEquals(rule.id, parsed.id)
        assertEquals(rule.commandPath, parsed.commandPath)
        assertEquals(rule.arguments, parsed.arguments)
        assertEquals(rule.stdinTemplate, parsed.stdinTemplate)
        assertEquals(rule.workingDirectory, parsed.workingDirectory)
        assertEquals(rule.maxLaunchCount, parsed.maxLaunchCount)
    }

    @Test
    fun matcherSupportsNotificationAndSmsFilters() {
        val notificationRule = TriggerRule(
            name = "Chat",
            source = TriggerSource.NOTIFICATION_POSTED,
            promptTemplate = "reply",
            packageName = "com.chat",
            titleFilter = "Alice",
            textFilter = "approve",
        )
        val notificationSignal = TriggerSignal(
            source = TriggerSource.NOTIFICATION_POSTED,
            payload = mapOf(
                "package" to "com.chat",
                "title" to "Alice",
                "text" to "please approve this",
            ),
        )
        assertTrue(TriggerMatcher.matches(notificationRule, notificationSignal))

        val smsRule = TriggerRule(
            name = "SMS",
            source = TriggerSource.SMS_RECEIVED,
            promptTemplate = "read",
            phoneNumberFilter = "1555",
            messageFilter = "code",
        )
        val smsSignal = TriggerSignal(
            source = TriggerSource.SMS_RECEIVED,
            payload = mapOf(
                "phone_number" to "+15551234567",
                "message" to "your code is 1234",
            ),
        )
        assertTrue(TriggerMatcher.matches(smsRule, smsSignal))
        assertFalse(TriggerMatcher.matches(smsRule.copy(messageFilter = "missing"), smsSignal))
    }

    @Test
    fun templateRendererExpandsRuleAndPayloadKeys() {
        val rule = TriggerRule(
            id = "rule-1",
            name = "Foreground",
            source = TriggerSource.APP_ENTERED,
            promptTemplate = "App {{trigger.package}} via {{rule.name}}",
        )
        val signal = TriggerSignal(
            source = TriggerSource.APP_ENTERED,
            timestampMs = 1234L,
            payload = mapOf("package" to "com.android.settings"),
        )

        assertEquals(
            "App com.android.settings via Foreground",
            TriggerTemplateRenderer.render(rule.promptTemplate, rule, signal),
        )
    }

    @Test
    fun jsonParserAcceptsCommandAliases() {
        val parsed = TriggerJson.ruleFromJson(
            JSONObject()
                .put("name", "alias")
                .put("source", "SMS_RECEIVED")
                .put("promptTemplate", "prompt")
                .put("scriptPath", "/data/data/com.termux/files/home/hook.sh")
                .put("commandArguments", org.json.JSONArray(listOf("--x"))),
        )

        assertEquals("/data/data/com.termux/files/home/hook.sh", parsed.commandPath)
        assertEquals(listOf("--x"), parsed.arguments)
    }
}
