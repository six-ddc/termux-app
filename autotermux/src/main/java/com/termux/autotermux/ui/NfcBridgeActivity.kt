package com.termux.autotermux.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NfcBridgeActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PARAM = "param"
        private const val EXTRA_VALUE = "value"

        private val lock = Any()
        private var pendingRequest: PendingRequest? = null
        private var activeActivity: NfcBridgeActivity? = null

        fun request(
            context: Context,
            mode: String,
            param: String,
            value: String,
            timeoutMs: Long,
        ): NfcResult {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            if (adapter == null) {
                return NfcResult(success = false, json = nfcStatusJson(context))
            }
            if (!adapter.isEnabled) {
                return NfcResult(success = false, json = nfcStatusJson(context))
            }

            val request = PendingRequest()
            synchronized(lock) {
                if (pendingRequest != null) {
                    return NfcResult(success = false, error = "nfc request already in progress")
                }
                pendingRequest = request
            }

            val intent = Intent(context, NfcBridgeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_MODE, mode)
                putExtra(EXTRA_PARAM, param)
                putExtra(EXTRA_VALUE, value)
            }
            return try {
                context.startActivity(intent)
                if (!request.latch.await(timeoutMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)) {
                    synchronized(lock) {
                        if (pendingRequest === request) pendingRequest = null
                        activeActivity?.let { activity ->
                            activity.runOnUiThread { activity.finish() }
                        }
                    }
                    NfcResult(success = false, error = "nfc request timed out")
                } else {
                    request.result ?: NfcResult(success = false, error = "nfc returned no result")
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (pendingRequest === request) pendingRequest = null
                    activeActivity?.let { activity ->
                        activity.runOnUiThread { activity.finish() }
                    }
                }
                NfcResult(success = false, error = "failed to start nfc bridge: ${e.message}")
            }
        }

        fun nfcStatusJson(context: Context, error: String = ""): JSONObject {
            val adapter = NfcAdapter.getDefaultAdapter(context)
            return JSONObject().apply {
                if (error.isNotBlank()) put("error", error)
                put("nfcPresent", adapter != null)
                if (adapter != null) put("nfcActive", adapter.isEnabled)
            }
        }

        private fun complete(result: NfcResult) {
            synchronized(lock) {
                pendingRequest?.let {
                    it.result = result
                    it.latch.countDown()
                }
                pendingRequest = null
                activeActivity = null
            }
        }
    }

    private var adapter: NfcAdapter? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        synchronized(lock) {
            activeActivity = this
        }
        adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null || adapter?.isEnabled != true) {
            completeAndFinish(NfcResult(success = false, json = nfcStatusJson(this)))
        }
    }

    override fun onResume() {
        super.onResume()
        val currentAdapter = adapter ?: return
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, NfcBridgeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE,
        )
        val filters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
        )
        currentAdapter.enableForegroundDispatch(this, pendingIntent, filters, null)
    }

    override fun onPause() {
        runCatching { adapter?.disableForegroundDispatch(this) }
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        completeAndFinish(handleTagIntent(intent))
    }

    override fun onDestroy() {
        if (!completed && isFinishing) {
            complete(NfcResult(success = false, error = "nfc bridge closed"))
        }
        super.onDestroy()
    }

    private fun handleTagIntent(tagIntent: Intent): NfcResult {
        return try {
            val mode = intent.getStringExtra(EXTRA_MODE).orEmpty().ifBlank { "read" }
            val param = intent.getStringExtra(EXTRA_PARAM).orEmpty().ifBlank { "short" }
            when (mode) {
                "read" -> {
                    val json = if (param == "full") readFull(tagIntent) else readShort(tagIntent)
                    NfcResult(success = true, json = json)
                }
                "write" -> {
                    writeText(tagIntent, intent.getStringExtra(EXTRA_VALUE).orEmpty())
                    NfcResult(success = true, json = JSONObject().put("written", true))
                }
                else -> NfcResult(success = false, json = unexpected("Wrong Params", "Should be correct mode value"))
            }
        } catch (e: Exception) {
            NfcResult(success = false, json = unexpected("exception", e.message.orEmpty()))
        }
    }

    private fun writeText(tagIntent: Intent, value: String) {
        val tag = tagIntent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            ?: throw IllegalArgumentException("No NFC tag")
        val ndef = Ndef.get(tag) ?: throw IllegalArgumentException("Tag does not support NDEF")
        val message = NdefMessage(arrayOf(NdefRecord.createTextRecord(Locale.ENGLISH.language, value)))
        ndef.connect()
        try {
            ndef.writeNdefMessage(message)
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun readShort(tagIntent: Intent): JSONObject {
        val records = ndefRecords(tagIntent)
        return JSONObject().apply {
            put(
                "Record",
                if (records.length() == 1) {
                    records.getJSONObject(0)
                } else {
                    records
                },
            )
        }
    }

    private fun readFull(tagIntent: Intent): JSONObject {
        val tag = tagIntent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            ?: throw IllegalArgumentException("No NFC tag")
        val ndef = Ndef.get(tag) ?: throw IllegalArgumentException("Tag does not support NDEF")
        return JSONObject().apply {
            put("id", tag.id.joinToString("") { "%02x".format(it) })
            put("typeTag", ndef.type)
            put("maxSize", ndef.maxSize)
            put("techList", JSONArray().apply { tag.techList.forEach { put(it) } })
            val records = fullNdefRecords(tagIntent)
            put("record", if (records.length() == 1) records.getJSONObject(0) else records)
        }
    }

    private fun ndefRecords(tagIntent: Intent): JSONArray {
        ensureNdefTag(tagIntent)
        return JSONArray().apply {
            forEachNdefRecord(tagIntent) { record ->
                put(JSONObject().put("Payload", decodePayload(record)))
            }
        }
    }

    private fun fullNdefRecords(tagIntent: Intent): JSONArray {
        ensureNdefTag(tagIntent)
        return JSONArray().apply {
            forEachNdefRecord(tagIntent) { record ->
                put(
                    JSONObject().apply {
                        put("type", String(record.type))
                        put("tnf", record.tnf)
                        record.toUri()?.let { put("URI", it.toString()) }
                        put("mime", record.toMimeType())
                        put("payload", decodePayload(record))
                    },
                )
            }
        }
    }

    private fun ensureNdefTag(tagIntent: Intent) {
        val tag = tagIntent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            ?: throw IllegalArgumentException("No NFC tag")
        if (!tag.techList.contains(Ndef::class.java.name)) {
            throw IllegalArgumentException("termux API supports only NDEF tags")
        }
    }

    private fun forEachNdefRecord(tagIntent: Intent, block: (NdefRecord) -> Unit) {
        val messages = tagIntent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            ?.mapNotNull { it as? NdefMessage }
            .orEmpty()
        messages.flatMap { it.records.toList() }.forEach(block)
    }

    private fun decodePayload(record: NdefRecord): String {
        val payload = record.payload ?: return ""
        if (payload.isEmpty()) return ""
        return if (record.tnf == NdefRecord.TNF_WELL_KNOWN) {
            val languageLength = payload[0].toInt() and 0x3f
            val utf16 = (payload[0].toInt() and 0x80) != 0
            val start = (1 + languageLength).coerceAtMost(payload.size)
            String(payload, start, payload.size - start, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
        } else {
            String(payload, Charset.defaultCharset())
        }
    }

    private fun unexpected(error: String, description: String): JSONObject =
        JSONObject().put("error", error).put("description", description)

    private fun completeAndFinish(result: NfcResult) {
        if (completed) return
        completed = true
        complete(result)
        finish()
    }

    private class PendingRequest {
        val latch = CountDownLatch(1)
        var result: NfcResult? = null
    }

    data class NfcResult(
        val success: Boolean,
        val json: JSONObject? = null,
        val error: String = "",
    )
}
