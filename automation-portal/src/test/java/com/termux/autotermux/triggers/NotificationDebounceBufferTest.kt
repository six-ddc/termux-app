package com.termux.autotermux.triggers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NotificationDebounceBufferTest {

    private var buffer: NotificationDebounceBuffer? = null

    @After
    fun tearDown() {
        buffer?.shutdown()
    }

    private fun rule(debounceMs: Long = 50L): TriggerRule = TriggerRule(
        id = "rule-1",
        name = "n",
        source = TriggerSource.NOTIFICATION_POSTED,
        promptTemplate = "p",
        notificationDebounceMs = debounceMs,
    )

    private fun signal(text: String, title: String = "Alice"): TriggerSignal = TriggerSignal(
        source = TriggerSource.NOTIFICATION_POSTED,
        payload = mapOf("package" to "com.chat", "title" to title, "text" to text),
    )

    @Test
    fun mergesBurstWithinDebounceWindow() {
        val flushCount = AtomicInteger(0)
        val flushedMessages = mutableListOf<List<BufferedMessage>>()
        val flushedSignals = mutableListOf<TriggerSignal>()
        val latch = CountDownLatch(1)

        val buf = NotificationDebounceBuffer(
            onFlush = { _, _, _, messages, mergedSignal ->
                synchronized(flushedMessages) {
                    flushedMessages.add(messages)
                    flushedSignals.add(mergedSignal)
                }
                flushCount.incrementAndGet()
                latch.countDown()
            },
            debounceMs = 50L,
            hardCapMs = 2_000L,
        )
        buffer = buf

        val r = rule(debounceMs = 50L)
        buf.add(r, signal("m1"))
        buf.add(r, signal("m2"))
        buf.add(r, signal("m3"))

        assertTrue("expected debounced flush", latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, flushCount.get())
        assertEquals(3, flushedMessages[0].size)
        val merged = flushedSignals[0]
        assertTrue(
            "merged text should contain m1/m2/m3",
            merged.payload["text"]?.contains("m1") == true &&
                merged.payload["text"]?.contains("m3") == true,
        )
        assertEquals("3", merged.payload["debounce_count"])
    }

    @Test
    fun separateBurstsProduceSeparateFlushes() {
        val flushCount = AtomicInteger(0)
        val first = CountDownLatch(1)
        val second = CountDownLatch(2)

        val buf = NotificationDebounceBuffer(
            onFlush = { _, _, _, _, _ ->
                flushCount.incrementAndGet()
                first.countDown()
                second.countDown()
            },
            debounceMs = 30L,
            hardCapMs = 2_000L,
        )
        buffer = buf

        val r = rule(debounceMs = 30L)
        buf.add(r, signal("burst1"))
        assertTrue("first burst flush", first.await(2, TimeUnit.SECONDS))

        buf.add(r, signal("burst2"))
        assertTrue("second burst flush", second.await(2, TimeUnit.SECONDS))
        assertEquals(2, flushCount.get())
    }

    @Test
    fun differentSendersGetSeparateBuffers() {
        val flushedSenders = mutableListOf<String>()
        val latch = CountDownLatch(2)

        val buf = NotificationDebounceBuffer(
            onFlush = { _, senderName, _, _, _ ->
                synchronized(flushedSenders) { flushedSenders.add(senderName) }
                latch.countDown()
            },
            debounceMs = 40L,
            hardCapMs = 2_000L,
        )
        buffer = buf

        val r = rule(debounceMs = 40L)
        buf.add(r, signal("hi", title = "Alice"))
        buf.add(r, signal("hi", title = "Bob"))
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(flushedSenders.containsAll(listOf("Alice", "Bob")))
    }

    @Test
    fun rulesDebounceMsOverridesConstructorDefault() {
        val capturedFirstSignal = mutableListOf<TriggerSignal>()
        val latch = CountDownLatch(1)
        val buf = NotificationDebounceBuffer(
            onFlush = { _, _, _, _, mergedSignal ->
                synchronized(capturedFirstSignal) { capturedFirstSignal.add(mergedSignal) }
                latch.countDown()
            },
            debounceMs = 5_000L,
            hardCapMs = 30_000L,
        )
        buffer = buf

        val fastRule = rule(debounceMs = 40L)
        buf.add(fastRule, signal("urgent"))
        assertTrue(
            "rule-level debounce of 40ms should override constructor default 5000ms",
            latch.await(2, TimeUnit.SECONDS),
        )
        assertNotNull(capturedFirstSignal.firstOrNull())
    }
}
