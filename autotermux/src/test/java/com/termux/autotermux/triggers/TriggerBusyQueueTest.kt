package com.termux.autotermux.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerBusyQueueTest {

    private fun entry(ruleId: String, ruleName: String = ruleId): TriggerQueueEntry =
        TriggerQueueEntry(
            ruleId = ruleId,
            ruleName = ruleName,
            source = TriggerSource.SMS_RECEIVED,
            renderedPrompt = "prompt for $ruleId",
            signal = TriggerSignal(source = TriggerSource.SMS_RECEIVED),
        )

    @Test
    fun enqueueAndPopFifoOrder() {
        val queue = TriggerBusyQueue(maxSize = 4)
        assertEquals(0, queue.size())
        assertTrue(queue.enqueue(entry("a")))
        assertTrue(queue.enqueue(entry("b")))
        assertTrue(queue.enqueue(entry("c")))
        assertEquals(3, queue.size())

        assertEquals("a", queue.popNext()?.ruleId)
        assertEquals("b", queue.popNext()?.ruleId)
        assertEquals("c", queue.popNext()?.ruleId)
        assertNull(queue.popNext())
    }

    @Test
    fun enqueueRejectsWhenFull() {
        val queue = TriggerBusyQueue(maxSize = 2)
        assertTrue(queue.enqueue(entry("a")))
        assertTrue(queue.enqueue(entry("b")))
        assertFalse("third enqueue must fail on full queue", queue.enqueue(entry("c")))
        assertEquals(2, queue.size())
    }

    @Test
    fun pushFrontReinsertsAtHead() {
        val queue = TriggerBusyQueue()
        queue.enqueue(entry("a"))
        queue.enqueue(entry("b"))
        val priority = entry("priority")
        queue.pushFront(priority)
        assertEquals("priority", queue.popNext()?.ruleId)
        assertEquals("a", queue.popNext()?.ruleId)
    }

    @Test
    fun removeByIdDropsMatchingEntry() {
        val queue = TriggerBusyQueue()
        val target = entry("a")
        queue.enqueue(target)
        queue.enqueue(entry("b"))
        assertTrue(queue.removeById(target.id))
        assertEquals(1, queue.size())
        assertEquals("b", queue.popNext()?.ruleId)
    }

    @Test
    fun listReturnsSnapshot() {
        val queue = TriggerBusyQueue()
        queue.enqueue(entry("a"))
        queue.enqueue(entry("b"))
        val snapshot = queue.list()
        assertEquals(2, snapshot.size)
        queue.clear()
        assertEquals("snapshot must be independent of queue mutations", 2, snapshot.size)
        assertEquals(0, queue.size())
    }

    @Test
    fun clearEmptiesQueue() {
        val queue = TriggerBusyQueue()
        queue.enqueue(entry("a"))
        queue.enqueue(entry("b"))
        queue.clear()
        assertEquals(0, queue.size())
        assertNull(queue.popNext())
        assertNotNull(queue.list())
    }
}
