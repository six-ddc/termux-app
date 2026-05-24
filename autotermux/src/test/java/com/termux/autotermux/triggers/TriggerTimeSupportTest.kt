package com.termux.autotermux.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TriggerTimeSupportTest {

    private fun midnightUtc(year: Int, month: Int, day: Int): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }
        return cal.timeInMillis
    }

    @Test
    fun nullForNonTimeSource() {
        val rule = TriggerRule(
            name = "n",
            source = TriggerSource.SMS_RECEIVED,
            promptTemplate = "p",
        )
        assertNull(TriggerTimeSupport.nextFireAt(rule, System.currentTimeMillis()))
    }

    @Test
    fun delayUsesCreatedAtPlusMinutes() {
        val createdAt = 1_000_000L
        val rule = TriggerRule(
            name = "n",
            source = TriggerSource.TIME_DELAY,
            promptTemplate = "p",
            delayMinutes = 5,
            createdAtMs = createdAt,
        )
        val nextAt = TriggerTimeSupport.nextFireAt(rule, createdAt)
        assertEquals(createdAt + 5 * 60_000L, nextAt)
    }

    @Test
    fun absoluteReturnsFutureValue() {
        val now = 1_000L
        val future = 2_000L
        val rule = TriggerRule(
            name = "n",
            source = TriggerSource.TIME_ABSOLUTE,
            promptTemplate = "p",
            absoluteTimeMillis = future,
        )
        assertEquals(future, TriggerTimeSupport.nextFireAt(rule, now))
    }

    @Test
    fun absoluteReturnsNullIfInPast() {
        val rule = TriggerRule(
            name = "n",
            source = TriggerSource.TIME_ABSOLUTE,
            promptTemplate = "p",
            absoluteTimeMillis = 500L,
        )
        assertNull(TriggerTimeSupport.nextFireAt(rule, 1_000L))
    }

    @Test
    fun dailyAdvancesToTomorrowWhenTimePassed() {
        val rule = TriggerRule(
            name = "n",
            source = TriggerSource.TIME_DAILY,
            promptTemplate = "p",
            dailyHour = 9,
            dailyMinute = 0,
        )
        val now = System.currentTimeMillis()
        val next = TriggerTimeSupport.nextFireAt(rule, now)
        assertNotNull(next)
        assertTrue("daily fire time must be after now", next!! > now)
    }

    @Test
    fun weeklyHitsRequestedDay() {
        val rule = TriggerRule(
            name = "n",
            source = TriggerSource.TIME_WEEKLY,
            promptTemplate = "p",
            dailyHour = 9,
            dailyMinute = 0,
            weeklyDaysOfWeek = listOf(Calendar.MONDAY, Calendar.WEDNESDAY, Calendar.FRIDAY),
        )
        val now = System.currentTimeMillis()
        val next = TriggerTimeSupport.nextFireAt(rule, now)
        assertNotNull(next)
        val cal = Calendar.getInstance().apply { timeInMillis = next!! }
        val day = cal.get(Calendar.DAY_OF_WEEK)
        assertTrue(
            "next fire day must be one of MON/WED/FRI but got $day",
            day == Calendar.MONDAY || day == Calendar.WEDNESDAY || day == Calendar.FRIDAY,
        )
        assertTrue(next!! > now)
    }

    @Test
    fun shouldDisableAfterFireForOneShotRules() {
        val delayRule = TriggerRule(
            name = "d",
            source = TriggerSource.TIME_DELAY,
            promptTemplate = "p",
            delayMinutes = 5,
        )
        assertTrue(TriggerTimeSupport.shouldDisableAfterFire(delayRule))

        val absoluteRule = delayRule.copy(source = TriggerSource.TIME_ABSOLUTE, absoluteTimeMillis = 100L)
        assertTrue(TriggerTimeSupport.shouldDisableAfterFire(absoluteRule))

        val dailyRule = delayRule.copy(source = TriggerSource.TIME_DAILY, dailyHour = 9, dailyMinute = 0)
        assertTrue(!TriggerTimeSupport.shouldDisableAfterFire(dailyRule))
    }
}
