package com.termux.autotermux.triggers

import java.util.UUID

data class TriggerQueueEntry(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String,
    val ruleName: String,
    val source: TriggerSource,
    val renderedPrompt: String,
    val signal: TriggerSignal,
    val enqueuedAtMs: Long = System.currentTimeMillis(),
)

class TriggerBusyQueue(private val maxSize: Int = 20) {
    private val lock = Any()
    private val entries: ArrayDeque<TriggerQueueEntry> = ArrayDeque()

    fun enqueue(entry: TriggerQueueEntry): Boolean {
        synchronized(lock) {
            if (entries.size >= maxSize) return false
            entries.addLast(entry)
            return true
        }
    }

    fun popNext(): TriggerQueueEntry? {
        synchronized(lock) {
            return entries.removeFirstOrNull()
        }
    }

    fun pushFront(entry: TriggerQueueEntry) {
        synchronized(lock) {
            entries.addFirst(entry)
        }
    }

    fun removeById(entryId: String): Boolean {
        synchronized(lock) {
            return entries.removeAll { it.id == entryId }
        }
    }

    fun list(): List<TriggerQueueEntry> {
        synchronized(lock) {
            return entries.toList()
        }
    }

    fun size(): Int {
        synchronized(lock) {
            return entries.size
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
        }
    }
}
