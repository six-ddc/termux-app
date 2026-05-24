package com.termux.autotermux.triggers

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class BufferedMessage(
    val text: String,
    val timestampMs: Long,
)

class NotificationDebounceBuffer(
    private val onFlush: (rule: TriggerRule, senderName: String, packageName: String, messages: List<BufferedMessage>, mergedSignal: TriggerSignal) -> Unit,
    private val debounceMs: Long = 5_000L,
    private val hardCapMs: Long = 30_000L,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NotifDebounce").apply { isDaemon = true }
    },
) {
    private class SenderBuffer(
        val senderName: String,
        val packageName: String,
        val messages: MutableList<BufferedMessage>,
        val firstMessageMs: Long,
        val rule: TriggerRule,
        val firstSignal: TriggerSignal,
    )

    private val lock = Any()
    private val buffers = mutableMapOf<String, SenderBuffer>()
    private val debounceTimers = mutableMapOf<String, ScheduledFuture<*>>()
    private val hardCapTimers = mutableMapOf<String, ScheduledFuture<*>>()

    fun add(rule: TriggerRule, signal: TriggerSignal) {
        val senderName = signal.payload["title"] ?: "Unknown"
        val packageName = signal.payload["package"] ?: ""
        val text = signal.payload["text"] ?: ""
        val key = "$packageName|$senderName|${rule.id}"
        val nowMs = nowProvider()
        val effectiveDebounceMs = rule.notificationDebounceMs.takeIf { it > 0L } ?: debounceMs

        synchronized(lock) {
            val existing = buffers[key]
            if (existing == null) {
                buffers[key] = SenderBuffer(
                    senderName = senderName,
                    packageName = packageName,
                    messages = mutableListOf(BufferedMessage(text, nowMs)),
                    firstMessageMs = nowMs,
                    rule = rule,
                    firstSignal = signal,
                )
                hardCapTimers[key] = executor.schedule(
                    { flush(key) },
                    hardCapMs,
                    TimeUnit.MILLISECONDS,
                )
            } else {
                existing.messages.add(BufferedMessage(text, nowMs))
            }

            debounceTimers[key]?.cancel(false)
            debounceTimers[key] = executor.schedule(
                { flush(key) },
                effectiveDebounceMs,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun flushAllNow() {
        val keys = synchronized(lock) { buffers.keys.toList() }
        keys.forEach { flush(it) }
    }

    private fun flush(key: String) {
        val buffer: SenderBuffer
        synchronized(lock) {
            buffer = buffers.remove(key) ?: return
            debounceTimers.remove(key)?.cancel(false)
            hardCapTimers.remove(key)?.cancel(false)
        }
        val mergedText = buffer.messages.joinToString("\n") { it.text }.ifBlank {
            buffer.firstSignal.payload["text"].orEmpty()
        }
        val mergedSignal = buffer.firstSignal.copy(
            payload = buffer.firstSignal.payload + mapOf(
                "text" to mergedText,
                "debounce_count" to buffer.messages.size.toString(),
                "debounce_first_ms" to buffer.firstMessageMs.toString(),
            ),
        )
        onFlush(buffer.rule, buffer.senderName, buffer.packageName, buffer.messages.toList(), mergedSignal)
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
