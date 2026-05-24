package com.termux.autotermux.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerRuleValidatorTest {

    private fun baseRule(
        source: TriggerSource = TriggerSource.SMS_RECEIVED,
        name: String = "rule",
        prompt: String = "do something",
    ): TriggerRule = TriggerRule(
        name = name,
        source = source,
        promptTemplate = prompt,
        cooldownSeconds = 5,
    )

    @Test
    fun rejectsBlankName() {
        val result = TriggerRuleValidator.validateForSave(baseRule(name = "  "))
        assertFalse(result.isValid)
        assertNotNull(result.firstIssueFor(TriggerRuleValidator.Field.NAME))
    }

    @Test
    fun rejectsBlankPromptTemplate() {
        val result = TriggerRuleValidator.validateForSave(baseRule(prompt = ""))
        assertFalse(result.isValid)
        assertNotNull(result.firstIssueFor(TriggerRuleValidator.Field.PROMPT_TEMPLATE))
    }

    @Test
    fun rejectsNegativeCooldown() {
        val result = TriggerRuleValidator.validateForSave(
            baseRule().copy(cooldownSeconds = -1),
        )
        assertFalse(result.isValid)
        assertNotNull(result.firstIssueFor(TriggerRuleValidator.Field.COOLDOWN_SECONDS))
    }

    @Test
    fun rejectsZeroMaxLaunchCount() {
        val result = TriggerRuleValidator.validateForSave(
            baseRule().copy(maxLaunchCount = 0),
        )
        assertFalse(result.isValid)
        assertNotNull(result.firstIssueFor(TriggerRuleValidator.Field.MAX_LAUNCH_COUNT))
    }

    @Test
    fun rejectsBatteryRuleWithoutThreshold() {
        val rule = baseRule(source = TriggerSource.BATTERY_LEVEL_CHANGED)
        val result = TriggerRuleValidator.validateForSave(rule)
        assertFalse(result.isValid)
        assertNotNull(result.firstIssueFor(TriggerRuleValidator.Field.THRESHOLD_VALUE))
    }

    @Test
    fun acceptsBatteryRuleWithThreshold() {
        val rule = baseRule(source = TriggerSource.BATTERY_LEVEL_CHANGED)
            .copy(thresholdValue = 25)
        val result = TriggerRuleValidator.validateForSave(rule)
        assertTrue(result.isValid)
        assertEquals(25, result.rule?.thresholdValue)
    }

    @Test
    fun rejectsTimeDelayWithoutMinutes() {
        val rule = baseRule(source = TriggerSource.TIME_DELAY)
        val result = TriggerRuleValidator.validateForSave(rule)
        assertFalse(result.isValid)
        assertNotNull(result.firstIssueFor(TriggerRuleValidator.Field.DELAY_MINUTES))
    }

    @Test
    fun rejectsAbsoluteTimeInPast() {
        val rule = baseRule(source = TriggerSource.TIME_ABSOLUTE)
            .copy(absoluteTimeMillis = 1L)
        val result = TriggerRuleValidator.validateForSave(rule, nowMs = 1_000L)
        assertFalse(result.isValid)
        assertNotNull(result.firstIssueFor(TriggerRuleValidator.Field.ABSOLUTE_TIME))
    }

    @Test
    fun rejectsWeeklyWithoutDays() {
        val rule = baseRule(source = TriggerSource.TIME_WEEKLY)
            .copy(dailyHour = 9, dailyMinute = 0)
        val result = TriggerRuleValidator.validateForSave(rule)
        assertFalse(result.isValid)
        assertNotNull(result.firstIssueFor(TriggerRuleValidator.Field.WEEKLY_DAYS))
    }

    @Test
    fun sanitizesIrrelevantFieldsForSmsSource() {
        val rule = baseRule(source = TriggerSource.SMS_RECEIVED).copy(
            packageName = "should.be.stripped",
            titleFilter = "should-strip",
            phoneNumberFilter = "+1234",
            messageFilter = "code",
        )
        val result = TriggerRuleValidator.validateForSave(rule)
        assertTrue(result.isValid)
        val sanitized = result.rule!!
        assertNull("packageName not used for SMS", sanitized.packageName)
        assertNull("titleFilter not used for SMS", sanitized.titleFilter)
        assertEquals("+1234", sanitized.phoneNumberFilter)
        assertEquals("code", sanitized.messageFilter)
    }

    @Test
    fun sanitizeKeepsNotificationDebounceForNotificationSource() {
        val rule = baseRule(source = TriggerSource.NOTIFICATION_POSTED).copy(
            packageName = "com.slack",
            titleFilter = "Alice",
            notificationDebounceMs = 2_000L,
        )
        val result = TriggerRuleValidator.validateForSave(rule)
        assertTrue(result.isValid)
        assertEquals(2_000L, result.rule?.notificationDebounceMs)
    }

    @Test
    fun sanitizeClearsDebounceForNonNotificationSource() {
        val rule = baseRule(source = TriggerSource.SMS_RECEIVED).copy(
            notificationDebounceMs = 5_000L,
        )
        val result = TriggerRuleValidator.validateForSave(rule)
        assertTrue(result.isValid)
        assertEquals(0L, result.rule?.notificationDebounceMs)
    }
}
