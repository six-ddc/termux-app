package com.termux.autotermux.audit

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

data class AuditEntry(
    val timestampMs: Long,
    val kind: Kind,
    val summary: String,
    val details: String? = null,
) {
    enum class Kind {
        ARMED_CHANGED,
        SERVICE_CONNECTED,
        SERVICE_DISCONNECTED,
        TRIGGER_FIRED,
        TRIGGER_BLOCKED_DISARMED,
        TRIGGER_FAILED,
        BRIDGE_ACTION,
        BRIDGE_BLOCKED_DISARMED,
        AUTO_ACCEPT_FIRED,
    }
}

fun interface AuditLogListener {
    fun onAuditUpdated()
}

class AuditLog private constructor(context: Context) {
    companion object {
        private const val FILE_NAME = "autotermux-audit.jsonl"
        private const val BACKUP_FILE_NAME = "autotermux-audit.jsonl.1"
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_ENTRIES = 500

        @Volatile
        private var INSTANCE: AuditLog? = null

        fun getInstance(context: Context): AuditLog {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuditLog(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val logFile = File(appContext.filesDir, FILE_NAME)
    private val backupFile = File(appContext.filesDir, BACKUP_FILE_NAME)
    private val entries = ArrayDeque<AuditEntry>(MAX_ENTRIES)
    private val listeners = CopyOnWriteArraySet<AuditLogListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AutoTermuxAuditLog").apply { isDaemon = true }
    }

    init {
        restoreFromDisk()
    }

    fun record(
        kind: AuditEntry.Kind,
        summary: String,
        details: JSONObject? = null,
    ) {
        record(kind, summary, details?.toString())
    }

    fun record(
        kind: AuditEntry.Kind,
        summary: String,
        details: String?,
    ) {
        val entry = AuditEntry(
            timestampMs = System.currentTimeMillis(),
            kind = kind,
            summary = summary.lineSequence().firstOrNull().orEmpty(),
            details = details,
        )
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(entry)
        }
        writer.execute {
            appendEntry(entry)
        }
        notifyListeners()
    }

    fun snapshot(limit: Int = 50): List<AuditEntry> {
        val safeLimit = limit.coerceIn(1, MAX_ENTRIES)
        return synchronized(entries) {
            entries.toList().takeLast(safeLimit).asReversed()
        }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
        writer.execute {
            runCatching { logFile.delete() }
            runCatching { backupFile.delete() }
        }
        notifyListeners()
    }

    fun addListener(listener: AuditLogListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AuditLogListener) {
        listeners.remove(listener)
    }

    private fun appendEntry(entry: AuditEntry) {
        runCatching {
            rotateIfNeeded()
            FileOutputStream(logFile, true).use { stream ->
                stream.write(entryToJson(entry).toString().toByteArray(Charsets.UTF_8))
                stream.write('\n'.code)
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() <= MAX_FILE_BYTES) return
        if (backupFile.exists()) backupFile.delete()
        logFile.renameTo(backupFile)
    }

    private fun restoreFromDisk() {
        val restored = mutableListOf<AuditEntry>()
        listOf(backupFile, logFile).forEach { file ->
            if (!file.exists()) return@forEach
            runCatching {
                file.forEachLine(Charsets.UTF_8) { line ->
                    parseLine(line)?.let { restored.add(it) }
                }
            }
        }
        restored.takeLast(MAX_ENTRIES).forEach { entries.addLast(it) }
    }

    private fun notifyListeners() {
        if (listeners.isEmpty()) return
        mainHandler.post {
            listeners.forEach { listener -> listener.onAuditUpdated() }
        }
    }

    private fun entryToJson(entry: AuditEntry): JSONObject =
        JSONObject().apply {
            put("t", entry.timestampMs)
            put("k", entry.kind.name)
            put("s", entry.summary)
            entry.details?.let { put("d", it) }
        }

    private fun parseLine(line: String): AuditEntry? {
        if (line.isBlank()) return null
        return runCatching {
            val json = JSONObject(line)
            AuditEntry(
                timestampMs = json.optLong("t", 0L),
                kind = AuditEntry.Kind.valueOf(json.optString("k")),
                summary = json.optString("s"),
                details = if (json.has("d")) json.optString("d") else null,
            )
        }.getOrNull()
    }
}
